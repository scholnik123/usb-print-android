package ru.usbprint.printing

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException

/** Owns short-lived, bounded PWG Raster files under the application's cache directory. */
class IppPwgSpoolManager(cacheDirectory: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
    private val directory = File(cacheDirectory, DIRECTORY_NAME).apply {
        if (!exists() && !mkdirs()) error("Unable to create IPP PWG spool directory")
        require(isDirectory) { "IPP PWG spool path is not a directory" }
    }

    init {
        require(maxBytes in 1..MAX_SUPPORTED_BYTES)
        cleanupAbandoned()
    }

    @Synchronized
    fun create(): IppPwgSpool {
        val file = File.createTempFile(FILE_PREFIX, FILE_SUFFIX, directory)
        return IppPwgSpool(file, maxBytes)
    }

    @Synchronized
    fun cleanupAbandoned(): Int {
        var deleted = 0
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .forEach { if (it.delete()) deleted++ }
        return deleted
    }

    companion object {
        const val DIRECTORY_NAME = "ipp-pwg-spool"
        const val FILE_PREFIX = "ipp-pwg-"
        const val FILE_SUFFIX = ".ras"
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024
        const val MAX_SUPPORTED_BYTES = 2L * 1024 * 1024 * 1024
    }
}

class IppPwgSpool internal constructor(val file: File, private val maxBytes: Long) : AutoCloseable {
    private var outputOpened = false
    val length: Long get() = file.length()

    fun openOutputStream(): OutputStream {
        check(!outputOpened) { "Spool output can only be opened once" }
        outputOpened = true
        return BoundedSpoolOutputStream(FileOutputStream(file, false), maxBytes)
    }

    fun openInputStream(): InputStream {
        check(outputOpened) { "Spool must be generated before it is read" }
        return FileInputStream(file)
    }

    override fun close() {
        if (file.exists()) file.delete()
    }
}

private class BoundedSpoolOutputStream(output: OutputStream, private val maxBytes: Long) : FilterOutputStream(output) {
    private var written = 0L

    override fun write(value: Int) {
        requireCapacity(1)
        out.write(value)
        written++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        requireCapacity(length)
        out.write(bytes, offset, length)
        written += length
    }

    private fun requireCapacity(additionalBytes: Int) {
        val next = try {
            Math.addExact(written, additionalBytes.toLong())
        } catch (overflow: ArithmeticException) {
            throw PrintException(AppError.OUT_OF_MEMORY_PREVENTED, overflow)
        }
        if (next > maxBytes) throw PrintException(AppError.OUT_OF_MEMORY_PREVENTED)
    }
}
