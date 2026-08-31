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
import ru.usbprint.usb.UsbTransport
import kotlin.math.ceil

/** Conservative ESC/POS encoder: initialize, text or monochrome GS v 0 raster, then feed. It never cuts paper unless a future confirmed capability enables it. */
class EscPosBackend : PrintingBackend {
    override val id = BackendId.ESC_POS

    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        repeat(job.settings.copies) { copy ->
            ensureNotCancelled(isCancelled)
            transport.write(byteArrayOf(ESC, AT, ESC, A, 0))
            when (job.document.kind) {
                DocumentKind.TEXT -> printText(job, transport, documents, isCancelled)
                DocumentKind.IMAGE, DocumentKind.PDF -> printRaster(job, transport, documents, isCancelled) { value ->
                    onProgress((((copy + value / 100f) / job.settings.copies) * 100).toInt().coerceIn(1, 99))
                }
                else -> throw PrintException(AppError.DOCUMENT_NOT_SUPPORTED)
            }
            transport.write(byteArrayOf(ESC, D, 4))
        }
        onProgress(100)
    }

    private suspend fun printText(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, isCancelled: () -> Boolean) {
        documents.openInput(job.document).bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                ensureNotCancelled(isCancelled)
                // ESC/POS code pages differ across devices; UTF-8 is used without claiming universal Cyrillic support.
                transport.write(line.take(96).toByteArray(Charsets.UTF_8) + byteArrayOf(LF))
            }
        }
    }

    private suspend fun printRaster(
        job: PrintJob,
        transport: UsbTransport,
        documents: DocumentRepository,
        isCancelled: () -> Boolean,
        onProgress: (Int) -> Unit
    ) {
        val pages = PageRangeParser.expand(job.settings.pageSelection, job.document.pageCount ?: 1)
        documents.createRenderer(job.document).use { renderer ->
            pages.forEachIndexed { pagePosition, userPage ->
                ensureNotCancelled(isCancelled)
                val bitmap = renderer.renderPage(userPage - 1, RASTER_WIDTH)
                try { sendBitmap(bitmap, transport, isCancelled) } finally { bitmap.recycle() }
                onProgress(((pagePosition + 1f) / pages.size * 100).toInt())
            }
        }
    }

    private suspend fun sendBitmap(source: Bitmap, transport: UsbTransport, isCancelled: () -> Boolean) {
        val scaled = if (source.width > RASTER_WIDTH) Bitmap.createScaledBitmap(source, RASTER_WIDTH, (source.height.toFloat() / source.width * RASTER_WIDTH).toInt().coerceAtLeast(1), true) else source
        try {
            val widthBytes = ceil(scaled.width / 8.0).toInt()
            var row = 0
            while (row < scaled.height) {
                ensureNotCancelled(isCancelled)
                val rows = minOf(BAND_HEIGHT, scaled.height - row)
                val payload = ByteArray(8 + widthBytes * rows)
                payload[0] = GS; payload[1] = V; payload[2] = ZERO; payload[3] = ZERO
                payload[4] = (widthBytes and 0xff).toByte(); payload[5] = ((widthBytes shr 8) and 0xff).toByte()
                payload[6] = (rows and 0xff).toByte(); payload[7] = ((rows shr 8) and 0xff).toByte()
                for (y in 0 until rows) for (x in 0 until scaled.width) {
                    val pixel = scaled.getPixel(x, row + y)
                    val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                    if (luminance < 160) {
                        val offset = 8 + y * widthBytes + x / 8
                        payload[offset] = (payload[offset].toInt() or (0x80 shr (x % 8))).toByte()
                    }
                }
                transport.write(payload)
                row += rows
            }
        } finally {
            if (scaled !== source) scaled.recycle()
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
