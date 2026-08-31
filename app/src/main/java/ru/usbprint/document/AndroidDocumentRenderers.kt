package ru.usbprint.document

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException
import kotlin.math.max

class PdfDocumentRenderer(resolver: ContentResolver, uri: Uri) : DocumentRenderer {
    private val descriptor: ParcelFileDescriptor = checkNotNull(resolver.openFileDescriptor(uri, "r"))
    private val renderer = PdfRenderer(descriptor)
    override val pageCount: Int get() = renderer.pageCount

    override fun pageSize(pageIndex: Int): Pair<Int, Int> = renderer.openPage(pageIndex).use { it.width to it.height }

    override fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap = renderer.openPage(pageIndex).use { page ->
        val width = targetWidthPx.coerceIn(64, 2048)
        val height = max(64, (page.height.toFloat() / page.width * width).toInt())
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT) }
    }

    override fun close() { renderer.close(); descriptor.close() }
}

class ImageDocumentRenderer(private val resolver: ContentResolver, private val uri: Uri) : DocumentRenderer {
    private val bounds = BitmapFactory.Options().also { options ->
        options.inJustDecodeBounds = true
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
    override val pageCount: Int = 1
    override fun pageSize(pageIndex: Int): Pair<Int, Int> = bounds.outWidth.coerceAtLeast(1) to bounds.outHeight.coerceAtLeast(1)

    override fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap {
        val sourceWidth = bounds.outWidth.coerceAtLeast(1)
        val sample = generateSequence(1) { it * 2 }.takeWhile { sourceWidth / it > targetWidthPx * 2 }.last()
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = requireNotNull(resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }) { "Не удалось декодировать изображение" }
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
        return applyExifOrientation(decoded, orientation)
    }

    override fun close() = Unit

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }
}

class TextDocumentRenderer(resolver: ContentResolver, uri: Uri) : DocumentRenderer {
    private val text = resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
        val content = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (content.length + count > MAX_TEXT_CHARS) throw PrintException(AppError.OUT_OF_MEMORY_PREVENTED)
            content.append(buffer, 0, count)
        }
        content.toString()
    }.orEmpty()
    /** Canonical text layout is calculated once, allowing range selection to address real text pages. */
    private val pages: List<List<String>> = paginate(text)
    override val pageCount: Int get() = pages.size
    override fun pageSize(pageIndex: Int): Pair<Int, Int> = 800 to 1131

    override fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap {
        val width = targetWidthPx.coerceIn(320, 1200)
        val bitmap = Bitmap.createBitmap(width, (width * 1.414f).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = width / 27f }
        val lineHeight = paint.fontSpacing
        var y = 32f - paint.ascent()
        pages[pageIndex.coerceIn(0, pages.lastIndex)].forEach { line ->
            canvas.drawText(line, 32f, y, paint)
            y += lineHeight
        }
        return bitmap
    }

    fun rawText(): String = text
    override fun close() = Unit

    private fun paginate(value: String): List<List<String>> {
        val output = mutableListOf<MutableList<String>>()
        var page = mutableListOf<String>()
        value.lineSequence().forEach { sourceLine ->
            sourceLine.ifEmpty { " " }.chunked(CANONICAL_COLUMNS).forEach { line ->
                if (page.size == LINES_PER_PAGE) { output += page; page = mutableListOf() }
                page += line
            }
        }
        if (page.isNotEmpty() || output.isEmpty()) output += page
        return output
    }

    private companion object {
        const val MAX_TEXT_CHARS = 1_000_000
        const val CANONICAL_COLUMNS = 54
        const val LINES_PER_PAGE = 34
    }
}
