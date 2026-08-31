package ru.usbprint.printing

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** CUPS/PWG Raster v2 page description (1796 bytes), encoded big-endian after the RaS2 sync word. */
data class PwgRasterHeader(
    val layout: RasterPageLayout,
    val colorMode: RasterColorMode,
    val duplex: Boolean,
    val tumble: Boolean
) {
    val bitsPerColor: Int get() = if (colorMode == RasterColorMode.MONOCHROME) 1 else 8
    val bitsPerPixel: Int get() = when (colorMode) { RasterColorMode.MONOCHROME -> 1; RasterColorMode.GRAYSCALE -> 8; RasterColorMode.RGB -> 24 }
    val numberOfColors: Int get() = if (colorMode == RasterColorMode.RGB) 3 else 1
    val bytesPerLine: Int get() = ceil(layout.widthPx * bitsPerPixel / 8.0).toInt()
    val colorSpace: Int get() = when (colorMode) {
        RasterColorMode.MONOCHROME -> CUPS_CSPACE_K
        RasterColorMode.GRAYSCALE -> CUPS_CSPACE_SW
        RasterColorMode.RGB -> CUPS_CSPACE_SRGB
    }

    fun toBytes(): ByteArray = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
        putCString(0, "")
        putCString(64, "")
        putCString(128, "stationery")
        putCString(192, "")
        // CUPS v1 portion: offsets are specified by the public CUPS raster format, not platform struct layout.
        putInt(256, 0); putInt(260, 0); putInt(264, 1); putInt(268, 0); putInt(272, if (duplex) 1 else 0)
        putInt(276, layout.dpi); putInt(280, layout.dpi)
        putInt(284, 0); putInt(288, 0); putInt(292, layout.widthPoints); putInt(296, layout.heightPoints)
        putInt(300, 0); putInt(304, 0); putInt(308, 0); putInt(312, 0); putInt(316, 0); putInt(320, 0)
        putInt(324, 0); putInt(328, 0); putInt(332, 0); putInt(336, 0); putInt(340, 1); putInt(344, 0)
        putInt(348, 0); putInt(352, layout.widthPoints); putInt(356, layout.heightPoints); putInt(360, 0); putInt(364, 0); putInt(368, if (tumble) 1 else 0)
        putInt(372, layout.widthPx); putInt(376, layout.heightPx); putInt(380, 0); putInt(384, bitsPerColor); putInt(388, bitsPerPixel)
        putInt(392, bytesPerLine); putInt(396, CUPS_ORDER_CHUNKED); putInt(400, colorSpace); putInt(404, 0)
        putInt(408, 0); putInt(412, 0); putInt(416, 0)
        // CUPS v2 extension.
        putInt(420, numberOfColors); putFloat(424, 1f); putFloat(428, layout.widthPoints.toFloat()); putFloat(432, layout.heightPoints.toFloat())
        putFloat(436, 0f); putFloat(440, 0f); putFloat(444, layout.widthPoints.toFloat()); putFloat(448, layout.heightPoints.toFloat())
        putCString(1732, layout.paper.pwgKeyword)
    }.array()

    private fun ByteBuffer.putCString(offset: Int, value: String) {
        val data = value.toByteArray(Charsets.US_ASCII).copyOf(63)
        position(offset); put(data); put(0)
    }

    companion object {
        const val HEADER_SIZE = 1796
        const val CUPS_ORDER_CHUNKED = 0
        const val CUPS_CSPACE_K = 3
        const val CUPS_CSPACE_SW = 18
        const val CUPS_CSPACE_SRGB = 19
    }
}

private val ru.usbprint.domain.model.PaperSize.pwgKeyword: String
    get() = when (this) {
        ru.usbprint.domain.model.PaperSize.A4, ru.usbprint.domain.model.PaperSize.AUTO -> "iso_a4_210x297mm"
        ru.usbprint.domain.model.PaperSize.A0 -> "iso_a0_841x1189mm"
        ru.usbprint.domain.model.PaperSize.A1 -> "iso_a1_594x841mm"
        ru.usbprint.domain.model.PaperSize.A2 -> "iso_a2_420x594mm"
        ru.usbprint.domain.model.PaperSize.A5 -> "iso_a5_148x210mm"
        ru.usbprint.domain.model.PaperSize.A3 -> "iso_a3_297x420mm"
        ru.usbprint.domain.model.PaperSize.A6 -> "iso_a6_105x148mm"
        ru.usbprint.domain.model.PaperSize.LETTER -> "na_letter_8.5x11in"
        ru.usbprint.domain.model.PaperSize.LEGAL -> "na_legal_8.5x14in"
        ru.usbprint.domain.model.PaperSize.EXECUTIVE -> "na_executive_7.25x10.5in"
        ru.usbprint.domain.model.PaperSize.STATEMENT -> "na_invoice_5.5x8.5in"
        ru.usbprint.domain.model.PaperSize.TABLOID -> "na_ledger_11x17in"
        ru.usbprint.domain.model.PaperSize.LEDGER -> "na_ledger_11x17in"
        ru.usbprint.domain.model.PaperSize.ENVELOPE_DL -> "iso_dl_110x220mm"
        ru.usbprint.domain.model.PaperSize.ENVELOPE_C5 -> "iso_c5_162x229mm"
    }

object PwgRasterEncoder {
    const val ENCODER_VERSION = 2
    val syncWord: ByteArray = byteArrayOf(0x52, 0x61, 0x53, 0x32) // RaS2, big endian CUPS/PWG raster v2.

    /** Encodes one raw line. The first byte is the repeat count (zero = one unique line). */
    fun encodeLine(raw: ByteArray, bytesPerColorValue: Int): ByteArray {
        require(bytesPerColorValue in 1..30 && raw.size % bytesPerColorValue == 0)
        val groups = raw.size / bytesPerColorValue
        val output = ByteArrayOutputStream(raw.size + 16)
        output.write(0) // this implementation does not coalesce previous equal lines; it remains fully valid.
        var index = 0
        while (index < groups) {
            var repeat = 1
            while (index + repeat < groups && repeat < 128 && equalsGroup(raw, index, index + repeat, bytesPerColorValue)) repeat++
            if (repeat >= 2) {
                output.write(repeat - 1)
                output.write(raw, index * bytesPerColorValue, bytesPerColorValue)
                index += repeat
            } else {
                val literalStart = index
                index++
                while (index < groups && index - literalStart < 128) {
                    var futureRepeat = 1
                    while (index + futureRepeat < groups && futureRepeat < 2 && equalsGroup(raw, index, index + futureRepeat, bytesPerColorValue)) futureRepeat++
                    if (futureRepeat >= 2) break
                    index++
                }
                val literalCount = index - literalStart
                if (literalCount == 1) {
                    output.write(0)
                    output.write(raw, literalStart * bytesPerColorValue, bytesPerColorValue)
                } else {
                    output.write(257 - literalCount)
                    output.write(raw, literalStart * bytesPerColorValue, literalCount * bytesPerColorValue)
                }
            }
        }
        return output.toByteArray()
    }

    private fun equalsGroup(data: ByteArray, first: Int, second: Int, size: Int): Boolean {
        val a = first * size; val b = second * size
        for (offset in 0 until size) if (data[a + offset] != data[b + offset]) return false
        return true
    }
}
