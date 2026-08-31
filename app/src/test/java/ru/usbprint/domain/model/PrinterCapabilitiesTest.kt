package ru.usbprint.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterCapabilitiesTest {
    @Test fun exposesOnlyDeclaredLanguagesAndHasSafeFallbackName() {
        val capabilities = PrinterCapabilities(vendorId = 0x1234, productId = 0xabcd, usbDeviceId = 1, supportedLanguages = setOf(PrinterLanguage.PDF, PrinterLanguage.PCL))
        assertTrue(capabilities.supportsPdf)
        assertTrue(capabilities.supportsPcl)
        assertEquals("USB-принтер 1234:abcd", capabilities.displayName)
    }
}
