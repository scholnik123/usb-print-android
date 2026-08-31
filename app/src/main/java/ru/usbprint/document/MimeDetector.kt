package ru.usbprint.document

import ru.usbprint.domain.model.DocumentKind

object MimeDetector {
    fun kind(mimeType: String?, name: String?): DocumentKind {
        val normalizedMime = mimeType?.lowercase().orEmpty()
        val extension = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            normalizedMime == "application/pdf" || extension == "pdf" -> DocumentKind.PDF
            normalizedMime.startsWith("image/") || extension in setOf("jpg", "jpeg", "png", "webp", "bmp") -> DocumentKind.IMAGE
            normalizedMime == "text/plain" || extension in setOf("txt", "text", "log") -> DocumentKind.TEXT
            normalizedMime in setOf("application/postscript", "application/ps") || extension == "ps" -> DocumentKind.POSTSCRIPT
            normalizedMime.contains("pcl") || extension in setOf("pcl", "pcl5", "pcl6", "prn") -> DocumentKind.PCL
            else -> DocumentKind.UNKNOWN
        }
    }

    fun isSupported(kind: DocumentKind) = kind != DocumentKind.UNKNOWN
}
