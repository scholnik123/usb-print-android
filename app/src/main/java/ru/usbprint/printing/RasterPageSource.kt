package ru.usbprint.printing

import android.graphics.Bitmap
import android.graphics.Color
import ru.usbprint.document.DocumentRenderer
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException
import kotlin.math.floor

enum class RasterColorMode { MONOCHROME, GRAYSCALE, RGB }

/**
 * Holds at most one rendered source page (never a whole job) and produces one target line at a time.
 * The source bitmap width is bounded so that a 600-DPI output does not imply a 600-DPI bitmap allocation.
 */
class RasterPageSource(
    private val renderer: DocumentRenderer,
    private val pageIndex: Int,
    private val layout: RasterPageLayout,
    private val mode: RasterColorMode
) : AutoCloseable {
    private var bitmap: Bitmap? = null
    private var cachedSourceRow = -1
    private var sourcePixels = IntArray(0)

    private fun source(): Bitmap {
        bitmap?.let { return it }
        val requestedWidth = layout.content.width.coerceIn(64, RasterMemoryPolicy.MAX_RENDER_WIDTH)
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

    /** Fills `out` exactly as a CUPS/PWG/PCL row: RGB, gray, or MSB-first 1-bit black. */
    fun renderRow(outputY: Int, out: ByteArray) {
        val image = source()
        when (mode) {
            RasterColorMode.RGB -> out.fill(0xff.toByte())
            RasterColorMode.GRAYSCALE -> out.fill(0xff.toByte())
            RasterColorMode.MONOCHROME -> out.fill(0)
        }
        val rect = layout.content
        if (outputY !in rect.top until rect.top + rect.height) return
        val sourceY = floor((outputY - rect.top).toDouble() * image.height / rect.height).toInt().coerceIn(0, image.height - 1)
        if (sourceY != cachedSourceRow) {
            image.getPixels(sourcePixels, 0, image.width, 0, sourceY, image.width, 1)
            cachedSourceRow = sourceY
        }
        val from = maxOf(0, rect.left)
        val to = minOf(layout.widthPx, rect.left + rect.width)
        for (x in from until to) {
            val sourceX = floor((x - rect.left).toDouble() * image.width / rect.width).toInt().coerceIn(0, image.width - 1)
            val color = sourcePixels[sourceX]
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
    }

    override fun close() { bitmap?.recycle(); bitmap = null; sourcePixels = IntArray(0) }

    private fun luminance(color: Int): Int = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

}
