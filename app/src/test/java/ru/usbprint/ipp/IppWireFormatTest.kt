package ru.usbprint.ipp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException

class IppWireFormatTest {
    @Test fun roundTripsRepeatedValuesAndZeroNameContinuation() {
        val bytes = IppEncoder.encode(IppRequest(
            operation = IppOperation.GET_PRINTER_ATTRIBUTES,
            requestId = 0x01020304,
            groups = listOf(IppAttributeGroup(IppGroupTag.PRINTER_ATTRIBUTES, listOf(
                IppAttribute("media-supported", listOf(IppValue.Keyword("iso_a4_210x297mm"), IppValue.Keyword("na_letter_8.5x11in")))
            )))
        ))
        val decoded = IppDecoder().decodeResponse(bytes)
        assertEquals(0x01020304, decoded.requestId)
        assertEquals(listOf(IppValue.Keyword("iso_a4_210x297mm"), IppValue.Keyword("na_letter_8.5x11in")), decoded.attributes("media-supported"))
        assertTrue(bytes.indices.any { index -> index + 2 < bytes.size && bytes[index] == IppValueTags.KEYWORD.toByte() && bytes[index + 1] == 0.toByte() && bytes[index + 2] == 0.toByte() })
    }

    @Test fun roundTripsNestedMediaCollection() {
        val collection = IppValue.CollectionValue(mapOf(
            "media-size" to listOf(IppValue.CollectionValue(mapOf(
                "x-dimension" to listOf(IppValue.IntegerRange(10_000, 21_000)),
                "y-dimension" to listOf(IppValue.IntegerRange(15_000, 42_000))
            ))),
            "media-source" to listOf(IppValue.Keyword("tray-2"))
        ))
        val bytes = IppEncoder.encode(IppRequest(
            operation = IppOperation.GET_PRINTER_ATTRIBUTES, requestId = 7,
            groups = listOf(IppAttributeGroup(IppGroupTag.PRINTER_ATTRIBUTES, listOf(IppAttribute("media-col-database", collection))))
        ))
        assertEquals(collection, IppDecoder().decodeResponse(bytes).first("media-col-database"))
    }

    @Test fun preservesUnknownValueTagAsRawBytes() {
        val unknown = IppValue.UnknownValue(0x7f, byteArrayOf(1, 2, 3, 4))
        val bytes = IppEncoder.encode(IppRequest(
            operation = IppOperation.GET_PRINTER_ATTRIBUTES, requestId = 8,
            groups = listOf(IppAttributeGroup(IppGroupTag.PRINTER_ATTRIBUTES, listOf(IppAttribute("vendor-value", unknown))))
        ))
        val decoded = IppDecoder().decodeResponse(bytes).first("vendor-value") as IppValue.UnknownValue
        assertEquals(0x7f, decoded.tag)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded.raw)
    }

    @Test fun rejectsMalformedAndTruncatedResponses() {
        val malformed = listOf(
            byteArrayOf(),
            byteArrayOf(1, 1, 0, 0, 0, 0, 0, 1, 4),
            byteArrayOf(1, 1, 0, 0, 0, 0, 0, 1, 4, 0x44, 0, 10, 65),
            byteArrayOf(1, 1, 0, 0, 0, 0, 0, 1, 4, 0x44, 0, 0, 0, 0, 3),
            byteArrayOf(1, 1, 0, 0, 0, 0, 0, 1, 4, 0x22, 0, 1, 97, 0, 2, 0, 1, 3)
        )
        malformed.forEach { bytes ->
            val error = runCatching { IppDecoder().decodeResponse(bytes) }.exceptionOrNull() as? PrintException
            assertEquals(AppError.IPP_MALFORMED_RESPONSE, error?.error)
        }
    }

    @Test fun enforcesAttributeCountLimit() {
        val bytes = IppEncoder.encode(IppRequest(
            operation = IppOperation.GET_PRINTER_ATTRIBUTES, requestId = 9,
            groups = listOf(IppAttributeGroup(IppGroupTag.PRINTER_ATTRIBUTES, listOf(
                IppAttribute("one", IppValue.IntegerValue(1)), IppAttribute("two", IppValue.IntegerValue(2))
            )))
        ))
        val error = runCatching { IppDecoder(IppDecoder.Limits(maxAttributes = 1)).decodeResponse(bytes) }.exceptionOrNull() as PrintException
        assertEquals(AppError.IPP_MALFORMED_RESPONSE, error.error)
    }
}
