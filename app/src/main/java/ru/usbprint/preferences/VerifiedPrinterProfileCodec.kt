package ru.usbprint.preferences

import java.net.URLDecoder
import java.net.URLEncoder
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.HardwarePrintIssue
import ru.usbprint.domain.model.HardwareTestOutcome
import ru.usbprint.domain.model.HardwareTestRecord
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrinterLanguage
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.VerifiedPrinterProfile
import ru.usbprint.domain.model.VerifiedPrinterStatus

/** Deterministic local DataStore codec. It never contains a raw serial, device key, document name, URI, or payload. */
object VerifiedPrinterProfileCodec {
    fun encode(profile: VerifiedPrinterProfile): String = encodeMap(buildMap {
        put("schema", profile.schemaVersion.toString())
        put("app", profile.appVersion)
        put("encoders", profile.encoderVersions.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}:${it.value}" })
        put("manufacturer", profile.manufacturer.orEmpty())
        put("model", profile.model.orEmpty())
        put("vid", profile.vendorId.toString())
        put("pid", profile.productId.toString())
        put("deviceHash", profile.deviceIdentifierHash)
        put("languages", profile.reportedLanguages.map { it.name }.sorted().joinToString(","))
        put("ippFormats", profile.ippFormats.map(String::lowercase).sorted().joinToString(","))
        put("backend", profile.backend.name)
        put("paper", profile.paper.name)
        put("xdpi", profile.resolution?.horizontalDpi?.toString().orEmpty())
        put("ydpi", profile.resolution?.verticalDpi?.toString().orEmpty())
        put("color", profile.color.name)
        put("duplex", profile.duplex.name)
        put("result", profile.result.name)
        put("issues", profile.issues.map { it.name }.sorted().joinToString(","))
        put("testedAt", profile.testedAtEpochMs.toString())
        put("status", profile.status.name)
        put("historyCount", profile.history.size.toString())
        profile.history.forEachIndexed { index, record -> put("history$index", encodeRecord(record)) }
    })

    fun decode(value: String): VerifiedPrinterProfile? = runCatching {
        val map = decodeMap(value)
        val historyCount = map.getValue("historyCount").toInt()
        val history = (0 until historyCount).map { index -> decodeRecord(map.getValue("history$index")) }
        VerifiedPrinterProfile(
            schemaVersion = map.getValue("schema").toInt(),
            appVersion = map.getValue("app"),
            encoderVersions = csv(map.getValue("encoders")).associate { entry ->
                val (backend, version) = entry.split(':', limit = 2)
                BackendId.valueOf(backend) to version.toInt()
            },
            manufacturer = map.getValue("manufacturer").takeIf(String::isNotBlank),
            model = map.getValue("model").takeIf(String::isNotBlank),
            vendorId = map.getValue("vid").toInt(),
            productId = map.getValue("pid").toInt(),
            deviceIdentifierHash = map.getValue("deviceHash"),
            reportedLanguages = csv(map.getValue("languages")).mapTo(linkedSetOf(), PrinterLanguage::valueOf),
            ippFormats = csv(map.getValue("ippFormats")).mapTo(linkedSetOf(), String::lowercase),
            backend = BackendId.valueOf(map.getValue("backend")),
            paper = PaperSize.valueOf(map.getValue("paper")),
            resolution = resolution(map),
            color = ColorMode.valueOf(map.getValue("color")),
            duplex = DuplexMode.valueOf(map.getValue("duplex")),
            result = HardwareTestOutcome.valueOf(map.getValue("result")),
            issues = csv(map.getValue("issues")).mapTo(linkedSetOf(), HardwarePrintIssue::valueOf),
            testedAtEpochMs = map.getValue("testedAt").toLong(),
            status = VerifiedPrinterStatus.valueOf(map.getValue("status")),
            history = history
        )
    }.getOrNull()

    private fun encodeRecord(record: HardwareTestRecord): String = encodeMap(mapOf(
        "app" to record.appVersion,
        "backend" to record.backend.name,
        "encoder" to record.encoderVersion.toString(),
        "paper" to record.paper.name,
        "xdpi" to record.resolution?.horizontalDpi?.toString().orEmpty(),
        "ydpi" to record.resolution?.verticalDpi?.toString().orEmpty(),
        "color" to record.color.name,
        "duplex" to record.duplex.name,
        "outcome" to record.outcome.name,
        "issues" to record.issues.map { it.name }.sorted().joinToString(","),
        "notes" to record.notes.orEmpty(),
        "testedAt" to record.testedAtEpochMs.toString()
    ))

    private fun decodeRecord(value: String): HardwareTestRecord {
        val map = decodeMap(value)
        return HardwareTestRecord(
            appVersion = map.getValue("app"),
            backend = BackendId.valueOf(map.getValue("backend")),
            encoderVersion = map.getValue("encoder").toInt(),
            paper = PaperSize.valueOf(map.getValue("paper")),
            resolution = resolution(map),
            color = ColorMode.valueOf(map.getValue("color")),
            duplex = DuplexMode.valueOf(map.getValue("duplex")),
            outcome = HardwareTestOutcome.valueOf(map.getValue("outcome")),
            issues = csv(map.getValue("issues")).mapTo(linkedSetOf(), HardwarePrintIssue::valueOf),
            notes = map.getValue("notes").takeIf(String::isNotBlank),
            testedAtEpochMs = map.getValue("testedAt").toLong()
        )
    }

    private fun resolution(map: Map<String, String>): PrinterResolution? =
        map.getValue("xdpi").toIntOrNull()?.let { x -> map.getValue("ydpi").toIntOrNull()?.let { y -> PrinterResolution(x, y) } }

    private fun csv(value: String): List<String> = value.split(',').filter(String::isNotBlank)
    private fun encodeMap(map: Map<String, String>): String = map.entries.sortedBy { it.key }.joinToString("&") { "${escape(it.key)}=${escape(it.value)}" }
    private fun decodeMap(value: String): Map<String, String> = value.split('&').filter { it.contains('=') }.associate { entry ->
        val (key, encoded) = entry.split('=', limit = 2)
        unescape(key) to unescape(encoded)
    }
    private fun escape(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun unescape(value: String) = URLDecoder.decode(value, Charsets.UTF_8.name())
}
