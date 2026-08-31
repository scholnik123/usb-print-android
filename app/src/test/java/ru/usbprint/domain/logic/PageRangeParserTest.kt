package ru.usbprint.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangeParserTest {
    @Test fun normalizesAndMergesRanges() {
        assertEquals(listOf(1..4, 7..7, 9..12), PageRangeParser.parse("9-12, 1-3, 4, 7", 12).getOrThrow())
    }
    @Test fun rejectsOutOfBoundsRange() { assertTrue(PageRangeParser.parse("1-5", 4).isFailure) }
}
