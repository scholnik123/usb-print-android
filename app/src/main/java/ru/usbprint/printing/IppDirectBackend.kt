package ru.usbprint.printing

import kotlinx.coroutines.delay
import ru.usbprint.document.DocumentRepository
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.DocumentKind
import ru.usbprint.domain.model.IppPrinterInfo
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.domain.model.PrintJobStatus
import ru.usbprint.ipp.IppClient
import ru.usbprint.ipp.IppJobReference
import ru.usbprint.ipp.IppJobState
import ru.usbprint.ipp.IppJobStatus
import ru.usbprint.ipp.IppOperation
import ru.usbprint.ipp.IppUsbSession
import ru.usbprint.usb.UsbTransport

/** Sends an exact-length PDF as an IPP Print-Job request over USB bulk endpoints. */
class IppDirectBackend(private val onJobStatus: (ru.usbprint.ipp.IppJobStatus) -> Unit = {}) : PrintingBackend {
    override val id = BackendId.IPP_DIRECT

    override suspend fun print(
        job: PrintJob,
        transport: UsbTransport,
        documents: DocumentRepository,
        onProgress: (PrintProgressUpdate) -> Unit,
        isCancelled: () -> Boolean,
        metrics: PrintMetricsSink
    ) {
        if (job.document.kind != DocumentKind.PDF) throw PrintException(AppError.IPP_DOCUMENT_FORMAT_NOT_SUPPORTED)
        val length = job.document.sizeBytes ?: throw PrintException(AppError.DOCUMENT_READ_ERROR)
        val ipp = job.printer.capabilities.ipp
        if (!ipp.supportsOperation(IppOperation.PRINT_JOB.code)) throw PrintException(AppError.IPP_OPERATION_NOT_SUPPORTED)
        if (!ipp.supportsFormat(PDF_MIME)) throw PrintException(AppError.IPP_DOCUMENT_FORMAT_NOT_SUPPORTED)
        if (ipp.acceptingJobs == false) throw PrintException(AppError.IPP_JOB_REJECTED)

        var generated = 0L
        onProgress(PrintProgressUpdate.bytes(PrintJobStatus.SENDING, 0L, length))
        val session = IppUsbSession(transport) { sent, total ->
            if (sent > generated) metrics.addGeneratedBytes(sent - generated)
            generated = sent
            onProgress(PrintProgressUpdate.bytes(PrintJobStatus.SENDING, sent, total.takeIf { it > 0L }))
        }
        val client = IppClient(session)
        val (_, reference) = documents.openInput(job.document).use { document ->
            client.printJob(
                document = document,
                documentLength = length,
                documentFormat = PDF_MIME,
                jobName = job.document.displayName,
                settings = job.settings,
                supportedAttributeNames = ipp.jobCreationAttributesSupported,
                pageCount = job.document.pageCount ?: 1,
                mediaColSupported = ipp.mediaColSupported,
                mediaColMargins = job.printer.capabilities.reportedHardwareMargins?.value,
                isCancelled = isCancelled
            )
        }
        monitorIppJob(client, reference, ipp, onProgress, isCancelled, onJobStatus, metrics)
    }

    private companion object {
        const val PDF_MIME = "application/pdf"
    }
}

/** Rasterizes locally, spools one bounded PWG document, and submits it exactly once with IPP Print-Job. */
class IppPwgBackend(
    private val spoolManager: IppPwgSpoolManager,
    private val onJobStatus: (IppJobStatus) -> Unit = {}
) : PrintingBackend {
    override val id = BackendId.IPP_PWG

    override suspend fun print(
        job: PrintJob,
        transport: UsbTransport,
        documents: DocumentRepository,
        onProgress: (PrintProgressUpdate) -> Unit,
        isCancelled: () -> Boolean,
        metrics: PrintMetricsSink
    ) {
        if (job.document.kind !in PRINTABLE_KINDS) throw PrintException(AppError.DOCUMENT_NOT_SUPPORTED)
        val ipp = job.printer.capabilities.ipp
        if (!ipp.supportsOperation(IppOperation.PRINT_JOB.code)) throw PrintException(AppError.IPP_OPERATION_NOT_SUPPORTED)
        if (!ipp.supportsFormat(IppPwgJobPipeline.PWG_MIME)) throw PrintException(AppError.IPP_DOCUMENT_FORMAT_NOT_SUPPORTED)
        if (ipp.acceptingJobs == false) throw PrintException(AppError.IPP_JOB_REJECTED)

        val plan = PwgRasterJobPlanner.plan(job, id)
        onProgress(PrintProgressUpdate.sheets(PrintJobStatus.GENERATING_PAYLOAD, 0, plan.physicalSheetCount))
        val session = IppUsbSession(transport) { sent, total ->
            onProgress(PrintProgressUpdate.bytes(PrintJobStatus.SENDING, sent, total.takeIf { it > 0L }))
        }
        val client = IppClient(session)
        val (_, reference) = IppPwgJobPipeline(spoolManager).submit(
            client = client,
            jobName = job.document.displayName,
            settings = job.settings,
            supportedAttributeNames = ipp.jobCreationAttributesSupported,
            mediaColSupported = ipp.mediaColSupported,
            mediaColMargins = job.printer.capabilities.reportedHardwareMargins?.value,
            pageCount = plan.physicalSheetCount,
            producer = PwgSpoolProducer { writeBytes ->
                PwgRasterDocumentWriter.write(
                    job = job,
                    capabilityBackend = id,
                    documents = documents,
                    plan = plan,
                    writeBytes = writeBytes,
                    onPageCompleted = { completed, total ->
                        onProgress(PrintProgressUpdate.sheets(PrintJobStatus.GENERATING_PAYLOAD, completed, total))
                    },
                    isCancelled = isCancelled,
                    metrics = metrics
                )
            },
            onSpoolReady = { length -> onProgress(PrintProgressUpdate.bytes(PrintJobStatus.SENDING, 0L, length)) },
            isCancelled = isCancelled
        )
        monitorIppJob(client, reference, ipp, onProgress, isCancelled, onJobStatus, metrics)
    }

    private companion object {
        val PRINTABLE_KINDS = setOf(DocumentKind.PDF, DocumentKind.IMAGE, DocumentKind.TEXT)
    }
}

private suspend fun monitorIppJob(
    client: IppClient,
    reference: IppJobReference,
    ipp: IppPrinterInfo,
    onProgress: (PrintProgressUpdate) -> Unit,
    isCancelled: () -> Boolean,
    onJobStatus: (IppJobStatus) -> Unit,
    metrics: PrintMetricsSink
) {
    if (reference.jobId == null && reference.jobUri == null) {
        return
    }
    if (!ipp.supportsOperation(IppOperation.GET_JOB_ATTRIBUTES.code)) {
        if (isCancelled()) throw PrintException(AppError.PRINT_CANCELLED)
        return
    }

    metrics.beginIppWait()
    try {
        onProgress(PrintProgressUpdate.indeterminate(PrintJobStatus.WAITING_STATUS))
        repeat(STATUS_POLLS) {
            if (isCancelled()) {
                if (ipp.supportsOperation(IppOperation.CANCEL_JOB.code)) {
                    runCatching { client.cancelJob(reference) }
                        .getOrElse { throw PrintException(AppError.IPP_JOB_CANCEL_FAILED, it) }
                }
                throw PrintException(AppError.PRINT_CANCELLED)
            }
            val (_, status) = client.getJobAttributes(reference)
            onJobStatus(status)
            when (status.state) {
                IppJobState.COMPLETED -> return
                IppJobState.CANCELED -> throw PrintException(AppError.PRINT_CANCELLED)
                IppJobState.ABORTED -> throw PrintException(AppError.IPP_JOB_REJECTED)
                else -> Unit
            }
            delay(STATUS_POLL_DELAY_MS)
        }
    } finally {
        metrics.endIppWait()
    }
}

private const val STATUS_POLLS = 6
private const val STATUS_POLL_DELAY_MS = 500L
