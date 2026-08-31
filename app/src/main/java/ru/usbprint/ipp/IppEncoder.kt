package ru.usbprint.ipp

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** RFC 8010 big-endian IPP operation encoder. Document data is appended by the transport. */
object IppEncoder {
    const val MAX_ATTRIBUTE_NAME_BYTES = 255
    const val MAX_VALUE_BYTES = 65_535

    fun encode(request: IppRequest): ByteArray = ByteArrayOutputStream().apply {
        write(request.version.major); write(request.version.minor)
        writeU16(request.operation.code); writeI32(request.requestId)
        request.groups.forEach { group ->
            write(group.tag.code)
            group.attributes.forEach { writeAttribute(it) }
        }
        write(END_OF_ATTRIBUTES)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeAttribute(attribute: IppAttribute) {
        val name = attribute.name.toByteArray(StandardCharsets.US_ASCII)
        require(name.size in 1..MAX_ATTRIBUTE_NAME_BYTES && name.all { (it.toInt() and 0xff) in 0x21..0x7e })
        attribute.values.forEachIndexed { index, value ->
            write(value.tag)
            if (index == 0) { writeU16(name.size); write(name) } else writeU16(0)
            writeValue(value)
        }
    }

    private fun ByteArrayOutputStream.writeValue(value: IppValue) {
        when (value) {
            is IppValue.IntegerValue -> { writeU16(4); writeI32(value.value) }
            is IppValue.EnumValue -> { writeU16(4); writeI32(value.value) }
            is IppValue.BooleanValue -> { writeU16(1); write(if (value.value) 1 else 0) }
            is IppValue.Resolution -> { writeU16(9); writeI32(value.x); writeI32(value.y); write(value.units.code) }
            is IppValue.IntegerRange -> { writeU16(8); writeI32(value.lower); writeI32(value.upper) }
            is IppValue.TextValue -> writeString(value.value)
            is IppValue.NameValue -> writeString(value.value)
            is IppValue.Keyword -> writeString(value.value)
            is IppValue.UriValue -> writeString(value.value)
            is IppValue.UriScheme -> writeString(value.value)
            is IppValue.Charset -> writeString(value.value)
            is IppValue.NaturalLanguage -> writeString(value.value)
            is IppValue.MimeMediaType -> writeString(value.value)
            is IppValue.Octets -> { require(value.value.size <= MAX_VALUE_BYTES); writeU16(value.value.size); write(value.value) }
            is IppValue.UnknownValue -> { require(value.raw.size <= MAX_VALUE_BYTES); writeU16(value.raw.size); write(value.raw) }
            is IppValue.OutOfBand -> writeU16(0)
            is IppValue.CollectionValue -> {
                writeU16(0)
                value.members.forEach { (memberName, values) ->
                    values.forEach { memberValue ->
                        write(IppValueTags.MEMBER_ATTR_NAME); writeU16(0); writeString(memberName)
                        write(memberValue.tag); writeU16(0); writeValue(memberValue)
                    }
                }
                write(IppValueTags.END_COLLECTION); writeU16(0); writeU16(0)
            }
        }
    }

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_VALUE_BYTES)
        writeU16(bytes.size); write(bytes)
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) { require(value in 0..0xffff); write(value ushr 8); write(value) }
    private fun ByteArrayOutputStream.writeI32(value: Int) { write(value ushr 24); write(value ushr 16); write(value ushr 8); write(value) }
    private const val END_OF_ATTRIBUTES = 0x03
}
