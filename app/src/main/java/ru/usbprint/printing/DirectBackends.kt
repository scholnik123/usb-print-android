package ru.usbprint.printing

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import ru.usbprint.document.DocumentRepository
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.domain.model.PrintJobStatus
import ru.usbprint.usb.UsbTransport

private suspend fun streamDocument(
    job: PrintJob,
    transport: UsbTransport,
    documents: DocumentRepository,
    onProgress: (PrintProgressUpdate) -> Unit,
    isCancelled: () -> Boolean,
    metrics: PrintMetricsSink
) {
    var total = job.document.sizeBytes?.takeIf { it > 0L }?.let { runCatching { Math.multiplyExact(it, job.settings.copies.toLong()) }.getOrNull() }
    var totalSent = 0L
    onProgress(PrintProgressUpdate.bytes(PrintJobStatus.SENDING, 0L, total))
    repeat(job.settings.copies) {
        documents.openInput(job.document).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                if (isCancelled()) throw PrintException(AppError.PRINT_CANCELLED)
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                metrics.addGeneratedBytes(count.toLong())
                totalSent = Math.addExact(totalSent, count.toLong())
                total?.let { declaredTotal -> if (totalSent > declaredTotal) total = null }
                transport.write(if (count == buffer.size) buffer else buffer.copyOf(count))
                onProgress(PrintProgressUpdate.bytes(PrintJobStatus.SENDING, totalSent, total))
            }
        }
    }
}

class PdfDirectBackend : PrintingBackend {
    override val id = BackendId.PDF_DIRECT
    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (PrintProgressUpdate) -> Unit, isCancelled: () -> Boolean, metrics: PrintMetricsSink) {
        streamDocument(job, transport, documents, onProgress, isCancelled, metrics)
    }
}

class RawUsbBackend : PrintingBackend {
    override val id = BackendId.RAW
    override suspend fun print(job: PrintJob, transport: UsbTransport, documents: DocumentRepository, onProgress: (PrintProgressUpdate) -> Unit, isCancelled: () -> Boolean, metrics: PrintMetricsSink) {
        streamDocument(job, transport, documents, onProgress, isCancelled, metrics)
    }
}
