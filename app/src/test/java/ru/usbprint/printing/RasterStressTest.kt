package ru.usbprint.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintMarginsMm
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.PrinterResolution

class RasterStressTest {
    @Test fun allowsStreamedA4At600Dpi() {
        val layout = PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A4), 595, 842, 600)
        assertEquals(4961, layout.widthPx)
        assertEquals(7016, layout.heightPx)
    }

    @Test fun allowsA2At300ButRejectsA3At600() {
        assertTrue(PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A2), 100, 100, 300).widthPx > 0)
        assertTrue(runCatching { PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A3), 100, 100, 600) }.isFailure)
    }

    @Test fun rejectsA0At600AndImpossibleSourceDimensions() {
        assertTrue(runCatching { PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A0), 100, 100, 600) }.isFailure)
        assertTrue(runCatching { PrintLayoutEngine.create(PrintSettings(), 0, 100, 300) }.isFailure)
        assertTrue(runCatching { PrintLayoutEngine.create(PrintSettings(), Int.MAX_VALUE, Int.MAX_VALUE, 300) }.isFailure)
    }

    @Test fun acceptsOneHundredMegapixelMetadataButRejectsTwoBillionPixels() {
        assertTrue(PrintLayoutEngine.create(PrintSettings(), 10_000, 10_000, 300).content.width > 0)
        assertTrue(runCatching { PrintLayoutEngine.create(PrintSettings(), 50_000, 50_000, 300) }.isFailure)
    }

    @Test fun byteEstimateUsesCheckedLongArithmetic() {
        assertEquals(4_008_000_000L, RasterMemoryPolicy.estimatedWorkingBytes(1_000_000, 1_000, 4))
        assertTrue(runCatching { RasterMemoryPolicy.estimatedWorkingBytes(Int.MAX_VALUE, Int.MAX_VALUE, 4) }.isFailure)
        assertTrue(runCatching { RasterMemoryPolicy.estimatedWorkingBytes(-1, 10, 4) }.isFailure)
    }

    @Test fun retainsAsymmetricResolutionWithoutCollapsingIt() {
        val resolution = PrinterResolution(600, 1200)
        val settings = PrintSettings(resolution = resolution)
        assertEquals(600, settings.selectedResolution!!.horizontalDpi)
        assertEquals(1200, settings.selectedResolution!!.verticalDpi)
    }

    @Test fun extremeMarginsCannotProduceNegativeContentRectangle() {
        val settings = PrintSettings(paperSize = PaperSize.A6, margins = PrintMarginsMm(60f, 60f, 60f, 60f))
        val layout = PrintLayoutEngine.create(settings, 100, 100, 300)
        assertTrue(layout.content.width > 0 && layout.content.height > 0)
    }
}
