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
    @Test fun neverFallsBackToRawUsbOnIppOnlyInterface() {
        val capabilities = ippOnlyCapabilities()
        val layoutChanging = PrintSettings(margins = ru.usbprint.domain.model.PrintMarginsMm.ZERO)
        assertEquals(BackendId.NONE, BackendRegistry.select(capabilities, pdf.copy(sizeBytes = 100), layoutChanging).selected)
    }

    private fun ippOnlyCapabilities(): PrinterCapabilities {
        val endpoints = listOf(UsbEndpointInfo(1, "OUT", "bulk", 512), UsbEndpointInfo(0x81, "IN", "bulk", 512))
        val interfaces = listOf(UsbInterfaceInfo(1, 7, 1, 4, endpoints), UsbInterfaceInfo(2, 7, 1, 4, endpoints))
        return PrinterCapabilities(
            vendorId = 1, productId = 2, usbDeviceId = 3, interfaces = interfaces,
            supportedLanguages = setOf(PrinterLanguage.PDF),
            ipp = IppPrinterInfo(setOf(1, 2), operationsSupported = setOf(2), documentFormatsSupported = setOf("application/pdf"))
        )
    }
}
