package ru.usbprint.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogTest {
    @Test fun boundsEntryCountAndIndividualMessageLength() {
        val log = DiagnosticLog(maxEntries = 2, maxEntryChars = 4)
        log.add("first-long")
        log.add("second-long")
        log.add("third-long")

        assertEquals(2, log.entries.value.size)
        assertTrue(log.entries.value[0].endsWith("seco"))
        assertTrue(log.entries.value[1].endsWith("thir"))
    }
}
