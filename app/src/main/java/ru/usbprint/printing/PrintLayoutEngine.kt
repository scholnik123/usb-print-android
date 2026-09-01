package ru.usbprint.printing

import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.ContentPosition
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.ScalingMode
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)
data class RasterPageFrame(
    val paper: PaperSize,
    val dpi: Int,
    val widthPx: Int,
    val heightPx: Int,
    val widthPoints: Int,
    val heightPoints: Int,
    val printableArea: PixelRect,
    val orientation: Orientation
) {
    fun withContent(content: PixelRect) = RasterPageLayout(
        paper, dpi, widthPx, heightPx, widthPoints, heightPoints, content, orientation
    )
}
data class RasterPageLayout(
    val paper: PaperSize,
    val dpi: Int,
    val widthPx: Int,
    val heightPx: Int,
    val widthPoints: Int,
    val heightPoints: Int,
    val content: PixelRect,
    val orientation: Orientation
)

/** Pure physical layout calculation shared by PWG, PostScript and PCL 5 raster paths. */
object PrintLayoutEngine {
    const val DEFAULT_DPI = 300
    const val MAX_PAGE_DIMENSION_PX = RasterMemoryPolicy.MAX_PAGE_DIMENSION_PX

    fun mmToPixels(mm: Float, dpi: Int): Int {
        require(mm >= 0f && dpi in 72..1200)
        val result = (mm.toDouble() / MM_PER_INCH * dpi).roundToInt()
        require(result in 1..MAX_PAGE_DIMENSION_PX) { "Raster dimension is outside the safe range" }
        return result
    }

    fun create(settings: PrintSettings, sourceWidth: Int, sourceHeight: Int, dpi: Int, hardwareMargins: HardwareMarginsMm = HardwareMarginsMm.ZERO): RasterPageLayout {
        RasterMemoryPolicy.requireSafeSourceMetadata(sourceWidth, sourceHeight)
        val orientation = resolveOrientation(settings.orientation, sourceWidth, sourceHeight)
        val frame = createFrame(settings, dpi, hardwareMargins, orientation)
        return frame.withContent(contentRect(settings, sourceWidth, sourceHeight, frame.printableArea, dpi))
    }

    fun resolveOrientation(requested: Orientation, sourceWidth: Int, sourceHeight: Int): Orientation = when (requested) {
        Orientation.AUTO -> if (sourceWidth > sourceHeight) Orientation.LANDSCAPE else Orientation.PORTRAIT
        else -> requested
    }

    /** Creates the physical media and printable rectangle independently from any logical page. */
    fun createFrame(
        settings: PrintSettings,
        dpi: Int,
        hardwareMargins: HardwareMarginsMm = HardwareMarginsMm.ZERO,
        orientation: Orientation
    ): RasterPageFrame {
        require(orientation != Orientation.AUTO)
        val paper = if (settings.paperSize == PaperSize.AUTO) PaperSize.A4 else settings.paperSize
        val paperWidthMm = if (orientation == Orientation.LANDSCAPE) paper.heightMm else paper.widthMm
        val paperHeightMm = if (orientation == Orientation.LANDSCAPE) paper.widthMm else paper.heightMm
        val width = mmToPixels(paperWidthMm, dpi)
        val height = mmToPixels(paperHeightMm, dpi)
        RasterMemoryPolicy.requireSafePage(width, height)
        val user = settings.margins
        val left = mmToPixelMargin(user.left + hardwareMargins.left, dpi)
        val top = mmToPixelMargin(user.top + hardwareMargins.top, dpi)
        val right = mmToPixelMargin(user.right + hardwareMargins.right, dpi)
        val bottom = mmToPixelMargin(user.bottom + hardwareMargins.bottom, dpi)
        val availableWidth = max(1, width - left - right)
        val availableHeight = max(1, height - top - bottom)
        return RasterPageFrame(
            paper = paper,
            dpi = dpi,
            widthPx = width,
            heightPx = height,
            widthPoints = (paperWidthMm / MM_PER_INCH * POINTS_PER_INCH).roundToInt(),
            heightPoints = (paperHeightMm / MM_PER_INCH * POINTS_PER_INCH).roundToInt(),
            printableArea = PixelRect(left, top, availableWidth, availableHeight),
            orientation = orientation
        )
    }

    /** Applies the existing scale and positioning semantics inside an arbitrary page or N-up slot. */
    fun contentRect(settings: PrintSettings, sourceWidth: Int, sourceHeight: Int, container: PixelRect, dpi: Int): PixelRect {
        RasterMemoryPolicy.requireSafeSourceMetadata(sourceWidth, sourceHeight)
        val scale = when (settings.scalingMode) {
            ScalingMode.FIT -> min(container.width.toDouble() / sourceWidth, container.height.toDouble() / sourceHeight)
            ScalingMode.FILL -> max(container.width.toDouble() / sourceWidth, container.height.toDouble() / sourceHeight)
            // PDF page units are points; the same conservative baseline is used for image/text with no physical DPI metadata.
            ScalingMode.ACTUAL_SIZE -> dpi.toDouble() / POINTS_PER_INCH
            ScalingMode.CUSTOM -> (settings.effectiveScalePercent ?: error("Custom scale must be validated")) / 100.0
        }
        val contentWidth = floor(sourceWidth * scale).toInt().coerceIn(1, MAX_CONTENT_DIMENSION_PX)
        val contentHeight = floor(sourceHeight * scale).toInt().coerceIn(1, MAX_CONTENT_DIMENSION_PX)
        val origin = positionedOrigin(settings.contentPosition, container.left, container.top, container.width, container.height, contentWidth, contentHeight)
        return PixelRect(origin.first, origin.second, contentWidth, contentHeight)
    }

    fun mmToPixelDistance(mm: Float, dpi: Int): Int {
        require(mm.isFinite() && mm in 0f..60f && dpi in 72..1200)
        return (mm.toDouble() / MM_PER_INCH * dpi).roundToInt()
    }

    private fun mmToPixelMargin(mm: Float, dpi: Int): Int = (mm.coerceIn(0f, 60f).toDouble() / MM_PER_INCH * dpi).roundToInt()

    private fun positionedOrigin(position: ContentPosition, left: Int, top: Int, availableWidth: Int, availableHeight: Int, contentWidth: Int, contentHeight: Int): Pair<Int, Int> {
        val x = when (position) {
            ContentPosition.TOP_LEFT, ContentPosition.MIDDLE_LEFT, ContentPosition.BOTTOM_LEFT -> left
            ContentPosition.TOP_RIGHT, ContentPosition.MIDDLE_RIGHT, ContentPosition.BOTTOM_RIGHT -> left + availableWidth - contentWidth
            else -> left + (availableWidth - contentWidth) / 2
        }
        val y = when (position) {
            ContentPosition.TOP_LEFT, ContentPosition.TOP_CENTER, ContentPosition.TOP_RIGHT -> top
            ContentPosition.BOTTOM_LEFT, ContentPosition.BOTTOM_CENTER, ContentPosition.BOTTOM_RIGHT -> top + availableHeight - contentHeight
            else -> top + (availableHeight - contentHeight) / 2
        }
        return x to y
    }

    private const val MM_PER_INCH = 25.4
    private const val POINTS_PER_INCH = 72.0
    private const val MAX_CONTENT_DIMENSION_PX = 48_000
}
