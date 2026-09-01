package ru.usbprint.printing

import ru.usbprint.domain.logic.PrintPagePlanner
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PrintSettings
import kotlin.math.min

data class NUpSlot(
    /** One-based logical page number from PrintPagePlanner. */
    val pageNumber: Int,
    val bounds: PixelRect,
    val content: PixelRect,
    val rotateClockwise: Boolean
)

data class NUpSheet(
    val layout: RasterPageLayout,
    val slots: List<NUpSlot>,
    val drawBorders: Boolean
)

/**
 * Pure logical-page to physical-sheet layout. Both preview and print encoders consume this exact model.
 * Page selection, order and copy expansion happen first in [PrintPagePlanner]; only then are pages chunked.
 */
object NUpLayoutEngine {
    fun plan(
        settings: PrintSettings,
        pageCount: Int,
        pageSize: (zeroBasedPageIndex: Int) -> Pair<Int, Int>,
        dpi: Int,
        hardwareMargins: HardwareMarginsMm = HardwareMarginsMm.ZERO
    ): List<NUpSheet> {
        require(settings.pagesPerSheet in setOf(1, 2, 4))
        val logicalPages = PrintPagePlanner.plan(settings, pageCount)
        require(logicalPages.isNotEmpty())
        return logicalPages.chunked(settings.pagesPerSheet).map { pages ->
            createSheet(settings, pages, pageSize, dpi, hardwareMargins)
        }
    }

    private fun createSheet(
        settings: PrintSettings,
        pages: List<Int>,
        pageSize: (Int) -> Pair<Int, Int>,
        dpi: Int,
        hardwareMargins: HardwareMarginsMm
    ): NUpSheet {
        if (settings.pagesPerSheet == 1) {
            val page = pages.single()
            val size = pageSize(page - 1)
            val layout = PrintLayoutEngine.create(settings, size.first, size.second, dpi, hardwareMargins)
            return NUpSheet(
                layout = layout,
                slots = listOf(NUpSlot(page, printableArea(settings, dpi, hardwareMargins, layout.orientation), layout.content, false)),
                drawBorders = false
            )
        }

        val pageSizes = pages.map { page -> page to pageSize(page - 1) }
        pageSizes.forEach { (_, size) -> RasterMemoryPolicy.requireSafeSourceMetadata(size.first, size.second) }
        val orientation = when (settings.orientation) {
            Orientation.AUTO -> listOf(Orientation.PORTRAIT, Orientation.LANDSCAPE).maxBy { candidate ->
                orientationScore(settings, pageSizes.map { it.second }, dpi, hardwareMargins, candidate)
            }
            else -> settings.orientation
        }
        val frame = PrintLayoutEngine.createFrame(settings, dpi, hardwareMargins, orientation)
        val cells = cells(frame.printableArea, settings.pagesPerSheet, orientation, PrintLayoutEngine.mmToPixelDistance(settings.nUpSpacingMm, dpi))
        val slots = pageSizes.mapIndexed { index, (page, size) ->
            val cell = cells[index]
            val rotate = settings.nUpAutoRotate && fitScale(size.second, size.first, cell) > fitScale(size.first, size.second, cell)
            val effectiveWidth = if (rotate) size.second else size.first
            val effectiveHeight = if (rotate) size.first else size.second
            NUpSlot(
                pageNumber = page,
                bounds = cell,
                content = PrintLayoutEngine.contentRect(settings, effectiveWidth, effectiveHeight, cell, dpi),
                rotateClockwise = rotate
            )
        }
        return NUpSheet(frame.withContent(frame.printableArea), slots, settings.nUpDrawBorders)
    }

    private fun orientationScore(
        settings: PrintSettings,
        pageSizes: List<Pair<Int, Int>>,
        dpi: Int,
        hardwareMargins: HardwareMarginsMm,
        orientation: Orientation
    ): Double {
        val frame = PrintLayoutEngine.createFrame(settings, dpi, hardwareMargins, orientation)
        val cells = cells(frame.printableArea, settings.pagesPerSheet, orientation, PrintLayoutEngine.mmToPixelDistance(settings.nUpSpacingMm, dpi))
        return pageSizes.mapIndexed { index, size ->
            val normal = fitScale(size.first, size.second, cells[index])
            if (settings.nUpAutoRotate) maxOf(normal, fitScale(size.second, size.first, cells[index])) else normal
        }.sum()
    }

    private fun cells(area: PixelRect, pagesPerSheet: Int, orientation: Orientation, spacing: Int): List<PixelRect> {
        val (columns, rows) = when (pagesPerSheet) {
            2 -> if (orientation == Orientation.LANDSCAPE) 2 to 1 else 1 to 2
            4 -> 2 to 2
            else -> error("N-up grid supports only 2 or 4 pages")
        }
        val usableWidth = area.width - spacing * (columns - 1)
        val usableHeight = area.height - spacing * (rows - 1)
        require(usableWidth >= columns && usableHeight >= rows) { "N-up spacing leaves no printable slot" }
        return buildList(columns * rows) {
            for (row in 0 until rows) for (column in 0 until columns) {
                val left = area.left + column * usableWidth / columns + column * spacing
                val right = area.left + (column + 1) * usableWidth / columns + column * spacing
                val top = area.top + row * usableHeight / rows + row * spacing
                val bottom = area.top + (row + 1) * usableHeight / rows + row * spacing
                add(PixelRect(left, top, right - left, bottom - top))
            }
        }
    }

    private fun fitScale(sourceWidth: Int, sourceHeight: Int, slot: PixelRect): Double =
        min(slot.width.toDouble() / sourceWidth, slot.height.toDouble() / sourceHeight)

    private fun printableArea(
        settings: PrintSettings,
        dpi: Int,
        hardwareMargins: HardwareMarginsMm,
        orientation: Orientation
    ) = PrintLayoutEngine.createFrame(settings, dpi, hardwareMargins, orientation).printableArea
}
