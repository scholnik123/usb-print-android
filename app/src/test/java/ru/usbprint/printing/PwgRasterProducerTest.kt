package ru.usbprint.printing

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.PrintJobStatus

class PwgRasterProducerTest {
    private val layout = RasterPageLayout(
        paper = PaperSize.A4,
        dpi = 300,
        widthPx = 8,
        heightPx = 2,
        widthPoints = 595,
        heightPoints = 842,
        content = PixelRect(0, 0, 8, 2),
        orientation = Orientation.PORTRAIT
    )

    @Test fun writesExactCompletePwgStream() = runBlocking {
        val output = ByteArrayOutputStream()
        val page = FakePage(listOf(ByteArray(1), byteArrayOf(0xff.toByte())))

        PwgRasterProducer.write(
            pages = listOf(1),
            openPage = { page },
            writeBytes = { output.write(it) }
        )

        val expected = ByteArrayOutputStream().apply {
            write(PwgRasterEncoder.syncWord)
            write(page.header.toBytes())
            write(PwgRasterEncoder.encodeLine(ByteArray(1), 1))
            write(PwgRasterEncoder.encodeLine(byteArrayOf(0xff.toByte()), 1))
        }.toByteArray()
        assertArrayEquals(expected, output.toByteArray())
        assertTrue(page.closed)
    }

    @Test fun preservesPlannedMultiPageOrderAndReportsCompletion() = runBlocking {
        val opened = mutableListOf<Int>()
        val completed = mutableListOf<Pair<Int, Int>>()
        val pages = mutableListOf<FakePage>()

        PwgRasterProducer.write(
            pages = listOf(3, 1, 3),
            openPage = { sourcePage ->
                opened += sourcePage
                FakePage(listOf(ByteArray(1), ByteArray(1))).also(pages::add)
            },
            writeBytes = {},
            onPageCompleted = { done, total -> completed += done to total }
        )

        assertEquals(listOf(3, 1, 3), opened)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), completed)
        assertTrue(pages.all { it.closed })
    }

    @Test fun cancellationBeforeStartWritesNoBytes() = runBlocking {
        val output = ByteArrayOutputStream()
        val error = runCatching {
            PwgRasterProducer.write(
                pages = listOf(1),
                openPage = { FakePage(listOf(ByteArray(1), ByteArray(1))) },
                writeBytes = { output.write(it) },
                isCancelled = { true }
            )
        }.exceptionOrNull() as PrintException

        assertEquals(AppError.PRINT_CANCELLED, error.error)
        assertEquals(0, output.size())
    }

    @Test fun recordsGeneratedBytesEncodingRenderingAndPhysicalSheets() = runBlocking {
        val output = ByteArrayOutputStream()
        val metrics = PrintMetricsCollector("metrics1", BackendId.PWG_RASTER, startedAtEpochMs = 1L)
        PwgRasterProducer.write(
            pages = listOf(1),
            openPage = { FakePage(listOf(ByteArray(1), ByteArray(1))) },
            writeBytes = { output.write(it) },
            metrics = metrics
        )

        val result = metrics.finish(PrintJobStatus.SENT)
        assertEquals(output.size().toLong(), result.bytesGenerated)
        assertEquals(1, result.physicalSheetsGenerated)
        assertTrue(result.peakRasterBufferBytes != null && result.peakRasterBufferBytes >= 1L)
        assertTrue(result.renderTimeMs != null)
        assertTrue(result.encodeTimeMs != null)
    }

    private inner class FakePage(private val rows: List<ByteArray>) : PwgRasterPage {
        override val header = PwgRasterHeader(layout, RasterColorMode.MONOCHROME, duplex = false, tumble = false)
        var closed = false

        override fun renderRow(rowIndex: Int, destination: ByteArray) {
            rows[rowIndex].copyInto(destination)
        }

        override fun close() {
            closed = true
        }
    }
}
