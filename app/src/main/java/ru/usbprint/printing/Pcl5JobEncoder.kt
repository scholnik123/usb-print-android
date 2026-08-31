package ru.usbprint.printing

import ru.usbprint.domain.model.PaperSize

/** Small, testable PCL 5 command builder for the deliberately limited monochrome raster subset. */
object Pcl5JobEncoder {
    const val ENCODER_VERSION = 2
    private const val ESC = '\u001b'
    fun reset(): ByteArray = "${ESC}E".toByteArray()
    fun beginPage(layout: RasterPageLayout, dpi: Int, duplexMode: ru.usbprint.domain.model.DuplexMode): ByteArray =
        "${ESC}&l${paperCode(layout.paper)}A${ESC}&l${if (layout.orientation == ru.usbprint.domain.model.Orientation.LANDSCAPE) 1 else 0}O${ESC}&l${when (duplexMode) { ru.usbprint.domain.model.DuplexMode.LONG_EDGE -> 1; ru.usbprint.domain.model.DuplexMode.SHORT_EDGE -> 2; else -> 0 }}S${ESC}*t${dpi}R${ESC}*r1A".toByteArray()
    fun row(length: Int): ByteArray = "${ESC}*b${length}W".toByteArray()
    fun endPage(): ByteArray = "${ESC}*rB\u000c".toByteArray()

    fun paperCode(size: PaperSize): Int = when (size) {
        PaperSize.LETTER -> 2; PaperSize.LEGAL -> 3; PaperSize.A5 -> 25; PaperSize.A4, PaperSize.AUTO -> 26; PaperSize.A3 -> 27
        else -> error("PCL 5 paper size is not implemented: $size")
    }
}
