package ru.usbprint.domain.model

enum class PrinterLanguage(val label: String) {
    PDF("PDF"),
    POSTSCRIPT("PostScript"),
    PCL("PCL"),
    PCL_XL("PCL XL"),
    PCLM("PCLm"),
    PWG_RASTER("PWG Raster"),
    URF("URF"),
    ESC_POS("ESC/POS"),
    UNKNOWN("Неизвестный")
}

data class UsbEndpointInfo(
    val address: Int,
    val direction: String,
    val type: String,
    val maxPacketSize: Int
)

data class UsbInterfaceInfo(
    val id: Int,
    val interfaceClass: Int,
    val subclass: Int,
    val protocol: Int,
    val endpoints: List<UsbEndpointInfo>
) {
    val isPrinterClass: Boolean get() = interfaceClass == USB_CLASS_PRINTER
    val isIppUsb: Boolean get() = isPrinterClass && subclass == IPP_SUBCLASS && protocol == IPP_PROTOCOL &&
        endpoints.any { it.direction == "IN" && it.type == "bulk" } && endpoints.any { it.direction == "OUT" && it.type == "bulk" }

    companion object { const val USB_CLASS_PRINTER = 7; const val IPP_SUBCLASS = 1; const val IPP_PROTOCOL = 4 }
}

data class PrinterCapabilities(
    val manufacturer: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val vendorId: Int,
    val productId: Int,
    val usbDeviceId: Int,
    val usbClass: Int? = null,
    val usbSubclass: Int? = null,
    val usbProtocol: Int? = null,
    val interfaces: List<UsbInterfaceInfo> = emptyList(),
    val supportedLanguages: Set<PrinterLanguage> = emptySet(),
    val supportsColor: Boolean? = null,
    val supportsDuplex: Boolean? = null,
    val supportedPaperSizes: Set<PaperSize> = emptySet(),
    val supportedResolutionsDpi: Set<Int> = emptySet(),
    /** Structured discovery data. Legacy fields above are retained for v2.0 compatibility. */
    val reportedPaperSizes: CapabilityValue<Set<PaperSize>>? = null,
    val reportedCustomPaperRange: CapabilityValue<CustomPaperRangeMm>? = null,
    val reportedResolutions: CapabilityValue<Set<PrinterResolution>>? = null,
    val reportedColorModes: CapabilityValue<Set<ColorMode>>? = null,
    val reportedDuplexModes: CapabilityValue<Set<DuplexMode>>? = null,
    val reportedOrientations: CapabilityValue<Set<Orientation>>? = null,
    val reportedCopiesRange: CapabilityValue<IntRange>? = null,
    val reportedMediaTypes: CapabilityValue<Set<MediaType>>? = null,
    val reportedMediaSources: CapabilityValue<Set<MediaSource>>? = null,
    val reportedOutputBins: CapabilityValue<Set<OutputBin>>? = null,
    val reportedHardwareMargins: CapabilityValue<HardwareMarginsMm>? = null,
    val reportedMediaTypeOptions: CapabilityValue<Set<PrinterKeywordOption>>? = null,
    val reportedMediaSourceOptions: CapabilityValue<Set<PrinterKeywordOption>>? = null,
    val reportedOutputBinOptions: CapabilityValue<Set<PrinterKeywordOption>>? = null,
    val reportedCustomPaperRangeMicrons: CapabilityValue<CustomPaperRangeMicrons>? = null,
    val ipp: IppPrinterInfo = IppPrinterInfo(),
    val rawDeviceId: String? = null,
    val deviceIdFields: Map<String, String> = emptyMap(),
    val productName: String? = null,
    /** USB Printer Class GET_PORT_STATUS. Null means the device did not answer the optional query. */
    val portStatus: PrinterPortStatus? = null
) {
    val displayName: String
        get() = listOfNotNull(manufacturer, model).joinToString(" ").ifBlank {
            productName ?: "USB-принтер ${vendorId.toString(16)}:${productId.toString(16)}"
        }
    val supportsPdf get() = PrinterLanguage.PDF in supportedLanguages
    val supportsPostScript get() = PrinterLanguage.POSTSCRIPT in supportedLanguages
    val supportsPcl get() = PrinterLanguage.PCL in supportedLanguages || PrinterLanguage.PCL_XL in supportedLanguages
    /** A PCL XL-only declaration is not enough to safely send a PCL 5 raster job. */
    val supportsPcl5 get() = PrinterLanguage.PCL in supportedLanguages
    val supportsPclm get() = PrinterLanguage.PCLM in supportedLanguages
    val supportsPwgRaster get() = PrinterLanguage.PWG_RASTER in supportedLanguages
    val supportsUrf get() = PrinterLanguage.URF in supportedLanguages
    val supportsEscPos get() = PrinterLanguage.ESC_POS in supportedLanguages
    val hasPrinterInterface get() = interfaces.any { it.isPrinterClass }
    val knownPaperSizes: CapabilityValue<Set<PaperSize>>?
        get() = reportedPaperSizes ?: supportedPaperSizes.takeIf { it.isNotEmpty() }?.let {
            CapabilityValue(it, CapabilitySource.IEEE1284, CapabilityConfidence.DERIVED)
        }
    val knownResolutions: CapabilityValue<Set<PrinterResolution>>?
        get() = reportedResolutions ?: supportedResolutionsDpi.takeIf { it.isNotEmpty() }?.mapTo(linkedSetOf()) { PrinterResolution(it) }?.let {
            CapabilityValue(it, CapabilitySource.IEEE1284, CapabilityConfidence.DERIVED)
        }
}

/** The USB Printer Class defines only these three status bits. All other bits are preserved raw. */
data class PrinterPortStatus(val rawValue: Int) {
    val paperEmpty: Boolean get() = rawValue and PAPER_EMPTY != 0
    val selected: Boolean get() = rawValue and SELECTED != 0
    val notError: Boolean get() = rawValue and NOT_ERROR != 0
    val userMessage: String
        get() = when {
            paperEmpty -> "Нет бумаги"
            !selected -> "Принтер не выбран / offline"
            !notError -> "Ошибка принтера"
            else -> "Готов"
        }

    companion object {
        const val NOT_ERROR = 0x08
        const val SELECTED = 0x10
        const val PAPER_EMPTY = 0x20
    }
}

data class PrinterRef(
    val deviceKey: String,
    val capabilities: PrinterCapabilities,
    val interfaceId: Int,
    val ippInterfaceId: Int? = null
)
