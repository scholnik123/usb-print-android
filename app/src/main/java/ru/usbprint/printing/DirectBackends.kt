package ru.usbprint.printing

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import ru.usbprint.document.DocumentRepository
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.usb.UsbTransport

private suspend fun streamDocument(
    job: PrintJob,
    transport: UsbTransport,
    documents: DocumentRepository,
    onProgress: (Int) -> Unit,
    isCancelled: () -> Boolean
) {
    val total = job.document.sizeBytes?.coerceAtLeast(1L)
    repeat(job.settings.copies) { copy ->
        var sent = 0L
        documents.openInput(job.document).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                if (isCancelled()) throw PrintException(AppError.PRINT_CANCELLED)
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                transport.write(if (count == buffer.size) buffer else buffer.copyOf(count))
                sent += count
                val fraction = if (total == null) 0.5f else sent.toFloat() / total
                onProgress((((copy + fraction) / job.settings.copies) * 100).toInt().coerceIn(1, 99))
            }
        }
    }
    onProgress(100)
}

class PdfDirectBackend : PrintingBackend {
    override val id = BackendId.PDF_DIRECT
    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        streamDocument(job, transport, documents, onProgress, isCancelled)
    }
}

class RawUsbBackend : PrintingBackend {
    override val id = BackendId.RAW
    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (Int) -> Unit, isCancelled: () -> Boolean) {
        streamDocument(job, transport, documents, onProgress, isCancelled)
    }
}
