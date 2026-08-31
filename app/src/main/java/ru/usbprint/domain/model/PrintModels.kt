package ru.usbprint.domain.model

import java.util.UUID

enum class DocumentKind { PDF, IMAGE, TEXT, POSTSCRIPT, PCL, UNKNOWN }

data class DocumentRef(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val kind: DocumentKind,
    val sizeBytes: Long? = null,
    val pageCount: Int? = null
)

enum class PaperSize(val label: String, val widthMm: Float, val heightMm: Float) {
    AUTO("Автоматически", 210f, 297f),
    A0("A0", 841f, 1189f),
    A1("A1", 594f, 841f),
    A2("A2", 420f, 594f),
    A4("A4", 210f, 297f),
    A5("A5", 148f, 210f),
    A3("A3", 297f, 420f),
    A6("A6", 105f, 148f),
    LETTER("Letter", 215.9f, 279.4f),
    LEGAL("Legal", 215.9f, 355.6f),
    EXECUTIVE("Executive", 184.15f, 266.7f),
    STATEMENT("Statement", 139.7f, 215.9f),
    TABLOID("Tabloid", 279.4f, 431.8f),
    LEDGER("Ledger", 431.8f, 279.4f),
    ENVELOPE_DL("Envelope DL", 110f, 220f),
    ENVELOPE_C5("Envelope C5", 162f, 229f)
}

enum class Orientation(val label: String) { AUTO("Авто"), PORTRAIT("Книжная"), LANDSCAPE("Альбомная") }
enum class ColorMode(val label: String) { AUTO("Авто"), COLOR("Цветная"), GRAYSCALE("Оттенки серого"), BLACK_ONLY("Только чёрный"), MONOCHROME("Чёрно-белая") }
enum class DuplexMode(val label: String) { OFF("Выключена"), LONG_EDGE("По длинному краю"), SHORT_EDGE("По короткому краю") }
enum class ScalingMode(val label: String) { FIT("Вписать"), FILL("Заполнить"), ACTUAL_SIZE("Фактический размер"), CUSTOM("Свой масштаб") }
enum class PrintQuality(val label: String) { DRAFT("Черновик"), NORMAL("Обычное"), HIGH("Высокое") }
enum class ContentPosition(val label: String) { CENTER("По центру"), TOP_LEFT("Слева сверху"), TOP_CENTER("По центру сверху"), TOP_RIGHT("Справа сверху"), MIDDLE_LEFT("Слева"), MIDDLE_RIGHT("Справа"), BOTTOM_LEFT("Слева снизу"), BOTTOM_CENTER("По центру снизу"), BOTTOM_RIGHT("Справа снизу") }
enum class PageOrder(val label: String) { NORMAL("Обычный"), REVERSE("В обратном порядке") }
enum class PrintPresetId(val label: String) { AUTO("Автоматически"), DRAFT("Черновик"), NORMAL("Обычная"), HIGH("Высокое качество"), PHOTO("Фото"), TEXT("Текст"), CUSTOM("Пользовательский") }

data class PrintMarginsMm(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(listOf(left, top, right, bottom).all { it >= 0f && it <= 60f }) }
    companion object { val ZERO = PrintMarginsMm(0f, 0f, 0f, 0f); fun uniform(value: Float) = PrintMarginsMm(value, value, value, value) }
}

sealed interface PageSelection {
    object All : PageSelection
    object Current : PageSelection
    object Odd : PageSelection
    object Even : PageSelection
    data class Ranges(val raw: String, val pages: List<IntRange>) : PageSelection
}

data class PrintSettings(
    val copies: Int = 1,
    val pageSelection: PageSelection = PageSelection.All,
    val paperSize: PaperSize = PaperSize.AUTO,
    val orientation: Orientation = Orientation.AUTO,
    val colorMode: ColorMode = ColorMode.AUTO,
    val duplexMode: DuplexMode = DuplexMode.OFF,
    val scalingMode: ScalingMode = ScalingMode.FIT,
    val quality: PrintQuality = PrintQuality.NORMAL,
    /** Legacy symmetric resolution persisted by v2.1. */
    val resolutionDpi: Int? = null,
    /** Exact resolution, including asymmetric X/Y values reported by IPP. */
    val resolution: PrinterResolution? = null,
    /** Legacy uniform margin. New settings use margins; kept for old saved state compatibility. */
    val marginsMm: Float = 5f,
    val margins: PrintMarginsMm = PrintMarginsMm.uniform(marginsMm),
    val contentPosition: ContentPosition = ContentPosition.CENTER,
    val customScalePercent: Int? = null,
    val pageOrder: PageOrder = PageOrder.NORMAL,
    val collate: Boolean = true,
    /** 1, 2 or 4 logical pages on one physical sheet. Raster backends currently implement 1. */
    val pagesPerSheet: Int = 1,
    val mediaType: MediaType? = null,
    val mediaSource: MediaSource? = null,
    val outputBin: OutputBin? = null,
    /** Exact IPP keywords selected from printer-reported options. */
    val mediaTypeKeyword: String? = null,
    val mediaSourceKeyword: String? = null,
    val outputBinKeyword: String? = null,
    val preset: PrintPresetId = PrintPresetId.AUTO
) {
    val effectiveScalePercent: Int? get() = customScalePercent?.takeIf { it in 10..400 }
    val selectedResolution: PrinterResolution? get() = resolution ?: resolutionDpi?.let { PrinterResolution(it) }
}

enum class BackendId(val title: String) {
    IPP_DIRECT("IPP-over-USB Direct"),
    IPP_PWG("IPP-over-USB PWG Raster"),
    PDF_DIRECT("PDF Direct"),
    PWG_RASTER("PWG Raster"),
    POSTSCRIPT_RASTER("PostScript Raster"),
    PCL5_RASTER("PCL 5 Raster"),
    ESC_POS("ESC/POS"),
    RAW("RAW USB"),
    NONE("Не выбран")
}

/** SENT means only that the bytes were accepted by the USB connection, not that paper exited the printer. */
enum class PrintJobStatus {
    IDLE, VALIDATING, OPENING_USB, PREPARING_DOCUMENT, RENDERING,
    GENERATING_PAYLOAD, SENDING, WAITING_STATUS, SENT, CANCELLED, ERROR
}

data class PrintJob(
    val id: String = UUID.randomUUID().toString(),
    val document: DocumentRef,
    val printer: PrinterRef,
    val settings: PrintSettings,
    val backend: BackendId,
    val status: PrintJobStatus = PrintJobStatus.IDLE,
    val progress: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

enum class AppError(val userMessage: String) {
    USB_HOST_UNSUPPORTED("Это устройство Android не поддерживает режим USB Host, необходимый для прямого подключения принтера."),
    USB_PERMISSION_DENIED("Доступ к USB-принтеру не предоставлен."),
    USB_DEVICE_DISCONNECTED("Принтер был отключён."),
    USB_INTERFACE_NOT_FOUND("У принтера не найден подходящий USB-интерфейс."),
    USB_ENDPOINT_NOT_FOUND("У принтера не найден endpoint для передачи данных."),
    USB_CLAIM_FAILED("Не удалось открыть USB-интерфейс принтера."),
    PRINTER_NOT_SUPPORTED("Принтер обнаружен, но USB Print не нашёл совместимый язык печати для этой модели."),
    DOCUMENT_NOT_SUPPORTED("Этот формат пока не поддерживается. Сохраните документ в PDF и попробуйте снова."),
    DOCUMENT_READ_ERROR("Не удалось прочитать выбранный документ."),
    RENDER_ERROR("Не удалось подготовить страницу для печати."),
    TRANSFER_TIMEOUT("Принтер не принял данные вовремя."),
    TRANSFER_ERROR("Не удалось передать задание принтеру."),
    PRINTER_REPORTED_ERROR("Принтер сообщил об ошибке."),
    PORT_STATUS_UNAVAILABLE("Статус принтера недоступен."),
    OUT_OF_MEMORY_PREVENTED("Файл слишком велик для безопасной обработки на этом устройстве."),
    PRINT_CANCELLED("Печать отменена."),
    INVALID_SETTINGS("Проверьте параметры печати."),
    IPP_TRANSPORT_ERROR("Ошибка обмена IPP с принтером по USB."),
    IPP_HTTP_ERROR("Принтер вернул ошибку HTTP при IPP-over-USB."),
    IPP_MALFORMED_RESPONSE("Принтер вернул повреждённый IPP-ответ."),
    IPP_REQUEST_ID_MISMATCH("IPP-ответ относится к другому запросу."),
    IPP_VERSION_NOT_SUPPORTED("Версия IPP принтера не поддерживается."),
    IPP_OPERATION_NOT_SUPPORTED("Принтер не поддерживает требуемую операцию IPP."),
    IPP_CLIENT_ERROR("Принтер отклонил IPP-запрос из-за параметров задания."),
    IPP_SERVER_ERROR("Внутренняя ошибка IPP-сервера принтера."),
    IPP_JOB_REJECTED("Принтер отклонил задание IPP."),
    IPP_DOCUMENT_FORMAT_NOT_SUPPORTED("Принтер не поддерживает формат документа для IPP."),
    IPP_ATTRIBUTE_NOT_SUPPORTED("Принтер не поддерживает один из параметров IPP-задания."),
    IPP_JOB_CANCEL_FAILED("Не удалось отменить IPP-задание на принтере."),
    PROFILE_SAVE_ERROR("Не удалось сохранить локальный профиль проверки принтера."),
    PROFILE_EXPORT_ERROR("Не удалось экспортировать запись совместимости принтера.")
}

class PrintException(val error: AppError, cause: Throwable? = null) : Exception(error.userMessage, cause)
