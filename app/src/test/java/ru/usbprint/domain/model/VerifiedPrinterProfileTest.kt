package ru.usbprint.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedPrinterProfileTest {
    @Test fun onlyExplicitCorrectObservationsPromoteConfirmation() {
        val first = profile(null, HardwareTestObservation(HardwareTestOutcome.PRINTED_CORRECTLY, observedAtEpochMs = 100))
        val second = profile(first, HardwareTestObservation(HardwareTestOutcome.PRINTED_CORRECTLY, observedAtEpochMs = 200))

        assertEquals(VerifiedPrinterStatus.USER_CONFIRMED, first.status)
        assertEquals(VerifiedPrinterStatus.MULTIPLE_TESTS_CONFIRMED, second.status)
        assertEquals(2, second.history.size)
    }

    @Test fun partialFailedAndOtherNeverBecomeConfirmed() {
        val partial = profile(null, HardwareTestObservation(
            HardwareTestOutcome.PRINTED_WITH_ISSUES,
            setOf(HardwarePrintIssue.CROPPED),
            observedAtEpochMs = 100
        ))
        val failed = profile(partial, HardwareTestObservation(HardwareTestOutcome.ACCEPTED_NO_PAGE, observedAtEpochMs = 200))
        val other = profile(failed, HardwareTestObservation(HardwareTestOutcome.OTHER, notes = "Требуется проверка", observedAtEpochMs = 300))

        assertEquals(VerifiedPrinterStatus.PARTIAL, partial.status)
        assertEquals(VerifiedPrinterStatus.FAILED, failed.status)
        assertEquals(VerifiedPrinterStatus.UNTESTED, other.status)
    }

    @Test fun mixedHistoryDoesNotClaimMultipleConfirmedTests() {
        val failed = profile(null, HardwareTestObservation(HardwareTestOutcome.NOTHING_HAPPENED, observedAtEpochMs = 100))
        val firstSuccess = profile(failed, HardwareTestObservation(HardwareTestOutcome.PRINTED_CORRECTLY, observedAtEpochMs = 200))
        val secondSuccess = profile(firstSuccess, HardwareTestObservation(HardwareTestOutcome.PRINTED_CORRECTLY, observedAtEpochMs = 300))

        assertEquals(VerifiedPrinterStatus.USER_CONFIRMED, secondSuccess.status)
    }

    @Test fun encoderChangeRequiresRevalidationWithoutDeletingHistory() {
        val original = profile(null, HardwareTestObservation(HardwareTestOutcome.PRINTED_CORRECTLY, observedAtEpochMs = 100))
        val invalidated = VerifiedPrinterProfileFactory.revalidate(original, mapOf(BackendId.PWG_RASTER to 3))

        assertEquals(VerifiedPrinterStatus.NEEDS_REVALIDATION, invalidated.status)
        assertEquals(original.history, invalidated.history)
        assertEquals(original.result, invalidated.result)
    }

    @Test fun identityIsStableHashAndNeverContainsRawSerial() {
        val printer = printer(serial = "PRIVATE-SERIAL-123")
        val first = PrinterIdentityHasher.hash(printer, "device-key-one")
        val second = PrinterIdentityHasher.hash(printer, "device-key-two")
        val different = PrinterIdentityHasher.hash(printer(serial = "OTHER"), "device-key-one")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertFalse(first.contains("PRIVATE", ignoreCase = true))
        assertNotEquals(first, different)
    }

    @Test fun historyIsBounded() {
        var current: VerifiedPrinterProfile? = null
        repeat(MAX_PROFILE_HISTORY + 5) { index ->
            current = profile(current, HardwareTestObservation(HardwareTestOutcome.PRINTED_CORRECTLY, observedAtEpochMs = (index + 1).toLong()))
        }
        assertEquals(MAX_PROFILE_HISTORY, current!!.history.size)
        assertEquals(6L, current!!.history.first().testedAtEpochMs)
    }

    private fun profile(existing: VerifiedPrinterProfile?, observation: HardwareTestObservation) =
        VerifiedPrinterProfileFactory.record(
            existing = existing,
            printer = printer(),
            deviceKey = "device-key",
            appVersion = "1.0.0",
            backend = BackendId.PWG_RASTER,
            encoderVersion = 2,
            settings = PrintSettings(
                paperSize = PaperSize.A4,
                resolution = PrinterResolution.DPI_600,
                colorMode = ColorMode.GRAYSCALE,
                duplexMode = DuplexMode.LONG_EDGE
            ),
            observation = observation
        )

    private fun printer(serial: String? = "SERIAL") = PrinterCapabilities(
        manufacturer = "Example",
        model = "Printer",
        serialNumber = serial,
        vendorId = 0x1234,
        productId = 0x5678,
        usbDeviceId = 9,
        supportedLanguages = setOf(PrinterLanguage.PWG_RASTER),
        ipp = IppPrinterInfo(documentFormatsSupported = setOf("image/pwg-raster"))
    )
}
