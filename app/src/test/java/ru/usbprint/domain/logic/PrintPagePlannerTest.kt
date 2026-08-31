package ru.usbprint.domain.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.usbprint.domain.model.PageOrder
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PrintSettings

class PrintPagePlannerTest {
    @Test fun oddAndReverseAreAppliedBeforeCopies() {
        val settings = PrintSettings(copies = 2, pageSelection = PageSelection.Odd, pageOrder = PageOrder.REVERSE, collate = true)
        assertEquals(listOf(5, 3, 1, 5, 3, 1), PrintPagePlanner.plan(settings, 5))
    }
    @Test fun uncollatedCopiesAreGroupedByPage() {
        val settings = PrintSettings(copies = 2, pageSelection = PageSelection.Ranges("1-2", listOf(1..2)), collate = false)
        assertEquals(listOf(1, 1, 2, 2), PrintPagePlanner.plan(settings, 2))
    }
}
