package ru.usbprint.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityRecordJsonTest {
    @Test fun exportsRequiredCompatibilityFactsAndEscapesStrings() {
        val json = CompatibilityRecordJson.encode(profile(), CompatibilityExportEnvironment("15\"beta", 35))

        assertTrue(json.startsWith("{\n"))
        assertTrue(json.endsWith("}\n"))
        assertTrue(json.contains("\"appVersion\": \"1.0.0\""))
        assertTrue(json.contains("\"release\": \"15\\\"beta\""))
        assertTrue(json.contains("\"sdk\": 35"))
        assertTrue(json.contains("\"manufacturer\": \"Example \\\"Print\\\"\""))
        assertTrue(json.contains("\"vendorId\": \"1234\""))
        assertTrue(json.contains("\"productId\": \"abcd\""))
        assertTrue(json.contains("\"backend\": \"IPP_PWG\""))
        assertTrue(json.contains("\"encoderVersion\": 2"))
        assertTrue(json.contains("\"languages\": [\"PDF\", \"PWG_RASTER\"]"))
        assertTrue(json.contains("\"ippDocumentFormats\": [\"application/pdf\", \"image/pwg-raster\"]"))
        assertTrue(json.contains("\"paper\": \"A4\""))
        assertTrue(json.contains("\"resolution\": \"600 DPI\""))
        assertTrue(json.contains("\"outcome\": \"PRINTED_WITH_ISSUES\""))
        assertTrue(json.contains("\"issues\": [\"INCORRECT_MARGINS\"]"))
    }

    @Test fun publicRecordOmitsAllPrivateAndDocumentFields() {
        val profile = profile()
        val json = CompatibilityRecordJson.encode(profile, CompatibilityExportEnvironment("15", 35))

        listOf(
            "PRIVATE-SERIAL",
            "PRIVATE-DEVICE-KEY",
            profile.deviceIdentifierHash,
            "secret-document.pdf",
            "content://private/document",
            "PRIVATE NOTE",
            "deviceIdentifierHash",
            "notes",
            "documentUri",
            "payload"
        ).forEach { forbidden -> assertFalse("Export leaked $forbidden", json.contains(forbidden, ignoreCase = true)) }
    }

    private fun profile(): VerifiedPrinterProfile {
        val printer = PrinterCapabilities(
            manufacturer = "Example \"Print\"",
            model = "Laser 1",
            serialNumber = "PRIVATE-SERIAL",
            vendorId = 0x1234,
            productId = 0xabcd,
            usbDeviceId = 1,
            supportedLanguages = setOf(PrinterLanguage.PDF, PrinterLanguage.PWG_RASTER),
            ipp = IppPrinterInfo(documentFormatsSupported = setOf("image/pwg-raster", "application/pdf"))
        )
        return VerifiedPrinterProfileFactory.record(
            existing = null,
            printer = printer,
            deviceKey = "PRIVATE-DEVICE-KEY",
            appVersion = "1.0.0",
            backend = BackendId.IPP_PWG,
            encoderVersion = 2,
            settings = PrintSettings(
                paperSize = PaperSize.A4,
                resolution = PrinterResolution.DPI_600,
                colorMode = ColorMode.GRAYSCALE,
                duplexMode = DuplexMode.OFF
            ),
            observation = HardwareTestObservation(
                outcome = HardwareTestOutcome.PRINTED_WITH_ISSUES,
                issues = setOf(HardwarePrintIssue.INCORRECT_MARGINS),
                notes = "PRIVATE NOTE secret-document.pdf content://private/document",
                observedAtEpochMs = 1234
            )
        )
    }
}
