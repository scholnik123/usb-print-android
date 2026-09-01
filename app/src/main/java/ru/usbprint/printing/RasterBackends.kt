package ru.usbprint.printing

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.usbprint.document.DocumentRepository
import ru.usbprint.document.DocumentRenderer
import ru.usbprint.domain.logic.BackendRegistry
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.usb.UsbTransport

class PwgRasterBackend : PrintingBackend {
    override val id = BackendId.PWG_RASTER

    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        PwgRasterDocumentWriter.write(
            job = job,
            capabilityBackend = id,
            documents = documents,
            writeBytes = transport::write,
            onPageCompleted = { completed, total -> onProgress((completed * 100 / total).coerceIn(1, 100)) },
            isCancelled = isCancelled
        )
    }
}

/** Emits Level 2 PostScript with normalized RGB image pages, so PDF/image/text share the same physical layout. */
class PostScriptRasterBackend : PrintingBackend {
    override val id = BackendId.POSTSCRIPT_RASTER

    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        transport.write(PostScriptRasterEncoder.prolog())
        var physicalSheets = 0
        documents.createRenderer(job.document).use { renderer ->
            val sheets = plannedSheets(job, renderer, allowedDpi(job, id), id)
            val total = sheets.size
            physicalSheets = total
            var completed = 0
            sheets.forEach { sheet ->
                    ensureNotCancelled(isCancelled)
                    val duplex = job.settings.duplexMode != DuplexMode.OFF && job.printer.capabilities.supportsDuplex == true
                    val pageNumber = completed + 1
                    val colorMode = if (job.settings.colorMode == ColorMode.COLOR) RasterColorMode.RGB else RasterColorMode.GRAYSCALE
                    transport.write(PostScriptRasterEncoder.beginPage(sheet.layout, pageNumber, duplex, job.settings.duplexMode == DuplexMode.SHORT_EDGE, colorMode))
                    NUpRasterPageSource(renderer, sheet, colorMode).use { source ->
                        val row = ByteArray(sheet.layout.widthPx * if (colorMode == RasterColorMode.RGB) 3 else 1)
                        for (y in 0 until sheet.layout.heightPx) {
                            ensureNotCancelled(isCancelled)
                            source.renderRow(y, row)
                            transport.write(PostScriptRasterEncoder.asciiHex(row))
                        }
                    }
                    transport.write(PostScriptRasterEncoder.endPage())
                    completed++
                    onProgress((completed * 100 / total).coerceIn(1, 100))
            }
        }
        transport.write(PostScriptRasterEncoder.trailer(physicalSheets))
    }
}

/** Minimal PCL 5 raster stream: reset, media/orientation/resolution, uncompressed monochrome raster rows, page eject. */
class Pcl5RasterBackend : PrintingBackend {
    override val id = BackendId.PCL5_RASTER

    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        transport.write(Pcl5JobEncoder.reset())
        documents.createRenderer(job.document).use { renderer ->
            val dpi = allowedDpi(job, id)
            val sheets = plannedSheets(job, renderer, dpi, id)
            val total = sheets.size
            var completed = 0
            sheets.forEach { sheet ->
                    ensureNotCancelled(isCancelled)
                    val duplex = if (job.printer.capabilities.supportsDuplex == true) job.settings.duplexMode else DuplexMode.OFF
                    transport.write(Pcl5JobEncoder.beginPage(sheet.layout, dpi, duplex))
                    NUpRasterPageSource(renderer, sheet, RasterColorMode.MONOCHROME).use { source ->
                        val row = ByteArray((sheet.layout.widthPx + 7) / 8)
                        for (y in 0 until sheet.layout.heightPx) {
                            ensureNotCancelled(isCancelled)
                            source.renderRow(y, row)
                            transport.write(Pcl5JobEncoder.row(row.size))
                            transport.write(row)
                        }
                    }
                    transport.write(Pcl5JobEncoder.endPage())
                    completed++
                    onProgress((completed * 100 / total).coerceIn(1, 100))
            }
        }
    }
}

private fun plannedSheets(job: PrintJob, renderer: DocumentRenderer, dpi: Int, backend: BackendId): List<NUpSheet> = try {
    val hardwareMargins = BackendRegistry.effectiveFor(backend, job.printer.capabilities).hardwareMargins?.value ?: ru.usbprint.domain.model.HardwareMarginsMm.ZERO
    NUpLayoutEngine.plan(job.settings, renderer.pageCount, renderer::pageSize, dpi, hardwareMargins)
} catch (badDimension: IllegalArgumentException) { throw PrintException(AppError.OUT_OF_MEMORY_PREVENTED, badDimension) }

private fun allowedDpi(job: PrintJob, backend: BackendId): Int {
    val allowed = BackendRegistry.effectiveFor(backend, job.printer.capabilities).resolutions?.value.orEmpty()
    val requested = job.settings.resolutionDpi
    return allowed.firstOrNull { it.horizontalDpi == requested && it.verticalDpi == requested }?.horizontalDpi
        ?: allowed.firstOrNull()?.horizontalDpi ?: PrintLayoutEngine.DEFAULT_DPI
}

private suspend fun ensureNotCancelled(cancelled: () -> Boolean) {
    if (cancelled()) throw PrintException(AppError.PRINT_CANCELLED)
    currentCoroutineContext().ensureActive()
}
