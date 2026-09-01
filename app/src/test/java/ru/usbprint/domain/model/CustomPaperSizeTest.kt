package ru.usbprint.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPaperSizeTest {
    @Test fun convertsMillimetresAndInchesToExactMicronStorage() {
        assertEquals(210_000L, Microns.fromMillimetres("210").value)
        assertEquals(215_900L, Microns.fromInches("8.5").value)
        assertEquals(21_590, Microns(215_900).toHundredthsMm())
    }

    @Test fun roundsSubMicronDecimalInputAndRejectsNonPositiveValues() {
        assertEquals(123_457L, Microns.fromMillimetres("123.4567").value)
        assertTrue(runCatching { Microns.fromMillimetres("0") }.isFailure)
        assertTrue(runCatching { Microns.fromInches("-1") }.isFailure)
    }

    @Test fun checkedRasterConversionRejectsUnsafeCustomDimensions() {
        assertEquals(1_181, RasterDimensionLimits.pixels(Microns(100_000), 300))
        assertTrue(runCatching { RasterDimensionLimits.pixels(Microns(2_000_000), 600) }.isFailure)
    }
}
