package ru.usbprint.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ru.usbprint.domain.logic.PageRangeParser
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.ContentPosition
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.ExperimentalPrinterOverride
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PageOrder
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintMarginsMm
import ru.usbprint.domain.model.PrintPresetId
import ru.usbprint.domain.model.PrintQuality
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.ScalingMode
import ru.usbprint.domain.model.VerifiedPrinterProfile
import ru.usbprint.domain.model.VerifiedPrinterProfileFactory
import ru.usbprint.printing.PrintingEncoderVersions
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

private val Context.printPreferencesDataStore by preferencesDataStore(name = "usb_print_preferences")

data class SavedPrintPreset(val id: String, val name: String, val settings: PrintSettings)

/** Local-only DataStore for opt-in advanced overrides and user-created presets. */
class PrintPreferencesRepository(private val context: Context) {
    val advancedMode: Flow<Boolean> = context.printPreferencesDataStore.data.map { it[ADVANCED_MODE] ?: false }
    val customPresets: Flow<List<SavedPrintPreset>> = context.printPreferencesDataStore.data.map { preferences ->
        preferences.asMap().entries.mapNotNull { (key, value) ->
            if (!key.name.startsWith(PRESET_PREFIX) || value !is String) null
            else PrintSettingsCodec.decode(value)?.let { SavedPrintPreset(key.name.removePrefix(PRESET_PREFIX), it.first, it.second) }
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun setAdvancedMode(enabled: Boolean) { context.printPreferencesDataStore.edit { it[ADVANCED_MODE] = enabled } }

    suspend fun saveCustomPreset(name: String, settings: PrintSettings): SavedPrintPreset {
        val preset = SavedPrintPreset(UUID.randomUUID().toString(), name.trim().ifBlank { "Мой пресет" }, settings.copy(preset = PrintPresetId.CUSTOM))
        context.printPreferencesDataStore.edit { it[stringPreferencesKey(PRESET_PREFIX + preset.id)] = PrintSettingsCodec.encode(preset.name, preset.settings) }
        return preset
    }

    suspend fun deleteCustomPreset(id: String) { context.printPreferencesDataStore.edit { it.remove(stringPreferencesKey(PRESET_PREFIX + id)) } }

    suspend fun overrideFor(deviceKey: String): ExperimentalPrinterOverride? = context.printPreferencesDataStore.data.first()[stringPreferencesKey(OVERRIDE_PREFIX + deviceHash(deviceKey))]?.let(PrintSettingsCodec::decodeOverride)

    suspend fun saveOverride(deviceKey: String, override: ExperimentalPrinterOverride) {
        context.printPreferencesDataStore.edit { preferences ->
            val key = stringPreferencesKey(OVERRIDE_PREFIX + deviceHash(deviceKey))
            if (override.isEmpty) preferences.remove(key) else preferences[key] = PrintSettingsCodec.encodeOverride(override)
        }
    }

    suspend fun verifiedProfileFor(deviceKey: String): VerifiedPrinterProfile? {
        val key = stringPreferencesKey(PROFILE_PREFIX + deviceHash(deviceKey))
        val stored = context.printPreferencesDataStore.data.first()[key]?.let(VerifiedPrinterProfileCodec::decode) ?: return null
        val checked = VerifiedPrinterProfileFactory.revalidate(stored, PrintingEncoderVersions.current)
        if (checked != stored) context.printPreferencesDataStore.edit { it[key] = VerifiedPrinterProfileCodec.encode(checked) }
        return checked
    }

    suspend fun saveVerifiedProfile(deviceKey: String, profile: VerifiedPrinterProfile) {
        context.printPreferencesDataStore.edit {
            it[stringPreferencesKey(PROFILE_PREFIX + deviceHash(deviceKey))] = VerifiedPrinterProfileCodec.encode(profile)
        }
    }

    private fun deviceHash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private companion object {
        val ADVANCED_MODE = booleanPreferencesKey("advanced_mode")
        const val PRESET_PREFIX = "preset_"
        const val OVERRIDE_PREFIX = "override_"
        const val PROFILE_PREFIX = "verified_profile_"
    }
}

/** Compact URL-style codec: no document content or URI is ever persisted in printer preferences. */
object PrintSettingsCodec {
    fun encode(name: String, settings: PrintSettings): String = buildMap<String, String> {
        put("name", name); put("copies", settings.copies.toString()); put("paper", settings.paperSize.name)
        put("orientation", settings.orientation.name); put("color", settings.colorMode.name); put("duplex", settings.duplexMode.name)
        put("scaling", settings.scalingMode.name); put("quality", settings.quality.name); put("dpi", settings.resolutionDpi?.toString().orEmpty())
        put("xdpi", settings.resolution?.horizontalDpi?.toString().orEmpty()); put("ydpi", settings.resolution?.verticalDpi?.toString().orEmpty())
        put("margins", listOf(settings.margins.left, settings.margins.top, settings.margins.right, settings.margins.bottom).joinToString(","))
        put("position", settings.contentPosition.name); put("scale", settings.customScalePercent?.toString().orEmpty())
        put("order", settings.pageOrder.name); put("collate", settings.collate.toString()); put("nup", settings.pagesPerSheet.toString())
        put("nupSpacing", settings.nUpSpacingMm.toString()); put("nupBorders", settings.nUpDrawBorders.toString()); put("nupRotate", settings.nUpAutoRotate.toString())
        put("mediaTypeKeyword", settings.mediaTypeKeyword.orEmpty()); put("mediaSourceKeyword", settings.mediaSourceKeyword.orEmpty())
        put("outputBinKeyword", settings.outputBinKeyword.orEmpty())
        when (val pages = settings.pageSelection) {
            PageSelection.All -> put("pages", "all")
            PageSelection.Current -> put("pages", "current")
            PageSelection.Odd -> put("pages", "odd")
            PageSelection.Even -> put("pages", "even")
            is PageSelection.Ranges -> put("pages", "ranges:${pages.raw}")
        }
    }.entries.joinToString("&") { "${escape(it.key)}=${escape(it.value)}" }

    fun decode(value: String): Pair<String, PrintSettings>? = runCatching {
        val map = parse(value)
        val margins = map.getValue("margins").split(',').map(String::toFloat)
        val pageRaw = map["pages"].orEmpty()
        val pages = when {
            pageRaw == "all" -> PageSelection.All
            pageRaw == "current" -> PageSelection.Current
            pageRaw == "odd" -> PageSelection.Odd
            pageRaw == "even" -> PageSelection.Even
            pageRaw.startsWith("ranges:") -> PageRangeParser.parse(pageRaw.removePrefix("ranges:")).getOrThrow().let { PageSelection.Ranges(pageRaw.removePrefix("ranges:"), it) }
            else -> PageSelection.All
        }
        val exactResolution = map["xdpi"]?.toIntOrNull()?.let { x -> map["ydpi"]?.toIntOrNull()?.let { y -> PrinterResolution(x, y) } }
        map.getValue("name") to PrintSettings(
            copies = map.getValue("copies").toInt(), pageSelection = pages, paperSize = enum(map, "paper", PaperSize.AUTO),
            orientation = enum(map, "orientation", Orientation.AUTO), colorMode = enum(map, "color", ColorMode.AUTO),
            duplexMode = enum(map, "duplex", DuplexMode.OFF), scalingMode = enum(map, "scaling", ScalingMode.FIT),
            quality = enum(map, "quality", PrintQuality.NORMAL), resolutionDpi = map["dpi"]?.toIntOrNull(), resolution = exactResolution,
            marginsMm = margins.first(), margins = PrintMarginsMm(margins[0], margins[1], margins[2], margins[3]),
            contentPosition = enum(map, "position", ContentPosition.CENTER), customScalePercent = map["scale"]?.toIntOrNull(),
            pageOrder = enum(map, "order", PageOrder.NORMAL), collate = map["collate"].toBoolean(), pagesPerSheet = map["nup"]?.toIntOrNull() ?: 1,
            nUpSpacingMm = map["nupSpacing"]?.toFloatOrNull() ?: 3f,
            nUpDrawBorders = map["nupBorders"].toBoolean(), nUpAutoRotate = map["nupRotate"]?.toBooleanStrictOrNull() ?: true,
            mediaTypeKeyword = map["mediaTypeKeyword"]?.takeIf(String::isNotBlank),
            mediaSourceKeyword = map["mediaSourceKeyword"]?.takeIf(String::isNotBlank),
            outputBinKeyword = map["outputBinKeyword"]?.takeIf(String::isNotBlank),
            preset = PrintPresetId.CUSTOM
        )
    }.getOrNull()

    fun encodeOverride(value: ExperimentalPrinterOverride): String = buildMap<String, String> {
        value.forcedBackend?.let { put("backend", it.name) }
        value.forcedResolution?.let { put("dpi", "${it.horizontalDpi},${it.verticalDpi}") }
        if (value.forceMonochrome) put("mono", "true")
        value.forcedPaper?.let { put("paper", it.name) }
        value.forcedMargins?.let { put("margins", listOf(it.left, it.top, it.right, it.bottom).joinToString(",")) }
    }.entries.joinToString("&") { "${escape(it.key)}=${escape(it.value)}" }

    fun decodeOverride(value: String): ExperimentalPrinterOverride? = runCatching {
        val map = parse(value); val dpi = map["dpi"]?.split(',')?.map(String::toInt)
        val margins = map["margins"]?.split(',')?.map(String::toFloat)
        ExperimentalPrinterOverride(
            forcedBackend = map["backend"]?.let { BackendId.valueOf(it) },
            forcedResolution = dpi?.let { PrinterResolution(it[0], it[1]) }, forceMonochrome = map["mono"].toBoolean(),
            forcedPaper = map["paper"]?.let { PaperSize.valueOf(it) },
            forcedMargins = margins?.let { HardwareMarginsMm(it[0], it[1], it[2], it[3]) }
        )
    }.getOrNull()

    private fun parse(value: String): Map<String, String> = value.split('&').filter { it.contains('=') }.associate {
        val (key, encoded) = it.split('=', limit = 2); unescape(key) to unescape(encoded)
    }
    private fun escape(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun unescape(value: String) = URLDecoder.decode(value, Charsets.UTF_8.name())
    private inline fun <reified T : Enum<T>> enum(map: Map<String, String>, key: String, default: T): T = map[key]?.let { enumValues<T>().firstOrNull { candidate -> candidate.name == it } } ?: default
}
