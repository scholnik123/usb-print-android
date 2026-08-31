package ru.usbprint.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException

private class FakeUsbTransport(private val results: ArrayDeque<Int>) : UsbWriteSink {
    val bytes = mutableListOf<Byte>()
    override fun writeChunk(chunk: ByteArray, timeoutMs: Int): Int {
        val result = if (results.isEmpty()) chunk.size else results.removeFirst()
        if (result > 0) bytes += chunk.take(result.coerceAtMost(chunk.size))
        return result
    }
}

class UsbTransferWriterTest {
    @Test fun completesPartialWrites() {
        val fake = FakeUsbTransport(ArrayDeque(listOf(5, 11, 16_384)))
        val source = ByteArray(20_000) { it.toByte() }
        assertEquals(source.size, UsbTransferWriter.writeAll(fake, source, 100))
        assertEquals(source.toList(), fake.bytes)
    }
    @Test fun translatesTimeout() {
        val error = runCatching { UsbTransferWriter.writeAll(FakeUsbTransport(ArrayDeque(listOf(0))), ByteArray(3), 100) }.exceptionOrNull() as PrintException
        assertEquals(AppError.TRANSFER_TIMEOUT, error.error)
    }
    @Test fun stopsWhenCancelled() {
        val error = runCatching { UsbTransferWriter.writeAll(FakeUsbTransport(ArrayDeque()), ByteArray(3), 100) { true } }.exceptionOrNull()
        assertTrue(error is PrintException && error.error == AppError.PRINT_CANCELLED)
    }
}
