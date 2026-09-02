package ru.usbprint

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.usbprint.domain.logic.BackendDecision
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.CapabilityConfidence
import ru.usbprint.domain.model.CapabilitySource
import ru.usbprint.domain.model.CapabilityValue
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.DocumentKind
import ru.usbprint.domain.model.DocumentRef
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.EffectivePrintCapabilities
import ru.usbprint.domain.model.ExperimentalPrinterOverride
import ru.usbprint.domain.model.HardwareMarginsMm
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PageSelectionKind
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrinterCapabilities
import ru.usbprint.domain.model.PrinterKeywordOption
import ru.usbprint.domain.model.PrinterRef
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.presentation.MainUiState
import ru.usbprint.presentation.theme.UsbPrintTheme
import ru.usbprint.usb.UsbPrinterState

@RunWith(AndroidJUnit4::class)
class ResponsiveLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun compact320_longContentRemainsScrollableAndActionable() {
        var printClicked = false
        composeRule.setContent {
            TestSurface(widthDp = 320, heightDp = 640) {
                MainScreenContent(state = populatedState(), onPrint = { printClicked = true })
            }
        }

        composeRule.onNodeWithText(LONG_PRINTER_NAME).assertExists()
        composeRule.onNodeWithText("Предварительный просмотр недоступен").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Настройки печати").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Печать").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(printClicked)
    }

    @Test
    fun largeFont2_compactWindowKeepsLowerControlsReachable() {
        composeRule.setContent {
            TestSurface(widthDp = 360, heightDp = 640, fontScale = 2f) {
                MainScreenContent(state = populatedState())
            }
        }

        composeRule.onNodeWithText("Выбрать файл").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Создать тестовую страницу A4").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Печать").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun expandedWindow_usesTwoPanes() {
        composeRule.setContent {
            TestSurface(widthDp = 1_024, heightDp = 720) {
                MainScreenContent(state = populatedState())
            }
        }

        val document = composeRule.onNodeWithText("Документ").fetchSemanticsNode().boundsInRoot
        val settings = composeRule.onNodeWithText("Настройки печати").fetchSemanticsNode().boundsInRoot
        assertTrue("actions should be in the right pane", settings.left > document.left)
    }

    @Test
    fun largeFontOnExpandedWindow_reflowsToOneColumn() {
        composeRule.setContent {
            TestSurface(widthDp = 1_024, heightDp = 720, fontScale = 2f) {
                MainScreenContent(state = populatedState())
            }
        }

        val document = composeRule.onNodeWithText("Документ").fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
        val settingsNode = composeRule.onNodeWithText("Настройки печати")
        val settings = settingsNode.fetchSemanticsNode().layoutInfo.coordinates.positionInRoot()
        assertTrue(
            "large text should stack actions below the document: document=$document settings=$settings",
            settings.y > document.y
        )
        settingsNode.performScrollTo().assertIsDisplayed()
    }

    @Test
    fun twentyPrinters_boundedSelectorReachesLastItem() {
        val printers = (1..20).map(::printer)
        var selected: String? = null
        composeRule.setContent {
            UsbPrintTheme(darkTheme = false) {
                PrinterSelectionDialog(
                    printers = printers,
                    selectedDeviceKey = printers.first().deviceKey,
                    onSelect = { selected = it },
                    onDismiss = {}
                )
            }
        }

        val lastName = printers.last().capabilities.displayName
        composeRule.onNodeWithTag("printer_selection_list").performScrollToIndex(printers.lastIndex)
        composeRule.onNodeWithText(lastName).performScrollTo().assertIsDisplayed().performClick()
        assertTrue(selected == printers.last().deviceKey)
    }

    @Test
    fun capabilityExplosion_settingsReachAdvancedControlsAtLargeFont() {
        composeRule.setContent {
            TestSurface(widthDp = 320, heightDp = 640, fontScale = 2f) {
                PrintSettingsDialog(
                    initial = PrintSettings(),
                    pageCount = 999,
                    capabilities = maximumCapabilities(),
                    compatibleBackends = listOf(BackendId.PCL5_RASTER),
                    customPresets = emptyList(),
                    advancedMode = true,
                    initialOverride = ExperimentalPrinterOverride(),
                    onDismiss = {},
                    onSave = {},
                    onApplyPreset = {},
                    onSavePreset = {},
                    onDeletePreset = {},
                    onAdvancedMode = {},
                    onSaveOverride = {}
                )
            }
        }

        composeRule.onNodeWithText("Сохранить переопределение").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Сохранить").assertExists()
        composeRule.onNodeWithText("Отмена").assertExists()
    }

    @Test
    fun diagnostics100Kb_usesOneBoundedScrollableTextSurface() {
        val diagnostic = "diagnostic-line\n".repeat(6_250) + "TAIL_MARKER"
        composeRule.setContent {
            UsbPrintTheme(darkTheme = true) {
                DiagnosticsDialog(
                    text = diagnostic,
                    logs = listOf("one bounded log Text node"),
                    onExportText = {},
                    onExportJson = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithText("TAIL_MARKER", substring = true).assertExists()
        composeRule.onNodeWithText("Копировать").assertIsDisplayed()
        composeRule.onNodeWithText("Экспорт TXT").assertIsDisplayed()
        composeRule.onNodeWithText("Экспорт JSON").assertIsDisplayed()
        composeRule.onNodeWithText("Закрыть").assertIsDisplayed()
    }

    @Composable
    private fun TestSurface(widthDp: Int, heightDp: Int, fontScale: Float = 1f, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
            Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                UsbPrintTheme(darkTheme = false, content = content)
            }
        }
    }

    private fun populatedState(): MainUiState {
        val selected = printer(1, LONG_PRINTER_NAME)
        return MainUiState(
            usb = UsbPrinterState.Ready(selected),
            document = DocumentRef(
                uri = "content://test/document",
                displayName = "A very long document name used to verify wrapping without hiding important controls.pdf",
                mimeType = "application/pdf",
                kind = DocumentKind.PDF,
                sizeBytes = 12_345_678,
                pageCount = 999
            ),
            backend = BackendDecision(BackendId.PCL5_RASTER, listOf(BackendId.PCL5_RASTER))
        )
    }

    private fun printer(index: Int, model: String = "Printer $index with a deliberately long selectable model name"): PrinterRef = PrinterRef(
        deviceKey = "/dev/bus/usb/test/$index",
        capabilities = PrinterCapabilities(
            manufacturer = null,
            model = model,
            vendorId = 0x1200 + index,
            productId = 0x5600 + index,
            usbDeviceId = index
        ),
        interfaceId = 0
    )

    private fun maximumCapabilities(): EffectivePrintCapabilities {
        fun <T> confirmed(value: T) = CapabilityValue(value, CapabilitySource.IPP, CapabilityConfidence.CONFIRMED)
        return EffectivePrintCapabilities(
            backendId = BackendId.PCL5_RASTER,
            copiesRange = confirmed(1..99),
            pageSelections = confirmed(PageSelectionKind.entries.toSet()),
            paperSizes = confirmed(PaperSize.entries.toSet()),
            customPaperRange = null,
            orientations = confirmed(Orientation.entries.toSet()),
            colorModes = confirmed(ColorMode.entries.toSet()),
            duplexModes = confirmed(DuplexMode.entries.toSet()),
            resolutions = confirmed((1..10).mapTo(linkedSetOf()) { PrinterResolution(it * 150) }),
            hardwareMargins = confirmed(HardwareMarginsMm(3f, 3f, 3f, 3f)),
            supportsMargins = true,
            supportsPositioning = true,
            supportsScaling = true,
            supportsCollate = true,
            supportsPageOrder = true,
            supportsNUp = true,
            mediaTypeOptions = confirmed((1..8).mapTo(linkedSetOf()) { PrinterKeywordOption("type-$it", "Тип носителя $it") }),
            mediaSourceOptions = confirmed((1..6).mapTo(linkedSetOf()) { PrinterKeywordOption("tray-$it", "Лоток $it") }),
            outputBinOptions = confirmed((1..4).mapTo(linkedSetOf()) { PrinterKeywordOption("bin-$it", "Выходной лоток $it") })
        )
    }

    private companion object {
        const val LONG_PRINTER_NAME = "Color Laser Printer With An Extremely Long Model Name For Narrow Window Testing"
    }
}
