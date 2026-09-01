package ru.usbprint.printing

import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import ru.usbprint.document.DocumentRepository
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.domain.model.PrintJobStatus
import ru.usbprint.usb.AndroidUsbTransport
import ru.usbprint.usb.UsbPrinterController
import ru.usbprint.utils.DiagnosticLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class PrintExecutionState(
    val jobId: String? = null,
    val status: PrintJobStatus = PrintJobStatus.IDLE,
    val progress: Int? = null,
    val progressDetail: String? = null,
    val error: AppError? = null,
    val detail: String? = null
)

/** Single-job executor used exclusively by the foreground service; it never retries a partly accepted job. */
class PrintExecutor(
    private val usbManager: UsbManager,
    private val printerController: UsbPrinterController,
    private val documents: DocumentRepository,
    ippPwgSpoolManager: IppPwgSpoolManager,
    private val log: DiagnosticLog,
    private val metricsStore: PrintJobMetricsStore
) {
    private val cancelled = AtomicBoolean(false)
    private val activeJob = AtomicReference<PrintJob?>(null)
    private val _state = MutableStateFlow(PrintExecutionState())
    val state = _state.asStateFlow()
    private val ippStatusListener: (ru.usbprint.ipp.IppJobStatus) -> Unit = { status ->
            val current = _state.value
            _state.value = current.copy(
                status = PrintJobStatus.WAITING_STATUS,
                progress = null,
                detail = status.state?.label
            )
        }
    private val backends = mapOf(
        BackendId.IPP_DIRECT to IppDirectBackend(ippStatusListener),
        BackendId.IPP_PWG to IppPwgBackend(ippPwgSpoolManager, ippStatusListener),
        BackendId.PDF_DIRECT to PdfDirectBackend(),
        BackendId.PWG_RASTER to PwgRasterBackend(),
        BackendId.POSTSCRIPT_RASTER to PostScriptRasterBackend(),
        BackendId.PCL5_RASTER to Pcl5RasterBackend(),
        BackendId.ESC_POS to EscPosBackend(),
        BackendId.RAW to RawUsbBackend()
    )

    suspend fun execute(job: PrintJob) = withContext(Dispatchers.IO) {
        if (!activeJob.compareAndSet(null, job)) throw PrintException(AppError.TRANSFER_ERROR)
        cancelled.set(false)
        val metrics = PrintMetricsCollector(job.id, job.backend)
        var terminalStatus = PrintJobStatus.ERROR
        var terminalError: AppError? = AppError.TRANSFER_ERROR
        try {
            _state.value = PrintExecutionState(job.id, PrintJobStatus.VALIDATING)
            val device = printerController.deviceFor(job.printer) ?: throw PrintException(AppError.USB_DEVICE_DISCONNECTED)
            if (!usbManager.hasPermission(device)) throw PrintException(AppError.USB_PERMISSION_DENIED)
            val interfaceId = if (job.backend == BackendId.IPP_DIRECT || job.backend == BackendId.IPP_PWG) job.printer.ippInterfaceId
                ?: throw PrintException(AppError.USB_INTERFACE_NOT_FOUND) else job.printer.interfaceId
            val interfaceToUse = printerController.interfaceFor(device, interfaceId) ?: throw PrintException(AppError.USB_INTERFACE_NOT_FOUND)
            val backend = backends[job.backend] ?: throw PrintException(AppError.PRINTER_NOT_SUPPORTED)
            log.add("Job ${job.id.take(8)} starting: ${backend.id.title}")
            _state.value = PrintExecutionState(job.id, PrintJobStatus.OPENING_USB)
            AndroidUsbTransport(usbManager, device, interfaceToUse).use { rawTransport ->
                rawTransport.open()
                val transport = MetricsUsbTransport(rawTransport, metrics)
                _state.value = PrintExecutionState(job.id, PrintJobStatus.PREPARING_DOCUMENT)
                metrics.markPrepared()
                backend.print(job, transport, documents, { progress ->
                    val current = _state.value
                    _state.value = PrintExecutionState(
                        job.id,
                        progress.status,
                        progress.percent,
                        progress.detail,
                        detail = current.detail.takeIf { progress.status == PrintJobStatus.WAITING_STATUS }
                    )
                }, { cancelled.get() }, metrics)
            }
            terminalStatus = PrintJobStatus.SENT
            terminalError = null
            _state.value = PrintExecutionState(job.id, PrintJobStatus.SENT, 100, _state.value.progressDetail, detail = _state.value.detail)
            log.add("Job ${job.id.take(8)} bytes handed to USB printer")
        } catch (exception: Throwable) {
            val error = (exception as? PrintException)?.error ?: AppError.TRANSFER_ERROR
            terminalStatus = if (error == AppError.PRINT_CANCELLED) PrintJobStatus.CANCELLED else PrintJobStatus.ERROR
            terminalError = error
            _state.value = PrintExecutionState(job.id, terminalStatus, _state.value.progress, _state.value.progressDetail, error)
            log.add("Job ${job.id.take(8)} ended: ${error.name}")
            throw exception
        } finally {
            val completedMetrics = metrics.finish(terminalStatus, terminalError)
            metricsStore.record(completedMetrics)
            log.add("Job metrics: ${completedMetrics.diagnosticLine()}")
            activeJob.set(null)
        }
    }

    fun cancel() { cancelled.set(true); log.add("Print cancellation requested") }
    fun cancelIfPrinting(deviceKey: String) { if (activeJob.get()?.printer?.deviceKey == deviceKey) cancel() }
}
