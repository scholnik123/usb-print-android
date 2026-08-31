package ru.usbprint.printing

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.usbprint.document.DocumentRepository
import ru.usbprint.domain.logic.BackendRegistry
import ru.usbprint.domain.logic.PrintPagePlanner
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.usb.UsbTransport

class PwgRasterBackend : PrintingBackend {
    override val id = BackendId.PWG_RASTER

    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        val pages = PrintPagePlanner.plan(job.settings, job.document.pageCount ?: 1)
        if (pages.isEmpty()) throw PrintException(AppError.INVALID_SETTINGS)
        val mode = when (job.settings.colorMode) {
            ColorMode.COLOR -> RasterColorMode.RGB
            ColorMode.BLACK_ONLY, ColorMode.MONOCHROME -> RasterColorMode.MONOCHROME
            ColorMode.GRAYSCALE, ColorMode.AUTO -> RasterColorMode.GRAYSCALE
        }
        val dpi = selectDpi(job)
        val duplex = job.settings.duplexMode != DuplexMode.OFF && job.printer.capabilities.supportsDuplex == true
        transport.write(PwgRasterEncoder.syncWord)
        documents.createRenderer(job.document).use { renderer ->
            val total = pages.size
            var completed = 0
            pages.forEach { page ->
                    ensureNotCancelled(isCancelled)
                    val (sourceWidth, sourceHeight) = renderer.pageSize(page - 1)
                    val layout = try { PrintLayoutEngine.create(job.settings, sourceWidth, sourceHeight, dpi, BackendRegistry.effectiveFor(id, job.printer.capabilities).hardwareMargins?.value ?: ru.usbprint.domain.model.HardwareMarginsMm.ZERO) }
                    catch (badDimension: IllegalArgumentException) { throw PrintException(AppError.OUT_OF_MEMORY_PREVENTED, badDimension) }
                    val header = PwgRasterHeader(layout, mode, duplex, job.settings.duplexMode == DuplexMode.SHORT_EDGE)
                    transport.write(header.toBytes())
                    RasterPageSource(renderer, page - 1, layout, mode).use { source ->
                        val line = ByteArray(header.bytesPerLine)
                        val colorValueSize = (header.bitsPerPixel + 7) / 8
                        for (y in 0 until layout.heightPx) {
                            ensureNotCancelled(isCancelled)
                            source.renderRow(y, line)
                            transport.write(PwgRasterEncoder.encodeLine(line, colorValueSize))
                        }
                    }
                    completed++
                    onProgress((completed * 100 / total).coerceIn(1, 100))
            }
        }
    }

    private fun selectDpi(job: PrintJob): Int {
        val allowed = BackendRegistry.effectiveFor(id, job.printer.capabilities).resolutions?.value.orEmpty()
        val requested = job.settings.resolutionDpi
        return allowed.firstOrNull { it.horizontalDpi == requested && it.verticalDpi == requested }?.horizontalDpi
            ?: allowed.firstOrNull()?.horizontalDpi ?: PrintLayoutEngine.DEFAULT_DPI
    }
}

/** Emits Level 2 PostScript with normalized RGB image pages, so PDF/image/text share the same physical layout. */
class PostScriptRasterBackend : PrintingBackend {
    override val id = BackendId.POSTSCRIPT_RASTER

    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        val pages = PrintPagePlanner.plan(job.settings, job.document.pageCount ?: 1)
        transport.write(PostScriptRasterEncoder.prolog())
        documents.createRenderer(job.document).use { renderer ->
            val total = pages.size
            var completed = 0
            pages.forEach { page ->
                    ensureNotCancelled(isCancelled)
                    val size = renderer.pageSize(page - 1)
                    val layout = safeLayout(job, size.first, size.second)
                    val duplex = job.settings.duplexMode != DuplexMode.OFF && job.printer.capabilities.supportsDuplex == true
                    val pageNumber = completed + 1
                    val colorMode = if (job.settings.colorMode == ColorMode.COLOR) RasterColorMode.RGB else RasterColorMode.GRAYSCALE
                    transport.write(PostScriptRasterEncoder.beginPage(layout, pageNumber, duplex, job.settings.duplexMode == DuplexMode.SHORT_EDGE, colorMode))
                    RasterPageSource(renderer, page - 1, layout, colorMode).use { source ->
                        val row = ByteArray(layout.widthPx * if (colorMode == RasterColorMode.RGB) 3 else 1)
                        for (y in 0 until layout.heightPx) {
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
        transport.write(PostScriptRasterEncoder.trailer(pages.size))
    }
}

/** Minimal PCL 5 raster stream: reset, media/orientation/resolution, uncompressed monochrome raster rows, page eject. */
class Pcl5RasterBackend : PrintingBackend {
    override val id = BackendId.PCL5_RASTER

    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        val pages = PrintPagePlanner.plan(job.settings, job.document.pageCount ?: 1)
        transport.write(Pcl5JobEncoder.reset())
        documents.createRenderer(job.document).use { renderer ->
            val total = pages.size
            var completed = 0
            pages.forEach { page ->
                    ensureNotCancelled(isCancelled)
                    val size = renderer.pageSize(page - 1)
                    val dpi = allowedDpi(job, id)
                    val layout = safeLayout(job, size.first, size.second, dpi)
                    val duplex = if (job.printer.capabilities.supportsDuplex == true) job.settings.duplexMode else DuplexMode.OFF
                    transport.write(Pcl5JobEncoder.beginPage(layout, dpi, duplex))
                    RasterPageSource(renderer, page - 1, layout, RasterColorMode.MONOCHROME).use { source ->
                        val row = ByteArray((layout.widthPx + 7) / 8)
                        for (y in 0 until layout.heightPx) {
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

private fun safeLayout(job: PrintJob, sourceWidth: Int, sourceHeight: Int, dpi: Int = allowedDpi(job, job.backend)): RasterPageLayout = try {
    val hardwareMargins = BackendRegistry.effectiveFor(job.backend, job.printer.capabilities).hardwareMargins?.value ?: ru.usbprint.domain.model.HardwareMarginsMm.ZERO
    PrintLayoutEngine.create(job.settings, sourceWidth, sourceHeight, dpi, hardwareMargins)
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
