package ru.usbprint.preferences

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.CustomPaperSizeMicrons
import ru.usbprint.domain.model.Microns

class PrintSettingsCodecTest {
    @Test fun roundTripsNUpCompositionOptions() {
        val settings = PrintSettings(
            pagesPerSheet = 4,
            nUpSpacingMm = 6.5f,
            nUpDrawBorders = true,
            nUpAutoRotate = false
        )

        val decoded = checkNotNull(PrintSettingsCodec.decode(PrintSettingsCodec.encode("N-up", settings)))

        assertEquals("N-up", decoded.first)
        assertEquals(4, decoded.second.pagesPerSheet)
        assertEquals(6.5f, decoded.second.nUpSpacingMm)
        assertEquals(true, decoded.second.nUpDrawBorders)
        assertEquals(false, decoded.second.nUpAutoRotate)
    }

    @Test fun oldPresetGetsSafeNUpDefaults() {
        val encoded = PrintSettingsCodec.encode("old", PrintSettings()).split('&')
            .filterNot { it.startsWith("nupSpacing=") || it.startsWith("nupBorders=") || it.startsWith("nupRotate=") }
            .joinToString("&")

        val settings = checkNotNull(PrintSettingsCodec.decode(encoded)).second

        assertEquals(3f, settings.nUpSpacingMm)
        assertEquals(false, settings.nUpDrawBorders)
        assertEquals(true, settings.nUpAutoRotate)
    }

    @Test fun roundTripsCustomPaperAsMicrons() {
        val custom = CustomPaperSizeMicrons(Microns(101_600), Microns(152_400))
        val decoded = checkNotNull(PrintSettingsCodec.decode(PrintSettingsCodec.encode("4x6", PrintSettings(customPaperSize = custom))))
        assertEquals(custom, decoded.second.customPaperSize)
    }
}
