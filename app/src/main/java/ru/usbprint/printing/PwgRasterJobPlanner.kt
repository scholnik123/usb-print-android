package ru.usbprint.printing

import ru.usbprint.document.DocumentRepository
import ru.usbprint.domain.logic.BackendRegistry
import ru.usbprint.domain.logic.PrintPagePlanner
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintJob

data class PwgRasterJobPlan(
    val pages: List<Int>,
    val pagesPerSheet: Int,
    val colorMode: RasterColorMode,
    val dpi: Int,
    val duplex: Boolean,
    val tumble: Boolean,
    val hardwareMargins: HardwareMarginsMm
) {
    val physicalSheetCount: Int get() = (pages.size + pagesPerSheet - 1) / pagesPerSheet
}

/** Resolves software-encoded PWG settings once for both legacy USB and IPP destinations. */
object PwgRasterJobPlanner {
    fun plan(job: PrintJob, capabilityBackend: BackendId): PwgRasterJobPlan {
        val effective = BackendRegistry.effectiveFor(capabilityBackend, job.printer.capabilities)
        if (job.settings.pagesPerSheet !in setOf(1, 2, 4) || job.settings.pagesPerSheet > 1 && !effective.supportsNUp) {
            throw PrintException(AppError.INVALID_SETTINGS)
        }
        val pages = PrintPagePlanner.plan(job.settings, job.document.pageCount ?: 1)
        if (pages.isEmpty()) throw PrintException(AppError.INVALID_SETTINGS)

        val allowedResolutions = effective.resolutions?.value.orEmpty().filter { it.horizontalDpi == it.verticalDpi }
        val requestedResolution = job.settings.selectedResolution
        val resolution = when {
            requestedResolution != null -> allowedResolutions.firstOrNull { it == requestedResolution }
            else -> allowedResolutions.minByOrNull { it.horizontalDpi }
        } ?: throw PrintException(AppError.INVALID_SETTINGS)

        val allowedColors = effective.colorModes?.value.orEmpty()
        val selectedColor = when (job.settings.colorMode) {
            ColorMode.AUTO -> listOf(ColorMode.GRAYSCALE, ColorMode.MONOCHROME, ColorMode.BLACK_ONLY, ColorMode.COLOR).firstOrNull { it in allowedColors }
            else -> job.settings.colorMode.takeIf { it in allowedColors }
        } ?: throw PrintException(AppError.INVALID_SETTINGS)
        val rasterColor = when (selectedColor) {
            ColorMode.COLOR -> RasterColorMode.RGB
            ColorMode.GRAYSCALE -> RasterColorMode.GRAYSCALE
            ColorMode.BLACK_ONLY, ColorMode.MONOCHROME -> RasterColorMode.MONOCHROME
            ColorMode.AUTO -> error("AUTO is resolved before raster planning")
        }

        val duplexMode = job.settings.duplexMode
        if (duplexMode !in effective.duplexModes?.value.orEmpty()) throw PrintException(AppError.INVALID_SETTINGS)
        return PwgRasterJobPlan(
            pages = pages,
            pagesPerSheet = job.settings.pagesPerSheet,
            colorMode = rasterColor,
            dpi = resolution.horizontalDpi,
            duplex = duplexMode != DuplexMode.OFF,
            tumble = duplexMode == DuplexMode.SHORT_EDGE,
            hardwareMargins = effective.hardwareMargins?.value ?: HardwareMarginsMm.ZERO
        )
    }
}

object PwgRasterDocumentWriter {
    suspend fun write(
        job: PrintJob,
        capabilityBackend: BackendId,
        documents: DocumentRepository,
        plan: PwgRasterJobPlan = PwgRasterJobPlanner.plan(job, capabilityBackend),
        writeBytes: suspend (ByteArray) -> Unit,
        onPageCompleted: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false }
    ) {
        documents.createRenderer(job.document).use { renderer ->
            val sheets = try {
                NUpLayoutEngine.plan(job.settings, renderer.pageCount, renderer::pageSize, plan.dpi, plan.hardwareMargins)
            } catch (badDimension: IllegalArgumentException) {
                throw PrintException(AppError.OUT_OF_MEMORY_PREVENTED, badDimension)
            }
            PwgRasterProducer.write(
                pages = sheets.indices.toList(),
                openPage = { sheetIndex ->
                    val sheet = sheets[sheetIndex]
                    val source = NUpRasterPageSource(renderer, sheet, plan.colorMode)
                    object : PwgRasterPage {
                        override val header = PwgRasterHeader(sheet.layout, plan.colorMode, plan.duplex, plan.tumble)
                        override fun renderRow(rowIndex: Int, destination: ByteArray) = source.renderRow(rowIndex, destination)
                        override fun close() = source.close()
                    }
                },
                writeBytes = writeBytes,
                onPageCompleted = onPageCompleted,
                isCancelled = isCancelled
            )
        }
    }
}
