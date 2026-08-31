package ru.usbprint.protocols

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.PrinterLanguage

class Ieee1284DeviceIdParserTest {
    @Test fun parsesAliasesAndLanguages() {
        val parsed = Ieee1284DeviceIdParser.parse("MFG:HP;MDL:LaserJet;CMD:PCL, PCLXL, POSTSCRIPT, PDF, PCLM, PWG-Raster;SN:ABC;")
        assertEquals("HP", parsed.field("MFG", "MANUFACTURER"))
        assertEquals("LaserJet", parsed.field("MDL"))
        assertTrue(PrinterLanguage.PDF in parsed.languages)
        assertTrue(PrinterLanguage.POSTSCRIPT in parsed.languages)
        assertTrue(PrinterLanguage.PCL in parsed.languages)
        assertTrue(PrinterLanguage.PCL_XL in parsed.languages)
        assertTrue(PrinterLanguage.PCLM in parsed.languages)
        assertTrue(PrinterLanguage.PWG_RASTER in parsed.languages)
    }

    @Test fun stripsLengthPrefixFromUsbResponse() {
        val body = "MFG:Epson;CMD:ESC/POS;".toByteArray(Charsets.ISO_8859_1)
        val response = byteArrayOf(0, (body.size + 2).toByte()) + body
        val parsed = Ieee1284DeviceIdParser.parseUsbResponse(response)
        assertEquals("Epson", parsed.field("MFG"))
        assertTrue(PrinterLanguage.ESC_POS in parsed.languages)
    }

    @Test fun acceptsMalformedLengthsAndDuplicateFieldsWithoutCrashing() {
        assertTrue(Ieee1284DeviceIdParser.parseUsbResponse(byteArrayOf(0)).fields.isEmpty())
        val oversized = byteArrayOf(0x7f, 0x7f) + "MFG:One;MFG:Two;CMD:UNKNOWN;".toByteArray()
        val parsed = Ieee1284DeviceIdParser.parseUsbResponse(oversized)
        assertEquals("Two", parsed.field("MFG"))
        assertTrue(parsed.languages.isEmpty())
    }
}
