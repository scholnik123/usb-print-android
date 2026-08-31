package ru.usbprint.ipp

import ru.usbprint.domain.model.CapabilityConfidence
import ru.usbprint.domain.model.CapabilitySource
import ru.usbprint.domain.model.CapabilityValue
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.CustomPaperRangeMicrons
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.IppPrinterInfo
import ru.usbprint.domain.model.MediaSource
import ru.usbprint.domain.model.MediaType
import ru.usbprint.domain.model.Microns
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.OutputBin
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterKeywordOption
import ru.usbprint.domain.model.PrinterLanguage
import ru.usbprint.domain.model.PrinterResolution
import kotlin.math.roundToInt

/** Maps only attributes actually returned by Get-Printer-Attributes. */
object IppPrinterCapabilitiesMapper {
    private val source get() = CapabilitySource.IPP
    private val confidence get() = CapabilityConfidence.CONFIRMED

    fun map(base: PrinterCapabilities, response: IppResponse): PrinterCapabilities {
        val formats = response.strings("document-format-supported").map(String::lowercase).toSet()
        val mediaKeywords = (response.strings("media-supported") + response.strings("media-ready") + response.strings("media-default")).toSet()
        val resolutions = response.attributes("printer-resolution-supported").mapNotNull(::resolution).toSet()
        val colorKeywords = response.strings("print-color-mode-supported").map(String::lowercase).toSet()
        val explicitColor = (response.first("color-supported") as? IppValue.BooleanValue)?.value
        val colors = buildSet {
            if ("color" in colorKeywords || explicitColor == true) add(ColorMode.COLOR)
            if (colorKeywords.any { it in setOf("monochrome", "process-monochrome", "bi-level") } || explicitColor == false) {
                add(ColorMode.GRAYSCALE); add(ColorMode.BLACK_ONLY)
            }
        }
        val sides = response.strings("sides-supported").mapNotNull {
            when (it.lowercase()) { "one-sided" -> DuplexMode.OFF; "two-sided-long-edge" -> DuplexMode.LONG_EDGE; "two-sided-short-edge" -> DuplexMode.SHORT_EDGE; else -> null }
        }.toSet()
        val orientations = response.attributes("orientation-requested-supported").mapNotNull {
            val code = when (it) { is IppValue.EnumValue -> it.value; is IppValue.IntegerValue -> it.value; else -> null }
            when (code) { 3 -> Orientation.PORTRAIT; 4 -> Orientation.LANDSCAPE; else -> null }
        }.toSet()
        val copies = response.attributes("copies-supported").firstNotNullOfOrNull {
            when (it) { is IppValue.IntegerRange -> it.lower..it.upper; is IppValue.IntegerValue -> 1..it.value; else -> null }
        }
        val mediaCollections = response.attributes("media-col-database").filterIsInstance<IppValue.CollectionValue>() +
            response.attributes("media-col-ready").filterIsInstance<IppValue.CollectionValue>() +
            response.attributes("media-col-default").filterIsInstance<IppValue.CollectionValue>()
        val papers = (mediaKeywords.mapNotNull(::paperFromKeyword) + mediaCollections.mapNotNull(::paperFromCollection)).toSet()
        val mediaSources = options(response.strings("media-source-supported") + mediaCollections.flatMap { it.memberStrings("media-source") }, ::sourceDisplay)
        val mediaTypes = options(response.strings("media-type-supported") + mediaCollections.flatMap { it.memberStrings("media-type") }, ::typeDisplay)
        val outputBins = options(response.strings("output-bin-supported"), ::outputDisplay)
        val hardwareMargins = response.attributes("media-col-default").filterIsInstance<IppValue.CollectionValue>().firstNotNullOfOrNull(::margins)
        val customRange = mediaCollections.firstNotNullOfOrNull(::customRange)
        val operations = response.ints("operations-supported").toSet()
        val versions = response.strings("ipp-versions-supported").toSet()
        val jobAttributes = response.strings("job-creation-attributes-supported").toSet()
        val languages = base.supportedLanguages + formats.mapNotNull(::languageFromMime)
        val makeAndModel = response.strings("printer-make-and-model").firstOrNull()

        return base.copy(
            model = makeAndModel ?: base.model,
            supportedLanguages = languages,
            supportsColor = explicitColor ?: base.supportsColor,
            supportsDuplex = sides.any { it != DuplexMode.OFF }.takeIf { sides.isNotEmpty() } ?: base.supportsDuplex,
            supportedPaperSizes = papers.ifEmpty { base.supportedPaperSizes },
            supportedResolutionsDpi = resolutions.filter { it.horizontalDpi == it.verticalDpi }.map { it.horizontalDpi }.toSet().ifEmpty { base.supportedResolutionsDpi },
            reportedPaperSizes = papers.takeIf { it.isNotEmpty() }?.confirmed(),
            reportedResolutions = resolutions.takeIf { it.isNotEmpty() }?.confirmed(),
            reportedColorModes = colors.takeIf { it.isNotEmpty() }?.confirmed(),
            reportedDuplexModes = sides.takeIf { it.isNotEmpty() }?.confirmed(),
            reportedOrientations = orientations.takeIf { it.isNotEmpty() }?.confirmed(),
            reportedCopiesRange = copies?.confirmed(),
            reportedMediaSources = mediaSources.mapNotNull { legacyMediaSource(it.rawKeyword) }.toSet().takeIf { it.isNotEmpty() }?.confirmed(),
            reportedMediaTypes = mediaTypes.mapNotNull { legacyMediaType(it.rawKeyword) }.toSet().takeIf { it.isNotEmpty() }?.confirmed(),
            reportedOutputBins = outputBins.mapNotNull { legacyOutputBin(it.rawKeyword) }.toSet().takeIf { it.isNotEmpty() }?.confirmed(),
            reportedMediaSourceOptions = mediaSources.takeIf { it.isNotEmpty() }?.confirmed(),
            reportedMediaTypeOptions = mediaTypes.takeIf { it.isNotEmpty() }?.confirmed(),
            reportedOutputBinOptions = outputBins.takeIf { it.isNotEmpty() }?.confirmed(),
            reportedHardwareMargins = hardwareMargins?.confirmed(),
            reportedCustomPaperRangeMicrons = customRange?.confirmed(),
            ipp = base.ipp.copy(
                versionStrings = versions, operationsSupported = operations, documentFormatsSupported = formats,
                jobCreationAttributesSupported = jobAttributes,
                printerState = response.int("printer-state"), printerStateReasons = response.strings("printer-state-reasons").toSet(),
                acceptingJobs = (response.first("printer-is-accepting-jobs") as? IppValue.BooleanValue)?.value,
                pageRangesSupported = (response.first("page-ranges-supported") as? IppValue.BooleanValue)?.value == true,
                rawStatusCode = response.statusCode, requestId = response.requestId
            )
        )
    }

    private fun resolution(value: IppValue): PrinterResolution? {
        val raw = value as? IppValue.Resolution ?: return null
        val factor = if (raw.units == IppValue.Resolution.Units.DPI) 1.0 else 2.54
        return runCatching { PrinterResolution((raw.x * factor).roundToInt(), (raw.y * factor).roundToInt()) }.getOrNull()
    }

    private fun margins(collection: IppValue.CollectionValue): HardwareMarginsMm? {
        fun margin(name: String) = collection.memberInt(name)?.div(100f)
        val left = margin("media-left-margin") ?: return null; val top = margin("media-top-margin") ?: return null
        val right = margin("media-right-margin") ?: return null; val bottom = margin("media-bottom-margin") ?: return null
        return runCatching { HardwareMarginsMm(left, top, right, bottom) }.getOrNull()
    }

    private fun customRange(collection: IppValue.CollectionValue): CustomPaperRangeMicrons? {
        val mediaSize = collection.members["media-size"]?.filterIsInstance<IppValue.CollectionValue>()?.firstOrNull() ?: return null
        val x = mediaSize.members["x-dimension"]?.filterIsInstance<IppValue.IntegerRange>()?.firstOrNull() ?: return null
        val y = mediaSize.members["y-dimension"]?.filterIsInstance<IppValue.IntegerRange>()?.firstOrNull() ?: return null
        return runCatching { CustomPaperRangeMicrons(Microns.fromHundredthsMm(x.lower), Microns.fromHundredthsMm(x.upper), Microns.fromHundredthsMm(y.lower), Microns.fromHundredthsMm(y.upper)) }.getOrNull()
    }

    private fun IppValue.CollectionValue.memberInt(name: String): Int? = members[name]?.firstNotNullOfOrNull {
        when (it) { is IppValue.IntegerValue -> it.value; is IppValue.EnumValue -> it.value; else -> null }
    }
    private fun IppValue.CollectionValue.memberStrings(name: String): List<String> = members[name].orEmpty().mapNotNull {
        when (it) { is IppValue.Keyword -> it.value; is IppValue.NameValue -> it.value; else -> null }
    }
    private fun paperFromCollection(collection: IppValue.CollectionValue): PaperSize? {
        collection.memberStrings("media-size-name").firstNotNullOfOrNull(::paperFromKeyword)?.let { return it }
        val size = collection.members["media-size"]?.filterIsInstance<IppValue.CollectionValue>()?.firstOrNull() ?: return null
        val x = size.memberInt("x-dimension") ?: return null
        val y = size.memberInt("y-dimension") ?: return null
        return PaperSize.entries.filterNot { it == PaperSize.AUTO }.firstOrNull { paper ->
            val width = (paper.widthMm * 100).roundToInt(); val height = (paper.heightMm * 100).roundToInt()
            (kotlin.math.abs(x - width) <= 5 && kotlin.math.abs(y - height) <= 5) ||
                (kotlin.math.abs(y - width) <= 5 && kotlin.math.abs(x - height) <= 5)
        }
    }
    private fun IppResponse.strings(name: String): List<String> = attributes(name).mapNotNull {
        when (it) { is IppValue.Keyword -> it.value; is IppValue.MimeMediaType -> it.value; is IppValue.NameValue -> it.value; is IppValue.TextValue -> it.value; else -> null }
    }
    private fun IppResponse.ints(name: String): List<Int> = attributes(name).mapNotNull { when (it) { is IppValue.IntegerValue -> it.value; is IppValue.EnumValue -> it.value; else -> null } }
    private fun IppResponse.int(name: String) = ints(name).firstOrNull()
    private fun <T> T.confirmed() = CapabilityValue(this, source, confidence)
    private fun options(values: List<String>, display: (String) -> String) = values.distinct().mapTo(linkedSetOf()) { PrinterKeywordOption(it, display(it)) }

    private fun paperFromKeyword(value: String): PaperSize? = when (value.lowercase()) {
        "iso_a0_841x1189mm" -> PaperSize.A0; "iso_a1_594x841mm" -> PaperSize.A1; "iso_a2_420x594mm" -> PaperSize.A2
        "iso_a3_297x420mm" -> PaperSize.A3; "iso_a4_210x297mm" -> PaperSize.A4; "iso_a5_148x210mm" -> PaperSize.A5
        "iso_a6_105x148mm" -> PaperSize.A6; "na_letter_8.5x11in" -> PaperSize.LETTER; "na_legal_8.5x14in" -> PaperSize.LEGAL
        "na_executive_7.25x10.5in" -> PaperSize.EXECUTIVE; "na_invoice_5.5x8.5in" -> PaperSize.STATEMENT
        "na_ledger_11x17in" -> PaperSize.TABLOID; "iso_dl_110x220mm" -> PaperSize.ENVELOPE_DL; "iso_c5_162x229mm" -> PaperSize.ENVELOPE_C5
        else -> null
    }
    private fun languageFromMime(value: String): PrinterLanguage? = when (value.lowercase()) {
        "application/pdf" -> PrinterLanguage.PDF; "image/pwg-raster" -> PrinterLanguage.PWG_RASTER
        "application/postscript" -> PrinterLanguage.POSTSCRIPT; "application/pclm" -> PrinterLanguage.PCLM
        "application/vnd.hp-pcl" -> PrinterLanguage.PCL; "image/urf" -> PrinterLanguage.URF; else -> null
    }
    private fun sourceDisplay(raw: String): String {
        val normalized = raw.lowercase()
        val trayNumber = Regex("(?:tray|main)-?(\\d+)").matchEntire(normalized)?.groupValues?.get(1)
        return when (normalized) { "auto", "automatic" -> "Автовыбор"; "main", "tray-1" -> "Основной лоток"; "manual" -> "Ручная подача"; "by-pass-tray", "bypass" -> "Обходной лоток"; "rear" -> "Задний лоток"; "photo" -> "Фотолоток"; else -> trayNumber?.let { "Лоток $it" } ?: raw }
    }
    private fun typeDisplay(raw: String) = when (raw.lowercase()) { "stationery", "plain" -> "Обычная бумага"; "photographic", "photographic-glossy" -> "Фотобумага"; "envelope" -> "Конверт"; "labels" -> "Этикетки"; "cardstock" -> "Картон"; "transparency" -> "Плёнка"; "recycled" -> "Переработанная бумага"; else -> raw }
    private fun outputDisplay(raw: String) = when (raw.lowercase()) { "auto" -> "Автовыбор"; "face-down" -> "Лицом вниз"; "face-up" -> "Лицом вверх"; else -> raw }
    private fun legacyMediaSource(raw: String) = when (raw.lowercase()) { "auto", "automatic" -> MediaSource.AUTO; "main", "tray-1" -> MediaSource.MAIN; "manual", "by-pass-tray", "bypass" -> MediaSource.MANUAL; "photo" -> MediaSource.PHOTO; else -> null }
    private fun legacyMediaType(raw: String) = when (raw.lowercase()) { "stationery", "plain" -> MediaType.PLAIN; "photographic", "photographic-glossy" -> MediaType.PHOTO; "envelope" -> MediaType.ENVELOPE; "labels" -> MediaType.LABEL; else -> null }
    private fun legacyOutputBin(raw: String) = when (raw.lowercase()) { "auto" -> OutputBin.AUTO; "face-down" -> OutputBin.STANDARD; "face-up" -> OutputBin.FACE_UP; else -> null }
}
