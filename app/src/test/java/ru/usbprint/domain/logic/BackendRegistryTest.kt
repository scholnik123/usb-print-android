package ru.usbprint.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.DocumentKind
import ru.usbprint.domain.model.DocumentRef
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterLanguage
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.IppPrinterInfo
import ru.usbprint.domain.model.CapabilityConfidence
import ru.usbprint.domain.model.CapabilitySource
import ru.usbprint.domain.model.CapabilityValue
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrintMarginsMm
import ru.usbprint.domain.model.UsbEndpointInfo
import ru.usbprint.domain.model.UsbInterfaceInfo

class BackendRegistryTest {
    private val pdf = DocumentRef("content://test", "a.pdf", "application/pdf", DocumentKind.PDF)
    @Test fun prefersDirectPdfOverEscPosRaster() {
        val capabilities = PrinterCapabilities(vendorId = 1, productId = 2, usbDeviceId = 3, supportedLanguages = setOf(PrinterLanguage.PDF, PrinterLanguage.ESC_POS))
        assertEquals(BackendId.PDF_DIRECT, BackendRegistry.select(capabilities, pdf).selected)
    }
    @Test fun selectsImplementedPwgRaster() {
        val capabilities = PrinterCapabilities(vendorId = 1, productId = 2, usbDeviceId = 3, supportedLanguages = setOf(PrinterLanguage.PWG_RASTER))
        assertEquals(BackendId.PWG_RASTER, BackendRegistry.select(capabilities, pdf).selected)
    }
    @Test fun selectsPwgWhenPdfNeedsPageRange() {
        val capabilities = PrinterCapabilities(vendorId = 1, productId = 2, usbDeviceId = 3, supportedLanguages = setOf(PrinterLanguage.PDF, PrinterLanguage.PWG_RASTER))
        val settings = PrintSettings(pageSelection = PageSelection.Ranges("1", listOf(1..1)))
        assertEquals(BackendId.PWG_RASTER, BackendRegistry.select(capabilities, pdf, settings).selected)
    }
    @Test fun selectsIppDirectForConfirmedPdfPrintJob() {
        val capabilities = ippOnlyCapabilities()
        assertEquals(BackendId.IPP_DIRECT, BackendRegistry.select(capabilities, pdf.copy(sizeBytes = 100)).selected)
    }
    @Test fun selectsIppPwgForSoftwareLayoutInsteadOfRawUsbFallback() {
        val capabilities = ippOnlyCapabilities(pwg = true)
        val layoutChanging = PrintSettings(margins = PrintMarginsMm.ZERO)
        assertEquals(BackendId.IPP_PWG, BackendRegistry.select(capabilities, pdf.copy(sizeBytes = 100), layoutChanging).selected)
    }
    @Test fun selectsIppPwgForRenderedImageWhenExplicitlyReported() {
        val image = DocumentRef("content://test", "photo.png", "image/png", DocumentKind.IMAGE, pageCount = 1)
        assertEquals(BackendId.IPP_PWG, BackendRegistry.select(ippOnlyCapabilities(pwg = true), image).selected)
    }
    @Test fun ippPwgRequiresPrintJobAndExactDocumentFormat() {
        val noOperation = ippOnlyCapabilities(pwg = true).let { it.copy(ipp = it.ipp.copy(operationsSupported = emptySet())) }
        val noFormat = ippOnlyCapabilities(pwg = true).let { it.copy(ipp = it.ipp.copy(documentFormatsSupported = setOf("application/pdf"))) }
        val layoutChanging = PrintSettings(margins = PrintMarginsMm.ZERO)
        assertEquals(BackendId.NONE, BackendRegistry.select(noOperation, pdf.copy(sizeBytes = 100), layoutChanging).selected)
        assertEquals(BackendId.NONE, BackendRegistry.select(noFormat, pdf.copy(sizeBytes = 100), layoutChanging).selected)
    }
    @Test fun ippPwgRejectsRasterTypesAndResolutionsTheEncoderCannotEmit() {
        val base = ippOnlyCapabilities(pwg = true)
        val unsupportedType = base.copy(ipp = base.ipp.copy(pwgRasterDocumentTypesSupported = setOf("srgb_16")))
        val unsupportedResolution = base.copy(
            reportedResolutions = setOf(PrinterResolution(1200)).confirmed(),
            ipp = base.ipp.copy(pwgRasterResolutionsSupported = setOf(PrinterResolution(1200)))
        )
        val layoutChanging = PrintSettings(margins = PrintMarginsMm.ZERO)

        assertEquals(BackendId.NONE, BackendRegistry.select(unsupportedType, pdf.copy(sizeBytes = 100), layoutChanging).selected)
        assertEquals(BackendId.NONE, BackendRegistry.select(unsupportedResolution, pdf.copy(sizeBytes = 100), layoutChanging).selected)
    }
    @Test fun neverFallsBackToRawUsbOnIppOnlyInterface() {
        val capabilities = ippOnlyCapabilities()
        val layoutChanging = PrintSettings(margins = ru.usbprint.domain.model.PrintMarginsMm.ZERO)
        assertEquals(BackendId.NONE, BackendRegistry.select(capabilities, pdf.copy(sizeBytes = 100), layoutChanging).selected)
    }

    private fun ippOnlyCapabilities(pwg: Boolean = false): PrinterCapabilities {
        val endpoints = listOf(UsbEndpointInfo(1, "OUT", "bulk", 512), UsbEndpointInfo(0x81, "IN", "bulk", 512))
        val interfaces = listOf(UsbInterfaceInfo(1, 7, 1, 4, endpoints), UsbInterfaceInfo(2, 7, 1, 4, endpoints))
        return PrinterCapabilities(
            vendorId = 1, productId = 2, usbDeviceId = 3, interfaces = interfaces,
            supportedLanguages = if (pwg) setOf(PrinterLanguage.PDF, PrinterLanguage.PWG_RASTER) else setOf(PrinterLanguage.PDF),
            reportedPaperSizes = setOf(PaperSize.A4).confirmed(),
            reportedResolutions = setOf(PrinterResolution.DPI_300, PrinterResolution.DPI_600).confirmed(),
            reportedColorModes = setOf(ColorMode.COLOR, ColorMode.GRAYSCALE, ColorMode.MONOCHROME).confirmed(),
            ipp = IppPrinterInfo(
                setOf(1, 2),
                operationsSupported = setOf(2),
                documentFormatsSupported = if (pwg) setOf("application/pdf", "image/pwg-raster") else setOf("application/pdf"),
                pwgRasterResolutionsSupported = if (pwg) setOf(PrinterResolution.DPI_300, PrinterResolution.DPI_600) else emptySet(),
                pwgRasterDocumentTypesSupported = if (pwg) setOf("sgray_8", "srgb_8") else emptySet()
            )
        )
    }

    private fun <T> T.confirmed() = CapabilityValue(this, CapabilitySource.IPP, CapabilityConfidence.CONFIRMED)
}
