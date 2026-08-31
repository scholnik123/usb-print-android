package ru.usbprint.domain.model

/**
 * Describes where a capability came from.  The value is deliberately kept
 * together with its provenance: a backend default is usable, but is not a
 * statement made by the physical printer.
 */
enum class CapabilitySource(val label: String, val priority: Int) {
    IPP("IPP Get-Printer-Attributes", 4),
    IEEE1284("IEEE-1284 Device ID", 3),
    USB_DESCRIPTOR("USB descriptor", 2),
    KNOWN_PROFILE("профиль устройства", 1),
    BACKEND_DEFAULT("безопасное значение backend", 0),
    USER_OVERRIDE("экспериментальное переопределение", 5)
}

enum class CapabilityConfidence(val label: String) {
    CONFIRMED("подтверждено"),
    DERIVED("выведено"),
    DEFAULT("значение по умолчанию"),
    EXPERIMENTAL("экспериментально")
}

data class CapabilityValue<T>(
    val value: T,
    val source: CapabilitySource,
    val confidence: CapabilityConfidence
) {
    val isPrinterConfirmed: Boolean get() = confidence == CapabilityConfidence.CONFIRMED && source != CapabilitySource.BACKEND_DEFAULT
    val disclosure: String get() = "${source.label}; ${confidence.label}"
}

data class PrinterResolution(val horizontalDpi: Int, val verticalDpi: Int = horizontalDpi) {
    init { require(horizontalDpi in 1..9600 && verticalDpi in 1..9600) }
    val displayName: String get() = if (horizontalDpi == verticalDpi) "$horizontalDpi DPI" else "${horizontalDpi}×${verticalDpi} DPI"
    companion object { val DPI_300 = PrinterResolution(300); val DPI_600 = PrinterResolution(600) }
}

/** IPP media dimensions are represented internally in micrometres. */
@JvmInline value class Microns(val value: Long) {
    init { require(value >= 0L) }
    fun toMillimetres(): Float = value / 1_000f
    companion object { fun fromHundredthsMm(value: Int) = Microns(Math.multiplyExact(value.toLong(), 10L)) }
}

data class CustomPaperRangeMicrons(
    val minWidth: Microns,
    val maxWidth: Microns,
    val minHeight: Microns,
    val maxHeight: Microns
) {
    init { require(minWidth.value <= maxWidth.value && minHeight.value <= maxHeight.value) }
}

/** Raw IPP keyword is retained because it is the value sent back in a Job request. */
data class PrinterKeywordOption(val rawKeyword: String, val localizedDisplayName: String)

data class IppPrinterInfo(
    val interfaceIds: Set<Int> = emptySet(),
    val versionStrings: Set<String> = emptySet(),
    val operationsSupported: Set<Int> = emptySet(),
    val documentFormatsSupported: Set<String> = emptySet(),
    val pwgRasterResolutionsSupported: Set<PrinterResolution> = emptySet(),
    val pwgRasterDocumentTypesSupported: Set<String> = emptySet(),
    val jobCreationAttributesSupported: Set<String> = emptySet(),
    val printerState: Int? = null,
    val printerStateReasons: Set<String> = emptySet(),
    val acceptingJobs: Boolean? = null,
    val pageRangesSupported: Boolean = false,
    val rawStatusCode: Int? = null,
    val requestId: Int? = null
) {
    val isDiscovered: Boolean get() = interfaceIds.isNotEmpty()
    fun supportsOperation(code: Int): Boolean = code in operationsSupported
    fun supportsFormat(mime: String): Boolean = documentFormatsSupported.any { it.equals(mime, ignoreCase = true) }
}

enum class MediaType(val label: String) { PLAIN("Обычная бумага"), PHOTO("Фотобумага"), ENVELOPE("Конверт"), LABEL("Этикетки") }
enum class MediaSource(val label: String) { AUTO("Автовыбор"), MAIN("Основной лоток"), MANUAL("Ручная подача"), PHOTO("Фотолоток") }
enum class OutputBin(val label: String) { AUTO("Автовыбор"), STANDARD("Стандартный"), FACE_UP("Лицом вверх") }

data class CustomPaperRangeMm(
    val minWidthMm: Float,
    val maxWidthMm: Float,
    val minHeightMm: Float,
    val maxHeightMm: Float
)

/** Printer's physical unprintable margins, measured from its media edges. */
data class HardwareMarginsMm(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(listOf(left, top, right, bottom).all { it >= 0f && it <= 60f }) }
    companion object { val ZERO = HardwareMarginsMm(0f, 0f, 0f, 0f) }
}

/**
 * Backend-side feature declaration. It is intentionally independent of a
 * connected device; EffectivePrintCapabilities performs the intersection.
 */
data class BackendCapabilityDescriptor(
    val backendId: BackendId,
    val copiesRange: IntRange? = null,
    val pageSelections: Set<PageSelectionKind> = emptySet(),
    val paperSizes: Set<PaperSize> = emptySet(),
    val supportsCustomPaper: Boolean = false,
    val orientations: Set<Orientation> = emptySet(),
    val colorModes: Set<ColorMode> = emptySet(),
    val duplexModes: Set<DuplexMode> = emptySet(),
    val resolutions: Set<PrinterResolution> = emptySet(),
    val supportsMargins: Boolean = false,
    val supportsPositioning: Boolean = false,
    val supportsScaling: Boolean = false,
    val supportsCollate: Boolean = false,
    val supportsPageOrder: Boolean = false,
    val supportsNUp: Boolean = false,
    val supportedMediaTypes: Set<MediaType> = emptySet(),
    val supportedMediaSources: Set<MediaSource> = emptySet(),
    val supportedOutputBins: Set<OutputBin> = emptySet()
)

enum class PageSelectionKind(val label: String) { ALL("Все"), RANGES("Диапазон"), ODD("Нечётные"), EVEN("Чётные") }

/**
 * The only model consumed by the settings UI and validator. Each selectable
 * value carries the source that allowed it. Empty means "not safely known".
 */
data class EffectivePrintCapabilities(
    val backendId: BackendId,
    val copiesRange: CapabilityValue<IntRange>?,
    val pageSelections: CapabilityValue<Set<PageSelectionKind>>?,
    val paperSizes: CapabilityValue<Set<PaperSize>>?,
    val customPaperRange: CapabilityValue<CustomPaperRangeMm>?,
    val orientations: CapabilityValue<Set<Orientation>>?,
    val colorModes: CapabilityValue<Set<ColorMode>>?,
    val duplexModes: CapabilityValue<Set<DuplexMode>>?,
    val resolutions: CapabilityValue<Set<PrinterResolution>>?,
    val hardwareMargins: CapabilityValue<HardwareMarginsMm>?,
    val supportsMargins: Boolean,
    val supportsPositioning: Boolean,
    val supportsScaling: Boolean,
    val supportsCollate: Boolean,
    val supportsPageOrder: Boolean,
    val supportsNUp: Boolean,
    val mediaTypes: CapabilityValue<Set<MediaType>>? = null,
    val mediaSources: CapabilityValue<Set<MediaSource>>? = null,
    val outputBins: CapabilityValue<Set<OutputBin>>? = null,
    val mediaTypeOptions: CapabilityValue<Set<PrinterKeywordOption>>? = null,
    val mediaSourceOptions: CapabilityValue<Set<PrinterKeywordOption>>? = null,
    val outputBinOptions: CapabilityValue<Set<PrinterKeywordOption>>? = null,
    val customPaperRangeMicrons: CapabilityValue<CustomPaperRangeMicrons>? = null,
    val limitations: List<String> = emptyList()
) {
    val isPrintable: Boolean get() = backendId != BackendId.NONE
    val capabilitySummary: String get() = buildList {
        paperSizes?.let { add("Бумага: ${it.disclosure}") }
        resolutions?.let { add("Разрешение: ${it.disclosure}") }
        colorModes?.let { add("Цвет: ${it.disclosure}") }
        duplexModes?.let { add("Дуплекс: ${it.disclosure}") }
    }.joinToString("\n")

    companion object {
        val NONE = EffectivePrintCapabilities(
            backendId = BackendId.NONE, copiesRange = null, pageSelections = null, paperSizes = null,
            customPaperRange = null, orientations = null, colorModes = null, duplexModes = null,
            resolutions = null, hardwareMargins = null, supportsMargins = false, supportsPositioning = false,
            supportsScaling = false, supportsCollate = false, supportsPageOrder = false, supportsNUp = false
        )
    }
}

/** Saved only after a deliberate action in the Advanced section. It is never auto-detected. */
data class ExperimentalPrinterOverride(
    val forcedBackend: BackendId? = null,
    val forcedResolution: PrinterResolution? = null,
    val forceMonochrome: Boolean = false,
    val forcedPaper: PaperSize? = null,
    val forcedMargins: HardwareMarginsMm? = null
) {
    val isEmpty: Boolean get() = forcedBackend == null && forcedResolution == null && !forceMonochrome && forcedPaper == null && forcedMargins == null
}
