package ru.usbprint.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test fun classifiesStandardAndStressWindowWidths() {
        val expected = mapOf(
            280 to AdaptiveWidthClass.COMPACT,
            320 to AdaptiveWidthClass.COMPACT,
            360 to AdaptiveWidthClass.COMPACT,
            400 to AdaptiveWidthClass.COMPACT,
            480 to AdaptiveWidthClass.COMPACT,
            599 to AdaptiveWidthClass.COMPACT,
            600 to AdaptiveWidthClass.MEDIUM,
            720 to AdaptiveWidthClass.MEDIUM,
            839 to AdaptiveWidthClass.MEDIUM,
            840 to AdaptiveWidthClass.EXPANDED,
            1_024 to AdaptiveWidthClass.EXPANDED,
            1_280 to AdaptiveWidthClass.EXPANDED
        )

        expected.forEach { (width, widthClass) ->
            assertEquals("width=$width", widthClass, AdaptiveLayoutPolicy.widthClass(width))
        }
    }
}
