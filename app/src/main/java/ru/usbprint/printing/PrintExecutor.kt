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
    val progress: Int = 0,
    val error: AppError? = null,
    val detail: String? = null
)

/** Single-job executor used exclusively by the foreground service; it never retries a partly accepted job. */
class PrintExecutor(
    private val usbManager: UsbManager,
    private val printerController: UsbPrinterController,
    private val documents: DocumentRepository,
    ippPwgSpoolManager: IppPwgSpoolManager,
    private val log: DiagnosticLog
) {
    private val cancelled = AtomicBoolean(false)
    private val activeJob = AtomicReference<PrintJob?>(null)
    private val _state = MutableStateFlow(PrintExecutionState())
    val state = _state.asStateFlow()
    private val ippStatusListener: (ru.usbprint.ipp.IppJobStatus) -> Unit = { status ->
            val current = _state.value
            _state.value = current.copy(
                status = PrintJobStatus.WAITING_STATUS,
                progress = current.progress.coerceAtLeast(96),
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
        try {
            _state.value = PrintExecutionState(job.id, PrintJobStatus.VALIDATING)
            val device = printerController.deviceFor(job.printer) ?: throw PrintException(AppError.USB_DEVICE_DISCONNECTED)
            if (!usbManager.hasPermission(device)) throw PrintException(AppError.USB_PERMISSION_DENIED)
            val isIpp = job.backend == BackendId.IPP_DIRECT || job.backend == BackendId.IPP_PWG
            val interfaceId = if (isIpp) job.printer.ippInterfaceId
                ?: throw PrintException(AppError.USB_INTERFACE_NOT_FOUND) else job.printer.interfaceId
            val interfaceToUse = printerController.interfaceFor(device, interfaceId) ?: throw PrintException(AppError.USB_INTERFACE_NOT_FOUND)
            val backend = backends[job.backend] ?: throw PrintException(AppError.PRINTER_NOT_SUPPORTED)
            log.add("Job ${job.id.take(8)} starting: ${backend.id.title}")
            _state.value = PrintExecutionState(job.id, PrintJobStatus.OPENING_USB, 0)
            AndroidUsbTransport(usbManager, device, interfaceToUse).use { transport ->
                transport.open()
                _state.value = PrintExecutionState(job.id, PrintJobStatus.PREPARING_DOCUMENT, 0)
                backend.print(job, transport, documents, { progress ->
                    val current = _state.value
                    val waiting = isIpp && progress >= 96
                    _state.value = PrintExecutionState(
                        job.id,
                        if (waiting) PrintJobStatus.WAITING_STATUS else PrintJobStatus.SENDING,
                        progress.coerceIn(0, 100),
                        detail = current.detail.takeIf { waiting }
                    )
                }) { cancelled.get() }
            }
            _state.value = PrintExecutionState(job.id, PrintJobStatus.SENT, 100, detail = _state.value.detail)
            log.add("Job ${job.id.take(8)} bytes handed to USB printer")
        } catch (exception: Throwable) {
            val error = (exception as? PrintException)?.error ?: AppError.TRANSFER_ERROR
            _state.value = PrintExecutionState(job.id, if (error == AppError.PRINT_CANCELLED) PrintJobStatus.CANCELLED else PrintJobStatus.ERROR, _state.value.progress, error)
            log.add("Job ${job.id.take(8)} ended: ${error.name}")
            throw exception
        } finally {
            activeJob.set(null)
        }
    }

    fun cancel() { cancelled.set(true); log.add("Print cancellation requested") }
    fun cancelIfPrinting(deviceKey: String) { if (activeJob.get()?.printer?.deviceKey == deviceKey) cancel() }
}
