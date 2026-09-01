package ru.usbprint.printing

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.CustomPaperSizeMicrons
import ru.usbprint.domain.model.Microns
import ru.usbprint.domain.model.PrintSettings

class PwgRasterEncoderTest {
    private val layout = PrintLayoutEngine.create(PrintSettings(paperSize = PaperSize.A4), 595, 842, 300)

    @Test fun producesExactV2HeaderSizeAndCoreFields() {
        val bytes = PwgRasterHeader(layout, RasterColorMode.RGB, duplex = true, tumble = false).toBytes()
        val values = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(1796, bytes.size)
        assertEquals(300, values.getInt(276))
        assertEquals(2480, values.getInt(372))
        assertEquals(3508, values.getInt(376))
        assertEquals(24, values.getInt(388))
        assertEquals(19, values.getInt(400))
        assertEquals(3, values.getInt(420))
    }
    @Test fun usesPwgV2SyncAndPackBitsLineEncoding() {
        assertArrayEquals(byteArrayOf(0x52, 0x61, 0x53, 0x32), PwgRasterEncoder.syncWord)
        assertArrayEquals(byteArrayOf(0, 7, 0), PwgRasterEncoder.encodeLine(ByteArray(8), 1))
    }
    @Test fun customMediaUsesExactNumericSizeWithoutInventingAStandardKeyword() {
        val customLayout = PrintLayoutEngine.create(
            PrintSettings(customPaperSize = CustomPaperSizeMicrons(Microns(100_000), Microns(200_000))), 595, 842, 300
        )
        val bytes = PwgRasterHeader(customLayout, RasterColorMode.MONOCHROME, duplex = false, tumble = false).toBytes()
        val values = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(283, values.getInt(292))
        assertEquals(567, values.getInt(296))
        assertEquals(0, bytes[1732].toInt())
    }
}
