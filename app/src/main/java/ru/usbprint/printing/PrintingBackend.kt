package ru.usbprint.printing

import ru.usbprint.document.DocumentRepository
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.usb.UsbTransport

interface PrintingBackend {
    val id: BackendId
    suspend fun print(
        job: PrintJob,
        transport: UsbTransport,
        documents: DocumentRepository,
        onProgress: (PrintProgressUpdate) -> Unit,
        isCancelled: () -> Boolean,
        metrics: PrintMetricsSink = PrintMetricsSink.NONE
    )
    suspend fun cancel() = Unit
}
