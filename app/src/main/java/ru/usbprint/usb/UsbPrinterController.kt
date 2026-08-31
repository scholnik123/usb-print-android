package ru.usbprint.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.IppPrinterInfo
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterRef
import ru.usbprint.domain.model.UsbEndpointInfo
import ru.usbprint.domain.model.UsbInterfaceInfo
import ru.usbprint.ipp.IppClient
import ru.usbprint.ipp.IppPrinterCapabilitiesMapper
import ru.usbprint.ipp.IppUsbSession
import ru.usbprint.utils.DiagnosticLog

sealed interface UsbPrinterState {
    object Checking : UsbPrinterState
    object HostUnsupported : UsbPrinterState
    object NoPrinter : UsbPrinterState
    data class PermissionRequired(val deviceName: String, val printers: List<PrinterRef> = emptyList()) : UsbPrinterState
    data class Connecting(val deviceName: String, val printers: List<PrinterRef> = emptyList()) : UsbPrinterState
    data class Ready(val printer: PrinterRef, val printers: List<PrinterRef> = listOf(printer)) : UsbPrinterState
    data class Error(val error: AppError, val printers: List<PrinterRef> = emptyList()) : UsbPrinterState
}

sealed interface PrinterConnectionEvent { data class Detached(val deviceKey: String) : PrinterConnectionEvent }

/** Owns discovery and probing only. A print job owns its transport and always closes it in PrintExecutor. */
class UsbPrinterController(
    private val context: Context,
    private val usbManager: UsbManager,
    private val log: DiagnosticLog
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val capabilityReader = PrinterCapabilityReader()
    private val _state = MutableStateFlow<UsbPrinterState>(UsbPrinterState.Checking)
    val state = _state.asStateFlow()
    private val _printers = MutableStateFlow<List<PrinterRef>>(emptyList())
    val printers = _printers.asStateFlow()
    private val _events = MutableSharedFlow<PrinterConnectionEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()
    private var selectedDeviceKey: String? = null
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                USB_PERMISSION_ACTION -> {
                    val device = intent.usbDeviceOrNull()
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device == null || device.deviceName == selectedDeviceKey) {
                        log.add(if (granted) "USB permission granted for ${device?.deviceName}" else "USB permission denied for ${device?.deviceName}")
                        if (!granted && device?.deviceName == selectedDeviceKey) _state.value = UsbPrinterState.Error(AppError.USB_PERMISSION_DENIED, _printers.value)
                        else refresh()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { log.add("USB device attached"); refresh() }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDeviceOrNull()
                    val detachedKey = device?.deviceName
                    if (detachedKey != null && detachedKey == selectedDeviceKey) {
                        _events.tryEmit(PrinterConnectionEvent.Detached(detachedKey))
                        log.add("Selected printer detached: $detachedKey")
                    } else log.add("USB device detached: ${device?.deviceName ?: "unknown"}")
                    refresh()
                }
            }
        }
    }

    fun start() {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            _state.value = UsbPrinterState.HostUnsupported
            return
        }
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(USB_PERMISSION_ACTION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        refresh()
    }

    fun refresh() = scope.launch {
        val devices = usbManager.deviceList.values.filter(::isPrintableUsbDevice)
        val discovered = devices.mapNotNull { device ->
            val legacy = findLegacyPrinterInterface(device)
            val ipp = findIppInterfaces(device)
            val primary = legacy ?: ipp.firstOrNull() ?: return@mapNotNull null
            PrinterRef(device.deviceName, device.toCapabilities(), primary.id, ipp.firstOrNull()?.id)
        }
            .sortedBy { it.capabilities.displayName }
        _printers.value = discovered
        if (discovered.isEmpty()) {
            selectedDeviceKey = null
            _state.value = UsbPrinterState.NoPrinter
            return@launch
        }
        if (selectedDeviceKey !in discovered.map { it.deviceKey }) selectedDeviceKey = discovered.first().deviceKey
        val selected = discovered.first { it.deviceKey == selectedDeviceKey }
        val device = usbManager.deviceList[selected.deviceKey] ?: run { _state.value = UsbPrinterState.NoPrinter; return@launch }
        if (!usbManager.hasPermission(device)) {
            _state.value = UsbPrinterState.PermissionRequired(selected.capabilities.displayName, discovered)
            return@launch
        }
        probe(device, discovered)
    }

    fun selectPrinter(deviceKey: String) {
        if (_printers.value.none { it.deviceKey == deviceKey }) return
        selectedDeviceKey = deviceKey
        log.add("Printer selected: $deviceKey")
        refresh()
    }

    fun requestPermission() {
        val device = selectedDeviceKey?.let(usbManager.deviceList::get) ?: return
        val intent = Intent(USB_PERMISSION_ACTION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        usbManager.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, intent, flags))
        log.add("USB permission requested for ${device.deviceName}")
    }

    private suspend fun probe(device: UsbDevice, discovered: List<PrinterRef>) {
        _state.value = UsbPrinterState.Connecting(device.productName ?: device.deviceName, discovered)
        val ippInterfaces = findIppInterfaces(device)
        val usbInterface = findLegacyPrinterInterface(device) ?: ippInterfaces.firstOrNull()
        if (usbInterface == null) { _state.value = UsbPrinterState.Error(AppError.USB_INTERFACE_NOT_FOUND, discovered); return }
        var capabilities = device.toCapabilities()
        AndroidUsbTransport(usbManager, device, usbInterface).use { transport ->
            try {
                transport.open()
                runCatching { capabilityReader.getIeee1284DeviceId(transport, usbInterface.id) }
                    .onSuccess { id -> capabilities = capabilityReader.applyDeviceId(capabilities, id); log.add("IEEE-1284 Device ID ${id?.take(180) ?: "not available"}") }
                    .onFailure { log.add("Device ID unavailable: ${it.message}") }
                runCatching { capabilityReader.getPortStatus(transport, usbInterface.id) }
                    .onSuccess { status -> capabilities = capabilities.copy(portStatus = status); log.add("USB port status: ${status?.rawValue?.toString(16) ?: "unavailable"}") }
                    .onFailure { log.add("Port status unavailable: ${it.message}") }
            } catch (exception: Exception) { log.add("Capability probe failed: ${exception.message}") }
        }
        val ippInterface = ippInterfaces.firstOrNull()
        if (ippInterface != null) {
            AndroidUsbTransport(usbManager, device, ippInterface).use { transport ->
                try {
                    transport.open()
                    val response = IppClient(IppUsbSession(transport)).getPrinterAttributes()
                    capabilities = IppPrinterCapabilitiesMapper.map(capabilities, response)
                    log.add("IPP attributes confirmed: operations=${capabilities.ipp.operationsSupported.size}; formats=${capabilities.ipp.documentFormatsSupported}")
                } catch (exception: Exception) {
                    log.add("IPP Get-Printer-Attributes failed: ${exception.message}")
                }
            }
        }
        val ref = PrinterRef(device.deviceName, capabilities, usbInterface.id, ippInterface?.id)
        val updated = discovered.map { if (it.deviceKey == ref.deviceKey) ref else it }
        _printers.value = updated
        log.add("Printer ready: ${capabilities.displayName}; legacy/primary interface ${usbInterface.id}; IPP interface ${ippInterface?.id ?: "none"}; languages ${capabilities.supportedLanguages}")
        _state.value = UsbPrinterState.Ready(ref, updated)
    }

    override fun close() {
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
        scope.cancel()
    }

    fun deviceFor(ref: PrinterRef): UsbDevice? = usbManager.deviceList[ref.deviceKey]
    fun interfaceFor(device: UsbDevice, id: Int): UsbInterface? = (0 until device.interfaceCount).map(device::getInterface).firstOrNull { it.id == id }
    private fun isPrintableUsbDevice(device: UsbDevice): Boolean = findLegacyPrinterInterface(device) != null || findIppInterfaces(device).isNotEmpty()
    private fun findLegacyPrinterInterface(device: UsbDevice): UsbInterface? = (0 until device.interfaceCount).map(device::getInterface).firstOrNull { usbInterface ->
        usbInterface.interfaceClass == UsbInterfaceInfo.USB_CLASS_PRINTER && usbInterface.interfaceProtocol != UsbInterfaceInfo.IPP_PROTOCOL &&
            (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint).any {
            it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK
        }
    }
    private fun findIppInterfaces(device: UsbDevice): List<UsbInterface> {
        val compliantIds = IppUsbDiscovery.compliantInterfaceIds(device.interfaceInfos())
        return (0 until device.interfaceCount).map(device::getInterface).filter { it.id in compliantIds }
    }

    private fun UsbDevice.toCapabilities(): PrinterCapabilities {
        val infos = interfaceInfos()
        val primary = infos.firstOrNull { it.isPrinterClass }
        return PrinterCapabilities(vendorId = vendorId, productId = productId, usbDeviceId = deviceId, usbClass = primary?.interfaceClass,
            usbSubclass = primary?.subclass, usbProtocol = primary?.protocol, interfaces = infos, productName = productName,
            ipp = IppPrinterInfo(interfaceIds = IppUsbDiscovery.compliantInterfaceIds(infos)))
    }

    private fun UsbDevice.interfaceInfos(): List<UsbInterfaceInfo> = (0 until interfaceCount).map { index -> getInterface(index).let { intf ->
            UsbInterfaceInfo(intf.id, intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol,
                (0 until intf.endpointCount).map { endpointIndex -> intf.getEndpoint(endpointIndex).let { endpoint ->
                    UsbEndpointInfo(endpoint.address, if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT",
                        when (endpoint.type) { UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"; UsbConstants.USB_ENDPOINT_XFER_INT -> "interrupt"; else -> "other" }, endpoint.maxPacketSize)
                } })
        } }

    private fun Intent.usbDeviceOrNull(): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else @Suppress("DEPRECATION") { getParcelableExtra(UsbManager.EXTRA_DEVICE) }

    private companion object { const val USB_PERMISSION_ACTION = "ru.usbprint.USB_PERMISSION" }
}
