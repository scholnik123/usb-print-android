package ru.usbprint.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException

class AndroidUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val usbInterface: UsbInterface
) : UsbTransport {
    private var connection: UsbDeviceConnection? = null
    private val outEndpoint: UsbEndpoint? = (0 until usbInterface.endpointCount)
        .map(usbInterface::getEndpoint)
        .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    private val inEndpoint: UsbEndpoint? = (0 until usbInterface.endpointCount)
        .map(usbInterface::getEndpoint)
        .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }

    override val isConnected: Boolean get() = connection != null

    override suspend fun open() = withContext(Dispatchers.IO) {
        if (!usbManager.hasPermission(device)) throw PrintException(AppError.USB_PERMISSION_DENIED)
        if (outEndpoint == null) throw PrintException(AppError.USB_ENDPOINT_NOT_FOUND)
        val opened = usbManager.openDevice(device) ?: throw PrintException(AppError.USB_CLAIM_FAILED)
        if (!opened.claimInterface(usbInterface, true)) {
            opened.close()
            throw PrintException(AppError.USB_CLAIM_FAILED)
        }
        connection = opened
    }

    override suspend fun write(bytes: ByteArray, timeoutMs: Int): Unit = withContext(Dispatchers.IO) {
        val activeConnection = connection ?: throw PrintException(AppError.USB_DEVICE_DISCONNECTED)
        val endpoint = outEndpoint ?: throw PrintException(AppError.USB_ENDPOINT_NOT_FOUND)
        UsbTransferWriter.writeAll(object : UsbWriteSink {
            override fun writeChunk(chunk: ByteArray, timeoutMs: Int): Int = activeConnection.bulkTransfer(endpoint, chunk, chunk.size, timeoutMs)
        }, bytes, timeoutMs)
        Unit
    }

    override suspend fun read(maxBytes: Int, timeoutMs: Int): ByteArray = withContext(Dispatchers.IO) {
        val activeConnection = connection ?: throw PrintException(AppError.USB_DEVICE_DISCONNECTED)
        val endpoint = inEndpoint ?: throw PrintException(AppError.USB_ENDPOINT_NOT_FOUND)
        val buffer = ByteArray(maxBytes.coerceIn(1, 64 * 1024))
        val read = activeConnection.bulkTransfer(endpoint, buffer, buffer.size, timeoutMs)
        when {
            read > 0 -> buffer.copyOf(read)
            read == 0 -> throw PrintException(AppError.TRANSFER_TIMEOUT)
            else -> throw PrintException(AppError.TRANSFER_ERROR)
        }
    }

    override suspend fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, timeoutMs: Int): Int =
        withContext(Dispatchers.IO) {
            val activeConnection = connection ?: throw PrintException(AppError.USB_DEVICE_DISCONNECTED)
            val result = activeConnection.controlTransfer(requestType, request, value, index, buffer, buffer.size, timeoutMs)
            if (result < 0) throw PrintException(AppError.TRANSFER_ERROR)
            result
        }

    override fun close() {
        connection?.let { active ->
            runCatching { active.releaseInterface(usbInterface) }
            active.close()
        }
        connection = null
    }
}
