package ru.usbprint.printing

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.CapabilityConfidence
import ru.usbprint.domain.model.CapabilitySource
import ru.usbprint.domain.model.CapabilityValue
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DocumentKind
import ru.usbprint.domain.model.DocumentRef
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.IppPrinterInfo
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterRef
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.domain.model.PrintSettings

class PwgRasterJobPlannerTest {
    @Test fun encodesPageRangeAndCopiesInPlannedPhysicalPageOrder() {
        val job = job(PrintSettings(copies = 2, pageSelection = PageSelection.Ranges("2-3", listOf(2..3))))
        assertEquals(listOf(2, 3, 2, 3), PwgRasterJobPlanner.plan(job, BackendId.IPP_PWG).pages)
    }

    @Test fun selectsConfirmedColorGrayscaleAndSixHundredDpi() {
        val color = PwgRasterJobPlanner.plan(job(PrintSettings(colorMode = ColorMode.COLOR, resolution = PrinterResolution.DPI_600)), BackendId.IPP_PWG)
        val grayscale = PwgRasterJobPlanner.plan(job(PrintSettings(colorMode = ColorMode.GRAYSCALE, resolution = PrinterResolution.DPI_600)), BackendId.IPP_PWG)

        assertEquals(RasterColorMode.RGB, color.colorMode)
        assertEquals(RasterColorMode.GRAYSCALE, grayscale.colorMode)
        assertEquals(600, color.dpi)
        assertEquals(600, grayscale.dpi)
    }

    @Test fun reportsComposedSheetCountAndLongVsShortEdgeDuplexHeaders() {
        val longEdge = PwgRasterJobPlanner.plan(job(PrintSettings(pagesPerSheet = 4, duplexMode = DuplexMode.LONG_EDGE)), BackendId.IPP_PWG)
        val shortEdge = PwgRasterJobPlanner.plan(job(PrintSettings(pagesPerSheet = 4, duplexMode = DuplexMode.SHORT_EDGE)), BackendId.IPP_PWG)

        assertEquals(2, longEdge.physicalSheetCount)
        assertEquals(true, longEdge.duplex)
        assertEquals(false, longEdge.tumble)
        assertEquals(true, shortEdge.duplex)
        assertEquals(true, shortEdge.tumble)
    }

    private fun job(settings: PrintSettings): PrintJob {
        val confirmed = { value: Set<PrinterResolution> -> CapabilityValue(value, CapabilitySource.IPP, CapabilityConfidence.CONFIRMED) }
        val capabilities = PrinterCapabilities(
            vendorId = 1,
            productId = 2,
            usbDeviceId = 3,
            reportedPaperSizes = CapabilityValue(setOf(PaperSize.A4), CapabilitySource.IPP, CapabilityConfidence.CONFIRMED),
            reportedResolutions = confirmed(setOf(PrinterResolution.DPI_300, PrinterResolution.DPI_600)),
            reportedColorModes = CapabilityValue(setOf(ColorMode.COLOR, ColorMode.GRAYSCALE, ColorMode.MONOCHROME), CapabilitySource.IPP, CapabilityConfidence.CONFIRMED),
            reportedDuplexModes = CapabilityValue(setOf(DuplexMode.OFF, DuplexMode.LONG_EDGE, DuplexMode.SHORT_EDGE), CapabilitySource.IPP, CapabilityConfidence.CONFIRMED),
            ipp = IppPrinterInfo(
                interfaceIds = setOf(1, 2),
                operationsSupported = setOf(2),
                documentFormatsSupported = setOf("image/pwg-raster"),
                pwgRasterResolutionsSupported = setOf(PrinterResolution.DPI_300, PrinterResolution.DPI_600),
                pwgRasterDocumentTypesSupported = setOf("sgray_8", "srgb_8")
            )
        )
        return PrintJob(
            document = DocumentRef("content://test", "sample.pdf", "application/pdf", DocumentKind.PDF, pageCount = 6),
            printer = PrinterRef("device", capabilities, interfaceId = 0, ippInterfaceId = 1),
            settings = settings,
            backend = BackendId.IPP_PWG
        )
    }
}
