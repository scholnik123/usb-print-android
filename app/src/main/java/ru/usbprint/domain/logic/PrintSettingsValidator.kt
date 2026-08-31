package ru.usbprint.domain.logic

import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.EffectivePrintCapabilities
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PageSelectionKind
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.ScalingMode

sealed interface SettingsValidation {
    data class Valid(val settings: PrintSettings) : SettingsValidation
    /** Caller must present these changes and only apply them after confirmation. */
    data class ValidWithAdjustments(val settings: PrintSettings, val adjustments: List<String>) : SettingsValidation
    data class Invalid(val errors: List<String>) : SettingsValidation
}

/** Validates selections against the effective device/backend intersection. Nothing is silently changed. */
object PrintSettingsValidator {
    fun validate(settings: PrintSettings, effective: EffectivePrintCapabilities, pageCount: Int?): SettingsValidation {
        val errors = mutableListOf<String>()
        val copyRange = effective.copiesRange?.value
        if (copyRange == null || settings.copies !in copyRange) errors += "Количество копий должно быть от ${copyRange?.first ?: "?"} до ${copyRange?.last ?: "?"}."
        val selectedKind = settings.pageSelection.kind()
        if (selectedKind !in effective.pageSelections?.value.orEmpty()) errors += "Выбранный режим страниц недоступен для ${effective.backendId.title}."
        if (settings.paperSize != PaperSize.AUTO && settings.paperSize !in effective.paperSizes?.value.orEmpty()) errors += "Формат ${settings.paperSize.label} не подтверждён для выбранного принтера и backend."
        if (settings.orientation != ru.usbprint.domain.model.Orientation.AUTO && settings.orientation !in effective.orientations?.value.orEmpty()) errors += "Ориентация недоступна для выбранного backend."
        if (settings.colorMode != ColorMode.AUTO && settings.colorMode !in effective.colorModes?.value.orEmpty()) errors += "Выбранный цветовой режим не подтверждён."
        if (settings.duplexMode != DuplexMode.OFF && settings.duplexMode !in effective.duplexModes?.value.orEmpty()) errors += "Двусторонняя печать не подтверждена."
        if (settings.selectedResolution != null && settings.selectedResolution !in effective.resolutions?.value.orEmpty()) errors += "Выбранное разрешение не подтверждено."
        if (settings.scalingMode == ScalingMode.CUSTOM && settings.effectiveScalePercent == null) errors += "Пользовательский масштаб должен быть от 10 до 400%."
        if (!effective.supportsMargins && settings.margins != PrintSettings().margins) errors += "Поля не поддерживаются выбранным backend."
        if (settings.pagesPerSheet !in setOf(1, 2, 4)) errors += "На листе может быть 1, 2 или 4 страницы."
        if (settings.pagesPerSheet > 1 && !effective.supportsNUp) errors += "N-up ещё не реализован для выбранного backend."
        if (!settings.collate && !effective.supportsCollate) errors += "Разборка по копиям недоступна для выбранного backend."
        if (settings.pageOrder != ru.usbprint.domain.model.PageOrder.NORMAL && !effective.supportsPageOrder) errors += "Обратный порядок страниц недоступен для выбранного backend."
        if (settings.mediaType != null && settings.mediaType !in effective.mediaTypes?.value.orEmpty()) errors += "Тип носителя не подтверждён."
        if (settings.mediaSource != null && settings.mediaSource !in effective.mediaSources?.value.orEmpty()) errors += "Источник бумаги не подтверждён."
        if (settings.outputBin != null && settings.outputBin !in effective.outputBins?.value.orEmpty()) errors += "Выходной лоток не подтверждён."
        if (settings.mediaTypeKeyword != null && effective.mediaTypeOptions?.value?.none { it.rawKeyword == settings.mediaTypeKeyword } != false) errors += "IPP-тип носителя не подтверждён."
        if (settings.mediaSourceKeyword != null && effective.mediaSourceOptions?.value?.none { it.rawKeyword == settings.mediaSourceKeyword } != false) errors += "IPP-источник бумаги не подтверждён."
        if (settings.outputBinKeyword != null && effective.outputBinOptions?.value?.none { it.rawKeyword == settings.outputBinKeyword } != false) errors += "IPP-выходной лоток не подтверждён."
        if (pageCount != null && settings.pageSelection is PageSelection.Ranges) {
            val ranges = settings.pageSelection.pages
            if (ranges.isEmpty() || ranges.any { it.first < 1 || it.last > pageCount }) errors += "Диапазон страниц выходит за пределы документа."
        }
        return if (errors.isEmpty()) SettingsValidation.Valid(settings) else SettingsValidation.Invalid(errors)
    }

    /** Compatibility helper for v2.0 callers and tests that do not have a connected printer. */
    fun validate(settings: PrintSettings, pageCount: Int?): String? {
        if (settings.copies !in 1..99) return "Количество копий должно быть от 1 до 99."
        if (settings.marginsMm !in 0f..30f || listOf(settings.margins.left, settings.margins.top, settings.margins.right, settings.margins.bottom).any { it !in 0f..60f }) return "Поля должны быть в допустимых пределах."
        if (settings.scalingMode == ScalingMode.CUSTOM && settings.effectiveScalePercent == null) return "Пользовательский масштаб должен быть от 10 до 400%."
        if (pageCount != null && settings.pageSelection is PageSelection.Ranges) {
            val ranges = settings.pageSelection.pages
            if (ranges.isEmpty() || ranges.any { it.first < 1 || it.last > pageCount }) return "Диапазон страниц выходит за пределы документа."
        }
        return null
    }

    private fun PageSelection.kind() = when (this) {
        PageSelection.All, PageSelection.Current -> PageSelectionKind.ALL
        PageSelection.Odd -> PageSelectionKind.ODD
        PageSelection.Even -> PageSelectionKind.EVEN
        is PageSelection.Ranges -> PageSelectionKind.RANGES
    }
}
