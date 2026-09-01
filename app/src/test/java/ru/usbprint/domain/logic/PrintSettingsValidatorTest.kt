package ru.usbprint.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.CapabilityConfidence
import ru.usbprint.domain.model.CapabilitySource
import ru.usbprint.domain.model.CapabilityValue
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.CustomPaperRangeMicrons
import ru.usbprint.domain.model.CustomPaperSizeMicrons
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.IppPrinterInfo
import ru.usbprint.domain.model.Microns
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrintSettings

class PrintSettingsValidatorTest {
    @Test fun acceptsSaneSettings() { assertNull(PrintSettingsValidator.validate(PrintSettings(copies = 2), 8)) }
    @Test fun rejectsInvalidCopiesAndRanges() {
        assertEquals("Количество копий должно быть от 1 до 99.", PrintSettingsValidator.validate(PrintSettings(copies = 0), 8))
        assertEquals("Диапазон страниц выходит за пределы документа.", PrintSettingsValidator.validate(PrintSettings(pageSelection = PageSelection.Ranges("9", listOf(9..9))), 8))
    }
    @Test fun rejectsInvalidNUpSpacing() {
        assertEquals("Интервал N-up должен быть от 0 до 20 мм.", PrintSettingsValidator.validate(PrintSettings(nUpSpacingMm = 21f), 8))
    }
    @Test fun acceptsOnlyCustomSizeInsideConfirmedMicronRange() {
        val effective = customEffective()
        val valid = PrintSettings(customPaperSize = CustomPaperSizeMicrons(Microns(150_000), Microns(200_000)))
        val outside = valid.copy(customPaperSize = CustomPaperSizeMicrons(Microns(99_999), Microns(200_000)))
        assertTrue(PrintSettingsValidator.validate(valid, effective, 1) is SettingsValidation.Valid)
        assertTrue((PrintSettingsValidator.validate(outside, effective, 1) as SettingsValidation.Invalid).errors.any { it.contains("диапазон") })
    }

    @Test fun rejectsCustomSizeWhenConfirmedRangeIsUnavailable() {
        val settings = PrintSettings(customPaperSize = CustomPaperSizeMicrons(Microns(150_000), Microns(200_000)))
        val result = PrintSettingsValidator.validate(settings, customEffective().copy(customPaperRangeMicrons = null), 1)
        assertTrue((result as SettingsValidation.Invalid).errors.any { it.contains("подтверждённом") })
    }

    @Test fun validatesHardwareMarginsAgainstTheRequestedOrientation() {
        val settings = PrintSettings(
            customPaperSize = CustomPaperSizeMicrons(Microns(100_000), Microns(150_000)),
            orientation = Orientation.PORTRAIT,
            margins = ru.usbprint.domain.model.PrintMarginsMm.ZERO
        )
        val hardwareMargins = CapabilityValue(HardwareMarginsMm(60f, 0f, 40f, 0f), CapabilitySource.IPP, CapabilityConfidence.CONFIRMED)
        val effective = customEffective().copy(hardwareMargins = hardwareMargins)

        assertTrue((PrintSettingsValidator.validate(settings, effective, 1) as SettingsValidation.Invalid).errors.any { it.contains("Поля") })
        assertTrue(PrintSettingsValidator.validate(settings.copy(orientation = Orientation.LANDSCAPE), effective, 1) is SettingsValidation.Valid)
        assertTrue(PrintSettingsValidator.validate(settings.copy(orientation = Orientation.AUTO), effective, 1) is SettingsValidation.Invalid)
    }

    @Test fun rejectsCustomMediaSmallerThanMarginsAndUnsafeRasterSize() {
        val tooSmall = PrintSettings(
            customPaperSize = CustomPaperSizeMicrons(Microns(100_000), Microns(150_000)),
            margins = ru.usbprint.domain.model.PrintMarginsMm.uniform(60f)
        )
        assertTrue((PrintSettingsValidator.validate(tooSmall, customEffective(), 1) as SettingsValidation.Invalid).errors.any { it.contains("Поля") })

        val hugeRange = CustomPaperRangeMicrons(Microns(100_000), Microns(2_000_000), Microns(100_000), Microns(2_000_000))
        val huge = PrintSettings(
            customPaperSize = CustomPaperSizeMicrons(Microns(1_000_000), Microns(1_000_000)),
            resolution = PrinterResolution.DPI_600
        )
        assertTrue((PrintSettingsValidator.validate(huge, customEffective(hugeRange), 1) as SettingsValidation.Invalid).errors.any { it.contains("raster budget") })
    }

    private fun customEffective(
        range: CustomPaperRangeMicrons = CustomPaperRangeMicrons(Microns(100_000), Microns(300_000), Microns(150_000), Microns(500_000))
    ) = BackendRegistry.effectiveFor(
        BackendId.IPP_PWG,
        PrinterCapabilities(
            vendorId = 1, productId = 2, usbDeviceId = 3,
            reportedCustomPaperRangeMicrons = CapabilityValue(range, CapabilitySource.IPP, CapabilityConfidence.CONFIRMED),
            reportedResolutions = CapabilityValue(setOf(PrinterResolution.DPI_300, PrinterResolution.DPI_600), CapabilitySource.IPP, CapabilityConfidence.CONFIRMED),
            reportedColorModes = CapabilityValue(setOf(ColorMode.GRAYSCALE), CapabilitySource.IPP, CapabilityConfidence.CONFIRMED),
            ipp = IppPrinterInfo(
                jobCreationAttributesSupported = setOf("media-col"), mediaColSupported = setOf("media-size"),
                pwgRasterResolutionsSupported = setOf(PrinterResolution.DPI_300, PrinterResolution.DPI_600),
                pwgRasterDocumentTypesSupported = setOf("sgray_8")
            )
        )
    )
}
