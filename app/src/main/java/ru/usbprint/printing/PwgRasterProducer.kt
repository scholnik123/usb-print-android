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
        isCancelled: () -> Boolean = { false }
    ) {
        require(pages.isNotEmpty())
        ensureNotCancelled(isCancelled)
        writeBytes(PwgRasterEncoder.syncWord)
        pages.forEachIndexed { index, sourcePage ->
            ensureNotCancelled(isCancelled)
            openPage(sourcePage).use { page ->
                val header = page.header
                writeBytes(header.toBytes())
                val line = ByteArray(header.bytesPerLine)
                val bytesPerColorValue = (header.bitsPerPixel + 7) / 8
                repeat(header.layout.heightPx) { rowIndex ->
                    ensureNotCancelled(isCancelled)
                    page.renderRow(rowIndex, line)
                    writeBytes(PwgRasterEncoder.encodeLine(line, bytesPerColorValue))
                }
            }
            onPageCompleted(index + 1, pages.size)
        }
    }

    private suspend fun ensureNotCancelled(cancelled: () -> Boolean) {
        if (cancelled()) throw PrintException(AppError.PRINT_CANCELLED)
        currentCoroutineContext().ensureActive()
    }
}
