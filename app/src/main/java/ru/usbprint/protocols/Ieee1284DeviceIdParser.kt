package ru.usbprint.protocols

import ru.usbprint.domain.model.PrinterLanguage

data class ParsedDeviceId(
    val fields: Map<String, String>,
    val languages: Set<PrinterLanguage>,
    val raw: String
) {
    fun field(vararg names: String): String? = names.firstNotNullOfOrNull { fields[it] }
}

/** IEEE-1284 key/value parser. Device firmware varies in spacing, aliases and terminators. */
object Ieee1284DeviceIdParser {
    fun parseUsbResponse(response: ByteArray): ParsedDeviceId {
        if (response.size < 2) return parse("")
        val length = ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff)
        val end = if (length in 3..response.size) length else response.size
        return parse(String(response.copyOfRange(2, end), Charsets.ISO_8859_1))
    }

    fun parse(value: String): ParsedDeviceId {
        val raw = value.trim('\u0000', ' ', '\r', '\n')
        val fields = linkedMapOf<String, String>()
        raw.split(';').forEach { token ->
            val delimiter = token.indexOf(':').takeIf { it >= 0 } ?: token.indexOf('=').takeIf { it >= 0 } ?: return@forEach
            val key = token.substring(0, delimiter).trim().uppercase().replace(Regex("\\s+"), " ")
            val item = token.substring(delimiter + 1).trim()
            if (key.isNotBlank() && item.isNotBlank()) fields[key] = item
        }
        val commandText = listOfNotNull(fields["CMD"], fields["COMMAND SET"], fields["COMMANDSET"]).joinToString(",")
        return ParsedDeviceId(fields, CommandSetParser.parse(commandText), raw)
    }
}

object CommandSetParser {
    fun parse(value: String): Set<PrinterLanguage> {
        val normalized = value.uppercase().replace('-', ' ').replace('_', ' ')
        val languages = linkedSetOf<PrinterLanguage>()
        if (Regex("\\bPDF\\b").containsMatchIn(normalized)) languages += PrinterLanguage.PDF
        if (Regex("POSTSCRIPT|\\bPS\\b").containsMatchIn(normalized)) languages += PrinterLanguage.POSTSCRIPT
        if (Regex("PCLM").containsMatchIn(normalized)) languages += PrinterLanguage.PCLM
        if (Regex("PCL\\s*(XL|6)").containsMatchIn(normalized)) languages += PrinterLanguage.PCL_XL
        if (Regex("\\bPCL(\\s*[345])?|PCL3GUI").containsMatchIn(normalized)) languages += PrinterLanguage.PCL
        if (Regex("PWG\\s*RASTER|\\bPWG\\b").containsMatchIn(normalized)) languages += PrinterLanguage.PWG_RASTER
        if (Regex("\\bURF\\b|APPLE RASTER").containsMatchIn(normalized)) languages += PrinterLanguage.URF
        if (Regex("ESC\\s*/?\\s*POS|ESCPOS").containsMatchIn(normalized)) languages += PrinterLanguage.ESC_POS
        return languages
    }
}
