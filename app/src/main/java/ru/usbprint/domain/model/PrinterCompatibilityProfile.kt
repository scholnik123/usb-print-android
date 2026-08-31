package ru.usbprint.domain.model

/** A diagnostic record format for genuine future hardware observations; the app ships with no invented profiles. */
data class PrinterCompatibilityProfile(
    val manufacturer: String?,
    val model: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceIdHash: String?,
    val languages: Set<PrinterLanguage>,
    val testedBackend: BackendId? = null,
    val result: HardwareTestResult? = null,
    val notes: String? = null
)

enum class HardwareTestResult { SUCCESS, FAILED, PARTIAL, NOT_TESTED }

/** User-observed physical outcome. Transport or IPP completion never creates one automatically. */
enum class HardwareTestOutcome(val label: String) {
    PRINTED_CORRECTLY("Напечатано правильно"),
    PRINTED_WITH_ISSUES("Напечатано с проблемами"),
    ACCEPTED_NO_PAGE("Задание принято, но страница не вышла"),
    PRINTER_ERROR("Ошибка принтера"),
    NOTHING_HAPPENED("Ничего не произошло"),
    CONNECTION_LOST("Соединение потеряно"),
    OTHER("Другое")
}

enum class HardwarePrintIssue(val label: String) {
    CROPPED("Обрезано"),
    WRONG_PAPER_SIZE("Неверный размер бумаги"),
    WRONG_ORIENTATION("Неверная ориентация"),
    WRONG_COLORS("Неверные цвета"),
    WRONG_GRAYSCALE("Неверные оттенки серого"),
    BLANK_PAGE("Пустая страница"),
    GARBAGE_OUTPUT("Нечитаемые символы / мусор"),
    EXTRA_PAGES("Лишние страницы"),
    INCORRECT_SCALE("Неверный масштаб"),
    INCORRECT_MARGINS("Неверные поля"),
    INCORRECT_DUPLEX("Неверная двусторонняя печать")
}

data class HardwareTestObservation(
    val outcome: HardwareTestOutcome,
    val issues: Set<HardwarePrintIssue> = emptySet(),
    val notes: String? = null,
    val observedAtEpochMs: Long = System.currentTimeMillis()
) {
    init {
        require(outcome == HardwareTestOutcome.PRINTED_WITH_ISSUES || issues.isEmpty())
        require(outcome != HardwareTestOutcome.PRINTED_WITH_ISSUES || issues.isNotEmpty())
        require(outcome != HardwareTestOutcome.OTHER || !notes.isNullOrBlank())
    }
}
