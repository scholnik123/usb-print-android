package ru.usbprint.printing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintSettings

class PwgGoldenStreamTest {
    @Test fun validatesCompleteGoldenFixtures() {
        val fixtures = listOf(
            Fixture(PaperSize.A4, 300, RasterColorMode.MONOCHROME),
            Fixture(PaperSize.A4, 300, RasterColorMode.GRAYSCALE),
            Fixture(PaperSize.A4, 300, RasterColorMode.RGB),
            Fixture(PaperSize.A4, 600, RasterColorMode.MONOCHROME),
            Fixture(PaperSize.LETTER, 300, RasterColorMode.MONOCHROME),
            Fixture(PaperSize.A4, 300, RasterColorMode.MONOCHROME, Orientation.LANDSCAPE),
            Fixture(PaperSize.A4, 300, RasterColorMode.MONOCHROME, duplex = DuplexMode.LONG_EDGE),
            Fixture(PaperSize.A4, 300, RasterColorMode.MONOCHROME, duplex = DuplexMode.SHORT_EDGE)
        )
        fixtures.forEach { fixture ->
            val layout = PrintLayoutEngine.create(PrintSettings(paperSize = fixture.paper, orientation = fixture.orientation), 595, 842, fixture.dpi)
            val stream = completeStream(listOf(PwgRasterHeader(layout, fixture.color, fixture.duplex != DuplexMode.OFF, fixture.duplex == DuplexMode.SHORT_EDGE)))
            val page = PwgStreamInspector.inspect(stream, 1).single()
            assertEquals(fixture.dpi, page.xDpi)
            assertEquals(layout.widthPx, page.width)
            assertEquals(layout.heightPx, page.height)
            assertEquals(fixture.duplex != DuplexMode.OFF, page.duplex)
            assertEquals(fixture.duplex == DuplexMode.SHORT_EDGE, page.tumble)
            assertTrue(page.mediaKeyword.isNotBlank())
        }
    }

    @Test fun validatesTwoPageTerminationBoundary() {
        val first = PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A4), 595, 842, 300)
        val second = PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.LETTER), 612, 792, 300)
        val pages = PwgStreamInspector.inspect(completeStream(listOf(
            PwgRasterHeader(first, RasterColorMode.MONOCHROME, false, false),
            PwgRasterHeader(second, RasterColorMode.GRAYSCALE, false, false)
        )), 2)
        assertEquals(2, pages.size)
        assertEquals(1, pages[0].bitsPerPixel)
        assertEquals(8, pages[1].bitsPerPixel)
    }

    private fun completeStream(headers: List<PwgRasterHeader>): ByteArray = ByteArrayOutputStream().apply {
        write(PwgRasterEncoder.syncWord)
        headers.forEach { header ->
            write(header.toBytes())
            val row = ByteArray(header.bytesPerLine)
            val valueSize = (header.bitsPerPixel + 7) / 8
            repeat(header.layout.heightPx) { write(PwgRasterEncoder.encodeLine(row, valueSize)) }
        }
    }.toByteArray()

    private data class Fixture(
        val paper: PaperSize,
        val dpi: Int,
        val color: RasterColorMode,
        val orientation: Orientation = Orientation.PORTRAIT,
        val duplex: DuplexMode = DuplexMode.OFF
    )
}

private data class InspectedPwgPage(
    val xDpi: Int, val yDpi: Int, val width: Int, val height: Int, val bitsPerPixel: Int,
    val bytesPerLine: Int, val colorSpace: Int, val duplex: Boolean, val tumble: Boolean, val mediaKeyword: String
)

/** Test-only parser, deliberately independent of the production writer. */
private object PwgStreamInspector {
    fun inspect(bytes: ByteArray, expectedPages: Int): List<InspectedPwgPage> {
        require(bytes.take(4).toByteArray().contentEquals("RaS2".toByteArray()))
        var cursor = 4
        val pages = buildList {
            repeat(expectedPages) {
                require(cursor + PwgRasterHeader.HEADER_SIZE <= bytes.size)
                val headerBytes = bytes.copyOfRange(cursor, cursor + PwgRasterHeader.HEADER_SIZE)
                cursor += PwgRasterHeader.HEADER_SIZE
                val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
                val page = InspectedPwgPage(
                    header.getInt(276), header.getInt(280), header.getInt(372), header.getInt(376), header.getInt(388),
                    header.getInt(392), header.getInt(400), header.getInt(272) == 1, header.getInt(368) == 1,
                    headerBytes.copyOfRange(1732, 1796).takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
                )
                require(page.width > 0 && page.height > 0 && page.bytesPerLine > 0)
                val valueSize = (page.bitsPerPixel + 7) / 8
                var rows = 0
                while (rows < page.height) {
                    require(cursor < bytes.size)
                    val verticalRepeats = (bytes[cursor++].toInt() and 0xff) + 1
                    var decoded = 0
                    while (decoded < page.bytesPerLine) {
                        require(cursor < bytes.size)
                        val control = bytes[cursor++].toInt() and 0xff
                        if (control <= 127) {
                            cursor += valueSize
                            decoded += (control + 1) * valueSize
                        } else {
                            val literalValues = 257 - control
                            cursor += literalValues * valueSize
                            decoded += literalValues * valueSize
                        }
                        require(cursor <= bytes.size && decoded <= page.bytesPerLine)
                    }
                    rows += verticalRepeats
                }
                require(rows == page.height)
                add(page)
            }
        }
        assertTrue("Unparsed bytes after complete PWG stream", cursor == bytes.size)
        return pages
    }
}
