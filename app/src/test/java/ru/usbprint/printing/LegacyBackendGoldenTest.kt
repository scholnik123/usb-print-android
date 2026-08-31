package ru.usbprint.printing

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintSettings

class LegacyBackendGoldenTest {
    @Test fun pcl5GoldenCoversMediaOrientationDpiDuplexAndRows() {
        val variants = listOf(
            Triple(PrintSettings(paperSize = PaperSize.A4), 300, DuplexMode.OFF),
            Triple(PrintSettings(paperSize = PaperSize.LETTER, orientation = Orientation.LANDSCAPE), 300, DuplexMode.LONG_EDGE),
            Triple(PrintSettings(paperSize = PaperSize.A4), 600, DuplexMode.SHORT_EDGE)
        )
        variants.forEach { (settings, dpi, duplex) ->
            val layout = PrintLayoutEngine.create(settings, 100, 200, dpi)
            val row = ByteArray((layout.widthPx + 7) / 8)
            val stream = ByteArrayOutputStream().apply {
                write(Pcl5JobEncoder.reset()); write(Pcl5JobEncoder.beginPage(layout, dpi, duplex))
                repeat(2) { write(Pcl5JobEncoder.row(row.size)); write(row) }
                write(Pcl5JobEncoder.endPage())
            }.toByteArray()
            val parsed = PclSubsetInspector.inspect(stream)
            assertEquals(Pcl5JobEncoder.paperCode(settings.paperSize), parsed.paperCode)
            assertEquals(dpi, parsed.dpi)
            assertEquals(if (settings.orientation == Orientation.LANDSCAPE) 1 else 0, parsed.orientation)
            assertEquals(if (duplex == DuplexMode.LONG_EDGE) 1 else if (duplex == DuplexMode.SHORT_EDGE) 2 else 0, parsed.duplex)
            assertEquals(2, parsed.rowLengths.size)
            assertTrue(parsed.hasRasterEnd && parsed.hasFormFeed)
        }
    }

    @Test fun postScriptGoldenHasCompleteTwoPageDscAndExactHexPayload() {
        val layout = PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A4), 2, 2, 300)
        val payload = byteArrayOf(0, 1, 2, 3, 4, 5)
        val stream = buildString {
            append(PostScriptRasterEncoder.prolog().decodeToString())
            repeat(2) { page ->
                append(PostScriptRasterEncoder.beginPage(layout, page + 1, duplex = true, tumble = false).decodeToString())
                append(PostScriptRasterEncoder.asciiHex(payload).decodeToString())
                append(PostScriptRasterEncoder.endPage().decodeToString())
            }
            append(PostScriptRasterEncoder.trailer(2).decodeToString())
        }
        assertTrue(stream.startsWith("%!PS-Adobe-3.0"))
        assertEquals(2, Regex("(?m)^%%Page: ").findAll(stream).count())
        assertEquals(2, Regex("colorimage").findAll(stream).count())
        assertEquals(2, Regex("(?m)^showpage$").findAll(stream).count())
        assertEquals(2, Regex("000102030405").findAll(stream).count())
        assertTrue(stream.contains("/PageSize ["))
        assertTrue(stream.endsWith("%%EOF\n"))
    }
}

private data class PclInspection(val paperCode: Int, val orientation: Int, val duplex: Int, val dpi: Int, val rowLengths: List<Int>, val hasRasterEnd: Boolean, val hasFormFeed: Boolean)

/** Test-only parser for the exact PCL 5 subset emitted by this application. */
private object PclSubsetInspector {
    fun inspect(bytes: ByteArray): PclInspection {
        val text = bytes.toString(Charsets.ISO_8859_1)
        require(text.startsWith("\u001bE"))
        fun capture(pattern: String) = Regex(pattern).find(text)?.groupValues?.get(1)?.toInt() ?: error("Missing PCL command $pattern")
        val rows = Regex("\u001b\\*b(\\d+)W").findAll(text).map { it.groupValues[1].toInt() }.toList()
        var scan = 0
        rows.forEach { length ->
            val command = Regex("\u001b\\*b${length}W").find(text, scan) ?: error("Missing row")
            scan = command.range.last + 1 + length
            require(scan <= bytes.size)
        }
        return PclInspection(
            capture("\u001b&l(\\d+)A"), capture("\u001b&l(\\d+)O"), capture("\u001b&l(\\d+)S"), capture("\u001b\\*t(\\d+)R"),
            rows, text.contains("\u001b*rB"), text.endsWith("\u000c")
        )
    }
}
