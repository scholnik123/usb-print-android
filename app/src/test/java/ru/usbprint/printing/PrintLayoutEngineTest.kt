package ru.usbprint.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.ContentPosition
import ru.usbprint.domain.model.CustomPaperSizeMicrons
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.Microns
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.ScalingMode

class PrintLayoutEngineTest {
    @Test fun calculatesA4At300DpiFromPhysicalMillimetres() {
        val layout = PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A4), 595, 842, 300)
        assertEquals(2480, layout.widthPx)
        assertEquals(3508, layout.heightPx)
    }
    @Test fun resolvesAutoOrientationAndFitInsideMargins() {
        val layout = PrintLayoutEngine.create(PrintSettings(scalingMode = ScalingMode.FIT), 1000, 500, 300)
        assertEquals(Orientation.LANDSCAPE, layout.orientation)
        assertTrue(layout.content.width <= layout.widthPx)
        assertTrue(layout.content.height <= layout.heightPx)
    }
    @Test fun convertsMillimetresSafely() { assertEquals(300, PrintLayoutEngine.mmToPixels(25.4f, 300)) }
    @Test fun appliesAsymmetricUserAndHardwareMarginsBeforePositioning() {
        val layout = PrintLayoutEngine.create(
            PrintSettings(paperSize = PaperSize.A4, contentPosition = ContentPosition.TOP_LEFT, margins = ru.usbprint.domain.model.PrintMarginsMm(5f, 6f, 7f, 8f)),
            100, 100, 300, HardwareMarginsMm(2f, 3f, 4f, 5f)
        )
        assertEquals(83, layout.content.left)
        assertEquals(106, layout.content.top)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsRasterPageAboveSharedMemoryBudget() {
        PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A0), 100, 100, 300)
    }
    @Test fun customMicronMediaControlsExactPortraitAndLandscapeRasterDimensions() {
        val custom = CustomPaperSizeMicrons(Microns(100_000), Microns(200_000))
        val portrait = PrintLayoutEngine.create(PrintSettings(customPaperSize = custom, orientation = Orientation.PORTRAIT), 595, 842, 300)
        val landscape = PrintLayoutEngine.create(PrintSettings(customPaperSize = custom, orientation = Orientation.LANDSCAPE), 595, 842, 300)
        assertEquals(1_181, portrait.widthPx)
        assertEquals(2_362, portrait.heightPx)
        assertEquals(2_362, landscape.widthPx)
        assertEquals(1_181, landscape.heightPx)
        assertEquals("", portrait.mediaName)
    }
}
