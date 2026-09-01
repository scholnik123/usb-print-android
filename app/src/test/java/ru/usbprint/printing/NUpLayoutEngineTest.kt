package ru.usbprint.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PageOrder
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintSettings

class NUpLayoutEngineTest {
    private val portraitSize: (Int) -> Pair<Int, Int> = { 595 to 842 }

    @Test fun twoUpFourLogicalPagesBecomeTwoPhysicalSheets() {
        val sheets = plan(PrintSettings(pagesPerSheet = 2), 4)
        assertEquals(listOf(listOf(1, 2), listOf(3, 4)), sheets.pageNumbers())
    }

    @Test fun fourUpEightLogicalPagesBecomeTwoPhysicalSheets() {
        val sheets = plan(PrintSettings(pagesPerSheet = 4), 8)
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(5, 6, 7, 8)), sheets.pageNumbers())
    }

    @Test fun oddLogicalPageCountLeavesOnlyTheFinalSlotEmpty() {
        val sheets = plan(PrintSettings(pagesPerSheet = 2), 3)
        assertEquals(listOf(listOf(1, 2), listOf(3)), sheets.pageNumbers())
    }

    @Test fun rangeAndReverseAreResolvedBeforeSheetComposition() {
        val settings = PrintSettings(
            pagesPerSheet = 2,
            pageSelection = PageSelection.Ranges("2-5", listOf(2..5)),
            pageOrder = PageOrder.REVERSE
        )
        assertEquals(listOf(listOf(5, 4), listOf(3, 2)), plan(settings, 8).pageNumbers())
    }

    @Test fun collatedCopiesPreserveDocumentOrderBeforeChunking() {
        val settings = PrintSettings(pagesPerSheet = 2, copies = 2, collate = true)
        assertEquals(listOf(listOf(1, 2), listOf(3, 1), listOf(2, 3)), plan(settings, 3).pageNumbers())
    }

    @Test fun uncollatedCopiesStayGroupedBeforeChunking() {
        val settings = PrintSettings(pagesPerSheet = 2, copies = 2, collate = false)
        assertEquals(listOf(listOf(1, 1), listOf(2, 2)), plan(settings, 2).pageNumbers())
    }

    @Test fun explicitPortraitAndLandscapeControlPhysicalSheetAndTwoUpGrid() {
        val portrait = plan(PrintSettings(pagesPerSheet = 2, orientation = Orientation.PORTRAIT), 2).single()
        val landscape = plan(PrintSettings(pagesPerSheet = 2, orientation = Orientation.LANDSCAPE), 2).single()

        assertEquals(Orientation.PORTRAIT, portrait.layout.orientation)
        assertTrue(portrait.slots[1].bounds.top > portrait.slots[0].bounds.top)
        assertEquals(Orientation.LANDSCAPE, landscape.layout.orientation)
        assertTrue(landscape.slots[1].bounds.left > landscape.slots[0].bounds.left)
    }

    @Test fun spacingBordersAndAutoRotationArePartOfTheSharedLayout() {
        val settings = PrintSettings(
            pagesPerSheet = 2,
            orientation = Orientation.LANDSCAPE,
            nUpSpacingMm = 5f,
            nUpDrawBorders = true,
            nUpAutoRotate = true
        )
        val sheet = NUpLayoutEngine.plan(settings, 2, { 842 to 595 }, 300).single()

        assertTrue(sheet.drawBorders)
        assertTrue(sheet.slots[1].bounds.left > sheet.slots[0].bounds.left + sheet.slots[0].bounds.width)
        assertTrue(sheet.slots.all { it.rotateClockwise })
    }

    @Test fun disablingAutoRotationKeepsSourceOrientation() {
        val sheet = NUpLayoutEngine.plan(
            PrintSettings(pagesPerSheet = 2, orientation = Orientation.LANDSCAPE, nUpAutoRotate = false),
            2,
            { 842 to 595 },
            300
        ).single()
        assertFalse(sheet.slots.any { it.rotateClockwise })
    }

    @Test fun duplexModeDoesNotChangePhysicalSheetGrouping() {
        val longEdge = plan(PrintSettings(pagesPerSheet = 2, duplexMode = DuplexMode.LONG_EDGE), 4)
        val shortEdge = plan(PrintSettings(pagesPerSheet = 2, duplexMode = DuplexMode.SHORT_EDGE), 4)
        assertEquals(longEdge.pageNumbers(), shortEdge.pageNumbers())
    }

    private fun plan(settings: PrintSettings, pages: Int) = NUpLayoutEngine.plan(
        settings.copy(paperSize = PaperSize.A4), pages, portraitSize, 300
    )

    private fun List<NUpSheet>.pageNumbers() = map { sheet -> sheet.slots.map(NUpSlot::pageNumber) }
}
