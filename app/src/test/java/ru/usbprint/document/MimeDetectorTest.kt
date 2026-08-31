package ru.usbprint.document

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.usbprint.domain.model.DocumentKind

class MimeDetectorTest {
    @Test fun detectsByMimeAndExtension() {
        assertEquals(DocumentKind.PDF, MimeDetector.kind(null, "invoice.PDF"))
        assertEquals(DocumentKind.IMAGE, MimeDetector.kind("image/webp", "a.bin"))
        assertEquals(DocumentKind.UNKNOWN, MimeDetector.kind("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a.docx"))
    }
}
