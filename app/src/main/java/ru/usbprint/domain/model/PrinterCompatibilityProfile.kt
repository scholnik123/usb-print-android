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
