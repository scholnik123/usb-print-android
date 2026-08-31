package ru.usbprint.ipp

data class IppVersion(val major: Int = 1, val minor: Int = 1) {
    init { require(major in 0..255 && minor in 0..255) }
}

enum class IppOperation(val code: Int) {
    PRINT_JOB(0x0002), VALIDATE_JOB(0x0004), CREATE_JOB(0x0005), SEND_DOCUMENT(0x0006),
    CANCEL_JOB(0x0008), GET_JOB_ATTRIBUTES(0x0009), GET_PRINTER_ATTRIBUTES(0x000B)
}

enum class IppGroupTag(val code: Int) {
    OPERATION_ATTRIBUTES(0x01), JOB_ATTRIBUTES(0x02), PRINTER_ATTRIBUTES(0x04),
    UNSUPPORTED_ATTRIBUTES(0x05), SUBSCRIPTION_ATTRIBUTES(0x06), EVENT_NOTIFICATION_ATTRIBUTES(0x07),
    RESOURCE_ATTRIBUTES(0x08), DOCUMENT_ATTRIBUTES(0x09), SYSTEM_ATTRIBUTES(0x0A);

    companion object { fun fromCode(code: Int) = entries.firstOrNull { it.code == code } }
}

object IppValueTags {
    const val UNSUPPORTED = 0x10; const val UNKNOWN = 0x12; const val NO_VALUE = 0x13
    const val INTEGER = 0x21; const val BOOLEAN = 0x22; const val ENUM = 0x23
    const val OCTET_STRING = 0x30; const val DATE_TIME = 0x31; const val RESOLUTION = 0x32
    const val RANGE_OF_INTEGER = 0x33; const val BEGIN_COLLECTION = 0x34
    const val TEXT_WITH_LANGUAGE = 0x35; const val NAME_WITH_LANGUAGE = 0x36; const val END_COLLECTION = 0x37
    const val TEXT = 0x41; const val NAME = 0x42; const val KEYWORD = 0x44; const val URI = 0x45
    const val URI_SCHEME = 0x46; const val CHARSET = 0x47; const val NATURAL_LANGUAGE = 0x48
    const val MIME_MEDIA_TYPE = 0x49; const val MEMBER_ATTR_NAME = 0x4A
}

sealed interface IppValue { val tag: Int
    data class IntegerValue(val value: Int) : IppValue { override val tag = IppValueTags.INTEGER }
    data class BooleanValue(val value: Boolean) : IppValue { override val tag = IppValueTags.BOOLEAN }
    data class EnumValue(val value: Int) : IppValue { override val tag = IppValueTags.ENUM }
    data class Octets(val value: ByteArray, override val tag: Int = IppValueTags.OCTET_STRING) : IppValue {
        override fun equals(other: Any?) = other is Octets && tag == other.tag && value.contentEquals(other.value)
        override fun hashCode() = 31 * tag + value.contentHashCode()
    }
    data class Resolution(val x: Int, val y: Int, val units: Units) : IppValue {
        override val tag = IppValueTags.RESOLUTION
        enum class Units(val code: Int) { DPI(3), DPCM(4); companion object { fun fromCode(code: Int) = entries.firstOrNull { it.code == code } } }
    }
    data class IntegerRange(val lower: Int, val upper: Int) : IppValue { override val tag = IppValueTags.RANGE_OF_INTEGER }
    data class TextValue(val value: String) : IppValue { override val tag = IppValueTags.TEXT }
    data class NameValue(val value: String) : IppValue { override val tag = IppValueTags.NAME }
    data class Keyword(val value: String) : IppValue { override val tag = IppValueTags.KEYWORD }
    data class UriValue(val value: String) : IppValue { override val tag = IppValueTags.URI }
    data class UriScheme(val value: String) : IppValue { override val tag = IppValueTags.URI_SCHEME }
    data class Charset(val value: String) : IppValue { override val tag = IppValueTags.CHARSET }
    data class NaturalLanguage(val value: String) : IppValue { override val tag = IppValueTags.NATURAL_LANGUAGE }
    data class MimeMediaType(val value: String) : IppValue { override val tag = IppValueTags.MIME_MEDIA_TYPE }
    data class CollectionValue(val members: Map<String, List<IppValue>>) : IppValue { override val tag = IppValueTags.BEGIN_COLLECTION }
    data class OutOfBand(override val tag: Int) : IppValue
    data class UnknownValue(override val tag: Int, val raw: ByteArray) : IppValue {
        override fun equals(other: Any?) = other is UnknownValue && tag == other.tag && raw.contentEquals(other.raw)
        override fun hashCode() = 31 * tag + raw.contentHashCode()
    }
}

data class IppAttribute(val name: String, val values: List<IppValue>) {
    init { require(name.isNotBlank() && values.isNotEmpty()) }
    constructor(name: String, value: IppValue) : this(name, listOf(value))
}

data class IppAttributeGroup(val tag: IppGroupTag, val attributes: List<IppAttribute>)

data class IppRequest(
    val version: IppVersion = IppVersion(),
    val operation: IppOperation,
    val requestId: Int,
    val groups: List<IppAttributeGroup>
)

data class IppResponse(
    val version: IppVersion,
    val statusCode: Int,
    val requestId: Int,
    val groups: List<IppAttributeGroup>,
    val trailingData: ByteArray = ByteArray(0)
) {
    val isSuccessful: Boolean get() = statusCode in 0x0000..0x00FF
    fun attributes(name: String): List<IppValue> = groups.flatMap { group -> group.attributes.filter { it.name == name }.flatMap(IppAttribute::values) }
    fun first(name: String): IppValue? = attributes(name).firstOrNull()
}

data class IppJobReference(val jobId: Int?, val jobUri: String?)

enum class IppJobState(val code: Int, val label: String) {
    PENDING(3, "Ожидает"), PENDING_HELD(4, "Остановлено"), PROCESSING(5, "Обрабатывается"),
    PROCESSING_STOPPED(6, "Печать остановлена"), CANCELED(7, "Отменено"), ABORTED(8, "Прервано"), COMPLETED(9, "Завершено");
    companion object { fun fromCode(code: Int) = entries.firstOrNull { it.code == code } }
}

data class IppJobStatus(
    val state: IppJobState?,
    val reasons: Set<String>,
    val impressionsCompleted: Int?,
    val mediaSheetsCompleted: Int?
)

object IppJobStatusMapper {
    fun map(response: IppResponse) = IppJobStatus(
        state = (response.first("job-state") as? IppValue.EnumValue)?.value?.let(IppJobState::fromCode),
        reasons = response.attributes("job-state-reasons").mapNotNull { (it as? IppValue.Keyword)?.value }.toSet(),
        impressionsCompleted = (response.first("job-impressions-completed") as? IppValue.IntegerValue)?.value,
        mediaSheetsCompleted = (response.first("job-media-sheets-completed") as? IppValue.IntegerValue)?.value
    )
}
