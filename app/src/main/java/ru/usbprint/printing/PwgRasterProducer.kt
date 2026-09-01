package ru.usbprint.printing

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException

/** One lazily rendered physical page consumed by the reusable PWG stream producer. */
interface PwgRasterPage : AutoCloseable {
    val header: PwgRasterHeader
    fun renderRow(rowIndex: Int, destination: ByteArray)
    override fun close()
}

/**
 * Produces one complete PWG Raster stream without owning its destination.
 * Legacy USB writes and IPP temporary spooling therefore use identical bytes.
 */
object PwgRasterProducer {
    suspend fun write(
        pages: List<Int>,
        openPage: (sourcePage: Int) -> PwgRasterPage,
        writeBytes: suspend (ByteArray) -> Unit,
        onPageCompleted: (completed: Int, total: Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
        metrics: PrintMetricsSink = PrintMetricsSink.NONE
    ) {
        require(pages.isNotEmpty())
        ensureNotCancelled(isCancelled)
        suspend fun emit(bytes: ByteArray) { metrics.addGeneratedBytes(bytes.size.toLong()); writeBytes(bytes) }
        emit(PwgRasterEncoder.syncWord)
        pages.forEachIndexed { index, sourcePage ->
            ensureNotCancelled(isCancelled)
            openPage(sourcePage).use { page ->
                val header = page.header
                emit(metrics.measureEncode { header.toBytes() })
                val line = ByteArray(header.bytesPerLine)
                val bytesPerColorValue = (header.bitsPerPixel + 7) / 8
                metrics.allocateRasterBuffer(line.size.toLong())
                try {
                    repeat(header.layout.heightPx) { rowIndex ->
                        ensureNotCancelled(isCancelled)
                        metrics.measureRender { page.renderRow(rowIndex, line) }
                        val encoded = metrics.measureEncode { PwgRasterEncoder.encodeLine(line, bytesPerColorValue) }
                        metrics.allocateRasterBuffer(encoded.size.toLong())
                        try { emit(encoded) } finally { metrics.releaseRasterBuffer(encoded.size.toLong()) }
                    }
                } finally {
                    metrics.releaseRasterBuffer(line.size.toLong())
                }
            }
            metrics.recordPhysicalSheet()
            onPageCompleted(index + 1, pages.size)
        }
    }

    private suspend fun ensureNotCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw PrintException(AppError.PRINT_CANCELLED)
        currentCoroutineContext().ensureActive()
    }
}
