package ru.usbprint.ipp

import ru.usbprint.domain.logic.PageRangeParser
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintQuality
import ru.usbprint.domain.model.PrintSettings
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class IppClient(
    private val session: IppSession,
    private val decoder: IppDecoder = IppDecoder(),
    private val requestIds: AtomicInteger = AtomicInteger(1)
) {
    suspend fun getPrinterAttributes(): IppResponse = execute(
        IppOperation.GET_PRINTER_ATTRIBUTES,
        operationAttributes(listOf(IppAttribute("requested-attributes", REQUESTED_PRINTER_ATTRIBUTES.map(IppValue::Keyword))))
    )

    suspend fun printJob(
        document: InputStream,
        documentLength: Long,
        documentFormat: String,
        jobName: String,
        settings: PrintSettings,
        supportedAttributeNames: Set<String>,
        pageCount: Int,
        isCancelled: () -> Boolean = { false }
    ): Pair<IppResponse, IppJobReference> {
        val operation = mutableListOf(
            IppAttribute("attributes-charset", IppValue.Charset("utf-8")),
            IppAttribute("attributes-natural-language", IppValue.NaturalLanguage("ru")),
            IppAttribute("printer-uri", IppValue.UriValue(PRINTER_URI)),
            IppAttribute("requesting-user-name", IppValue.NameValue("usb-print")),
            IppAttribute("job-name", IppValue.NameValue(jobName.take(127))),
            IppAttribute("document-format", IppValue.MimeMediaType(documentFormat)),
            IppAttribute("ipp-attribute-fidelity", IppValue.BooleanValue(true))
        )
        val job = buildJobAttributes(settings, supportedAttributeNames, pageCount)
        val groups = buildList {
            add(IppAttributeGroup(IppGroupTag.OPERATION_ATTRIBUTES, operation))
            if (job.isNotEmpty()) add(IppAttributeGroup(IppGroupTag.JOB_ATTRIBUTES, job))
        }
        val response = execute(IppOperation.PRINT_JOB, groups, document, documentLength, isCancelled)
        val reference = IppJobReference(
            jobId = (response.first("job-id") as? IppValue.IntegerValue)?.value,
            jobUri = (response.first("job-uri") as? IppValue.UriValue)?.value
        )
        return response to reference
    }

    suspend fun getJobAttributes(reference: IppJobReference): Pair<IppResponse, IppJobStatus> {
        val target = jobTargetAttributes(reference)
        val requested = listOf("job-state", "job-state-reasons", "job-impressions-completed", "job-media-sheets-completed")
        val response = execute(IppOperation.GET_JOB_ATTRIBUTES, operationAttributes(
            target + IppAttribute("requested-attributes", requested.map(IppValue::Keyword)),
            includePrinterUri = reference.jobUri == null
        ))
        return response to IppJobStatusMapper.map(response)
    }

    suspend fun cancelJob(reference: IppJobReference): IppResponse = execute(
        IppOperation.CANCEL_JOB,
        operationAttributes(jobTargetAttributes(reference), includePrinterUri = reference.jobUri == null)
    )

    private suspend fun execute(
        operation: IppOperation,
        groups: List<IppAttributeGroup>,
        document: InputStream? = null,
        documentLength: Long = 0,
        isCancelled: () -> Boolean = { false }
    ): IppResponse {
        val requestId = nextRequestId()
        val payload = IppEncoder.encode(IppRequest(operation = operation, requestId = requestId, groups = groups))
        val response = decoder.decodeResponse(session.exchange(payload, document, documentLength, isCancelled))
        if (response.requestId != requestId) throw PrintException(AppError.IPP_REQUEST_ID_MISMATCH)
        if (response.version.major !in 1..2) throw PrintException(AppError.IPP_VERSION_NOT_SUPPORTED)
        requireSuccessful(response)
        return response
    }

    private fun operationAttributes(additional: List<IppAttribute>, includePrinterUri: Boolean = true) = listOf(IppAttributeGroup(
        IppGroupTag.OPERATION_ATTRIBUTES,
        buildList {
            add(
            IppAttribute("attributes-charset", IppValue.Charset("utf-8")),
            )
            add(
            IppAttribute("attributes-natural-language", IppValue.NaturalLanguage("ru")),
            )
            if (includePrinterUri) add(IppAttribute("printer-uri", IppValue.UriValue(PRINTER_URI)))
            addAll(additional)
        }
    ))

    private fun jobTargetAttributes(reference: IppJobReference): List<IppAttribute> = when {
        reference.jobUri != null -> listOf(IppAttribute("job-uri", IppValue.UriValue(reference.jobUri)))
        reference.jobId != null -> listOf(IppAttribute("job-id", IppValue.IntegerValue(reference.jobId)))
        else -> throw PrintException(AppError.IPP_MALFORMED_RESPONSE)
    }

    private fun buildJobAttributes(settings: PrintSettings, supported: Set<String>, pageCount: Int): List<IppAttribute> = buildList {
        fun addIfSupported(name: String, value: IppValue) { if (name in supported) add(IppAttribute(name, value)) }
        addIfSupported("copies", IppValue.IntegerValue(settings.copies))
        if (settings.paperSize != PaperSize.AUTO) paperKeyword(settings.paperSize)?.let { addIfSupported("media", IppValue.Keyword(it)) }
        val sides = when (settings.duplexMode) { DuplexMode.OFF -> "one-sided"; DuplexMode.LONG_EDGE -> "two-sided-long-edge"; DuplexMode.SHORT_EDGE -> "two-sided-short-edge" }
        addIfSupported("sides", IppValue.Keyword(sides))
        when (settings.colorMode) {
            ColorMode.COLOR -> addIfSupported("print-color-mode", IppValue.Keyword("color"))
            ColorMode.GRAYSCALE -> addIfSupported("print-color-mode", IppValue.Keyword("process-monochrome"))
            ColorMode.BLACK_ONLY, ColorMode.MONOCHROME -> addIfSupported("print-color-mode", IppValue.Keyword("monochrome"))
            ColorMode.AUTO -> Unit
        }
        settings.selectedResolution?.let { addIfSupported("printer-resolution", IppValue.Resolution(it.horizontalDpi, it.verticalDpi, IppValue.Resolution.Units.DPI)) }
        when (settings.orientation) {
            Orientation.PORTRAIT -> addIfSupported("orientation-requested", IppValue.EnumValue(3))
            Orientation.LANDSCAPE -> addIfSupported("orientation-requested", IppValue.EnumValue(4))
            Orientation.AUTO -> Unit
        }
        val quality = when (settings.quality) { PrintQuality.DRAFT -> 3; PrintQuality.NORMAL -> 4; PrintQuality.HIGH -> 5 }
        addIfSupported("print-quality", IppValue.EnumValue(quality))
        if (settings.copies > 1) addIfSupported(
            "multiple-document-handling",
            IppValue.Keyword(if (settings.collate) "separate-documents-collated-copies" else "separate-documents-uncollated-copies")
        )
        if (settings.pageSelection !is PageSelection.All && "page-ranges" in supported) {
            val pages = PageRangeParser.expand(settings.pageSelection, pageCount)
            val ranges = pagesToRanges(pages).map { IppValue.IntegerRange(it.first, it.last) }
            if (ranges.isNotEmpty()) add(IppAttribute("page-ranges", ranges))
        }
        (settings.mediaSourceKeyword ?: settings.mediaSource?.let(::mediaSourceKeyword))?.let { addIfSupported("media-source", IppValue.Keyword(it)) }
        (settings.mediaTypeKeyword ?: settings.mediaType?.let(::mediaTypeKeyword))?.let { addIfSupported("media-type", IppValue.Keyword(it)) }
        (settings.outputBinKeyword ?: settings.outputBin?.let(::outputBinKeyword))?.let { addIfSupported("output-bin", IppValue.Keyword(it)) }
    }

    private fun pagesToRanges(pages: List<Int>): List<IntRange> = pages.sorted().distinct().fold(mutableListOf()) { result, page ->
        val last = result.lastOrNull()
        if (last != null && page == last.last + 1) result[result.lastIndex] = last.first..page else result += page..page
        result
    }

    private fun requireSuccessful(response: IppResponse) {
        if (response.isSuccessful) return
        val error = when (response.statusCode) {
            0x0401 -> AppError.IPP_OPERATION_NOT_SUPPORTED
            0x040A -> AppError.IPP_DOCUMENT_FORMAT_NOT_SUPPORTED
            0x040B, 0x040C -> AppError.IPP_ATTRIBUTE_NOT_SUPPORTED
            in 0x0400..0x04FF -> AppError.IPP_CLIENT_ERROR
            in 0x0500..0x05FF -> AppError.IPP_SERVER_ERROR
            else -> AppError.IPP_JOB_REJECTED
        }
        throw PrintException(error)
    }

    private fun nextRequestId(): Int = requestIds.getAndUpdate { if (it == Int.MAX_VALUE) 1 else it + 1 }.coerceAtLeast(1)

    private fun paperKeyword(paper: PaperSize): String? = when (paper) {
        PaperSize.AUTO -> null; PaperSize.A0 -> "iso_a0_841x1189mm"; PaperSize.A1 -> "iso_a1_594x841mm"
        PaperSize.A2 -> "iso_a2_420x594mm"; PaperSize.A3 -> "iso_a3_297x420mm"; PaperSize.A4 -> "iso_a4_210x297mm"
        PaperSize.A5 -> "iso_a5_148x210mm"; PaperSize.A6 -> "iso_a6_105x148mm"; PaperSize.LETTER -> "na_letter_8.5x11in"
        PaperSize.LEGAL -> "na_legal_8.5x14in"; PaperSize.EXECUTIVE -> "na_executive_7.25x10.5in"
        PaperSize.STATEMENT -> "na_invoice_5.5x8.5in"; PaperSize.TABLOID, PaperSize.LEDGER -> "na_ledger_11x17in"
        PaperSize.ENVELOPE_DL -> "iso_dl_110x220mm"; PaperSize.ENVELOPE_C5 -> "iso_c5_162x229mm"
    }
    private fun mediaSourceKeyword(value: ru.usbprint.domain.model.MediaSource) = when (value) { ru.usbprint.domain.model.MediaSource.AUTO -> "auto"; ru.usbprint.domain.model.MediaSource.MAIN -> "main"; ru.usbprint.domain.model.MediaSource.MANUAL -> "manual"; ru.usbprint.domain.model.MediaSource.PHOTO -> "photo" }
    private fun mediaTypeKeyword(value: ru.usbprint.domain.model.MediaType) = when (value) { ru.usbprint.domain.model.MediaType.PLAIN -> "stationery"; ru.usbprint.domain.model.MediaType.PHOTO -> "photographic"; ru.usbprint.domain.model.MediaType.ENVELOPE -> "envelope"; ru.usbprint.domain.model.MediaType.LABEL -> "labels" }
    private fun outputBinKeyword(value: ru.usbprint.domain.model.OutputBin) = when (value) { ru.usbprint.domain.model.OutputBin.AUTO -> "auto"; ru.usbprint.domain.model.OutputBin.STANDARD -> "face-down"; ru.usbprint.domain.model.OutputBin.FACE_UP -> "face-up" }

    companion object {
        const val PRINTER_URI = "ipp://localhost/ipp/print"
        val REQUESTED_PRINTER_ATTRIBUTES = listOf(
            "printer-name", "printer-info", "printer-make-and-model", "printer-state", "printer-state-reasons", "printer-is-accepting-jobs",
            "ipp-versions-supported", "operations-supported", "document-format-supported", "document-format-default",
            "media-supported", "media-ready", "media-default", "media-col-supported", "media-col-ready", "media-col-database", "media-col-default",
            "media-source-supported", "media-source-default", "media-type-supported", "media-type-default", "output-bin-supported", "output-bin-default",
            "printer-resolution-supported", "printer-resolution-default", "sides-supported", "sides-default", "print-color-mode-supported",
            "print-color-mode-default", "color-supported", "copies-supported", "copies-default", "multiple-document-handling-supported",
            "print-quality-supported", "print-quality-default", "orientation-requested-supported", "orientation-requested-default", "page-ranges-supported",
            "job-creation-attributes-supported", "job-hold-until-supported", "compression-supported", "urf-supported",
            "pwg-raster-document-resolution-supported", "pwg-raster-document-type-supported", "pwg-raster-document-sheet-back-supported",
            "pwg-raster-document-sheet-back"
        )
    }
}
