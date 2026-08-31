package ru.usbprint.ipp

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException
import ru.usbprint.usb.UsbTransport
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class IppHttpResponse(val statusCode: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray)

object IppHttpRequestEncoder {
    fun encodeHeaders(contentLength: Long): ByteArray {
        require(contentLength in 0..MAX_CONTENT_LENGTH)
        return ("POST /ipp/print HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/ipp\r\n" +
            "Accept: application/ipp\r\n" +
            "Content-Length: $contentLength\r\n" +
            "Connection: keep-alive\r\n\r\n").toByteArray(StandardCharsets.US_ASCII)
    }
    const val MAX_CONTENT_LENGTH = 2L * 1024 * 1024 * 1024
}

/** Incremental bounded HTTP/1.1 response accumulator for Content-Length or chunked replies. */
class IppHttpResponseAccumulator(
    private val maxHeaderBytes: Int = 32 * 1024,
    private val maxBodyBytes: Int = 2 * 1024 * 1024
) {
    private val bytes = ByteArrayOutputStream()

    fun append(chunk: ByteArray): IppHttpResponse? {
        require(chunk.isNotEmpty())
        require(bytes.size().toLong() + chunk.size <= maxHeaderBytes.toLong() + maxBodyBytes + MAX_CHUNK_OVERHEAD)
        bytes.write(chunk)
        return parseIfComplete(bytes.toByteArray())
    }

    private fun parseIfComplete(all: ByteArray): IppHttpResponse? {
        val headerEnd = all.indexOf(HEADER_END)
        if (headerEnd < 0) { require(all.size <= maxHeaderBytes); return null }
        require(headerEnd + HEADER_END.size <= maxHeaderBytes)
        val lines = all.copyOfRange(0, headerEnd).toString(StandardCharsets.ISO_8859_1).split("\r\n")
        val statusParts = lines.firstOrNull()?.split(' ', limit = 3).orEmpty()
        require(statusParts.size >= 2 && statusParts[0] == "HTTP/1.1") { "Expected HTTP/1.1 response" }
        val status = statusParts[1].toIntOrNull() ?: error("Malformed HTTP status")
        val headers = linkedMapOf<String, String>()
        lines.drop(1).forEach { line ->
            val colon = line.indexOf(':'); require(colon > 0)
            val name = line.substring(0, colon).trim().lowercase(); val value = line.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it,$value" } ?: value
        }
        val bodyStart = headerEnd + HEADER_END.size
        val bodyAvailable = all.size - bodyStart
        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        val body = when {
            transferEncoding?.split(',')?.map(String::trim)?.contains("chunked") == true -> decodeChunked(all, bodyStart) ?: return null
            headers["content-length"] != null -> {
                val length = headers.getValue("content-length").toLongOrNull() ?: error("Invalid Content-Length")
                require(length in 0..maxBodyBytes.toLong())
                if (bodyAvailable < length) return null
                all.copyOfRange(bodyStart, bodyStart + length.toInt())
            }
            else -> error("HTTP response has neither Content-Length nor chunked encoding")
        }
        require(body.size <= maxBodyBytes)
        return IppHttpResponse(status, statusParts.getOrElse(2) { "" }, headers, body)
    }

    private fun decodeChunked(all: ByteArray, start: Int): ByteArray? {
        val output = ByteArrayOutputStream(); var cursor = start; var chunks = 0
        while (true) {
            require(++chunks <= MAX_CHUNKS)
            val lineEnd = all.indexOf(CRLF, cursor)
            if (lineEnd < 0) return null
            val sizeText = all.copyOfRange(cursor, lineEnd).toString(StandardCharsets.US_ASCII).substringBefore(';').trim()
            val size = sizeText.toLongOrNull(16) ?: error("Invalid chunk size")
            require(size in 0..maxBodyBytes.toLong() && output.size().toLong() + size <= maxBodyBytes)
            cursor = lineEnd + 2
            if (size == 0L) {
                // Empty trailers end with one CRLF; non-empty trailers end with CRLFCRLF.
                if (all.size < cursor + 2) return null
                if (all[cursor] == '\r'.code.toByte() && all[cursor + 1] == '\n'.code.toByte()) return output.toByteArray()
                return if (all.indexOf(HEADER_END, cursor) >= 0) output.toByteArray() else null
            }
            if (all.size < cursor + size.toInt() + 2) return null
            output.write(all, cursor, size.toInt()); cursor += size.toInt()
            require(all[cursor] == '\r'.code.toByte() && all[cursor + 1] == '\n'.code.toByte())
            cursor += 2
        }
    }

    private fun ByteArray.indexOf(pattern: ByteArray, from: Int = 0): Int {
        if (pattern.isEmpty()) return from
        for (index in from..size - pattern.size) if (pattern.indices.all { this[index + it] == pattern[it] }) return index
        return -1
    }

    private companion object {
        val HEADER_END = "\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        const val MAX_CHUNKS = 65_536
        const val MAX_CHUNK_OVERHEAD = 1024 * 1024
    }
}

interface IppSession {
    suspend fun exchange(ippData: ByteArray, document: InputStream? = null, documentLength: Long = 0L, isCancelled: () -> Boolean = { false }): ByteArray
}

/** HTTP/1.1 over a protocol-4 USB bulk IN/OUT interface. No Android network API is used. */
class IppUsbSession(
    private val transport: UsbTransport,
    private val onUploadProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
) : IppSession {
    override suspend fun exchange(ippData: ByteArray, document: InputStream?, documentLength: Long, isCancelled: () -> Boolean): ByteArray = coroutineScope {
        val totalLength = try { Math.addExact(ippData.size.toLong(), documentLength) }
        catch (error: ArithmeticException) { throw PrintException(AppError.OUT_OF_MEMORY_PREVENTED, error) }
        require(totalLength <= IppHttpRequestEncoder.MAX_CONTENT_LENGTH)
        val response = async {
            val accumulator = IppHttpResponseAccumulator()
            while (true) {
                val parsed = try { accumulator.append(transport.read(32 * 1024, RESPONSE_TIMEOUT_MS)) }
                catch (error: PrintException) { throw error }
                catch (error: Throwable) { throw PrintException(AppError.IPP_HTTP_ERROR, error) }
                if (parsed != null) {
                    if (parsed.statusCode != 200) throw PrintException(AppError.IPP_HTTP_ERROR)
                    if (!parsed.headers["content-type"].orEmpty().substringBefore(';').trim().equals("application/ipp", ignoreCase = true)) throw PrintException(AppError.IPP_HTTP_ERROR)
                    return@async parsed.body
                }
            }
            @Suppress("UNREACHABLE_CODE") ByteArray(0)
        }
        try {
            transport.write(IppHttpRequestEncoder.encodeHeaders(totalLength))
            transport.write(ippData)
            if (document != null) {
                var remaining = documentLength; var sent = 0L; val buffer = ByteArray(16 * 1024)
                while (remaining > 0L) {
                    if (isCancelled()) throw PrintException(AppError.PRINT_CANCELLED)
                    val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                    val count = document.read(buffer, 0, wanted)
                    if (count < 0) throw PrintException(AppError.DOCUMENT_READ_ERROR)
                    transport.write(if (count == buffer.size) buffer else buffer.copyOf(count))
                    remaining -= count
                    sent += count
                    onUploadProgress(sent, documentLength)
                }
                require(document.read() == -1) { "Document is larger than declared Content-Length" }
            }
            response.await()
        } catch (error: PrintException) { throw error }
        catch (error: Throwable) { throw PrintException(AppError.IPP_TRANSPORT_ERROR, error) }
    }

    private companion object { const val RESPONSE_TIMEOUT_MS = 30_000 }
}
