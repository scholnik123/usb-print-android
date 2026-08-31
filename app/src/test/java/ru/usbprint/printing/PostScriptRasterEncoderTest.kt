package ru.usbprint.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.PrintSettings

class PostScriptRasterEncoderTest {
    @Test fun emitsLevel2ImageFramingAndAsciiHex() {
        val layout = PrintLayoutEngine.create(PrintSettings(), 595, 842, 300)
        assertTrue(PostScriptRasterEncoder.prolog().decodeToString().startsWith("%!PS-Adobe-3.0"))
        assertTrue(PostScriptRasterEncoder.beginPage(layout, 1, true, false).decodeToString().contains("colorimage"))
        assertEquals("00FF10\n", PostScriptRasterEncoder.asciiHex(byteArrayOf(0, -1, 16)).decodeToString())
    }
}
