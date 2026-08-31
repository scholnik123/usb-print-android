package ru.usbprint.ipp

import java.io.InputStream
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.usb.UsbTransport

class IppHttpAndClientTest {
    @Test fun encodesRequiredHttp11Headers() {
        val text = IppHttpRequestEncoder.encodeHeaders(123).toString(StandardCharsets.US_ASCII)
        assertTrue(text.startsWith("POST /ipp/print HTTP/1.1\r\n"))
        assertTrue(text.contains("Host: localhost\r\n"))
        assertTrue(text.contains("Content-Type: application/ipp\r\n"))
        assertTrue(text.contains("Content-Length: 123\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test fun accumulatesPartialContentLengthResponse() {
        val body = byteArrayOf(1, 1, 0, 0, 0, 0, 0, 4, 3)
        val wire = ("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body
        val accumulator = IppHttpResponseAccumulator()
        assertEquals(null, accumulator.append(wire.copyOfRange(0, 17)))
        assertEquals(null, accumulator.append(wire.copyOfRange(17, wire.size - 2)))
        assertArrayEquals(body, accumulator.append(wire.copyOfRange(wire.size - 2, wire.size))!!.body)
    }

    @Test fun decodesChunkedResponseAcrossReads() {
        val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Type: application/ipp\r\n\r\n4\r\nABCD\r\n3\r\nXYZ\r\n0\r\n\r\n".toByteArray()
        val accumulator = IppHttpResponseAccumulator()
        var response: IppHttpResponse? = null
        wire.toList().chunked(3).forEach { response = accumulator.append(it.toByteArray()) ?: response }
        assertArrayEquals("ABCDXYZ".toByteArray(), response!!.body)
    }

    @Test fun rejectsHttp10AndOversizedBody() {
        val http10 = "HTTP/1.0 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray()
        assertTrue(runCatching { IppHttpResponseAccumulator().append(http10) }.isFailure)
        val oversized = "HTTP/1.1 200 OK\r\nContent-Length: 99\r\n\r\n".toByteArray()
        assertTrue(runCatching { IppHttpResponseAccumulator(maxBodyBytes = 8).append(oversized) }.isFailure)
    }

    @Test fun checksResponseRequestId() = runBlocking {
        val session = object : IppSession {
            override suspend fun exchange(ippData: ByteArray, document: InputStream?, documentLength: Long, isCancelled: () -> Boolean): ByteArray = successResponse(999)
        }
        val error = runCatching { IppClient(session, requestIds = AtomicInteger(20)).getPrinterAttributes() }.exceptionOrNull() as PrintException
        assertEquals(AppError.IPP_REQUEST_ID_MISMATCH, error.error)
    }

    @Test fun getPrinterAttributesUsesBoundedRequestedList() = runBlocking {
        var captured = ByteArray(0)
        val session = object : IppSession {
            override suspend fun exchange(ippData: ByteArray, document: InputStream?, documentLength: Long, isCancelled: () -> Boolean): ByteArray {
                captured = ippData
                val id = ((ippData[4].toInt() and 0xff) shl 24) or ((ippData[5].toInt() and 0xff) shl 16) or ((ippData[6].toInt() and 0xff) shl 8) or (ippData[7].toInt() and 0xff)
                return successResponse(id)
            }
        }
        IppClient(session).getPrinterAttributes()
        val decoded = IppDecoder().decodeResponse(captured)
        val requested = decoded.attributes("requested-attributes")
        assertEquals(IppClient.REQUESTED_PRINTER_ATTRIBUTES.size, requested.size)
        assertTrue(requested.size < 64)
    }

    @Test fun printJobSendsOnlyConfirmedJobAttributesAndKeepsRawKeywords() = runBlocking {
        var captured = ByteArray(0)
        var documentSeen = ByteArray(0)
        val session = object : IppSession {
            override suspend fun exchange(ippData: ByteArray, document: InputStream?, documentLength: Long, isCancelled: () -> Boolean): ByteArray {
                captured = ippData
                documentSeen = document!!.readBytes()
                assertEquals(documentSeen.size.toLong(), documentLength)
                val id = ((ippData[4].toInt() and 0xff) shl 24) or ((ippData[5].toInt() and 0xff) shl 16) or ((ippData[6].toInt() and 0xff) shl 8) or (ippData[7].toInt() and 0xff)
                return IppEncoder.encode(IppRequest(
                    operation = IppOperation.PRINT_JOB, requestId = id,
                    groups = listOf(IppAttributeGroup(IppGroupTag.JOB_ATTRIBUTES, listOf(IppAttribute("job-id", IppValue.IntegerValue(77)))))
                ))
            }
        }
        val settings = PrintSettings(
            copies = 2, paperSize = PaperSize.A4, orientation = Orientation.LANDSCAPE, colorMode = ColorMode.COLOR,
            duplexMode = DuplexMode.LONG_EDGE, resolution = PrinterResolution(600, 1200), mediaSourceKeyword = "tray-2"
        )
        val (_, reference) = IppClient(session).printJob(
            ByteArrayInputStream("%PDF".toByteArray()), 4, "application/pdf", "test.pdf", settings,
            setOf("copies", "media", "sides", "printer-resolution", "media-source"), 1
        )
        val decoded = IppDecoder().decodeResponse(captured)
        assertEquals(77, reference.jobId)
        assertEquals(IppValue.Keyword("tray-2"), decoded.first("media-source"))
        assertEquals(IppValue.Resolution(600, 1200, IppValue.Resolution.Units.DPI), decoded.first("printer-resolution"))
        assertEquals(null, decoded.first("print-color-mode"))
        assertArrayEquals("%PDF".toByteArray(), documentSeen)
    }

    @Test fun reportsUnsupportedIppVersionSeparately() = runBlocking {
        val session = object : IppSession {
            override suspend fun exchange(ippData: ByteArray, document: InputStream?, documentLength: Long, isCancelled: () -> Boolean): ByteArray {
                val id = ((ippData[4].toInt() and 0xff) shl 24) or ((ippData[5].toInt() and 0xff) shl 16) or ((ippData[6].toInt() and 0xff) shl 8) or (ippData[7].toInt() and 0xff)
                return successResponse(id).also { it[0] = 3 }
            }
        }
        val error = runCatching { IppClient(session).getPrinterAttributes() }.exceptionOrNull() as PrintException
        assertEquals(AppError.IPP_VERSION_NOT_SUPPORTED, error.error)
    }

    @Test fun usbSessionUsesExactCombinedContentLengthAndPayloadBytes() = runBlocking {
        val ipp = byteArrayOf(1, 1, 0, 2, 0, 0, 0, 9, 3)
        val document = "RaS2-pwg".toByteArray()
        val transport = RecordingIppUsbTransport(successHttpResponse(successResponse(9)))

        IppUsbSession(transport).exchange(ipp, ByteArrayInputStream(document), document.size.toLong())

        val headers = transport.writes.first().toString(StandardCharsets.US_ASCII)
        assertTrue(headers.contains("Content-Length: ${ipp.size + document.size}\r\n"))
        assertArrayEquals(ipp + document, transport.writes.drop(1).fold(ByteArray(0)) { all, part -> all + part })
    }

    @Test fun usbSessionStopsDuringUploadWithoutSecondRequest() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val transport = RecordingIppUsbTransport(successHttpResponse(successResponse(1))) { writeCount ->
            if (writeCount == 3) cancelled.set(true)
        }
        val document = ByteArray(32 * 1024)

        val error = runCatching {
            IppUsbSession(transport).exchange(byteArrayOf(1, 1, 0, 2, 0, 0, 0, 1, 3), ByteArrayInputStream(document), document.size.toLong()) {
                cancelled.get()
            }
        }.exceptionOrNull() as PrintException

        assertEquals(AppError.PRINT_CANCELLED, error.error)
        assertEquals(3, transport.writes.size)
    }

    private fun successResponse(requestId: Int) = byteArrayOf(
        1, 1, 0, 0,
        (requestId ushr 24).toByte(), (requestId ushr 16).toByte(), (requestId ushr 8).toByte(), requestId.toByte(),
        3
    )

    private fun successHttpResponse(body: ByteArray): ByteArray =
        ("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray() + body
}

private class RecordingIppUsbTransport(
    private val response: ByteArray,
    private val afterWrite: (Int) -> Unit = {}
) : UsbTransport {
    val writes = mutableListOf<ByteArray>()
    override val isConnected = true
    override suspend fun open() = Unit
    override suspend fun write(bytes: ByteArray, timeoutMs: Int) {
        writes += bytes.copyOf()
        afterWrite(writes.size)
    }
    override suspend fun read(maxBytes: Int, timeoutMs: Int): ByteArray = response.copyOf()
    override suspend fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, timeoutMs: Int): Int = 0
    override fun close() = Unit
}
