package ru.usbprint.printing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.usbprint.domain.model.BackendId

class PrintingEncoderVersionsTest {
    @Test fun profileVersionsTrackActualProtocolEncoders() {
        assertEquals(PwgRasterEncoder.ENCODER_VERSION, PrintingEncoderVersions.forBackend(BackendId.PWG_RASTER))
        assertEquals(PwgRasterEncoder.ENCODER_VERSION, PrintingEncoderVersions.forBackend(BackendId.IPP_PWG))
        assertEquals(PostScriptRasterEncoder.ENCODER_VERSION, PrintingEncoderVersions.forBackend(BackendId.POSTSCRIPT_RASTER))
        assertEquals(Pcl5JobEncoder.ENCODER_VERSION, PrintingEncoderVersions.forBackend(BackendId.PCL5_RASTER))
        assertNull(PrintingEncoderVersions.forBackend(BackendId.NONE))
    }
}
