package ru.usbprint

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.usbprint.document.DocumentRepository
import ru.usbprint.printing.PrintExecutor
import ru.usbprint.printing.PrintJobStore
import ru.usbprint.printing.HardwareTestPageFactory
import ru.usbprint.preferences.PrintPreferencesRepository
import ru.usbprint.usb.PrinterConnectionEvent
import ru.usbprint.usb.UsbPrinterController
import ru.usbprint.utils.DiagnosticLog

class UsbPrintApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(val appContext: Context) {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = DiagnosticLog()
    val usbManager: UsbManager = checkNotNull(appContext.getSystemService(Context.USB_SERVICE) as? UsbManager)
    val documents = DocumentRepository(appContext.contentResolver)
    val printerController = UsbPrinterController(appContext.applicationContext, usbManager, log)
    val printExecutor = PrintExecutor(usbManager, printerController, documents, log)
    val printJobStore = PrintJobStore()
    val hardwareTestPage = HardwareTestPageFactory(appContext)
    val preferences = PrintPreferencesRepository(appContext)

    init {
        applicationScope.launch {
            printerController.events.collect { event ->
                if (event is PrinterConnectionEvent.Detached) printExecutor.cancelIfPrinting(event.deviceKey)
            }
        }
    }
}
