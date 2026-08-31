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
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    )
    suspend fun cancel() = Unit
}
