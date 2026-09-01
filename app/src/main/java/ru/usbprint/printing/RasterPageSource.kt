package ru.usbprint.printing

import android.graphics.Bitmap
import android.graphics.Color
import ru.usbprint.document.DocumentRenderer
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException
import kotlin.math.floor

enum class RasterColorMode { MONOCHROME, GRAYSCALE, RGB }

interface RasterRowSource : AutoCloseable {
    fun renderRow(outputY: Int, out: ByteArray)
}

/**
 * Holds at most one rendered source page (never a whole job) and produces one target line at a time.
 * The source bitmap width is bounded so that a 600-DPI output does not imply a 600-DPI bitmap allocation.
 */
class RasterPageSource(
    private val renderer: DocumentRenderer,
    private val pageIndex: Int,
    private val layout: RasterPageLayout,
    private val mode: RasterColorMode
) : RasterRowSource {
    private val slot = RasterSlotSource(renderer, pageIndex, layout.widthPx, layout.content, PixelRect(0, 0, layout.widthPx, layout.heightPx), false, mode)

    /** Fills `out` exactly as a CUPS/PWG/PCL row: RGB, gray, or MSB-first 1-bit black. */
    override fun renderRow(outputY: Int, out: ByteArray) {
        fillWhite(out, mode)
        slot.renderRow(outputY, out)
    }

    override fun close() = slot.close()
}

/** Composes already planned logical-page slots into a single physical raster page. */
class NUpRasterPageSource(
    renderer: DocumentRenderer,
    private val sheet: NUpSheet,
    private val mode: RasterColorMode
) : RasterRowSource {
    private val sources = sheet.slots.map { slot ->
        RasterSlotSource(renderer, slot.pageNumber - 1, sheet.layout.widthPx, slot.content, slot.bounds, slot.rotateClockwise, mode)
    }

    override fun renderRow(outputY: Int, out: ByteArray) {
        fillWhite(out, mode)
        sources.forEach { it.renderRow(outputY, out) }
        if (sheet.drawBorders) sheet.slots.forEach { drawBorder(outputY, out, it.bounds, sheet.layout.widthPx, mode) }
    }

    override fun close() = sources.forEach(RasterSlotSource::close)
}

/** A clipped page image; bitmap allocation is lazy so only the current N-up row is resident. */
private class RasterSlotSource(
    private val renderer: DocumentRenderer,
    private val pageIndex: Int,
    private val canvasWidth: Int,
    private val content: PixelRect,
    private val clip: PixelRect,
    private val rotateClockwise: Boolean,
    private val mode: RasterColorMode
) : AutoCloseable {
    private var bitmap: Bitmap? = null
    private var cachedSourceRow = -1
    private var sourcePixels = IntArray(0)
    private var exhausted = false

    private fun source(): Bitmap {
        bitmap?.let { return it }
        val requestedWidth = (if (rotateClockwise) content.height else content.width).coerceIn(64, RasterMemoryPolicy.MAX_RENDER_WIDTH)
        return try {
            renderer.renderPage(pageIndex, requestedWidth).also {
                if (it.width.toLong() * it.height > RasterMemoryPolicy.MAX_SOURCE_PIXELS) {
                    it.recycle()
                    throw PrintException(AppError.OUT_OF_MEMORY_PREVENTED)
                }
                bitmap = it
                sourcePixels = IntArray(it.width)
            }
        } catch (error: PrintException) { throw error }
        catch (error: Throwable) { throw PrintException(AppError.RENDER_ERROR, error) }
    }

    fun renderRow(outputY: Int, out: ByteArray) {
        val visibleTop = maxOf(content.top, clip.top, 0)
        val visibleBottom = minOf(content.top + content.height, clip.top + clip.height)
        if (exhausted || outputY !in visibleTop until visibleBottom) return
        val image = source()
        if (!rotateClockwise) {
            val sourceY = floor((outputY - content.top).toDouble() * image.height / content.height).toInt().coerceIn(0, image.height - 1)
            if (sourceY != cachedSourceRow) {
                image.getPixels(sourcePixels, 0, image.width, 0, sourceY, image.width, 1)
                cachedSourceRow = sourceY
            }
        }
        val from = maxOf(0, content.left, clip.left)
        val to = minOf(canvasWidth, content.left + content.width, clip.left + clip.width)
        for (x in from until to) {
            val color = if (rotateClockwise) {
                val sourceX = floor((outputY - content.top).toDouble() * image.width / content.height).toInt().coerceIn(0, image.width - 1)
                val sourceY = image.height - 1 - floor((x - content.left).toDouble() * image.height / content.width).toInt().coerceIn(0, image.height - 1)
                image.getPixel(sourceX, sourceY)
            } else {
                val sourceX = floor((x - content.left).toDouble() * image.width / content.width).toInt().coerceIn(0, image.width - 1)
                sourcePixels[sourceX]
            }
            writePixel(out, x, color, mode)
        }
        if (outputY == visibleBottom - 1) { close(); exhausted = true }
    }

    override fun close() { bitmap?.recycle(); bitmap = null; sourcePixels = IntArray(0) }

}

private fun fillWhite(out: ByteArray, mode: RasterColorMode) = when (mode) {
    RasterColorMode.RGB, RasterColorMode.GRAYSCALE -> out.fill(0xff.toByte())
    RasterColorMode.MONOCHROME -> out.fill(0)
}

private fun writePixel(out: ByteArray, x: Int, color: Int, mode: RasterColorMode) {
    when (mode) {
        RasterColorMode.RGB -> {
            val offset = x * 3
            out[offset] = Color.red(color).toByte()
            out[offset + 1] = Color.green(color).toByte()
            out[offset + 2] = Color.blue(color).toByte()
        }
        RasterColorMode.GRAYSCALE -> out[x] = luminance(color).toByte()
        RasterColorMode.MONOCHROME -> if (luminance(color) < 160) {
            val offset = x / 8
            out[offset] = (out[offset].toInt() or (0x80 ushr (x % 8))).toByte()
        }
    }
}

private fun drawBorder(outputY: Int, out: ByteArray, rect: PixelRect, canvasWidth: Int, mode: RasterColorMode) {
    if (outputY !in rect.top until rect.top + rect.height) return
    val from = maxOf(0, rect.left)
    val to = minOf(canvasWidth, rect.left + rect.width)
    if (from >= to) return
    if (outputY == rect.top || outputY == rect.top + rect.height - 1) {
        for (x in from until to) writePixel(out, x, Color.BLACK, mode)
    } else {
        writePixel(out, from, Color.BLACK, mode)
        writePixel(out, to - 1, Color.BLACK, mode)
    }
}

private fun luminance(color: Int): Int = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
