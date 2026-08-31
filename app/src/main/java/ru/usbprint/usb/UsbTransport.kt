package ru.usbprint.usb

import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException

interface UsbTransport : AutoCloseable {
    suspend fun open()
    suspend fun write(bytes: ByteArray, timeoutMs: Int = DEFAULT_TIMEOUT_MS)
    suspend fun read(maxBytes: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): ByteArray
    suspend fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Int
    val isConnected: Boolean
    override fun close()

    companion object { const val DEFAULT_TIMEOUT_MS = 15_000 }
}

interface UsbWriteSink { fun writeChunk(chunk: ByteArray, timeoutMs: Int): Int }

object UsbTransferWriter {
    const val CHUNK_SIZE = 16 * 1024

    /** A partial bulk write is retried from its unwritten offset; zero is a timeout. */
    fun writeAll(sink: UsbWriteSink, bytes: ByteArray, timeoutMs: Int, isCancelled: () -> Boolean = { false }): Int {
        var offset = 0
        while (offset < bytes.size) {
            if (isCancelled()) throw PrintException(AppError.PRINT_CANCELLED)
            val count = minOf(CHUNK_SIZE, bytes.size - offset)
            val chunk = bytes.copyOfRange(offset, offset + count)
            val written = sink.writeChunk(chunk, timeoutMs)
            when {
                written > 0 -> offset += minOf(written, chunk.size)
                written == 0 -> throw PrintException(AppError.TRANSFER_TIMEOUT)
                else -> throw PrintException(AppError.TRANSFER_ERROR)
            }
        }
        return offset
    }
}
