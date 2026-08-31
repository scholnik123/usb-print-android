package ru.usbprint.domain.logic

import ru.usbprint.domain.model.PageOrder
import ru.usbprint.domain.model.PrintSettings

/** Produces the exact source-page order sent to a raster backend. */
object PrintPagePlanner {
    fun plan(settings: PrintSettings, pageCount: Int): List<Int> {
        val selected = PageRangeParser.expand(settings.pageSelection, pageCount).let {
            if (settings.pageOrder == PageOrder.REVERSE) it.asReversed() else it
        }
        return if (settings.collate) List(settings.copies) { selected }.flatten()
        else selected.flatMap { page -> List(settings.copies) { page } }
    }
}
