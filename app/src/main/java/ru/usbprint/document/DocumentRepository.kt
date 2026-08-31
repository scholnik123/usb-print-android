package ru.usbprint.document

import android.content.ContentResolver
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.DocumentKind
import ru.usbprint.domain.model.DocumentRef
import ru.usbprint.domain.model.PrintException

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
}
