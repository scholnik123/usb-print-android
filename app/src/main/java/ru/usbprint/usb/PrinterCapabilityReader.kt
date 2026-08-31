package ru.usbprint.usb

import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterPortStatus
import ru.usbprint.protocols.Ieee1284DeviceIdParser

object PrinterClassRequests {
    const val GET_DEVICE_ID = 0
    const val GET_PORT_STATUS = 1
    const val REQUEST_TYPE_CLASS_INTERFACE_IN = 0xA1
}

class PrinterCapabilityReader {
    suspend fun getIeee1284DeviceId(transport: UsbTransport, interfaceId: Int): String? {
        val buffer = ByteArray(1024)
        val bytes = transport.controlTransfer(
            PrinterClassRequests.REQUEST_TYPE_CLASS_INTERFACE_IN,
            PrinterClassRequests.GET_DEVICE_ID,
            0,
            interfaceId,
            buffer
        )
        return if (bytes >= 2) Ieee1284DeviceIdParser.parseUsbResponse(buffer.copyOf(bytes)).raw else null
    }

    /** GET_PORT_STATUS is optional; callers deliberately treat a missing response as unavailable, not a print failure. */
    suspend fun getPortStatus(transport: UsbTransport, interfaceId: Int): PrinterPortStatus? {
        val buffer = ByteArray(1)
        val bytes = transport.controlTransfer(
            PrinterClassRequests.REQUEST_TYPE_CLASS_INTERFACE_IN,
            PrinterClassRequests.GET_PORT_STATUS,
            0,
            interfaceId,
            buffer
        )
        return if (bytes >= 1) PrinterPortStatus(buffer[0].toInt() and 0xff) else null
    }

    fun applyDeviceId(base: PrinterCapabilities, rawDeviceId: String?): PrinterCapabilities {
        if (rawDeviceId.isNullOrBlank()) return base
        val parsed = Ieee1284DeviceIdParser.parse(rawDeviceId)
        return base.copy(
            manufacturer = parsed.field("MFG", "MANUFACTURER") ?: base.manufacturer,
            model = parsed.field("MDL", "MODEL") ?: base.model,
            serialNumber = parsed.field("SN", "SERN", "SERIALNUMBER") ?: base.serialNumber,
            supportedLanguages = parsed.languages,
            rawDeviceId = parsed.raw,
            deviceIdFields = parsed.fields
        )
    }
}
