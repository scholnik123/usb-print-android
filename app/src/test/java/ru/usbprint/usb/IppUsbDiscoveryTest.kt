package ru.usbprint.usb

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.usbprint.domain.model.UsbEndpointInfo
import ru.usbprint.domain.model.UsbInterfaceInfo

class IppUsbDiscoveryTest {
    private fun ipp(id: Int, includeIn: Boolean = true) = UsbInterfaceInfo(id, 7, 1, 4, buildList {
        add(UsbEndpointInfo(1, "OUT", "bulk", 512))
        if (includeIn) add(UsbEndpointInfo(0x81, "IN", "bulk", 512))
    })

    @Test fun requiresTwoEquivalentBidirectionalProtocol4Interfaces() {
        assertEquals(emptySet<Int>(), IppUsbDiscovery.compliantInterfaceIds(listOf(ipp(1))))
        assertEquals(setOf(1, 2), IppUsbDiscovery.compliantInterfaceIds(listOf(ipp(1), ipp(2))))
    }

    @Test fun rejectsWrongProtocolAndMissingBulkIn() {
        val legacy = UsbInterfaceInfo(3, 7, 1, 2, listOf(UsbEndpointInfo(1, "OUT", "bulk", 64)))
        assertEquals(emptySet<Int>(), IppUsbDiscovery.compliantInterfaceIds(listOf(ipp(1, includeIn = false), ipp(2), legacy)))
    }
}
