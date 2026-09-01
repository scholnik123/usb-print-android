package ru.usbprint.document

import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.DocumentKind
import ru.usbprint.domain.model.DocumentRef
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.printing.NUpLayoutEngine
import ru.usbprint.printing.NUpSlot
import ru.usbprint.printing.PrintLayoutEngine
import ru.usbprint.printing.RasterMemoryPolicy
import kotlin.math.roundToInt

class DocumentRepository(private val resolver: ContentResolver) {
    suspend fun inspect(uri: Uri): DocumentRef = withContext(Dispatchers.IO) {
        val metadata = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor -> readMetadata(cursor) }
        val name = metadata?.first ?: uri.lastPathSegment ?: "document"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val kind = MimeDetector.kind(mime, name)
        if (!MimeDetector.isSupported(kind)) throw PrintException(AppError.DOCUMENT_NOT_SUPPORTED)
        val pages = if (kind == DocumentKind.PDF) runCatching { PdfDocumentRenderer(resolver, uri).use { it.pageCount } }.getOrElse {
            throw PrintException(AppError.DOCUMENT_READ_ERROR, it)
        } else 1
        DocumentRef(uri.toString(), name, mime, kind, metadata?.second, pages)
    }

    suspend fun renderPreview(document: DocumentRef, pageIndex: Int = 0, width: Int = 720): Bitmap = withContext(Dispatchers.IO) {
        createRenderer(document).use { it.renderPage(pageIndex.coerceIn(0, it.pageCount - 1), width) }
    }

    /** Renders the first physical sheet from the same N-up geometry used by the print encoders. */
    suspend fun renderPrintPreview(
        document: DocumentRef,
        settings: PrintSettings,
        dpi: Int = PrintLayoutEngine.DEFAULT_DPI,
        hardwareMargins: HardwareMarginsMm = HardwareMarginsMm.ZERO,
        width: Int = 720
    ): Bitmap = withContext(Dispatchers.IO) {
        createRenderer(document).use { renderer ->
            val sheet = NUpLayoutEngine.plan(settings, renderer.pageCount, renderer::pageSize, dpi, hardwareMargins).first()
            val previewWidth = width.coerceIn(240, 1440)
            val scale = previewWidth.toFloat() / sheet.layout.widthPx
            val previewHeight = (sheet.layout.heightPx * scale).roundToInt().coerceAtLeast(1)
            RasterMemoryPolicy.requireSafePage(previewWidth, previewHeight)
            Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888).also { output ->
                val canvas = Canvas(output).apply { drawColor(Color.WHITE) }
                sheet.slots.forEach { slot -> drawPreviewSlot(canvas, renderer, slot, scale) }
                if (sheet.drawBorders) {
                    val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1f }
                    sheet.slots.forEach { canvas.drawRect(it.bounds.scaled(scale), border) }
                }
            }
        }
    }

    fun createRenderer(document: DocumentRef): DocumentRenderer = when (document.kind) {
        DocumentKind.PDF -> PdfDocumentRenderer(resolver, Uri.parse(document.uri))
        DocumentKind.IMAGE -> ImageDocumentRenderer(resolver, Uri.parse(document.uri))
        DocumentKind.TEXT -> TextDocumentRenderer(resolver, Uri.parse(document.uri))
        else -> throw PrintException(AppError.DOCUMENT_NOT_SUPPORTED)
    }

    fun openInput(document: DocumentRef) = resolver.openInputStream(Uri.parse(document.uri)) ?: throw PrintException(AppError.DOCUMENT_READ_ERROR)

    private fun readMetadata(cursor: Cursor): Pair<String?, Long?>? {
        if (!cursor.moveToFirst()) return null
        val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
        val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index -> if (cursor.isNull(index)) null else cursor.getLong(index) }
        return name to size
    }

    private fun drawPreviewSlot(canvas: Canvas, renderer: DocumentRenderer, slot: NUpSlot, scale: Float) {
        val targetWidth = ((if (slot.rotateClockwise) slot.content.height else slot.content.width) * scale).roundToInt()
            .coerceIn(64, RasterMemoryPolicy.MAX_RENDER_WIDTH)
        val page = renderer.renderPage(slot.pageNumber - 1, targetWidth)
        try {
            val destination = slot.content.scaled(scale)
            canvas.save()
            canvas.clipRect(slot.bounds.scaled(scale))
            if (slot.rotateClockwise) {
                canvas.translate(destination.right, destination.top)
                canvas.rotate(90f)
                canvas.drawBitmap(page, null, RectF(0f, 0f, destination.height(), destination.width()), null)
            } else {
                canvas.drawBitmap(page, null, destination, null)
            }
            canvas.restore()
        } finally {
            page.recycle()
        }
    }

    private fun ru.usbprint.printing.PixelRect.scaled(scale: Float) = RectF(
        left * scale,
        top * scale,
        (left + width) * scale,
        (top + height) * scale
    )
}
