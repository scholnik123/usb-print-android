package ru.usbprint.domain.logic

import ru.usbprint.domain.model.BackendCapabilityDescriptor
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.CapabilityConfidence
import ru.usbprint.domain.model.CapabilitySource
import ru.usbprint.domain.model.CapabilityValue
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DocumentKind
import ru.usbprint.domain.model.DocumentRef
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.EffectivePrintCapabilities
import ru.usbprint.domain.model.ExperimentalPrinterOverride
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PageSelectionKind
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrintSettings

/** Legacy view retained for callers that only need simple visibility flags. */
data class BackendCapabilities(
    val supportsCopies: Boolean,
    val supportsPageRange: Boolean,
    val supportsPaper: Boolean,
    val supportsOrientation: Boolean,
    val supportsColor: Boolean,
    val supportsDuplex: Boolean,
    val supportsScaling: Boolean,
    val resolutionsDpi: Set<Int> = emptySet(),
    val descriptor: BackendCapabilityDescriptor
)

data class BackendDecision(val selected: BackendId, val compatible: List<BackendId>, val reason: String? = null)

/**
 * Selection contains only encoders that are actually implemented. Language
 * detection is never treated as support for an unimplemented protocol.
 */
object BackendRegistry {
    fun select(
        capabilities: PrinterCapabilities,
        document: DocumentRef?,
        settings: PrintSettings = PrintSettings(),
        override: ExperimentalPrinterOverride? = null
    ): BackendDecision {
        if (document == null) return BackendDecision(BackendId.NONE, emptyList(), "Выберите документ")
        val hasLegacyUsbPath = !capabilities.ipp.isDiscovered || capabilities.interfaces.any {
            it.isPrinterClass && !it.isIppUsb && it.endpoints.any { endpoint -> endpoint.direction == "OUT" && endpoint.type == "bulk" }
        }
        val detected = buildList {
            if (document.kind == DocumentKind.PDF && document.sizeBytes != null && capabilities.ipp.isDiscovered && capabilities.ipp.acceptingJobs != false &&
                capabilities.ipp.supportsOperation(ru.usbprint.ipp.IppOperation.PRINT_JOB.code) && capabilities.ipp.supportsFormat("application/pdf") &&
                isIppDirectSafe(settings)) add(BackendId.IPP_DIRECT)
            if (hasLegacyUsbPath) {
                if (document.kind == DocumentKind.PDF && capabilities.supportsPdf && isPdfDirectSafe(settings)) add(BackendId.PDF_DIRECT)
                if (capabilities.supportsPwgRaster && document.kind in printableDocumentKinds) add(BackendId.PWG_RASTER)
                if (capabilities.supportsPostScript && document.kind in printableDocumentKinds) add(BackendId.POSTSCRIPT_RASTER)
                if (capabilities.supportsPcl5 && document.kind in printableDocumentKinds) add(BackendId.PCL5_RASTER)
                if (capabilities.supportsEscPos && document.kind in setOf(DocumentKind.PDF, DocumentKind.IMAGE, DocumentKind.TEXT)) add(BackendId.ESC_POS)
                if ((document.kind == DocumentKind.POSTSCRIPT && capabilities.supportsPostScript) ||
                    (document.kind == DocumentKind.PCL && capabilities.supportsPcl)) add(BackendId.RAW)
            }
        }
        val forced = override?.forcedBackend
        val available = if (forced != null && forced in detected) listOf(forced) + detected.filterNot { it == forced } else detected
        return if (available.isEmpty()) {
            BackendDecision(BackendId.NONE, emptyList(), "Нет реализованного совместимого backend для этого документа и принтера")
        } else {
            val selected = available.first()
            val note = when {
                forced != null && forced in detected -> "Выбран экспериментальный backend: ${forced.title}"
                selected == BackendId.PWG_RASTER -> "PWG Raster: параметры проверяются по доступным возможностям"
                else -> null
            }
            BackendDecision(selected, available, note)
        }
    }

    /** Declared once per implementation; EffectivePrintCapabilities intersects this with the printer. */
    fun descriptorFor(id: BackendId): BackendCapabilityDescriptor = when (id) {
        BackendId.IPP_DIRECT -> BackendCapabilityDescriptor(
            backendId = id, copiesRange = 1..99,
            pageSelections = setOf(PageSelectionKind.ALL, PageSelectionKind.RANGES, PageSelectionKind.ODD, PageSelectionKind.EVEN),
            paperSizes = PaperSize.entries.filterNot { it == PaperSize.AUTO }.toSet(), supportsCustomPaper = true,
            orientations = setOf(Orientation.AUTO, Orientation.PORTRAIT, Orientation.LANDSCAPE),
            colorModes = setOf(ColorMode.COLOR, ColorMode.GRAYSCALE, ColorMode.BLACK_ONLY, ColorMode.MONOCHROME),
            duplexModes = setOf(DuplexMode.OFF, DuplexMode.LONG_EDGE, DuplexMode.SHORT_EDGE),
            supportedMediaTypes = ru.usbprint.domain.model.MediaType.entries.toSet(),
            supportedMediaSources = ru.usbprint.domain.model.MediaSource.entries.toSet(),
            supportedOutputBins = ru.usbprint.domain.model.OutputBin.entries.toSet()
        )
        BackendId.PWG_RASTER -> rasterDescriptor(id, setOf(ColorMode.COLOR, ColorMode.GRAYSCALE, ColorMode.BLACK_ONLY, ColorMode.MONOCHROME), setOf(PrinterResolution.DPI_300, PrinterResolution.DPI_600))
        BackendId.POSTSCRIPT_RASTER -> rasterDescriptor(id, setOf(ColorMode.COLOR, ColorMode.GRAYSCALE, ColorMode.BLACK_ONLY), setOf(PrinterResolution.DPI_300))
        BackendId.PCL5_RASTER -> rasterDescriptor(
            id, setOf(ColorMode.GRAYSCALE, ColorMode.BLACK_ONLY, ColorMode.MONOCHROME),
            setOf(PrinterResolution.DPI_300, PrinterResolution.DPI_600),
            setOf(PaperSize.A3, PaperSize.A4, PaperSize.A5, PaperSize.LETTER, PaperSize.LEGAL)
        )
        BackendId.ESC_POS -> BackendCapabilityDescriptor(
            backendId = id, copiesRange = 1..99,
            pageSelections = setOf(PageSelectionKind.ALL, PageSelectionKind.RANGES),
            colorModes = setOf(ColorMode.BLACK_ONLY), resolutions = setOf(PrinterResolution.DPI_300)
        )
        // Direct and raw jobs cannot safely transform settings; no settings UI is exposed.
        BackendId.PDF_DIRECT, BackendId.RAW, BackendId.NONE -> BackendCapabilityDescriptor(backendId = id)
    }

    fun capabilitiesFor(id: BackendId, printer: PrinterCapabilities): BackendCapabilities {
        val descriptor = descriptorFor(id)
        val effective = effectiveFor(id, printer)
        return BackendCapabilities(
            supportsCopies = effective.copiesRange != null,
            supportsPageRange = effective.pageSelections?.value?.contains(PageSelectionKind.RANGES) == true,
            supportsPaper = effective.paperSizes?.value?.isNotEmpty() == true,
            supportsOrientation = effective.orientations?.value?.isNotEmpty() == true,
            supportsColor = effective.colorModes?.value?.any { it == ColorMode.COLOR } == true,
            supportsDuplex = effective.duplexModes?.value?.any { it != DuplexMode.OFF } == true,
            supportsScaling = effective.supportsScaling,
            resolutionsDpi = effective.resolutions?.value?.map { it.horizontalDpi }?.toSet().orEmpty(),
            descriptor = descriptor
        )
    }

    /**
     * Applies the conservative rule: a device value is shown only if both the
     * device and selected encoder support it. If it is unknown, one safe
     * backend fallback may be exposed and is never labelled printer support.
     */
    fun effectiveFor(
        id: BackendId,
        printer: PrinterCapabilities,
        override: ExperimentalPrinterOverride? = null
    ): EffectivePrintCapabilities {
        val backend = descriptorFor(id)
        if (id == BackendId.NONE || backend.copiesRange == null) return EffectivePrintCapabilities.NONE
        val isIpp = id == BackendId.IPP_DIRECT
        val paper = if (isIpp) printer.reportedPaperSizes?.let { intersectOrFallback(it, backend.paperSizes, emptySet(), false) }
            else intersectOrFallback(printer.knownPaperSizes, backend.paperSizes, setOf(override?.forcedPaper ?: PaperSize.A4), override?.forcedPaper != null)
        val resolutions = if (isIpp) printer.reportedResolutions else intersectOrFallback(printer.knownResolutions, backend.resolutions, setOf(override?.forcedResolution ?: PrinterResolution.DPI_300), override?.forcedResolution != null)
        val printerColors = printer.reportedColorModes ?: printer.supportsColor?.takeUnless { isIpp }?.let {
            CapabilityValue(if (it) setOf(ColorMode.COLOR, ColorMode.GRAYSCALE, ColorMode.BLACK_ONLY) else setOf(ColorMode.GRAYSCALE, ColorMode.BLACK_ONLY), CapabilitySource.IEEE1284, CapabilityConfidence.DERIVED)
        }
        val colors = if (isIpp) intersectOrNull(printerColors, backend.colorModes)
            else intersectOrFallback(printerColors, backend.colorModes, setOf(if (override?.forceMonochrome == true) ColorMode.MONOCHROME else ColorMode.GRAYSCALE), override?.forceMonochrome == true)
        val printerDuplex = printer.reportedDuplexModes ?: printer.supportsDuplex?.takeUnless { isIpp }?.let {
            CapabilityValue(if (it) setOf(DuplexMode.OFF, DuplexMode.LONG_EDGE, DuplexMode.SHORT_EDGE) else setOf(DuplexMode.OFF), CapabilitySource.IEEE1284, CapabilityConfidence.DERIVED)
        }
        val duplex = intersectOrFallback(printerDuplex, backend.duplexModes, setOf(DuplexMode.OFF), false)
        val margins = override?.forcedMargins?.let { CapabilityValue(it, CapabilitySource.USER_OVERRIDE, CapabilityConfidence.EXPERIMENTAL) }
            ?: printer.reportedHardwareMargins
            ?: CapabilityValue(HardwareMarginsMm.ZERO, CapabilitySource.BACKEND_DEFAULT, CapabilityConfidence.DEFAULT)

        return EffectivePrintCapabilities(
            backendId = id,
            copiesRange = printer.reportedCopiesRange?.let { clipRange(it, backend.copiesRange) }
                ?: CapabilityValue(if (isIpp) 1..1 else backend.copiesRange, CapabilitySource.BACKEND_DEFAULT, CapabilityConfidence.DEFAULT),
            pageSelections = CapabilityValue(
                if (isIpp && !printer.ipp.pageRangesSupported) setOf(PageSelectionKind.ALL) else backend.pageSelections,
                if (isIpp && printer.ipp.pageRangesSupported) CapabilitySource.IPP else CapabilitySource.BACKEND_DEFAULT,
                if (isIpp && printer.ipp.pageRangesSupported) CapabilityConfidence.CONFIRMED else CapabilityConfidence.DEFAULT
            ),
            paperSizes = paper,
            customPaperRange = printer.reportedCustomPaperRange?.takeIf { backend.supportsCustomPaper },
            orientations = if (isIpp) printer.reportedOrientations ?: CapabilityValue(setOf(Orientation.AUTO), CapabilitySource.BACKEND_DEFAULT, CapabilityConfidence.DEFAULT)
                else CapabilityValue(backend.orientations, CapabilitySource.BACKEND_DEFAULT, CapabilityConfidence.DEFAULT),
            colorModes = colors,
            duplexModes = duplex,
            resolutions = resolutions,
            hardwareMargins = margins,
            supportsMargins = backend.supportsMargins,
            supportsPositioning = backend.supportsPositioning,
            supportsScaling = backend.supportsScaling,
            supportsCollate = backend.supportsCollate,
            supportsPageOrder = backend.supportsPageOrder,
            supportsNUp = backend.supportsNUp,
            mediaTypes = intersectOrNull(printer.reportedMediaTypes, backend.supportedMediaTypes),
            mediaSources = intersectOrNull(printer.reportedMediaSources, backend.supportedMediaSources),
            outputBins = intersectOrNull(printer.reportedOutputBins, backend.supportedOutputBins),
            mediaTypeOptions = printer.reportedMediaTypeOptions.takeIf { isIpp },
            mediaSourceOptions = printer.reportedMediaSourceOptions.takeIf { isIpp },
            outputBinOptions = printer.reportedOutputBinOptions.takeIf { isIpp },
            customPaperRangeMicrons = printer.reportedCustomPaperRangeMicrons.takeIf { isIpp && backend.supportsCustomPaper },
            limitations = buildList {
                if (!isIpp && printer.knownPaperSizes == null) add("Формат бумаги A4 — безопасное значение backend; принтер его не подтвердил.")
                if (!isIpp && printer.knownResolutions == null) add("300 DPI — безопасное значение backend; принтер не сообщил своё разрешение.")
                if (isIpp && printer.reportedPaperSizes == null) add("IPP не сообщил media-supported; выбор формата скрыт.")
                if (isIpp && printer.reportedResolutions == null) add("IPP не сообщил printer-resolution-supported; выбор DPI скрыт.")
                if (printer.reportedHardwareMargins == null && override?.forcedMargins == null) add("Физические непечатаемые поля принтера неизвестны.")
                if (id == BackendId.PCL5_RASTER) add("PCL 5 Raster выводит монохромный поток.")
            }
        )
    }

    private fun rasterDescriptor(
        id: BackendId,
        colors: Set<ColorMode>,
        resolutions: Set<PrinterResolution>,
        papers: Set<PaperSize> = PaperSize.entries.filterNot { it == PaperSize.AUTO }.toSet()
    ) = BackendCapabilityDescriptor(
        backendId = id,
        copiesRange = 1..99,
        pageSelections = setOf(PageSelectionKind.ALL, PageSelectionKind.RANGES, PageSelectionKind.ODD, PageSelectionKind.EVEN),
        paperSizes = papers,
        orientations = setOf(Orientation.AUTO, Orientation.PORTRAIT, Orientation.LANDSCAPE),
        colorModes = colors,
        duplexModes = setOf(DuplexMode.OFF, DuplexMode.LONG_EDGE, DuplexMode.SHORT_EDGE),
        resolutions = resolutions,
        supportsMargins = true,
        supportsPositioning = true,
        supportsScaling = true,
        supportsCollate = true,
        supportsPageOrder = true
    )

    private fun <T> intersectOrFallback(reported: CapabilityValue<Set<T>>?, backend: Set<T>, fallback: Set<T>, overridden: Boolean): CapabilityValue<Set<T>>? {
        if (overridden) return CapabilityValue(fallback, CapabilitySource.USER_OVERRIDE, CapabilityConfidence.EXPERIMENTAL)
        if (reported == null) return fallback.takeIf { it.isNotEmpty() }?.let { CapabilityValue(it, CapabilitySource.BACKEND_DEFAULT, CapabilityConfidence.DEFAULT) }
        return reported.value.intersect(backend).takeIf { it.isNotEmpty() }?.let { CapabilityValue(it, reported.source, reported.confidence) }
    }

    private fun clipRange(reported: CapabilityValue<IntRange>, backend: IntRange): CapabilityValue<IntRange>? {
        val range = maxOf(reported.value.first, backend.first)..minOf(reported.value.last, backend.last)
        return range.takeIf { !it.isEmpty() }?.let { reported.copy(value = it) }
    }

    private fun <T> intersectOrNull(reported: CapabilityValue<Set<T>>?, backend: Set<T>): CapabilityValue<Set<T>>? {
        if (reported == null || backend.isEmpty()) return null
        return reported.value.intersect(backend).takeIf { it.isNotEmpty() }?.let { reported.copy(value = it) }
    }

    private fun isPdfDirectSafe(settings: PrintSettings): Boolean =
        settings.copies == 1 && settings.pageSelection is PageSelection.All && settings.paperSize == PaperSize.AUTO &&
            settings.orientation == Orientation.AUTO && settings.colorMode == ColorMode.AUTO && settings.duplexMode == DuplexMode.OFF &&
            settings.selectedResolution == null && settings.scalingMode == ru.usbprint.domain.model.ScalingMode.FIT &&
            settings.marginsMm == 5f && settings.quality == ru.usbprint.domain.model.PrintQuality.NORMAL && settings.pagesPerSheet == 1

    private fun isIppDirectSafe(settings: PrintSettings): Boolean =
        settings.scalingMode == ru.usbprint.domain.model.ScalingMode.FIT && settings.margins == PrintSettings().margins &&
            settings.contentPosition == ru.usbprint.domain.model.ContentPosition.CENTER && settings.pagesPerSheet == 1 &&
            settings.pageOrder == ru.usbprint.domain.model.PageOrder.NORMAL

    private val printableDocumentKinds = setOf(DocumentKind.PDF, DocumentKind.IMAGE, DocumentKind.TEXT)
}
