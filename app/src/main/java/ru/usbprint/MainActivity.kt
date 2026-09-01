@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.usbprint

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.usbprint.domain.logic.PageRangeParser
import ru.usbprint.domain.logic.BackendRegistry
import ru.usbprint.domain.logic.PrintPresetResolver
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.ContentPosition
import ru.usbprint.domain.model.CustomPaperSizeMicrons
import ru.usbprint.domain.model.DuplexMode
import ru.usbprint.domain.model.Orientation
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PaperSize
import ru.usbprint.domain.model.PrintJobStatus
import ru.usbprint.domain.model.PrintQuality
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.PrintPresetId
import ru.usbprint.domain.model.PageOrder
import ru.usbprint.domain.model.EffectivePrintCapabilities
import ru.usbprint.domain.model.ExperimentalPrinterOverride
import ru.usbprint.domain.model.HardwarePrintIssue
import ru.usbprint.domain.model.HardwareTestOutcome
import ru.usbprint.domain.model.Microns
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrinterRef
import ru.usbprint.domain.model.PrinterKeywordOption
import ru.usbprint.domain.model.ScalingMode
import ru.usbprint.presentation.MainUiState
import ru.usbprint.presentation.MainViewModel
import ru.usbprint.presentation.AdaptiveContentContainer
import ru.usbprint.presentation.AdaptiveTwoColumnFields
import ru.usbprint.presentation.AdaptiveWidthClass
import ru.usbprint.presentation.ResponsiveChoiceFlow
import ru.usbprint.presentation.theme.UsbPrintTheme
import ru.usbprint.usb.UsbPrinterState
import java.math.BigDecimal
import java.math.RoundingMode

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.factory((application as UsbPrintApplication).container) }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::loadDocument) }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val exportDiagnosticsTxt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { viewModel.exportDiagnostics(it, false) } }
    private val exportDiagnosticsJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { viewModel.exportDiagnostics(it, true) } }
    private val exportCompatibilityJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(viewModel::exportCompatibilityRecord) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            handleIncomingIntent(intent)
        }
        setContent {
            UsbPrintTheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme()) {
                UsbPrintScreen(
                    viewModel = viewModel,
                    onSelect = { openDocument.launch(SUPPORTED_MIME_TYPES) },
                    onExportText = { exportDiagnosticsTxt.launch("USB-Print-diagnostics.txt") },
                    onExportJson = { exportDiagnosticsJson.launch("USB-Print-diagnostics.json") },
                    onExportCompatibility = { exportCompatibilityJson.launch("USB-Print-compatibility.json") }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIncomingIntent(intent) }
    private fun handleIncomingIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else @Suppress("DEPRECATION") { intent.getParcelableExtra(Intent.EXTRA_STREAM) }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } as? android.net.Uri
        uri?.let { viewModel.loadDocument(it) }
    }

    private companion object {
        val SUPPORTED_MIME_TYPES = arrayOf("application/pdf", "image/*", "text/plain", "application/postscript", "application/vnd.hp-PCL")
    }
}

@Composable
private fun UsbPrintScreen(
    viewModel: MainViewModel,
    onSelect: () -> Unit,
    onExportText: () -> Unit,
    onExportJson: () -> Unit,
    onExportCompatibility: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.error) { state.error?.let { errorMessage = it.userMessage; viewModel.clearError() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("USB Print") }, actions = { IconButton(onClick = { showDiagnostics = true }) { Icon(Icons.Default.Usb, "Информация о принтере") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
        contentWindowInsets = WindowInsets.safeDrawing
    ) { padding ->
        AdaptiveContentContainer(
            Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
        ) { widthClass ->
            Spacer(Modifier.height(2.dp))
            PrinterCard(state, viewModel::requestUsbPermission, viewModel::refreshPrinter, viewModel::selectPrinter, widthClass == AdaptiveWidthClass.COMPACT)
            DocumentCard(state, onSelect, viewModel::loadHardwareTestPage)
            if (state.document != null) {
                OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth(), enabled = !isJobRunning(state.jobStatus)) { Text("Настройки печати") }
                Text("Режим печати: ${state.backend.selected.title}", style = MaterialTheme.typography.bodyMedium, color = if (state.backend.selected == BackendId.NONE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                state.backend.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            PrintAction(state, viewModel::print, viewModel::cancelPrint)
            if (state.hardwareTestAwaitingResult) {
                OutlinedButton(onClick = viewModel::openHardwareTestWizard, modifier = Modifier.fillMaxWidth()) {
                    Text("Оценить результат тестовой печати")
                }
            }
            state.lastHardwareTestObservation?.let { observation ->
                Text("Последняя оценка теста: ${observation.outcome.label}", style = MaterialTheme.typography.bodySmall)
            }
            state.verifiedPrinterProfile?.let { profile ->
                Text("Профиль совместимости: ${profile.status.label} · ${profile.history.size} набл.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onExportCompatibility, modifier = Modifier.fillMaxWidth()) {
                    Text("Экспорт записи совместимости JSON")
                }
                Text("Просмотрите JSON перед публикацией: комментарии и идентификаторы в него не включаются.", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
    if (showSettings) PrintSettingsDialog(
        initial = state.settings, pageCount = state.document?.pageCount, capabilities = state.effectiveCapabilities,
        compatibleBackends = state.backend.compatible, customPresets = state.customPresets, advancedMode = state.advancedMode, initialOverride = state.printerOverride,
        onDismiss = { showSettings = false }, onSave = { viewModel.updateSettings(it); showSettings = false },
        onApplyPreset = viewModel::applyPreset, onSavePreset = viewModel::saveCustomPreset, onDeletePreset = viewModel::deleteCustomPreset,
        onAdvancedMode = viewModel::setAdvancedMode, onSaveOverride = viewModel::saveExperimentalOverride
    )
    if (showDiagnostics) DiagnosticsDialog(viewModel.diagnostics(), logs, onExportText, onExportJson, onDismiss = { showDiagnostics = false })
    errorMessage?.let { message -> ErrorDialog(message, onDismiss = { errorMessage = null }) }
    if (state.showHardwareTestWizard) HardwareTestResultDialog(
        onDismiss = viewModel::dismissHardwareTestWizard,
        onSave = viewModel::recordHardwareTestObservation
    )
}

@Composable
private fun HardwareTestResultDialog(
    onDismiss: () -> Unit,
    onSave: (HardwareTestOutcome, Set<HardwarePrintIssue>, String?) -> Unit
) {
    var outcomeName by rememberSaveable { mutableStateOf<String?>(null) }
    var issueNamesValue by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    val outcome = outcomeName?.let { name -> HardwareTestOutcome.entries.firstOrNull { it.name == name } }
    val issues = issueNamesValue.split('|').mapNotNull { name -> HardwarePrintIssue.entries.firstOrNull { it.name == name } }.toSet()
    val canSave = outcome != null &&
        (outcome != HardwareTestOutcome.PRINTED_WITH_ISSUES || issues.isNotEmpty()) &&
        (outcome != HardwareTestOutcome.OTHER || notes.isNotBlank())

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Что произошло на бумаге?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SENT или IPP completed не подтверждают физическую печать. Выберите только то, что вы увидели.")
                if (outcome == null) {
                    HardwareTestOutcome.entries.forEach { item ->
                        OutlinedButton(onClick = { outcomeName = item.name }, modifier = Modifier.fillMaxWidth()) { Text(item.label) }
                    }
                } else {
                    Text("Результат: ${outcome?.label}", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { outcomeName = null; issueNamesValue = "" }) { Text("Изменить результат") }
                    if (outcome == HardwareTestOutcome.PRINTED_WITH_ISSUES) {
                        Text("Отметьте все замеченные проблемы:")
                        ResponsiveChoiceFlow {
                            HardwarePrintIssue.entries.forEach { issue ->
                                FilterChip(
                                    selected = issue in issues,
                                    onClick = { issueNamesValue = (if (issue in issues) issues - issue else issues + issue).joinToString("|") { it.name } },
                                    label = { Text(issue.label) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it.take(500) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (outcome == HardwareTestOutcome.OTHER) "Описание (обязательно)" else "Комментарий (необязательно)") },
                        supportingText = { Text("Не указывайте имя документа, серийный номер или другие личные данные.") }
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { outcome?.let { onSave(it, issues, notes) } }, enabled = canSave) { Text("Сохранить наблюдение") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Позже") } }
    )
}

@Composable private fun PrinterCard(
    state: MainUiState,
    onPermission: () -> Unit,
    onRefresh: () -> Unit,
    onSelectPrinter: (String) -> Unit,
    stackAction: Boolean
) {
    var showPrinterSelector by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column {
            if (stackAction) {
                Row(Modifier.padding(start = 18.dp, top = 18.dp, end = 18.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PrinterIcon()
                    PrinterSummary(state, Modifier.weight(1f))
                }
                PrinterStateAction(state, onPermission, onRefresh, Modifier.padding(horizontal = 18.dp, vertical = 12.dp).fillMaxWidth())
            } else {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PrinterIcon()
                    PrinterSummary(state, Modifier.weight(1f))
                    PrinterStateAction(state, onPermission, onRefresh)
                }
            }
            val available = when (val usb = state.usb) {
                is UsbPrinterState.Ready -> usb.printers
                is UsbPrinterState.PermissionRequired -> usb.printers
                is UsbPrinterState.Connecting -> usb.printers
                is UsbPrinterState.Error -> usb.printers
                else -> emptyList()
            }
            if (available.size > 1) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Доступно принтеров: ${available.size}", style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { showPrinterSelector = true }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать принтер") }
                }
            }
        }
    }
    if (showPrinterSelector) {
        PrinterSelectionDialog(
            printers = availablePrinters(state),
            selectedDeviceKey = (state.usb as? UsbPrinterState.Ready)?.printer?.deviceKey,
            onSelect = { deviceKey -> showPrinterSelector = false; onSelectPrinter(deviceKey) },
            onDismiss = { showPrinterSelector = false }
        )
    }
}

private fun availablePrinters(state: MainUiState): List<PrinterRef> = when (val usb = state.usb) {
    is UsbPrinterState.Ready -> usb.printers
    is UsbPrinterState.PermissionRequired -> usb.printers
    is UsbPrinterState.Connecting -> usb.printers
    is UsbPrinterState.Error -> usb.printers
    else -> emptyList()
}

@Composable private fun PrinterSelectionDialog(
    printers: List<PrinterRef>,
    selectedDeviceKey: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Выберите принтер") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(printers, key = PrinterRef::deviceKey) { printer ->
                    OutlinedButton(onClick = { onSelect(printer.deviceKey) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = printer.capabilities.displayName + if (printer.deviceKey == selectedDeviceKey) " · выбран" else "",
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        },
        confirmButton = { OutlinedButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable private fun PrinterIcon() {
    Icon(Icons.Default.Print, null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
}

@Composable private fun PrinterSummary(state: MainUiState, modifier: Modifier = Modifier) {
    Column(modifier) {
        when (val usb = state.usb) {
            UsbPrinterState.Checking -> Text("Поиск USB-принтера…")
            UsbPrinterState.HostUnsupported -> Text("USB Host не поддерживается", color = MaterialTheme.colorScheme.error)
            UsbPrinterState.NoPrinter -> { Text("Принтер не подключён"); Text("Проверьте OTG-переходник и питание принтера.", style = MaterialTheme.typography.bodySmall) }
            is UsbPrinterState.PermissionRequired -> { Text("Требуется доступ к USB-принтеру"); Text("USB Print требуется доступ к подключенному принтеру.", style = MaterialTheme.typography.bodySmall) }
            is UsbPrinterState.Connecting -> Text("Подключение к принтеру…")
            is UsbPrinterState.Ready -> {
                Text(usb.printer.capabilities.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(usb.printer.capabilities.portStatus?.userMessage ?: "USB подключён · Status unavailable", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
            is UsbPrinterState.Error -> Text(usb.error.userMessage, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun PrinterStateAction(
    state: MainUiState,
    onPermission: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (state.usb) {
        is UsbPrinterState.PermissionRequired -> Button(onClick = onPermission, modifier = modifier) { Text("Разрешить") }
        UsbPrinterState.NoPrinter, is UsbPrinterState.Error -> OutlinedButton(onClick = onRefresh, modifier = modifier) { Text("Обновить") }
        else -> Unit
    }
}

@Composable private fun DocumentCard(state: MainUiState, onSelect: () -> Unit, onHardwareTest: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Icon(Icons.Default.Description, null); Text("Документ", style = MaterialTheme.typography.titleMedium) }
            if (state.isLoadingDocument) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(20.dp)); Text("Подготовка предварительного просмотра…") }
            state.document?.let { document ->
                Text(document.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${document.mimeType} · ${document.sizeBytes?.let(::formatBytes) ?: "размер неизвестен"} · ${document.pageCount ?: 1} стр.", style = MaterialTheme.typography.bodySmall)
                state.preview?.let { bitmap -> Image(bitmap.asImageBitmap(), "Предварительный просмотр первой страницы", modifier = Modifier.fillMaxWidth().height(220.dp)) }
            } ?: Text("Выберите PDF, изображение или TXT-файл.", style = MaterialTheme.typography.bodyMedium)
            ElevatedButton(onClick = onSelect, modifier = Modifier.fillMaxWidth(), enabled = !state.isLoadingDocument) { Text("Выбрать файл") }
            OutlinedButton(onClick = onHardwareTest, modifier = Modifier.fillMaxWidth(), enabled = !state.isLoadingDocument) { Text("Создать тестовую страницу A4") }
        }
    }
}

@Composable private fun PrintAction(state: MainUiState, onPrint: () -> Unit, onCancel: () -> Unit) {
    val printing = isJobRunning(state.jobStatus)
    if (printing) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(listOfNotNull(state.progressDetail, state.jobDetail?.let { "Статус принтера: $it" }).joinToString(" · ").ifBlank { "Подготовка печати…" })
            state.progress?.let { progress -> LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth()) }
                ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Отменить") }
        }
    } else {
        Button(onClick = onPrint, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = state.document != null && state.usb is UsbPrinterState.Ready && state.backend.selected != BackendId.NONE) { Icon(Icons.Default.Print, null); Spacer(Modifier.size(10.dp)); Text("Печать") }
        if (state.jobStatus == PrintJobStatus.SENT) Text(state.jobDetail?.let { "Последний статус IPP: $it" } ?: "Задание передано принтеру.", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun PrintSettingsDialog(
    initial: PrintSettings,
    pageCount: Int?,
    capabilities: EffectivePrintCapabilities,
    compatibleBackends: List<BackendId>,
    customPresets: List<ru.usbprint.preferences.SavedPrintPreset>,
    advancedMode: Boolean,
    initialOverride: ExperimentalPrinterOverride?,
    onDismiss: () -> Unit,
    onSave: (PrintSettings) -> Unit,
    onApplyPreset: (PrintSettings) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onAdvancedMode: (Boolean) -> Unit,
    onSaveOverride: (ExperimentalPrinterOverride) -> Unit
) {
    var copiesText by remember(initial) { mutableStateOf(initial.copies.toString()) }
    var pageMode by remember(initial) { mutableStateOf(initial.pageSelection) }
    var rangeText by remember(initial) { mutableStateOf((initial.pageSelection as? PageSelection.Ranges)?.raw.orEmpty()) }
    var paper by remember(initial) { mutableStateOf(initial.paperSize) }
    var customPaperEnabled by remember(initial) { mutableStateOf(initial.customPaperSize != null) }
    var paperUnit by remember(initial) { mutableStateOf(PaperUnit.MILLIMETRES) }
    var customWidth by remember(initial) { mutableStateOf(initial.customPaperSize?.width?.let { formatPaperDimension(it, PaperUnit.MILLIMETRES) }.orEmpty()) }
    var customHeight by remember(initial) { mutableStateOf(initial.customPaperSize?.height?.let { formatPaperDimension(it, PaperUnit.MILLIMETRES) }.orEmpty()) }
    var orientation by remember(initial) { mutableStateOf(initial.orientation) }
    var color by remember(initial) { mutableStateOf(initial.colorMode) }
    var duplex by remember(initial) { mutableStateOf(initial.duplexMode) }
    var resolution by remember(initial) { mutableStateOf(initial.selectedResolution) }
    var mediaTypeKeyword by remember(initial, capabilities.backendId) { mutableStateOf(initial.mediaTypeKeyword?.takeIf { raw -> capabilities.mediaTypeOptions?.value?.any { it.rawKeyword == raw } == true }) }
    var mediaSourceKeyword by remember(initial, capabilities.backendId) { mutableStateOf(initial.mediaSourceKeyword?.takeIf { raw -> capabilities.mediaSourceOptions?.value?.any { it.rawKeyword == raw } == true }) }
    var outputBinKeyword by remember(initial, capabilities.backendId) { mutableStateOf(initial.outputBinKeyword?.takeIf { raw -> capabilities.outputBinOptions?.value?.any { it.rawKeyword == raw } == true }) }
    var scaling by remember(initial) { mutableStateOf(initial.scalingMode) }
    var scaleText by remember(initial) { mutableStateOf(initial.customScalePercent?.toString().orEmpty()) }
    var position by remember(initial) { mutableStateOf(initial.contentPosition) }
    var order by remember(initial) { mutableStateOf(initial.pageOrder) }
    var collate by remember(initial) { mutableStateOf(initial.collate) }
    var pagesPerSheet by remember(initial) { mutableIntStateOf(initial.pagesPerSheet) }
    var nUpSpacing by remember(initial) { mutableStateOf(initial.nUpSpacingMm.toString()) }
    var nUpBorders by remember(initial) { mutableStateOf(initial.nUpDrawBorders) }
    var nUpAutoRotate by remember(initial) { mutableStateOf(initial.nUpAutoRotate) }
    var marginLeft by remember(initial) { mutableStateOf(initial.margins.left.toString()) }
    var marginTop by remember(initial) { mutableStateOf(initial.margins.top.toString()) }
    var marginRight by remember(initial) { mutableStateOf(initial.margins.right.toString()) }
    var marginBottom by remember(initial) { mutableStateOf(initial.margins.bottom.toString()) }
    var presetName by remember { mutableStateOf("") }
    var override by remember(initialOverride) { mutableStateOf(initialOverride ?: ExperimentalPrinterOverride()) }
    val pageKinds = capabilities.pageSelections?.value.orEmpty()
    val enabled = capabilities.isPrintable
    val nUpAvailable = capabilities.supportsNUp || compatibleBackends.any { BackendRegistry.descriptorFor(it).supportsNUp }

    AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = onDismiss, title = { Text("Настройки печати") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${capabilities.backendId.title} · показываются только значения, пересекающиеся с возможностями backend и принтера.", style = MaterialTheme.typography.bodySmall)
            if (capabilities.limitations.isNotEmpty()) Text(capabilities.limitations.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)

            Text("Пресеты", style = MaterialTheme.typography.labelLarge)
            ResponsiveChoiceFlow {
                listOf(PrintPresetId.AUTO, PrintPresetId.DRAFT, PrintPresetId.NORMAL, PrintPresetId.HIGH, PrintPresetId.PHOTO, PrintPresetId.TEXT).forEach { preset ->
                    FilterChip(initial.preset == preset, { onApplyPreset(PrintPresetResolver.resolve(preset, initial, capabilities).settings) }, label = { Text(preset.label) })
                }
            }
            if (customPresets.isNotEmpty()) {
                Text("Мои пресеты", style = MaterialTheme.typography.labelLarge)
                customPresets.forEach { preset -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { OutlinedButton({ onApplyPreset(preset.settings) }, modifier = Modifier.weight(1f)) { Text(preset.name) }; IconButton({ onDeletePreset(preset.id) }) { Icon(Icons.Default.Delete, "Удалить пресет ${preset.name}") } } }
            }
            OutlinedTextField(presetName, { presetName = it.take(40) }, label = { Text("Сохранить текущие настройки как") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton({ onSavePreset(presetName) }, enabled = presetName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить мой пресет") }

            capabilities.copiesRange?.let { OutlinedTextField(copiesText, { copiesText = it.filter(Char::isDigit).take(2) }, label = { Text("Количество копий (${it.value.first}–${it.value.last})") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text(it.disclosure) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            if (pageKinds.isNotEmpty()) {
                Text("Страницы", style = MaterialTheme.typography.labelLarge)
                ResponsiveChoiceFlow {
                    if (ru.usbprint.domain.model.PageSelectionKind.ALL in pageKinds) FilterChip(pageMode is PageSelection.All, { pageMode = PageSelection.All }, label = { Text("Все") })
                    if (ru.usbprint.domain.model.PageSelectionKind.RANGES in pageKinds) FilterChip(pageMode is PageSelection.Ranges, { pageMode = PageSelection.Ranges("", emptyList()) }, label = { Text("Диапазон") })
                    if (ru.usbprint.domain.model.PageSelectionKind.ODD in pageKinds) FilterChip(pageMode is PageSelection.Odd, { pageMode = PageSelection.Odd }, label = { Text("Нечётные") })
                    if (ru.usbprint.domain.model.PageSelectionKind.EVEN in pageKinds) FilterChip(pageMode is PageSelection.Even, { pageMode = PageSelection.Even }, label = { Text("Чётные") })
                }
                if (pageMode is PageSelection.Ranges) OutlinedTextField(rangeText, { rangeText = it }, label = { Text("Например: 1-4, 7") }, supportingText = { Text("Всего страниц: ${pageCount ?: 1}") }, modifier = Modifier.fillMaxWidth())
            }
            val customRange = capabilities.customPaperRangeMicrons
            if (capabilities.paperSizes != null || customRange != null) {
                Text("Бумага", style = MaterialTheme.typography.labelLarge)
                ResponsiveChoiceFlow {
                    capabilities.paperSizes?.let { values ->
                        (setOf(PaperSize.AUTO) + values.value).forEach { value ->
                            FilterChip(!customPaperEnabled && paper == value, { customPaperEnabled = false; paper = value }, label = { Text(value.label) })
                        }
                    }
                    customRange?.let { range ->
                        FilterChip(customPaperEnabled, {
                            customPaperEnabled = true
                            paper = PaperSize.AUTO
                            if (parsePaperDimension(customWidth, paperUnit) == null) customWidth = formatPaperDimension(range.value.minWidth, paperUnit)
                            if (parsePaperDimension(customHeight, paperUnit) == null) customHeight = formatPaperDimension(range.value.minHeight, paperUnit)
                        }, label = { Text("Свой размер") })
                    }
                }
                capabilities.paperSizes?.let { Text(it.disclosure, style = MaterialTheme.typography.bodySmall) }
                if (customPaperEnabled && customRange != null) {
                    Text("Единицы", style = MaterialTheme.typography.labelLarge)
                    ResponsiveChoiceFlow {
                        PaperUnit.entries.forEach { unit -> FilterChip(paperUnit == unit, {
                            val widthMicrons = parsePaperDimension(customWidth, paperUnit)
                            val heightMicrons = parsePaperDimension(customHeight, paperUnit)
                            paperUnit = unit
                            widthMicrons?.let { customWidth = formatPaperDimension(it, unit) }
                            heightMicrons?.let { customHeight = formatPaperDimension(it, unit) }
                        }, label = { Text(unit.label) }) }
                    }
                    OutlinedTextField(customWidth, { customWidth = paperNumberInput(it) }, label = { Text("Ширина, ${paperUnit.symbol}") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(customHeight, { customHeight = paperNumberInput(it) }, label = { Text("Высота, ${paperUnit.symbol}") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Text(
                        "Подтверждённый диапазон: ${formatPaperDimension(customRange.value.minWidth, paperUnit)}–${formatPaperDimension(customRange.value.maxWidth, paperUnit)} × " +
                            "${formatPaperDimension(customRange.value.minHeight, paperUnit)}–${formatPaperDimension(customRange.value.maxHeight, paperUnit)} ${paperUnit.symbol} · ${customRange.disclosure}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            capabilities.orientations?.let { EnumChips("Ориентация · Авто не отправляет IPP attribute", setOf(Orientation.AUTO) + it.value, orientation, { orientation = it }) { value -> value.label } }
            capabilities.colorModes?.let { EnumChips("Цвет · ${it.disclosure}", setOf(ColorMode.AUTO) + it.value, color, { color = it }) { value -> value.label } }
            capabilities.duplexModes?.takeIf { it.value.any { mode -> mode != DuplexMode.OFF } }?.let { EnumChips("Двусторонняя · ${it.disclosure}", it.value, duplex, { duplex = it }) { value -> value.label } }
            capabilities.resolutions?.let { values ->
                Text("Разрешение · ${values.disclosure}", style = MaterialTheme.typography.labelLarge)
                ResponsiveChoiceFlow { FilterChip(resolution == null, { resolution = null }, label = { Text("Авто") }); values.value.forEach { dpi -> FilterChip(resolution == dpi, { resolution = dpi }, label = { Text(dpi.displayName) }) } }
            }
            capabilities.mediaSourceOptions?.let { KeywordChips("Источник бумаги · ${it.disclosure}", it.value, mediaSourceKeyword) { value -> mediaSourceKeyword = value } }
            capabilities.mediaTypeOptions?.let { KeywordChips("Тип бумаги · ${it.disclosure}", it.value, mediaTypeKeyword) { value -> mediaTypeKeyword = value } }
            capabilities.outputBinOptions?.let { KeywordChips("Выходной лоток · ${it.disclosure}", it.value, outputBinKeyword) { value -> outputBinKeyword = value } }
            if (capabilities.supportsScaling) {
                EnumChips("Масштаб", ScalingMode.entries, scaling, { scaling = it }) { it.label }
                if (scaling == ScalingMode.CUSTOM) OutlinedTextField(scaleText, { scaleText = it.filter(Char::isDigit).take(3) }, label = { Text("Масштаб, % (10–400)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (capabilities.supportsMargins) {
                Text("Поля пользователя, мм", style = MaterialTheme.typography.labelLarge)
                AdaptiveTwoColumnFields { fieldModifier ->
                    MarginField("Слева", marginLeft, { marginLeft = it }, fieldModifier)
                    MarginField("Сверху", marginTop, { marginTop = it }, fieldModifier)
                    MarginField("Справа", marginRight, { marginRight = it }, fieldModifier)
                    MarginField("Снизу", marginBottom, { marginBottom = it }, fieldModifier)
                }
                capabilities.hardwareMargins?.let { Text("Физические поля: ${it.value.left}/${it.value.top}/${it.value.right}/${it.value.bottom} мм · ${it.disclosure}", style = MaterialTheme.typography.bodySmall) }
            }
            if (capabilities.supportsPositioning) EnumChips("Позиционирование", ContentPosition.entries, position, { position = it }) { it.label }
            if (capabilities.supportsPageOrder) EnumChips("Порядок", PageOrder.entries, order, { order = it }) { it.label }
            if (capabilities.supportsCollate) LabeledCheckbox(collate, { collate = it }, "Разбирать по копиям")
            if (nUpAvailable) {
                EnumChips("Страниц на физическом листе", listOf(1, 2, 4), pagesPerSheet, { pagesPerSheet = it }) { it.toString() }
                if (pagesPerSheet > 1) {
                    OutlinedTextField(
                        nUpSpacing,
                        { value -> nUpSpacing = value.filter { it.isDigit() || it == '.' }.take(5) },
                        label = { Text("Интервал между страницами, мм (0–20)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    LabeledCheckbox(nUpBorders, { nUpBorders = it }, "Рамки вокруг логических страниц")
                    LabeledCheckbox(nUpAutoRotate, { nUpAutoRotate = it }, "Автоповорот для лучшего заполнения")
                }
            }

            HorizontalDivider()
            LabeledSwitch(
                checked = advancedMode,
                onCheckedChange = onAdvancedMode,
                title = "Расширенные настройки",
                supportingText = "Только ручные экспериментальные переопределения для этого принтера."
            )
            if (advancedMode) {
                Text("Экспериментальные значения помечены в диагностике и не включаются автоматически.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("Принудительный backend", style = MaterialTheme.typography.labelLarge)
                ResponsiveChoiceFlow { FilterChip(override.forcedBackend == null, { override = override.copy(forcedBackend = null) }, label = { Text("Нет") }); compatibleBackends.forEach { id -> FilterChip(override.forcedBackend == id, { override = override.copy(forcedBackend = id) }, label = { Text(id.title) }) } }
                capabilities.resolutions?.let { values -> ResponsiveChoiceFlow { FilterChip(override.forcedResolution == null, { override = override.copy(forcedResolution = null) }, label = { Text("Авто DPI") }); values.value.forEach { dpi -> FilterChip(override.forcedResolution == dpi, { override = override.copy(forcedResolution = dpi) }, label = { Text("Force ${dpi.displayName}") }) } } }
                LabeledCheckbox(override.forceMonochrome, { override = override.copy(forceMonochrome = it) }, "Принудительно монохром")
                OutlinedButton({ onSaveOverride(override) }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить переопределение") }
            }
        }
    }, confirmButton = { Button(onClick = {
        if (!enabled) return@Button
        val selection = when (pageMode) {
            is PageSelection.Ranges -> PageRangeParser.parse(rangeText, pageCount).getOrNull()?.let { PageSelection.Ranges(rangeText, it) } ?: return@Button
            else -> pageMode
        }
        val margins = listOf(marginLeft, marginTop, marginRight, marginBottom).map { it.toFloatOrNull() ?: return@Button }
        if (margins.any { it !in 0f..60f }) return@Button
        val spacing = nUpSpacing.toFloatOrNull() ?: return@Button
        if (spacing !in 0f..20f) return@Button
        val customPaper = if (customPaperEnabled) {
            CustomPaperSizeMicrons(
                parsePaperDimension(customWidth, paperUnit) ?: return@Button,
                parsePaperDimension(customHeight, paperUnit) ?: return@Button
            )
        } else null
        onSave(initial.copy(
            copies = copiesText.toIntOrNull() ?: return@Button, pageSelection = selection,
            paperSize = if (customPaper != null) PaperSize.AUTO else paper, customPaperSize = customPaper,
            orientation = orientation, colorMode = color, duplexMode = duplex, scalingMode = scaling,
            resolutionDpi = resolution?.takeIf { it.horizontalDpi == it.verticalDpi }?.horizontalDpi, resolution = resolution,
            marginsMm = margins.first(), margins = ru.usbprint.domain.model.PrintMarginsMm(margins[0], margins[1], margins[2], margins[3]),
            contentPosition = position, customScalePercent = if (scaling == ScalingMode.CUSTOM) scaleText.toIntOrNull() else null,
            pageOrder = order, collate = collate, pagesPerSheet = pagesPerSheet,
            nUpSpacingMm = spacing, nUpDrawBorders = nUpBorders, nUpAutoRotate = nUpAutoRotate,
            mediaType = null, mediaSource = null, outputBin = null,
            mediaTypeKeyword = mediaTypeKeyword, mediaSourceKeyword = mediaSourceKeyword, outputBinKeyword = outputBinKeyword,
            preset = PrintPresetId.CUSTOM
        ))
    }, enabled = enabled) { Text("Сохранить") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Отмена") } })
}

private enum class PaperUnit(val label: String, val symbol: String) {
    MILLIMETRES("Миллиметры", "мм"), INCHES("Дюймы", "in")
}

private fun parsePaperDimension(value: String, unit: PaperUnit): Microns? = runCatching {
    when (unit) {
        PaperUnit.MILLIMETRES -> Microns.fromMillimetres(value)
        PaperUnit.INCHES -> Microns.fromInches(value)
    }
}.getOrNull()

private fun formatPaperDimension(value: Microns, unit: PaperUnit): String {
    val divisor = if (unit == PaperUnit.MILLIMETRES) BigDecimal("1000") else BigDecimal("25400")
    return BigDecimal.valueOf(value.value).divide(divisor, 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

private fun paperNumberInput(value: String): String = value.replace(',', '.').filter { it.isDigit() || it == '.' }.take(10)

@Composable private fun MarginField(label: String, value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { onValue(it.filter { c -> c.isDigit() || c == '.' }.take(5)) }, label = { Text(label) }, singleLine = true, modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}

@Composable private fun LabeledCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable private fun LabeledSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, supportingText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(supportingText, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable private fun <T> EnumChips(title: String, items: Iterable<T>, selected: T, select: (T) -> Unit, label: (T) -> String) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    ResponsiveChoiceFlow { items.forEach { value -> FilterChip(selected == value, { select(value) }, label = { Text(label(value)) }) } }
}

@Composable private fun KeywordChips(title: String, items: Iterable<PrinterKeywordOption>, selected: String?, select: (String?) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    ResponsiveChoiceFlow {
        FilterChip(selected == null, { select(null) }, label = { Text("Авто") })
        items.forEach { option ->
            FilterChip(selected == option.rawKeyword, { select(option.rawKeyword) }, label = { Text(option.localizedDisplayName) })
        }
    }
}

@Composable private fun DiagnosticsDialog(text: String, logs: List<String>, onExportText: () -> Unit, onExportJson: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(modifier = Modifier.imePadding(), onDismissRequest = onDismiss, title = { Text("Информация о принтере") }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall); if (logs.isNotEmpty()) { HorizontalDivider(Modifier.padding(vertical = 10.dp)); Text("Журнал", style = MaterialTheme.typography.labelLarge); Text(logs.joinToString("\n"), style = MaterialTheme.typography.bodySmall) } }
    }, confirmButton = { Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text + "\n\n" + logs.joinToString("\n"))) }) { Text("Копировать") } }, dismissButton = { Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) { OutlinedButton(onClick = onExportText) { Text("Экспорт TXT") }; OutlinedButton(onClick = onExportJson) { Text("Экспорт JSON") }; OutlinedButton(onClick = onDismiss) { Text("Закрыть") } } })
}

@Composable private fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Не удалось выполнить действие") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { Text(message) } },
        confirmButton = { Button(onClick = onDismiss) { Text("Закрыть") } }
    )
}

private fun formatBytes(bytes: Long): String = when { bytes >= 1_048_576 -> "%.1f МБ".format(bytes / 1_048_576f); bytes >= 1024 -> "%.1f КБ".format(bytes / 1024f); else -> "$bytes Б" }
private fun isJobRunning(status: PrintJobStatus?): Boolean = status != null && status !in setOf(PrintJobStatus.IDLE, PrintJobStatus.SENT, PrintJobStatus.CANCELLED, PrintJobStatus.ERROR)
