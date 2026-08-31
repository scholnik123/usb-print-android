package ru.usbprint.domain.logic

import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.EffectivePrintCapabilities
import ru.usbprint.domain.model.PrintPresetId
import ru.usbprint.domain.model.PrintQuality
import ru.usbprint.domain.model.PrintSettings

data class PresetResolution(val settings: PrintSettings, val notes: List<String>)

/** Presets are resolved only from effective capabilities, never from guesses about a printer. */
object PrintPresetResolver {
    fun resolve(id: PrintPresetId, current: PrintSettings, capabilities: EffectivePrintCapabilities): PresetResolution {
        val resolutions = capabilities.resolutions?.value.orEmpty().sortedBy { it.horizontalDpi }
        val colors = capabilities.colorModes?.value.orEmpty()
        val target = when (id) {
            PrintPresetId.AUTO -> current.copy(preset = id, resolutionDpi = null, resolution = null, colorMode = ColorMode.AUTO, quality = PrintQuality.NORMAL)
            PrintPresetId.DRAFT -> resolutions.firstOrNull().let { resolution -> current.copy(preset = id, quality = PrintQuality.DRAFT, resolutionDpi = resolution?.horizontalDpi?.takeIf { it == resolution.verticalDpi }, resolution = resolution, colorMode = colors.prefer(ColorMode.GRAYSCALE, ColorMode.BLACK_ONLY, ColorMode.MONOCHROME) ?: current.colorMode) }
            PrintPresetId.NORMAL -> resolutions.firstOrNull().let { resolution -> current.copy(preset = id, quality = PrintQuality.NORMAL, resolutionDpi = resolution?.horizontalDpi?.takeIf { it == resolution.verticalDpi }, resolution = resolution) }
            PrintPresetId.HIGH -> resolutions.lastOrNull().let { resolution -> current.copy(preset = id, quality = PrintQuality.HIGH, resolutionDpi = resolution?.horizontalDpi?.takeIf { it == resolution.verticalDpi }, resolution = resolution) }
            PrintPresetId.PHOTO -> resolutions.lastOrNull().let { resolution -> current.copy(preset = id, quality = PrintQuality.HIGH, resolutionDpi = resolution?.horizontalDpi?.takeIf { it == resolution.verticalDpi }, resolution = resolution, colorMode = colors.prefer(ColorMode.COLOR) ?: current.colorMode) }
            PrintPresetId.TEXT -> current.copy(preset = id, quality = PrintQuality.NORMAL, colorMode = colors.prefer(ColorMode.BLACK_ONLY, ColorMode.MONOCHROME, ColorMode.GRAYSCALE) ?: current.colorMode)
            PrintPresetId.CUSTOM -> current.copy(preset = id)
        }
        val notes = buildList {
            if (id == PrintPresetId.PHOTO && ColorMode.COLOR !in colors) add("Цвет не подтверждён — пресет «Фото» не включает цветной режим.")
            if (resolutions.isEmpty()) add("Разрешение принтера не подтверждено; пресет его не меняет.")
        }
        return PresetResolution(target, notes)
    }

    private fun Set<ColorMode>.prefer(vararg choices: ColorMode): ColorMode? = choices.firstOrNull { it in this }
}
