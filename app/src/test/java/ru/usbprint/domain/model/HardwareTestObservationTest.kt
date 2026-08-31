package ru.usbprint.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareTestObservationTest {
    @Test fun exposesEveryRequiredPhysicalOutcome() {
        assertEquals(
            setOf(
                HardwareTestOutcome.PRINTED_CORRECTLY,
                HardwareTestOutcome.PRINTED_WITH_ISSUES,
                HardwareTestOutcome.ACCEPTED_NO_PAGE,
                HardwareTestOutcome.PRINTER_ERROR,
                HardwareTestOutcome.NOTHING_HAPPENED,
                HardwareTestOutcome.CONNECTION_LOST,
                HardwareTestOutcome.OTHER
            ),
            HardwareTestOutcome.entries.toSet()
        )
    }

    @Test fun exposesEveryRequiredPrintedIssue() {
        assertEquals(11, HardwarePrintIssue.entries.size)
        assertTrue(HardwarePrintIssue.entries.containsAll(setOf(
            HardwarePrintIssue.CROPPED,
            HardwarePrintIssue.WRONG_PAPER_SIZE,
            HardwarePrintIssue.WRONG_ORIENTATION,
            HardwarePrintIssue.WRONG_COLORS,
            HardwarePrintIssue.WRONG_GRAYSCALE,
            HardwarePrintIssue.BLANK_PAGE,
            HardwarePrintIssue.GARBAGE_OUTPUT,
            HardwarePrintIssue.EXTRA_PAGES,
            HardwarePrintIssue.INCORRECT_SCALE,
            HardwarePrintIssue.INCORRECT_MARGINS,
            HardwarePrintIssue.INCORRECT_DUPLEX
        )))
    }

    @Test fun issuesRequirePrintedWithIssuesOutcome() {
        assertTrue(runCatching {
            HardwareTestObservation(HardwareTestOutcome.PRINTED_CORRECTLY, setOf(HardwarePrintIssue.CROPPED))
        }.isFailure)
        assertTrue(runCatching { HardwareTestObservation(HardwareTestOutcome.PRINTED_WITH_ISSUES) }.isFailure)
    }

    @Test fun otherRequiresPrivacyReviewedNote() {
        assertTrue(runCatching { HardwareTestObservation(HardwareTestOutcome.OTHER, notes = "  ") }.isFailure)
        assertEquals("Описание", HardwareTestObservation(HardwareTestOutcome.OTHER, notes = "Описание").notes)
    }
}
