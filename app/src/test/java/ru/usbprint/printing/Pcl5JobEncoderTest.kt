package ru.usbprint.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.PrintSettings

class Pcl5JobEncoderTest {
    @Test fun emitsPclResetA4AndRasterCommands() {
        val layout = PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A4), 595, 842, 300)
        assertEquals("\u001bE", Pcl5JobEncoder.reset().decodeToString())
        assertTrue(Pcl5JobEncoder.beginPage(layout, 300, DuplexMode.OFF).decodeToString().contains("&l26A"))
        assertEquals("\u001b*b42W", Pcl5JobEncoder.row(42).decodeToString())
        assertTrue(Pcl5JobEncoder.endPage().decodeToString().endsWith("\u000c"))
    }
}
