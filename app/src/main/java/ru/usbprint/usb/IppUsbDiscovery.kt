package ru.usbprint.usb

import ru.usbprint.domain.model.UsbInterfaceInfo

/** Pure USB descriptor filter. The IPP-over-USB specification requires at least two equivalent protocol-4 interfaces. */
object IppUsbDiscovery {
    const val MIN_EQUIVALENT_INTERFACES = 2

    fun compliantInterfaceIds(interfaces: List<UsbInterfaceInfo>): Set<Int> {
        val ids = interfaces.asSequence().filter(UsbInterfaceInfo::isIppUsb).map(UsbInterfaceInfo::id).toCollection(linkedSetOf())
        return ids.takeIf { it.size >= MIN_EQUIVALENT_INTERFACES }.orEmpty()
    }
}
