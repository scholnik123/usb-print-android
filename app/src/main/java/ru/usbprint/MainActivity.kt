@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.usbprint

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.usbprint.domain.logic.PageRangeParser
import ru.usbprint.domain.logic.PrintPresetResolver
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.ColorMode
import ru.usbprint.domain.model.ContentPosition
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
import ru.usbprint.domain.model.PrinterResolution
import ru.usbprint.domain.model.PrinterKeywordOption
import ru.usbprint.domain.model.ScalingMode
import ru.usbprint.presentation.MainUiState
import ru.usbprint.presentation.MainViewModel
import ru.usbprint.presentation.theme.UsbPrintTheme
import ru.usbprint.usb.UsbPrinterState

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels { MainViewModel.factory((application as UsbPrintApplication).container) }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::loadDocument) }
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val exportDiagnosticsTxt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { viewModel.exportDiagnostics(it, false) } }
    private val exportDiagnosticsJson = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { viewModel.exportDiagnostics(it, true) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        handleIncomingIntent(intent)
        setContent {
            UsbPrintTheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme()) {
                UsbPrintScreen(viewModel = viewModel, onSelect = { openDocument.launch(SUPPORTED_MIME_TYPES) }, onExportText = { exportDiagnosticsTxt.launch("USB-Print-diagnostics.txt") }, onExportJson = { exportDiagnosticsJson.launch("USB-Print-diagnostics.json") })
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
private fun UsbPrintScreen(viewModel: MainViewModel, onSelect: () -> Unit, onExportText: () -> Unit, onExportJson: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    LaunchedEffect(state.error) { state.error?.let { snackbars.showSnackbar(it.userMessage); viewModel.clearError() } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("USB Print") }, actions = { IconButton(onClick = { showDiagnostics = true }) { Icon(Icons.Default.Usb, "Информация о принтере") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)) },
        snackbarHost = { SnackbarHost(snackbars) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(2.dp))
            PrinterCard(state, viewModel::requestUsbPermission, viewModel::refreshPrinter, viewModel::selectPrinter)
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
    var outcome by remember { mutableStateOf<HardwareTestOutcome?>(null) }
    var issues by remember { mutableStateOf(emptySet<HardwarePrintIssue>()) }
    var notes by remember { mutableStateOf("") }
    val canSave = outcome != null &&
        (outcome != HardwareTestOutcome.PRINTED_WITH_ISSUES || issues.isNotEmpty()) &&
        (outcome != HardwareTestOutcome.OTHER || notes.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Что произошло на бумаге?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("SENT или IPP completed не подтверждают физическую печать. Выберите только то, что вы увидели.")
                if (outcome == null) {
                    HardwareTestOutcome.entries.forEach { item ->
                        OutlinedButton(onClick = { outcome = item }, modifier = Modifier.fillMaxWidth()) { Text(item.label) }
                    }
                } else {
                    Text("Результат: ${outcome?.label}", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { outcome = null; issues = emptySet() }) { Text("Изменить результат") }
                    if (outcome == HardwareTestOutcome.PRINTED_WITH_ISSUES) {
                        Text("Отметьте все замеченные проблемы:")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HardwarePrintIssue.entries.forEach { issue ->
                                FilterChip(
                                    selected = issue in issues,
                                    onClick = { issues = if (issue in issues) issues - issue else issues + issue },
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

@Composable private fun PrinterCard(state: MainUiState, onPermission: () -> Unit, onRefresh: () -> Unit, onSelectPrinter: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.Print, null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                when (val usb = state.usb) {
                    UsbPrinterState.Checking -> Text("Поиск USB-принтера…")
                    UsbPrinterState.HostUnsupported -> Text("USB Host не поддерживается", color = MaterialTheme.colorScheme.error)
                    UsbPrinterState.NoPrinter -> { Text("Принтер не подключён"); Text("Проверьте OTG-переходник и питание принтера.", style = MaterialTheme.typography.bodySmall) }
                    is UsbPrinterState.PermissionRequired -> { Text("Требуется доступ к USB-принтеру"); Text("USB Print требуется доступ к подключенному принтеру.", style = MaterialTheme.typography.bodySmall) }
                    is UsbPrinterState.Connecting -> Text("Подключение к принтеру…")
                    is UsbPrinterState.Ready -> { Text(usb.printer.capabilities.displayName); Text(usb.printer.capabilities.portStatus?.userMessage ?: "USB подключён · Status unavailable", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                    is UsbPrinterState.Error -> Text(usb.error.userMessage, color = MaterialTheme.colorScheme.error)
                }
            }
            when (state.usb) {
                is UsbPrinterState.PermissionRequired -> Button(onClick = onPermission) { Text("Разрешить") }
                UsbPrinterState.NoPrinter, is UsbPrinterState.Error -> OutlinedButton(onClick = onRefresh) { Text("Обновить") }
                else -> Unit
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
                Text("Выберите принтер", style = MaterialTheme.typography.labelLarge)
                available.forEach { ref ->
                    OutlinedButton(onClick = { onSelectPrinter(ref.deviceKey) }, modifier = Modifier.fillMaxWidth()) {
                        Text(ref.capabilities.displayName + if ((state.usb as? UsbPrinterState.Ready)?.printer?.deviceKey == ref.deviceKey) " · выбран" else "")
                    }
                }
            }
        }
        }
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
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(state.jobDetail?.let { "Статус принтера: $it · ${state.progress}%" } ?: "Печать: ${state.progress}%"); LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth()); OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Отменить") } }
    } else {
        Button(onClick = onPrint, modifier = Modifier.fillMaxWidth().height(56.dp), enabled = state.document != null && state.usb is UsbPrinterState.Ready && state.backend.selected != BackendId.NONE) { Icon(Icons.Default.Print, null); Spacer(Modifier.size(10.dp)); Text("Печать") }
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
    var marginLeft by remember(initial) { mutableStateOf(initial.margins.left.toString()) }
    var marginTop by remember(initial) { mutableStateOf(initial.margins.top.toString()) }
    var marginRight by remember(initial) { mutableStateOf(initial.margins.right.toString()) }
    var marginBottom by remember(initial) { mutableStateOf(initial.margins.bottom.toString()) }
    var presetName by remember { mutableStateOf("") }
    var override by remember(initialOverride) { mutableStateOf(initialOverride ?: ExperimentalPrinterOverride()) }
    val pageKinds = capabilities.pageSelections?.value.orEmpty()
    val enabled = capabilities.isPrintable

    AlertDialog(onDismissRequest = onDismiss, title = { Text("Настройки печати") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${capabilities.backendId.title} · показываются только значения, пересекающиеся с возможностями backend и принтера.", style = MaterialTheme.typography.bodySmall)
            if (capabilities.limitations.isNotEmpty()) Text(capabilities.limitations.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)

            Text("Пресеты", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PrintPresetId.AUTO, PrintPresetId.DRAFT, PrintPresetId.NORMAL, PrintPresetId.HIGH, PrintPresetId.PHOTO, PrintPresetId.TEXT).forEach { preset ->
                    FilterChip(initial.preset == preset, { onApplyPreset(PrintPresetResolver.resolve(preset, initial, capabilities).settings) }, label = { Text(preset.label) })
                }
            }
            if (customPresets.isNotEmpty()) {
                Text("Мои пресеты", style = MaterialTheme.typography.labelLarge)
                customPresets.forEach { preset -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { OutlinedButton({ onApplyPreset(preset.settings) }, modifier = Modifier.weight(1f)) { Text(preset.name) }; IconButton({ onDeletePreset(preset.id) }) { Text("×") } } }
            }
            OutlinedTextField(presetName, { presetName = it.take(40) }, label = { Text("Сохранить текущие настройки как") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton({ onSavePreset(presetName) }, enabled = presetName.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Сохранить мой пресет") }

            capabilities.copiesRange?.let { OutlinedTextField(copiesText, { copiesText = it.filter(Char::isDigit).take(2) }, label = { Text("Количество копий (${it.value.first}–${it.value.last})") }, singleLine = true, modifier = Modifier.fillMaxWidth(), supportingText = { Text(it.disclosure) }) }
            if (pageKinds.isNotEmpty()) {
                Text("Страницы", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ru.usbprint.domain.model.PageSelectionKind.ALL in pageKinds) FilterChip(pageMode is PageSelection.All, { pageMode = PageSelection.All }, label = { Text("Все") })
                    if (ru.usbprint.domain.model.PageSelectionKind.RANGES in pageKinds) FilterChip(pageMode is PageSelection.Ranges, { pageMode = PageSelection.Ranges("", emptyList()) }, label = { Text("Диапазон") })
                    if (ru.usbprint.domain.model.PageSelectionKind.ODD in pageKinds) FilterChip(pageMode is PageSelection.Odd, { pageMode = PageSelection.Odd }, label = { Text("Нечётные") })
                    if (ru.usbprint.domain.model.PageSelectionKind.EVEN in pageKinds) FilterChip(pageMode is PageSelection.Even, { pageMode = PageSelection.Even }, label = { Text("Чётные") })
                }
                if (pageMode is PageSelection.Ranges) OutlinedTextField(rangeText, { rangeText = it }, label = { Text("Например: 1-4, 7") }, supportingText = { Text("Всего страниц: ${pageCount ?: 1}") }, modifier = Modifier.fillMaxWidth())
            }
            capabilities.paperSizes?.let { EnumChips("Бумага · ${it.disclosure}", setOf(PaperSize.AUTO) + it.value, paper, { paper = it }) { value -> value.label } }
            capabilities.orientations?.let { EnumChips("Ориентация · Авто не отправляет IPP attribute", setOf(Orientation.AUTO) + it.value, orientation, { orientation = it }) { value -> value.label } }
            capabilities.colorModes?.let { EnumChips("Цвет · ${it.disclosure}", setOf(ColorMode.AUTO) + it.value, color, { color = it }) { value -> value.label } }
            capabilities.duplexModes?.takeIf { it.value.any { mode -> mode != DuplexMode.OFF } }?.let { EnumChips("Двусторонняя · ${it.disclosure}", it.value, duplex, { duplex = it }) { value -> value.label } }
            capabilities.resolutions?.let { values ->
                Text("Разрешение · ${values.disclosure}", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(resolution == null, { resolution = null }, label = { Text("Авто") }); values.value.forEach { dpi -> FilterChip(resolution == dpi, { resolution = dpi }, label = { Text(dpi.displayName) }) } }
            }
            capabilities.mediaSourceOptions?.let { KeywordChips("Источник бумаги · ${it.disclosure}", it.value, mediaSourceKeyword) { value -> mediaSourceKeyword = value } }
            capabilities.mediaTypeOptions?.let { KeywordChips("Тип бумаги · ${it.disclosure}", it.value, mediaTypeKeyword) { value -> mediaTypeKeyword = value } }
            capabilities.outputBinOptions?.let { KeywordChips("Выходной лоток · ${it.disclosure}", it.value, outputBinKeyword) { value -> outputBinKeyword = value } }
            if (capabilities.supportsScaling) {
                EnumChips("Масштаб", ScalingMode.entries, scaling, { scaling = it }) { it.label }
                if (scaling == ScalingMode.CUSTOM) OutlinedTextField(scaleText, { scaleText = it.filter(Char::isDigit).take(3) }, label = { Text("Масштаб, % (10–400)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            if (capabilities.supportsMargins) {
                Text("Поля пользователя, мм", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MarginField("Слева", marginLeft, { marginLeft = it }); MarginField("Сверху", marginTop, { marginTop = it })
                    MarginField("Справа", marginRight, { marginRight = it }); MarginField("Снизу", marginBottom, { marginBottom = it })
                }
                capabilities.hardwareMargins?.let { Text("Физические поля: ${it.value.left}/${it.value.top}/${it.value.right}/${it.value.bottom} мм · ${it.disclosure}", style = MaterialTheme.typography.bodySmall) }
            }
            if (capabilities.supportsPositioning) EnumChips("Позиционирование", ContentPosition.entries, position, { position = it }) { it.label }
            if (capabilities.supportsPageOrder) EnumChips("Порядок", PageOrder.entries, order, { order = it }) { it.label }
            if (capabilities.supportsCollate) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(collate, { collate = it }); Text("Разбирать по копиям") }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) { Column(Modifier.weight(1f)) { Text("Расширенные настройки", style = MaterialTheme.typography.labelLarge); Text("Только ручные экспериментальные переопределения для этого принтера.", style = MaterialTheme.typography.bodySmall) }; Switch(advancedMode, onAdvancedMode) }
            if (advancedMode) {
                Text("Экспериментальные значения помечены в диагностике и не включаются автоматически.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Text("Принудительный backend", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(override.forcedBackend == null, { override = override.copy(forcedBackend = null) }, label = { Text("Нет") }); compatibleBackends.forEach { id -> FilterChip(override.forcedBackend == id, { override = override.copy(forcedBackend = id) }, label = { Text(id.title) }) } }
                capabilities.resolutions?.let { values -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(override.forcedResolution == null, { override = override.copy(forcedResolution = null) }, label = { Text("Авто DPI") }); values.value.forEach { dpi -> FilterChip(override.forcedResolution == dpi, { override = override.copy(forcedResolution = dpi) }, label = { Text("Force ${dpi.displayName}") }) } } }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(override.forceMonochrome, { override = override.copy(forceMonochrome = it) }); Text("Принудительно монохром") }
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
        onSave(initial.copy(
            copies = copiesText.toIntOrNull() ?: return@Button, pageSelection = selection, paperSize = paper,
            orientation = orientation, colorMode = color, duplexMode = duplex, scalingMode = scaling,
            resolutionDpi = resolution?.takeIf { it.horizontalDpi == it.verticalDpi }?.horizontalDpi, resolution = resolution,
            marginsMm = margins.first(), margins = ru.usbprint.domain.model.PrintMarginsMm(margins[0], margins[1], margins[2], margins[3]),
            contentPosition = position, customScalePercent = if (scaling == ScalingMode.CUSTOM) scaleText.toIntOrNull() else null,
            pageOrder = order, collate = collate, mediaType = null, mediaSource = null, outputBin = null,
            mediaTypeKeyword = mediaTypeKeyword, mediaSourceKeyword = mediaSourceKeyword, outputBinKeyword = outputBinKeyword,
            preset = PrintPresetId.CUSTOM
        ))
    }, enabled = enabled) { Text("Сохранить") } }, dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable private fun MarginField(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(value, { onValue(it.filter { c -> c.isDigit() || c == '.' }.take(5)) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(0.47f))
}

@Composable private fun <T> EnumChips(title: String, items: Iterable<T>, selected: T, select: (T) -> Unit, label: (T) -> String) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items.forEach { value -> FilterChip(selected == value, { select(value) }, label = { Text(label(value)) }) } }
}

@Composable private fun KeywordChips(title: String, items: Iterable<PrinterKeywordOption>, selected: String?, select: (String?) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == null, { select(null) }, label = { Text("Авто") })
        items.forEach { option ->
            FilterChip(selected == option.rawKeyword, { select(option.rawKeyword) }, label = { Text(option.localizedDisplayName) })
        }
    }
}

@Composable private fun DiagnosticsDialog(text: String, logs: List<String>, onExportText: () -> Unit, onExportJson: () -> Unit, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Информация о принтере") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodySmall); if (logs.isNotEmpty()) { HorizontalDivider(Modifier.padding(vertical = 10.dp)); Text("Журнал", style = MaterialTheme.typography.labelLarge); Text(logs.joinToString("\n"), style = MaterialTheme.typography.bodySmall) } }
    }, confirmButton = { Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text + "\n\n" + logs.joinToString("\n"))) }) { Text("Копировать") } }, dismissButton = { Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) { OutlinedButton(onClick = onExportText) { Text("Экспорт TXT") }; OutlinedButton(onClick = onExportJson) { Text("Экспорт JSON") }; OutlinedButton(onClick = onDismiss) { Text("Закрыть") } } })
}

private fun formatBytes(bytes: Long): String = when { bytes >= 1_048_576 -> "%.1f МБ".format(bytes / 1_048_576f); bytes >= 1024 -> "%.1f КБ".format(bytes / 1024f); else -> "$bytes Б" }
private fun isJobRunning(status: PrintJobStatus?): Boolean = status != null && status !in setOf(PrintJobStatus.IDLE, PrintJobStatus.SENT, PrintJobStatus.CANCELLED, PrintJobStatus.ERROR)
