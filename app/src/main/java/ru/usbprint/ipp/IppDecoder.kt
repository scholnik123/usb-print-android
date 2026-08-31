package ru.usbprint.ipp

import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.PrintException
import java.nio.charset.StandardCharsets

/** Bounded RFC 8010 response decoder. Unknown value tags are retained as raw values. */
class IppDecoder(private val limits: Limits = Limits()) {
    data class Limits(
        val maxMessageBytes: Int = 2 * 1024 * 1024,
        val maxAttributes: Int = 4096,
        val maxNameBytes: Int = 255,
        val maxValueBytes: Int = 65_535,
        val maxCollectionDepth: Int = 8
    )

    fun decodeResponse(bytes: ByteArray): IppResponse = malformedOnFailure {
        require(bytes.size in 9..limits.maxMessageBytes) { "IPP response size is invalid" }
        val cursor = Cursor(bytes)
        val version = IppVersion(cursor.u8(), cursor.u8())
        val status = cursor.u16()
        val requestId = cursor.i32()
        val groups = mutableListOf<IppAttributeGroup>()
        var attributeCount = 0
        while (true) {
            val delimiter = cursor.u8()
            if (delimiter == END_OF_ATTRIBUTES) break
            val groupTag = IppGroupTag.fromCode(delimiter) ?: error("Unknown IPP delimiter tag 0x${delimiter.toString(16)}")
            val attributes = linkedMapOf<String, MutableList<IppValue>>()
            var currentName: String? = null
            while (cursor.remaining > 0 && cursor.peekU8() >= 0x10) {
                val valueTag = cursor.u8()
                val nameLength = cursor.u16()
                require(nameLength <= limits.maxNameBytes)
                val name = if (nameLength == 0) requireNotNull(currentName) { "Continuation without attribute name" }
                    else cursor.bytes(nameLength).toString(StandardCharsets.US_ASCII).also { currentName = it }
                val valueLength = cursor.u16()
                require(valueLength <= limits.maxValueBytes)
                val value = if (valueTag == IppValueTags.BEGIN_COLLECTION) {
                    require(valueLength == 0) { "beginCollection must have empty value" }
                    decodeCollection(cursor, 1) { attributeCount++; require(attributeCount <= limits.maxAttributes) }
                } else decodeValue(valueTag, cursor.bytes(valueLength))
                attributes.getOrPut(name) { mutableListOf() }.add(value)
                attributeCount++
                require(attributeCount <= limits.maxAttributes)
            }
            groups += IppAttributeGroup(groupTag, attributes.map { IppAttribute(it.key, it.value) })
        }
        IppResponse(version, status, requestId, groups, cursor.bytes(cursor.remaining))
    }

    private fun decodeCollection(cursor: Cursor, depth: Int, count: () -> Unit): IppValue.CollectionValue {
        require(depth <= limits.maxCollectionDepth) { "IPP collection nesting is too deep" }
        val members = linkedMapOf<String, MutableList<IppValue>>()
        var memberName: String? = null
        while (true) {
            require(cursor.remaining >= 5) { "Truncated collection" }
            val tag = cursor.u8()
            val nameLength = cursor.u16()
            require(nameLength == 0) { "Collection records must use zero name length" }
            val valueLength = cursor.u16()
            require(valueLength <= limits.maxValueBytes)
            when (tag) {
                IppValueTags.END_COLLECTION -> {
                    require(valueLength == 0)
                    return IppValue.CollectionValue(members)
                }
                IppValueTags.MEMBER_ATTR_NAME -> {
                    memberName = cursor.bytes(valueLength).toString(StandardCharsets.UTF_8)
                    require(memberName!!.isNotBlank() && memberName!!.toByteArray().size <= limits.maxNameBytes)
                }
                IppValueTags.BEGIN_COLLECTION -> {
                    require(valueLength == 0)
                    val name = requireNotNull(memberName) { "Collection value has no memberAttrName" }
                    members.getOrPut(name) { mutableListOf() }.add(decodeCollection(cursor, depth + 1, count))
                    count()
                }
                else -> {
                    val name = requireNotNull(memberName) { "Collection value has no memberAttrName" }
                    members.getOrPut(name) { mutableListOf() }.add(decodeValue(tag, cursor.bytes(valueLength)))
                    count()
                }
            }
        }
    }

    private fun decodeValue(tag: Int, raw: ByteArray): IppValue = when (tag) {
        IppValueTags.INTEGER -> { require(raw.size == 4); IppValue.IntegerValue(raw.i32()) }
        IppValueTags.ENUM -> { require(raw.size == 4); IppValue.EnumValue(raw.i32()) }
        IppValueTags.BOOLEAN -> { require(raw.size == 1 && raw[0].toInt() in 0..1); IppValue.BooleanValue(raw[0].toInt() == 1) }
        IppValueTags.RESOLUTION -> { require(raw.size == 9); val units = IppValue.Resolution.Units.fromCode(raw[8].toInt() and 0xff) ?: return IppValue.UnknownValue(tag, raw); IppValue.Resolution(raw.i32(0), raw.i32(4), units) }
        IppValueTags.RANGE_OF_INTEGER -> { require(raw.size == 8); IppValue.IntegerRange(raw.i32(0), raw.i32(4)) }
        IppValueTags.TEXT -> IppValue.TextValue(raw.utf8())
        IppValueTags.NAME -> IppValue.NameValue(raw.utf8())
        IppValueTags.KEYWORD -> IppValue.Keyword(raw.utf8())
        IppValueTags.URI -> IppValue.UriValue(raw.utf8())
        IppValueTags.URI_SCHEME -> IppValue.UriScheme(raw.utf8())
        IppValueTags.CHARSET -> IppValue.Charset(raw.utf8())
        IppValueTags.NATURAL_LANGUAGE -> IppValue.NaturalLanguage(raw.utf8())
        IppValueTags.MIME_MEDIA_TYPE -> IppValue.MimeMediaType(raw.utf8())
        IppValueTags.UNSUPPORTED, IppValueTags.UNKNOWN, IppValueTags.NO_VALUE -> { require(raw.isEmpty()); IppValue.OutOfBand(tag) }
        IppValueTags.OCTET_STRING, IppValueTags.DATE_TIME, IppValueTags.TEXT_WITH_LANGUAGE, IppValueTags.NAME_WITH_LANGUAGE -> IppValue.Octets(raw, tag)
        else -> IppValue.UnknownValue(tag, raw)
    }

    private inline fun <T> malformedOnFailure(block: () -> T): T = try { block() }
    catch (error: PrintException) { throw error }
    catch (error: Throwable) { throw PrintException(AppError.IPP_MALFORMED_RESPONSE, error) }

    private class Cursor(private val bytes: ByteArray) {
        var offset = 0; val remaining get() = bytes.size - offset
        fun peekU8(): Int { require(remaining >= 1); return bytes[offset].toInt() and 0xff }
        fun u8(): Int = bytes(1)[0].toInt() and 0xff
        fun u16(): Int { val value = bytes(2); return ((value[0].toInt() and 0xff) shl 8) or (value[1].toInt() and 0xff) }
        fun i32(): Int {
            val value = bytes(4)
            return ((value[0].toInt() and 0xff) shl 24) or ((value[1].toInt() and 0xff) shl 16) or
                ((value[2].toInt() and 0xff) shl 8) or (value[3].toInt() and 0xff)
        }
        fun bytes(count: Int): ByteArray { require(count >= 0 && count <= remaining) { "IPP field exceeds response" }; return bytes.copyOfRange(offset, offset + count).also { offset += count } }
    }

    private fun ByteArray.i32(offset: Int = 0): Int = ((this[offset].toInt() and 0xff) shl 24) or ((this[offset + 1].toInt() and 0xff) shl 16) or ((this[offset + 2].toInt() and 0xff) shl 8) or (this[offset + 3].toInt() and 0xff)
    private fun ByteArray.utf8(): String = toString(StandardCharsets.UTF_8)
    private companion object { const val END_OF_ATTRIBUTES = 0x03 }
}
