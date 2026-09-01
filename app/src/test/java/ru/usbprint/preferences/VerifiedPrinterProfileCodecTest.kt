package ru.usbprint.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.CustomPaperSizeMicrons
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.HardwarePrintIssue
import ru.usbprint.domain.model.HardwareTestOutcome
import ru.usbprint.domain.model.HardwareTestObservation
import ru.usbprint.domain.model.IppPrinterInfo
import ru.usbprint.domain.model.Microns
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterLanguage
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.VerifiedPrinterProfileFactory

class VerifiedPrinterProfileCodecTest {
    @Test fun roundTripsVersionedProfileAndHistoryWithoutRawIdentity() {
        val printer = PrinterCapabilities(
            manufacturer = "Производитель & Co",
            model = "Model=1",
            serialNumber = "PRIVATE-SERIAL",
            vendorId = 1,
            productId = 2,
            usbDeviceId = 3,
            supportedLanguages = setOf(PrinterLanguage.PWG_RASTER, PrinterLanguage.PDF),
            ipp = IppPrinterInfo(documentFormatsSupported = setOf("application/pdf", "image/pwg-raster"))
        )
        val profile = VerifiedPrinterProfileFactory.record(
            existing = null,
            printer = printer,
            deviceKey = "PRIVATE-DEVICE-KEY",
            appVersion = "1.0.0",
            backend = BackendId.IPP_PWG,
            encoderVersion = 2,
            settings = PrintSettings(
                paperSize = PaperSize.AUTO,
                customPaperSize = CustomPaperSizeMicrons(Microns(101_600), Microns(152_400)),
                resolution = PrinterResolution.DPI_600,
                colorMode = ColorMode.GRAYSCALE,
                duplexMode = DuplexMode.OFF
            ),
            observation = HardwareTestObservation(
                HardwareTestOutcome.PRINTED_WITH_ISSUES,
                setOf(HardwarePrintIssue.INCORRECT_MARGINS),
                notes = "Поля & масштаб",
                observedAtEpochMs = 1234
            )
        )

        val encoded = VerifiedPrinterProfileCodec.encode(profile)

        assertEquals(profile, VerifiedPrinterProfileCodec.decode(encoded))
        assertEquals(101_600L, profile.customPaperSize?.width?.value)
        assertEquals(profile.customPaperSize, profile.history.single().customPaperSize)
        assertFalse(encoded.contains("PRIVATE-SERIAL"))
        assertFalse(encoded.contains("PRIVATE-DEVICE-KEY"))
    }

    @Test fun rejectsMalformedProfile() {
        assertNull(VerifiedPrinterProfileCodec.decode("schema=1&deviceHash=bad"))
    }
}
