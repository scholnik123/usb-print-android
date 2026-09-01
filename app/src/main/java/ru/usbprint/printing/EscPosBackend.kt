package ru.usbprint.printing

import android.graphics.Bitmap
import android.graphics.Color
import ru.usbprint.document.DocumentRepository
import ru.usbprint.domain.logic.PageRangeParser
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.DocumentKind
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.domain.model.PrintJobStatus
import ru.usbprint.usb.UsbTransport
import kotlin.math.ceil

/** Conservative ESC/POS encoder: initialize, text or monochrome GS v 0 raster, then feed. It never cuts paper unless a future confirmed capability enables it. */
class EscPosBackend : PrintingBackend {
    override val id = BackendId.ESC_POS

    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (PrintProgressUpdate) -> Unit, isCancelled: () -> Boolean, metrics: PrintMetricsSink) {
        suspend fun emit(bytes: ByteArray) { metrics.addGeneratedBytes(bytes.size.toLong()); transport.write(bytes) }
        val pagesPerCopy = if (job.document.kind in setOf(DocumentKind.IMAGE, DocumentKind.PDF)) {
            PageRangeParser.expand(job.settings.pageSelection, job.document.pageCount ?: 1)
        } else emptyList()
        val totalPages = Math.multiplyExact(pagesPerCopy.size, job.settings.copies)
        var completedPages = 0
        onProgress(if (totalPages > 0) PrintProgressUpdate.pages(PrintJobStatus.SENDING, 0, totalPages) else PrintProgressUpdate.indeterminate(PrintJobStatus.SENDING))
        repeat(job.settings.copies) {
            ensureNotCancelled(isCancelled)
            emit(metrics.measureEncode { byteArrayOf(ESC, AT, ESC, A, 0) })
            when (job.document.kind) {
                DocumentKind.TEXT -> printText(job, transport, documents, isCancelled, metrics)
                DocumentKind.IMAGE, DocumentKind.PDF -> printRaster(job, pagesPerCopy, transport, documents, isCancelled, metrics) {
                    completedPages++
                    onProgress(PrintProgressUpdate.pages(PrintJobStatus.SENDING, completedPages, totalPages))
                }
                else -> throw PrintException(AppError.DOCUMENT_NOT_SUPPORTED)
            }
            emit(metrics.measureEncode { byteArrayOf(ESC, D, 4) })
        }
    }

    private suspend fun printText(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, isCancelled: () -> Boolean, metrics: PrintMetricsSink) {
        documents.openInput(job.document).bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                ensureNotCancelled(isCancelled)
                // ESC/POS code pages differ across devices; UTF-8 is used without claiming universal Cyrillic support.
                val bytes = metrics.measureEncode { line.take(96).toByteArray(Charsets.UTF_8) + byteArrayOf(LF) }
                metrics.addGeneratedBytes(bytes.size.toLong())
                transport.write(bytes)
            }
        }
    }

    private suspend fun printRaster(
        job: PrintJob,
        pages: List<Int>,
        transport: UsbTransport,
        documents: DocumentRepository,
        isCancelled: () -> Boolean,
        metrics: PrintMetricsSink,
        onPageCompleted: () -> Unit
    ) {
        documents.createRenderer(job.document).use { renderer ->
            pages.forEach { userPage ->
                ensureNotCancelled(isCancelled)
                val bitmap = metrics.measureRender { renderer.renderPage(userPage - 1, RASTER_WIDTH) }
                val bitmapBytes = bitmap.allocationByteCount.toLong()
                metrics.allocateRasterBuffer(bitmapBytes)
                try { sendBitmap(bitmap, transport, isCancelled, metrics) }
                finally { bitmap.recycle(); metrics.releaseRasterBuffer(bitmapBytes) }
                metrics.recordPageRendered()
                onPageCompleted()
            }
        }
    }

    private suspend fun sendBitmap(source: Bitmap, transport: UsbTransport, isCancelled: () -> Boolean, metrics: PrintMetricsSink) {
        val scaled = if (source.width > RASTER_WIDTH) metrics.measureRender {
            Bitmap.createScaledBitmap(source, RASTER_WIDTH, (source.height.toFloat() / source.width * RASTER_WIDTH).toInt().coerceAtLeast(1), true)
        } else source
        val scaledBytes = scaled.allocationByteCount.toLong().takeIf { scaled !== source } ?: 0L
        if (scaledBytes > 0L) metrics.allocateRasterBuffer(scaledBytes)
        try {
            val widthBytes = ceil(scaled.width / 8.0).toInt()
            var row = 0
            while (row < scaled.height) {
                ensureNotCancelled(isCancelled)
                val rows = minOf(BAND_HEIGHT, scaled.height - row)
                val payload = metrics.measureEncode {
                    ByteArray(8 + widthBytes * rows).also { output ->
                        output[0] = GS; output[1] = V; output[2] = ZERO; output[3] = ZERO
                        output[4] = (widthBytes and 0xff).toByte(); output[5] = ((widthBytes shr 8) and 0xff).toByte()
                        output[6] = (rows and 0xff).toByte(); output[7] = ((rows shr 8) and 0xff).toByte()
                        for (y in 0 until rows) for (x in 0 until scaled.width) {
                            val pixel = scaled.getPixel(x, row + y)
                            val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                            if (luminance < 160) {
                                val offset = 8 + y * widthBytes + x / 8
                                output[offset] = (output[offset].toInt() or (0x80 shr (x % 8))).toByte()
                            }
                        }
                    }
                }
                metrics.allocateRasterBuffer(payload.size.toLong())
                try {
                    metrics.addGeneratedBytes(payload.size.toLong())
                    transport.write(payload)
                } finally {
                    metrics.releaseRasterBuffer(payload.size.toLong())
                }
                row += rows
            }
        } finally {
            if (scaled !== source) scaled.recycle()
            if (scaledBytes > 0L) metrics.releaseRasterBuffer(scaledBytes)
        }
    }

    private fun ensureNotCancelled(cancelled: () -> Boolean) { if (cancelled()) throw PrintException(AppError.PRINT_CANCELLED) }

    private companion object {
        const val RASTER_WIDTH = 576
        const val BAND_HEIGHT = 96
        const val ESC: Byte = 0x1B
        const val GS: Byte = 0x1D
        const val AT: Byte = 0x40
        const val A: Byte = 0x61
        const val D: Byte = 0x64
        const val V: Byte = 0x76
        const val ZERO: Byte = 0
        const val LF: Byte = 0x0A
    }
}
