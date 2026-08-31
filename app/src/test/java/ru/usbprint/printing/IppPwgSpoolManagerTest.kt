package ru.usbprint.printing

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException

class IppPwgSpoolManagerTest {
    @Test fun createsUniqueCacheFilesAndDeletesThemOnClose() = withTempDirectory { cache ->
        val manager = IppPwgSpoolManager(cache, maxBytes = 16)
        val first = manager.create()
        val second = manager.create()

        assertNotEquals(first.file, second.file)
        assertTrue(first.file.canonicalPath.startsWith(cache.canonicalPath + File.separator))
        first.openOutputStream().use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(3L, first.length)
        assertTrue(first.file.exists())

        first.close()
        second.close()
        assertFalse(first.file.exists())
        assertFalse(second.file.exists())
    }

    @Test fun enforcesExactMaximumSize() = withTempDirectory { cache ->
        val spool = IppPwgSpoolManager(cache, maxBytes = 4).create()
        try {
            spool.openOutputStream().use { output ->
                output.write(byteArrayOf(1, 2, 3, 4))
                val error = runCatching { output.write(5) }.exceptionOrNull() as PrintException
                assertEquals(AppError.OUT_OF_MEMORY_PREVENTED, error.error)
            }
            assertEquals(4L, spool.length)
        } finally {
            spool.close()
        }
        assertFalse(spool.file.exists())
    }

    @Test fun removesAbandonedSpoolsWhenManagerStarts() = withTempDirectory { cache ->
        val spoolDirectory = File(cache, IppPwgSpoolManager.DIRECTORY_NAME).apply { mkdirs() }
        val abandoned = File(spoolDirectory, "${IppPwgSpoolManager.FILE_PREFIX}abandoned${IppPwgSpoolManager.FILE_SUFFIX}").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val unrelated = File(spoolDirectory, "keep.txt").apply { writeText("keep") }

        val manager = IppPwgSpoolManager(cache, maxBytes = 16)

        assertFalse(abandoned.exists())
        assertTrue(unrelated.exists())
        manager.create().close()
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("usb-print-spool-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
