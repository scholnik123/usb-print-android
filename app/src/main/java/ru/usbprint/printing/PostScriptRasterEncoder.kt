package ru.usbprint.printing

/** Pure PostScript Level 2 framing used by PostScriptRasterBackend; image data remains streamed by the backend. */
object PostScriptRasterEncoder {
    const val ENCODER_VERSION = 2
    fun prolog(): ByteArray = "%!PS-Adobe-3.0\n%%Creator: USB Print 1.0\n%%LanguageLevel: 2\n%%Pages: (atend)\n%%EndComments\n".toByteArray()
    fun beginPage(layout: RasterPageLayout, pageNumber: Int, duplex: Boolean, tumble: Boolean, colorMode: RasterColorMode = RasterColorMode.RGB): ByteArray {
        val operator = if (colorMode == RasterColorMode.RGB) "false 3 colorimage" else "image"
        return "%%Page: $pageNumber $pageNumber\n<< /PageSize [${layout.widthPoints} ${layout.heightPoints}] /Duplex ${if (duplex) "true" else "false"} /Tumble ${if (tumble) "true" else "false"} >> setpagedevice\ngsave\n${layout.widthPx} ${layout.heightPx} 8 [${layout.widthPx} 0 0 -${layout.heightPx} 0 ${layout.heightPx}] { currentfile /ASCIIHexDecode filter } $operator\n".toByteArray()
    }
    fun endPage(): ByteArray = ">\ngrestore\nshowpage\n".toByteArray()
    fun trailer(pageCount: Int): ByteArray = "%%Trailer\n%%Pages: $pageCount\n%%EOF\n".toByteArray()
    fun asciiHex(bytes: ByteArray): ByteArray {
        val digits = "0123456789ABCDEF".toCharArray()
        return ByteArray(bytes.size * 2 + 1).also { output ->
            bytes.forEachIndexed { index, value ->
                val unsigned = value.toInt() and 0xff
                output[index * 2] = digits[unsigned ushr 4].code.toByte()
                output[index * 2 + 1] = digits[unsigned and 15].code.toByte()
            }
            output[output.lastIndex] = '\n'.code.toByte()
        }
    }
}
