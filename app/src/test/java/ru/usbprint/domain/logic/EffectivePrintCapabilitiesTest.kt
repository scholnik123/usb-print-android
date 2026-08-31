package ru.usbprint.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.CapabilityConfidence
import ru.usbprint.domain.model.CapabilitySource
import ru.usbprint.domain.model.CapabilityValue
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterLanguage
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrintSettings

class EffectivePrintCapabilitiesTest {
    private fun printer(vararg languages: PrinterLanguage) = PrinterCapabilities(vendorId = 1, productId = 2, usbDeviceId = 3, supportedLanguages = languages.toSet())

    @Test fun unknownPrinterDoesNotClaimAllPaperOrHighResolution() {
        val effective = BackendRegistry.effectiveFor(BackendId.PWG_RASTER, printer(PrinterLanguage.PWG_RASTER))
        assertEquals(setOf(PaperSize.A4), effective.paperSizes?.value)
        assertEquals(CapabilitySource.BACKEND_DEFAULT, effective.paperSizes?.source)
        assertEquals(setOf(PrinterResolution.DPI_300), effective.resolutions?.value)
        assertFalse(PaperSize.A3 in effective.paperSizes!!.value)
    }

    @Test fun confirmedPrinterCapabilitiesAreIntersectedWithPclBackend() {
        val capabilities = printer(PrinterLanguage.PCL).copy(
            reportedPaperSizes = CapabilityValue(setOf(PaperSize.A3, PaperSize.ENVELOPE_C5), CapabilitySource.IPP, CapabilityConfidence.CONFIRMED),
            reportedColorModes = CapabilityValue(setOf(ColorMode.COLOR), CapabilitySource.IPP, CapabilityConfidence.CONFIRMED),
            reportedResolutions = CapabilityValue(setOf(PrinterResolution.DPI_600), CapabilitySource.IPP, CapabilityConfidence.CONFIRMED)
        )
        val effective = BackendRegistry.effectiveFor(BackendId.PCL5_RASTER, capabilities)
        assertEquals(setOf(PaperSize.A3), effective.paperSizes?.value)
        assertTrue(effective.colorModes?.value.orEmpty().isEmpty())
        assertEquals(setOf(PrinterResolution.DPI_600), effective.resolutions?.value)
    }

    @Test fun validatorRejectsChoiceOutsideEffectiveIntersection() {
        val caps = BackendRegistry.effectiveFor(BackendId.PWG_RASTER, printer(PrinterLanguage.PWG_RASTER))
        val result = PrintSettingsValidator.validate(PrintSettings(paperSize = PaperSize.A3), caps, 1)
        assertTrue(result is SettingsValidation.Invalid)
    }
}
