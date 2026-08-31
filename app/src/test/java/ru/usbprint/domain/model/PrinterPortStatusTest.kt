package ru.usbprint.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterPortStatusTest {
    @Test fun decodesOnlyUsbPrinterClassStatusBits() {
        val ready = PrinterPortStatus(0x18)
        assertTrue(ready.selected)
        assertTrue(ready.notError)
        assertFalse(ready.paperEmpty)
    }
    @Test fun reportsPaperEmptyWithoutInventingOtherBits() {
        val status = PrinterPortStatus(0x20)
        assertTrue(status.paperEmpty)
        assertFalse(status.selected)
        assertFalse(status.notError)
    }
}
