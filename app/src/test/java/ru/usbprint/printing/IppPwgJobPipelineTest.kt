package ru.usbprint.printing

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.CustomPaperSizeMicrons
import ru.usbprint.domain.model.Microns
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.ipp.IppAttribute
import ru.usbprint.ipp.IppAttributeGroup
import ru.usbprint.ipp.IppClient
import ru.usbprint.ipp.IppDecoder
import ru.usbprint.ipp.IppEncoder
import ru.usbprint.ipp.IppGroupTag
import ru.usbprint.ipp.IppOperation
import ru.usbprint.ipp.IppRequest
import ru.usbprint.ipp.IppSession
import ru.usbprint.ipp.IppValue

class IppPwgJobPipelineTest {
    @Test fun submitsExactPwgBytesWithMimeAndWithoutDuplicatedSoftwareSettings() = withTempDirectory { cache -> runBlocking {
        val payload = "RaS2-pwg-payload".toByteArray()
        val session = CapturingSession()
        val settings = PrintSettings(
            copies = 3,
            pageSelection = PageSelection.Ranges("2-3", listOf(2..3)),
            mediaSourceKeyword = "tray-2"
        )

        IppPwgJobPipeline(IppPwgSpoolManager(cache, maxBytes = 1024)).submit(
            client = IppClient(session, requestIds = AtomicInteger(40)),
            jobName = "sample.pdf",
            settings = settings,
            supportedAttributeNames = setOf("copies", "page-ranges", "media-source"),
            pageCount = 6,
            producer = PwgSpoolProducer { write ->
                write(payload.copyOfRange(0, 5))
                write(payload.copyOfRange(5, payload.size))
            }
        )

        assertEquals(1, session.exchangeCalls)
        assertEquals(payload.size.toLong(), session.documentLength)
        assertArrayEquals(payload, session.documentBytes)
        val request = IppDecoder().decodeResponse(session.ippBytes)
        assertEquals(IppValue.MimeMediaType(IppPwgJobPipeline.PWG_MIME), request.first("document-format"))
        assertEquals(IppValue.Keyword("tray-2"), request.first("media-source"))
        assertEquals(null, request.first("copies"))
        assertEquals(null, request.first("page-ranges"))
        assertNoSpools(cache)
    } }

    @Test fun cancellationBeforeGenerationDoesNotCreateOrUploadSpool() = withTempDirectory { cache -> runBlocking {
        val session = CapturingSession()
        var producerCalled = false
        val error = runCatching {
            IppPwgJobPipeline(IppPwgSpoolManager(cache, maxBytes = 1024)).submit(
                client = IppClient(session),
                jobName = "cancel.pdf",
                settings = PrintSettings(),
                supportedAttributeNames = emptySet(),
                pageCount = 1,
                producer = PwgSpoolProducer { producerCalled = true },
                isCancelled = { true }
            )
        }.exceptionOrNull() as PrintException

        assertEquals(AppError.PRINT_CANCELLED, error.error)
        assertFalse(producerCalled)
        assertEquals(0, session.exchangeCalls)
        assertNoSpools(cache)
    } }

    @Test fun customMediaColSurvivesPwgPassthroughFilter() = withTempDirectory { cache -> runBlocking {
        val session = CapturingSession()
        IppPwgJobPipeline(IppPwgSpoolManager(cache, maxBytes = 1024)).submit(
            client = IppClient(session),
            jobName = "custom.pdf",
            settings = PrintSettings(customPaperSize = CustomPaperSizeMicrons(Microns(100_000), Microns(150_000))),
            supportedAttributeNames = setOf("media-col", "copies"),
            pageCount = 1,
            producer = PwgSpoolProducer { it(byteArrayOf(1)) },
            mediaColSupported = setOf("media-size")
        )

        val request = IppDecoder().decodeResponse(session.ippBytes)
        assertTrue(request.first("media-col") is IppValue.CollectionValue)
        assertEquals(null, request.first("copies"))
        assertNoSpools(cache)
    } }

    @Test fun cancellationDuringUploadDeletesSpoolAndDoesNotRetry() = withTempDirectory { cache -> runBlocking {
        var cancelled = false
        val session = CapturingSession { document, _, _ ->
            document!!.read()
            cancelled = true
            throw PrintException(AppError.PRINT_CANCELLED)
        }
        val error = runCatching {
            IppPwgJobPipeline(IppPwgSpoolManager(cache, maxBytes = 1024)).submit(
                client = IppClient(session),
                jobName = "cancel-upload.pdf",
                settings = PrintSettings(),
                supportedAttributeNames = emptySet(),
                pageCount = 1,
                producer = PwgSpoolProducer { it("payload".toByteArray()) },
                isCancelled = { cancelled }
            )
        }.exceptionOrNull() as PrintException

        assertEquals(AppError.PRINT_CANCELLED, error.error)
        assertEquals(1, session.exchangeCalls)
        assertNoSpools(cache)
    } }

    @Test fun httpFailureAndIppRejectBothDeleteSpool() = withTempDirectory { cache -> runBlocking {
        val manager = IppPwgSpoolManager(cache, maxBytes = 1024)
        val httpError = runCatching {
            IppPwgJobPipeline(manager).submit(
                client = IppClient(CapturingSession { _, _, _ -> throw PrintException(AppError.IPP_HTTP_ERROR) }),
                jobName = "http.pdf",
                settings = PrintSettings(),
                supportedAttributeNames = emptySet(),
                pageCount = 1,
                producer = PwgSpoolProducer { it(byteArrayOf(1)) }
            )
        }.exceptionOrNull() as PrintException
        assertEquals(AppError.IPP_HTTP_ERROR, httpError.error)
        assertNoSpools(cache)

        val rejectSession = CapturingSession { _, _, requestId -> ippResponse(requestId, 0x040A) }
        val reject = runCatching {
            IppPwgJobPipeline(manager).submit(
                client = IppClient(rejectSession),
                jobName = "reject.pdf",
                settings = PrintSettings(),
                supportedAttributeNames = emptySet(),
                pageCount = 1,
                producer = PwgSpoolProducer { it(byteArrayOf(2)) }
            )
        }.exceptionOrNull() as PrintException
        assertEquals(AppError.IPP_DOCUMENT_FORMAT_NOT_SUPPORTED, reject.error)
        assertEquals(1, rejectSession.exchangeCalls)
        assertNoSpools(cache)
    } }

    private inner class CapturingSession(
        private val exchange: ((InputStream?, Long, Int) -> ByteArray)? = null
    ) : IppSession {
        var exchangeCalls = 0
        var ippBytes = ByteArray(0)
        var documentBytes = ByteArray(0)
        var documentLength = -1L

        override suspend fun exchange(
            ippData: ByteArray,
            document: InputStream?,
            documentLength: Long,
            isCancelled: () -> Boolean
        ): ByteArray {
            exchangeCalls++
            ippBytes = ippData
            this.documentLength = documentLength
            val requestId = requestId(ippData)
            exchange?.let { return it(document, documentLength, requestId) }
            documentBytes = document!!.readBytes()
            return ippResponse(requestId, 0)
        }
    }

    private fun requestId(bytes: ByteArray): Int =
        ((bytes[4].toInt() and 0xff) shl 24) or ((bytes[5].toInt() and 0xff) shl 16) or
            ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)

    private fun ippResponse(requestId: Int, statusCode: Int): ByteArray = IppEncoder.encode(
        IppRequest(
            operation = IppOperation.PRINT_JOB,
            requestId = requestId,
            groups = if (statusCode == 0) listOf(
                IppAttributeGroup(IppGroupTag.JOB_ATTRIBUTES, listOf(IppAttribute("job-id", IppValue.IntegerValue(7))))
            ) else emptyList()
        )
    ).also {
        it[2] = (statusCode ushr 8).toByte()
        it[3] = statusCode.toByte()
    }

    private fun assertNoSpools(cache: File) {
        val files = File(cache, IppPwgSpoolManager.DIRECTORY_NAME).listFiles().orEmpty()
            .filter { it.name.startsWith(IppPwgSpoolManager.FILE_PREFIX) }
        assertTrue("Temporary PWG spool was not deleted", files.isEmpty())
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("usb-print-ipp-pwg-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
