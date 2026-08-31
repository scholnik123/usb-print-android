package ru.usbprint.ipp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.CapabilitySource
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.IppPrinterInfo
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterResolution

class IppCapabilityMapperTest {
    @Test fun mapsOnlyReturnedCapabilitiesWithIppProvenance() {
        val response = IppResponse(IppVersion(), 0, 41, listOf(IppAttributeGroup(IppGroupTag.PRINTER_ATTRIBUTES, listOf(
            IppAttribute("printer-make-and-model", IppValue.TextValue("Example 6000")),
            IppAttribute("document-format-supported", listOf(IppValue.MimeMediaType("application/pdf"), IppValue.MimeMediaType("image/pwg-raster"))),
            IppAttribute("operations-supported", listOf(IppValue.EnumValue(2), IppValue.EnumValue(9), IppValue.EnumValue(11))),
            IppAttribute("media-supported", listOf(IppValue.Keyword("iso_a4_210x297mm"), IppValue.Keyword("na_letter_8.5x11in"))),
            IppAttribute("printer-resolution-supported", listOf(IppValue.Resolution(600, 1200, IppValue.Resolution.Units.DPI))),
            IppAttribute("pwg-raster-document-resolution-supported", listOf(IppValue.Resolution(300, 300, IppValue.Resolution.Units.DPI), IppValue.Resolution(600, 600, IppValue.Resolution.Units.DPI))),
            IppAttribute("pwg-raster-document-type-supported", listOf(IppValue.Keyword("sgray_8"), IppValue.Keyword("srgb_8"))),
            IppAttribute("print-color-mode-supported", listOf(IppValue.Keyword("color"), IppValue.Keyword("monochrome"))),
            IppAttribute("sides-supported", listOf(IppValue.Keyword("one-sided"), IppValue.Keyword("two-sided-long-edge"))),
            IppAttribute("media-source-supported", listOf(IppValue.Keyword("tray-2"), IppValue.Keyword("manual"))),
            IppAttribute("media-type-supported", listOf(IppValue.Keyword("cardstock"))),
            IppAttribute("output-bin-supported", listOf(IppValue.Keyword("face-up"))),
            IppAttribute("job-creation-attributes-supported", listOf(IppValue.Keyword("copies"), IppValue.Keyword("media-source")))
        ))))
        val mapped = IppPrinterCapabilitiesMapper.map(PrinterCapabilities(vendorId = 1, productId = 2, usbDeviceId = 3, ipp = IppPrinterInfo(setOf(1, 2))), response)
        assertEquals("Example 6000", mapped.model)
        assertEquals(setOf(PaperSize.A4, PaperSize.LETTER), mapped.reportedPaperSizes!!.value)
        assertEquals(setOf(PrinterResolution(600, 1200)), mapped.reportedResolutions!!.value)
        assertEquals(CapabilitySource.IPP, mapped.reportedResolutions!!.source)
        assertEquals(setOf(PrinterResolution.DPI_300, PrinterResolution.DPI_600), mapped.ipp.pwgRasterResolutionsSupported)
        assertEquals(setOf("sgray_8", "srgb_8"), mapped.ipp.pwgRasterDocumentTypesSupported)
        assertTrue(ColorMode.COLOR in mapped.reportedColorModes!!.value)
        assertTrue(DuplexMode.LONG_EDGE in mapped.reportedDuplexModes!!.value)
        assertEquals(setOf("tray-2", "manual"), mapped.reportedMediaSourceOptions!!.value.map { it.rawKeyword }.toSet())
        assertFalse(mapped.ipp.pageRangesSupported)
    }

    @Test fun mapsCustomMediaRangeToExplicitMicrons() {
        val mediaSize = IppValue.CollectionValue(mapOf(
            "x-dimension" to listOf(IppValue.IntegerRange(10_000, 30_000)),
            "y-dimension" to listOf(IppValue.IntegerRange(15_000, 50_000))
        ))
        val mediaCol = IppValue.CollectionValue(mapOf("media-size" to listOf(mediaSize)))
        val response = IppResponse(IppVersion(), 0, 3, listOf(IppAttributeGroup(IppGroupTag.PRINTER_ATTRIBUTES, listOf(IppAttribute("media-col-database", mediaCol)))))
        val mapped = IppPrinterCapabilitiesMapper.map(PrinterCapabilities(vendorId = 1, productId = 2, usbDeviceId = 3), response)
        val range = mapped.reportedCustomPaperRangeMicrons!!.value
        assertEquals(100_000L, range.minWidth.value)
        assertEquals(500_000L, range.maxHeight.value)
    }
}
