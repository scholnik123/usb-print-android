package ru.usbprint.domain.model

import java.security.MessageDigest

const val VERIFIED_PRINTER_PROFILE_SCHEMA_VERSION = 1
const val MAX_PROFILE_HISTORY = 20

enum class VerifiedPrinterStatus(val label: String) {
    UNTESTED("Не подтверждено"),
    USER_CONFIRMED("Подтверждено пользователем"),
    MULTIPLE_TESTS_CONFIRMED("Подтверждено несколькими тестами"),
    PARTIAL("Работает с ограничениями"),
    FAILED("Тест не пройден"),
    NEEDS_REVALIDATION("Требуется повторная проверка")
}

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
        require(notes == null || notes.length <= 500)
        require(observedAtEpochMs > 0)
    }
}

data class HardwareTestRecord(
    val appVersion: String,
    val backend: BackendId,
    val encoderVersion: Int,
    val paper: PaperSize,
    val resolution: PrinterResolution?,
    val color: ColorMode,
    val duplex: DuplexMode,
    val outcome: HardwareTestOutcome,
    val issues: Set<HardwarePrintIssue>,
    val notes: String?,
    val testedAtEpochMs: Long
) {
    init {
        require(appVersion.isNotBlank())
        require(backend != BackendId.NONE)
        require(encoderVersion > 0)
        require(testedAtEpochMs > 0)
        require(outcome == HardwareTestOutcome.PRINTED_WITH_ISSUES || issues.isEmpty())
        require(outcome != HardwareTestOutcome.PRINTED_WITH_ISSUES || issues.isNotEmpty())
        require(outcome != HardwareTestOutcome.OTHER || !notes.isNullOrBlank())
        require(notes == null || notes.length <= 500)
    }
}

/** Versioned, local-only compatibility evidence. Raw serial and Android device key are never stored. */
data class VerifiedPrinterProfile(
    val schemaVersion: Int = VERIFIED_PRINTER_PROFILE_SCHEMA_VERSION,
    val appVersion: String,
    val encoderVersions: Map<BackendId, Int>,
    val manufacturer: String?,
    val model: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceIdentifierHash: String,
    val reportedLanguages: Set<PrinterLanguage>,
    val ippFormats: Set<String>,
    val backend: BackendId,
    val paper: PaperSize,
    val resolution: PrinterResolution?,
    val color: ColorMode,
    val duplex: DuplexMode,
    val result: HardwareTestOutcome,
    val issues: Set<HardwarePrintIssue>,
    val testedAtEpochMs: Long,
    val status: VerifiedPrinterStatus,
    val history: List<HardwareTestRecord>
) {
    init {
        require(schemaVersion > 0)
        require(appVersion.isNotBlank())
        require(encoderVersions.isNotEmpty() && encoderVersions.values.all { it > 0 })
        require(vendorId in 0..0xffff && productId in 0..0xffff)
        require(deviceIdentifierHash.matches(Regex("[0-9a-f]{64}")))
        require(backend != BackendId.NONE)
        require(history.isNotEmpty() && history.size <= MAX_PROFILE_HISTORY)
        require(testedAtEpochMs > 0)
        val latest = history.last()
        require(backend == latest.backend && encoderVersions[backend] == latest.encoderVersion)
        require(paper == latest.paper && resolution == latest.resolution && color == latest.color && duplex == latest.duplex)
        require(result == latest.outcome && issues == latest.issues && testedAtEpochMs == latest.testedAtEpochMs)
    }
}

object VerifiedPrinterProfileFactory {
    fun record(
        existing: VerifiedPrinterProfile?,
        printer: PrinterCapabilities,
        deviceKey: String,
        appVersion: String,
        backend: BackendId,
        encoderVersion: Int,
        settings: PrintSettings,
        observation: HardwareTestObservation
    ): VerifiedPrinterProfile {
        val identifierHash = PrinterIdentityHasher.hash(printer, deviceKey)
        val matching = existing?.takeIf { it.deviceIdentifierHash == identifierHash }
        val record = HardwareTestRecord(
            appVersion = appVersion,
            backend = backend,
            encoderVersion = encoderVersion,
            paper = settings.paperSize,
            resolution = settings.selectedResolution,
            color = settings.colorMode,
            duplex = settings.duplexMode,
            outcome = observation.outcome,
            issues = observation.issues,
            notes = observation.notes,
            testedAtEpochMs = observation.observedAtEpochMs
        )
        val history = (matching?.history.orEmpty() + record).takeLast(MAX_PROFILE_HISTORY)
        val status = when (observation.outcome) {
            HardwareTestOutcome.PRINTED_CORRECTLY -> history.filter {
                it.backend == backend && it.encoderVersion == encoderVersion
            }.let { matching ->
                if (matching.size >= 2 && matching.all { it.outcome == HardwareTestOutcome.PRINTED_CORRECTLY }) {
                    VerifiedPrinterStatus.MULTIPLE_TESTS_CONFIRMED
                } else VerifiedPrinterStatus.USER_CONFIRMED
            }
            HardwareTestOutcome.PRINTED_WITH_ISSUES -> VerifiedPrinterStatus.PARTIAL
            HardwareTestOutcome.OTHER -> VerifiedPrinterStatus.UNTESTED
            else -> VerifiedPrinterStatus.FAILED
        }
        return VerifiedPrinterProfile(
            appVersion = appVersion,
            encoderVersions = history.associate { it.backend to it.encoderVersion },
            manufacturer = printer.manufacturer ?: matching?.manufacturer,
            model = printer.model ?: matching?.model,
            vendorId = printer.vendorId,
            productId = printer.productId,
            deviceIdentifierHash = identifierHash,
            reportedLanguages = printer.supportedLanguages,
            ippFormats = printer.ipp.documentFormatsSupported,
            backend = backend,
            paper = settings.paperSize,
            resolution = settings.selectedResolution,
            color = settings.colorMode,
            duplex = settings.duplexMode,
            result = observation.outcome,
            issues = observation.issues,
            testedAtEpochMs = observation.observedAtEpochMs,
            status = status,
            history = history
        )
    }

    fun revalidate(profile: VerifiedPrinterProfile, currentEncoderVersions: Map<BackendId, Int>): VerifiedPrinterProfile {
        val schemaChanged = profile.schemaVersion != VERIFIED_PRINTER_PROFILE_SCHEMA_VERSION
        val encoderChanged = profile.encoderVersions.any { (backend, recorded) -> currentEncoderVersions[backend] != recorded }
        return if (schemaChanged || encoderChanged) profile.copy(status = VerifiedPrinterStatus.NEEDS_REVALIDATION) else profile
    }
}

object PrinterIdentityHasher {
    fun hash(printer: PrinterCapabilities, deviceKey: String): String {
        val stableIdentifier = printer.serialNumber?.takeIf(String::isNotBlank) ?: deviceKey
        val input = "usb-print-profile|${printer.vendorId}|${printer.productId}|$stableIdentifier"
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
