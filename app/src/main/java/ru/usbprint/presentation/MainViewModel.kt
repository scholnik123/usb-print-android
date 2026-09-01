package ru.usbprint.presentation

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.usbprint.AppContainer
import ru.usbprint.domain.logic.BackendDecision
import ru.usbprint.domain.logic.BackendRegistry
import ru.usbprint.domain.logic.PageRangeParser
import ru.usbprint.domain.logic.PrintSettingsValidator
import ru.usbprint.domain.logic.SettingsValidation
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.CompatibilityExportEnvironment
import ru.usbprint.domain.model.CompatibilityRecordJson
import ru.usbprint.domain.model.DocumentRef
import ru.usbprint.domain.model.EffectivePrintCapabilities
import ru.usbprint.domain.model.ExperimentalPrinterOverride
import ru.usbprint.domain.model.HardwarePrintIssue
import ru.usbprint.domain.model.HardwareTestObservation
import ru.usbprint.domain.model.HardwareTestOutcome
import ru.usbprint.domain.model.PageSelection
import ru.usbprint.domain.model.PrintException
import ru.usbprint.domain.model.PrintJob
import ru.usbprint.domain.model.PrintJobStatus
import ru.usbprint.domain.model.PrintSettings
import ru.usbprint.domain.model.VerifiedPrinterProfile
import ru.usbprint.domain.model.VerifiedPrinterProfileFactory
import ru.usbprint.preferences.SavedPrintPreset
import ru.usbprint.printing.PrintForegroundService
import ru.usbprint.printing.PrintingEncoderVersions
import ru.usbprint.usb.UsbPrinterState

data class MainUiState(
    val usb: UsbPrinterState = UsbPrinterState.Checking,
    val document: DocumentRef? = null,
    val preview: Bitmap? = null,
    val settings: PrintSettings = PrintSettings(),
    val backend: BackendDecision = BackendDecision(BackendId.NONE, emptyList()),
    val effectiveCapabilities: EffectivePrintCapabilities = EffectivePrintCapabilities.NONE,
    val printerOverride: ExperimentalPrinterOverride? = null,
    val advancedMode: Boolean = false,
    val customPresets: List<SavedPrintPreset> = emptyList(),
    val jobStatus: PrintJobStatus? = null,
    val progress: Int = 0,
    val jobDetail: String? = null,
    val error: AppError? = null,
    val isLoadingDocument: Boolean = false,
    val isHardwareTestDocument: Boolean = false,
    val hardwareTestAwaitingResult: Boolean = false,
    val showHardwareTestWizard: Boolean = false,
    val lastHardwareTestObservation: HardwareTestObservation? = null,
    val verifiedPrinterProfile: VerifiedPrinterProfile? = null
)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(MainUiState())
    val state = _state.asStateFlow()
    val logs = container.log.entries
    private val hardwareTestJobs = HardwareTestJobTracker()
    private var pendingHardwareTestJob: PrintJob? = null
    private var previewJob: Job? = null

    init {
        container.printerController.start()
        viewModelScope.launch {
            container.printerController.state.collectLatest { usb ->
                _state.update { current ->
                    val oldKey = (current.usb as? UsbPrinterState.Ready)?.printer?.deviceKey
                    val newKey = (usb as? UsbPrinterState.Ready)?.printer?.deviceKey
                    refreshed(current.copy(
                        usb = usb,
                        error = null,
                        verifiedPrinterProfile = current.verifiedPrinterProfile.takeIf { oldKey == newKey }
                    ))
                }
                refreshPreview(_state.value)
                (usb as? UsbPrinterState.Ready)?.printer?.let { printer -> loadPrinterLocalState(printer.deviceKey) }
            }
        }
        viewModelScope.launch { container.preferences.advancedMode.collectLatest { enabled -> _state.update { it.copy(advancedMode = enabled) } } }
        viewModelScope.launch { container.preferences.customPresets.collectLatest { presets -> _state.update { it.copy(customPresets = presets) } } }
        viewModelScope.launch {
            container.printExecutor.state.collectLatest { execution ->
                val shouldRequestObservation = hardwareTestJobs.onExecution(execution.jobId, execution.status)
                if (execution.jobId == pendingHardwareTestJob?.id && execution.status == PrintJobStatus.CANCELLED) {
                    pendingHardwareTestJob = null
                }
                _state.update { current ->
                    current.copy(
                        jobStatus = execution.status,
                        progress = execution.progress,
                        jobDetail = execution.detail,
                        error = execution.error ?: current.error,
                        hardwareTestAwaitingResult = current.hardwareTestAwaitingResult || shouldRequestObservation,
                        showHardwareTestWizard = current.showHardwareTestWizard || shouldRequestObservation
                    )
                }
            }
        }
    }

    fun requestUsbPermission() = container.printerController.requestPermission()
    fun refreshPrinter() = container.printerController.refresh()
    fun selectPrinter(deviceKey: String) = container.printerController.selectPrinter(deviceKey)

    fun loadDocument(uri: Uri) = viewModelScope.launch { loadDocument(uri, isHardwareTest = false) }

    private suspend fun loadDocument(uri: Uri, isHardwareTest: Boolean) {
        previewJob?.cancel()
        _state.update {
            it.copy(
                isLoadingDocument = true,
                error = null,
                jobStatus = null,
                preview = null,
                isHardwareTestDocument = isHardwareTest,
                hardwareTestAwaitingResult = if (isHardwareTest) false else it.hardwareTestAwaitingResult,
                showHardwareTestWizard = if (isHardwareTest) false else it.showHardwareTestWizard,
                lastHardwareTestObservation = if (isHardwareTest) null else it.lastHardwareTestObservation
            )
        }
        runCatching {
            val document = container.documents.inspect(uri)
            val previewState = refreshed(_state.value.copy(document = document))
            document to container.documents.renderPrintPreview(
                document,
                previewState.settings,
                previewDpi(previewState),
                previewState.effectiveCapabilities.hardwareMargins?.value ?: ru.usbprint.domain.model.HardwareMarginsMm.ZERO
            )
        }.onSuccess { (document, preview) ->
            _state.update { current ->
                refreshed(current.copy(document = document, preview = preview, isLoadingDocument = false))
            }
            container.log.add("Document selected: ${document.kind}, ${document.sizeBytes ?: 0} bytes")
        }.onFailure { throwable ->
            _state.update {
                it.copy(
                    isLoadingDocument = false,
                    isHardwareTestDocument = false,
                    error = (throwable as? PrintException)?.error ?: AppError.DOCUMENT_READ_ERROR
                )
            }
        }
    }

    fun loadHardwareTestPage() = viewModelScope.launch {
        runCatching { container.hardwareTestPage.create() }
            .onSuccess { uri ->
                container.log.add("Local hardware test page created")
                loadDocument(uri, isHardwareTest = true)
            }
            .onFailure { _state.update { it.copy(error = AppError.DOCUMENT_READ_ERROR) } }
    }

    fun updateSettings(settings: PrintSettings) {
        val current = _state.value
        val pageCount = current.document?.pageCount
        val rangeError = (settings.pageSelection as? PageSelection.Ranges)?.let { PageRangeParser.parse(it.raw, pageCount).exceptionOrNull() }
        if (rangeError != null) {
            _state.update { it.copy(error = AppError.INVALID_SETTINGS) }
            return
        }
        val candidate = refreshed(current.copy(settings = settings, error = null))
        val validation = PrintSettingsValidator.validate(settings, candidate.effectiveCapabilities, pageCount)
        if (validation is SettingsValidation.Invalid) {
            _state.update { it.copy(error = AppError.INVALID_SETTINGS) }
            return
        }
        _state.value = candidate
        refreshPreview(candidate)
    }

    fun print() {
        val current = _state.value
        val printer = (current.usb as? UsbPrinterState.Ready)?.printer ?: return
        val document = current.document ?: return
        val validation = PrintSettingsValidator.validate(current.settings, current.effectiveCapabilities, document.pageCount)
        if (validation !is SettingsValidation.Valid || current.backend.selected == BackendId.NONE) {
            _state.update { it.copy(error = if (validation !is SettingsValidation.Valid) AppError.INVALID_SETTINGS else AppError.PRINTER_NOT_SUPPORTED) }
            return
        }
        val job = PrintJob(document = document, printer = printer, settings = current.settings, backend = current.backend.selected)
        if (current.isHardwareTestDocument) {
            hardwareTestJobs.started(job.id)
            pendingHardwareTestJob = job
        }
        if (PrintForegroundService.start(container.appContext, job)) {
            _state.update {
                it.copy(
                    jobStatus = PrintJobStatus.VALIDATING,
                    progress = 0,
                    error = null,
                    hardwareTestAwaitingResult = if (current.isHardwareTestDocument) false else it.hardwareTestAwaitingResult,
                    showHardwareTestWizard = if (current.isHardwareTestDocument) false else it.showHardwareTestWizard,
                    lastHardwareTestObservation = if (current.isHardwareTestDocument) null else it.lastHardwareTestObservation
                )
            }
        } else {
            hardwareTestJobs.startFailed(job.id)
            if (pendingHardwareTestJob?.id == job.id) pendingHardwareTestJob = null
            _state.update { it.copy(error = AppError.TRANSFER_ERROR) }
        }
    }

    fun cancelPrint() = PrintForegroundService.cancel(container.appContext)
    fun openHardwareTestWizard() = _state.update { if (it.hardwareTestAwaitingResult) it.copy(showHardwareTestWizard = true) else it }
    fun dismissHardwareTestWizard() = _state.update { it.copy(showHardwareTestWizard = false) }
    fun recordHardwareTestObservation(outcome: HardwareTestOutcome, issues: Set<HardwarePrintIssue>, notes: String?) {
        val job = pendingHardwareTestJob ?: run {
            _state.update { state -> state.copy(error = AppError.INVALID_SETTINGS) }
            return
        }
        val observation = runCatching {
            HardwareTestObservation(outcome, issues, notes?.trim()?.takeIf(String::isNotEmpty))
        }.getOrElse {
            _state.update { state -> state.copy(error = AppError.INVALID_SETTINGS) }
            return
        }
        _state.update { it.copy(showHardwareTestWizard = false) }
        viewModelScope.launch {
            runCatching {
                val encoderVersion = checkNotNull(PrintingEncoderVersions.forBackend(job.backend))
                val existing = container.preferences.verifiedProfileFor(job.printer.deviceKey)
                VerifiedPrinterProfileFactory.record(
                    existing = existing,
                    printer = job.printer.capabilities,
                    deviceKey = job.printer.deviceKey,
                    appVersion = ru.usbprint.BuildConfig.VERSION_NAME,
                    backend = job.backend,
                    encoderVersion = encoderVersion,
                    settings = job.settings,
                    observation = observation
                ).also { container.preferences.saveVerifiedProfile(job.printer.deviceKey, it) }
            }.onSuccess { profile ->
                pendingHardwareTestJob = null
                container.log.add("Hardware test observation recorded: ${outcome.name}; profile=${profile.status.name}")
                _state.update {
                    val currentKey = (it.usb as? UsbPrinterState.Ready)?.printer?.deviceKey
                    it.copy(
                        hardwareTestAwaitingResult = false,
                        lastHardwareTestObservation = observation,
                        verifiedPrinterProfile = profile.takeIf { currentKey == job.printer.deviceKey } ?: it.verifiedPrinterProfile,
                        error = null
                    )
                }
            }.onFailure {
                container.log.add("Hardware profile save failed: ${it.javaClass.simpleName}")
                _state.update { state -> state.copy(hardwareTestAwaitingResult = true, error = AppError.PROFILE_SAVE_ERROR) }
            }
        }
    }
    fun clearError() = _state.update { it.copy(error = null) }
    fun setAdvancedMode(enabled: Boolean) = viewModelScope.launch { container.preferences.setAdvancedMode(enabled) }
    fun applyPreset(settings: PrintSettings) = updateSettings(settings)
    fun saveCustomPreset(name: String) = viewModelScope.launch { container.preferences.saveCustomPreset(name, _state.value.settings) }
    fun deleteCustomPreset(id: String) = viewModelScope.launch { container.preferences.deleteCustomPreset(id) }

    fun saveExperimentalOverride(override: ExperimentalPrinterOverride) = viewModelScope.launch {
        val printer = (_state.value.usb as? UsbPrinterState.Ready)?.printer ?: return@launch
        container.preferences.saveOverride(printer.deviceKey, override)
        _state.update { refreshed(it.copy(printerOverride = override.takeUnless(ExperimentalPrinterOverride::isEmpty))) }
    }

    fun exportDiagnostics(uri: Uri, json: Boolean) = viewModelScope.launch {
        runCatching {
            val report = diagnostics()
            val content = if (json) diagnosticJson(report) else report
            checkNotNull(container.appContext.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(content) }
            container.log.add("Diagnostics exported as ${if (json) "JSON" else "TXT"}")
        }.onFailure { container.log.add("Diagnostics export failed: ${it.javaClass.simpleName}") }
    }

    fun exportCompatibilityRecord(uri: Uri) = viewModelScope.launch {
        val profile = _state.value.verifiedPrinterProfile ?: run {
            _state.update { it.copy(error = AppError.PROFILE_EXPORT_ERROR) }
            return@launch
        }
        runCatching {
            val json = CompatibilityRecordJson.encode(
                profile,
                CompatibilityExportEnvironment(android.os.Build.VERSION.RELEASE ?: "unknown", android.os.Build.VERSION.SDK_INT)
            )
            checkNotNull(container.appContext.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(json) }
            container.log.add("Privacy-safe compatibility record exported")
        }.onFailure {
            container.log.add("Compatibility export failed: ${it.javaClass.simpleName}")
            _state.update { state -> state.copy(error = AppError.PROFILE_EXPORT_ERROR) }
        }
    }

    fun diagnostics(): String {
        val printer = (_state.value.usb as? UsbPrinterState.Ready)?.printer?.capabilities
        return buildString {
            appendLine("USB Print — диагностика")
            appendLine("Статус: ${_state.value.usb::class.simpleName}")
            if (printer != null) {
                appendLine("Название: ${printer.displayName}")
                appendLine("Производитель: ${printer.manufacturer ?: "неизвестно"}")
                appendLine("Модель: ${printer.model ?: "неизвестно"}")
                appendLine("VID: ${printer.vendorId.toString(16).padStart(4, '0')}  PID: ${printer.productId.toString(16).padStart(4, '0')}")
                appendLine("USB Device ID: ${printer.usbDeviceId}")
                appendLine("USB class/subclass/protocol: ${printer.usbClass}/${printer.usbSubclass}/${printer.usbProtocol}")
                appendLine("Interfaces:")
                printer.interfaces.forEach { appendLine("  #${it.id}: ${it.interfaceClass}/${it.subclass}/${it.protocol}; ${it.endpoints.joinToString { e -> "${e.direction} ${e.type} 0x${e.address.toString(16)}" }}") }
                appendLine("CMD: ${printer.deviceIdFields["CMD"] ?: printer.deviceIdFields["COMMAND SET"] ?: "не предоставлен"}")
                appendLine("Обнаруженные языки: ${printer.supportedLanguages.joinToString { it.label }.ifBlank { "не предоставлены" }}")
                appendLine("IPP-over-USB interfaces: ${printer.ipp.interfaceIds.joinToString().ifBlank { "не обнаружены" }}")
                if (printer.ipp.isDiscovered) {
                    appendLine("IPP status code: ${printer.ipp.rawStatusCode?.let { "0x${it.toString(16).padStart(4, '0')}" } ?: "не получен"}")
                    appendLine("IPP request-id: ${printer.ipp.requestId ?: "не получен"}")
                    appendLine("IPP versions: ${printer.ipp.versionStrings.joinToString().ifBlank { "не предоставлены" }}")
                    appendLine("IPP operations: ${printer.ipp.operationsSupported.sorted().joinToString { "0x${it.toString(16).padStart(4, '0')}" }.ifBlank { "не предоставлены" }}")
                    appendLine("IPP document formats: ${printer.ipp.documentFormatsSupported.joinToString().ifBlank { "не предоставлены" }}")
                    appendLine("IPP printer state/reasons: ${printer.ipp.printerState ?: "не предоставлен"} / ${printer.ipp.printerStateReasons.joinToString().ifBlank { "не предоставлены" }}")
                    appendLine("IPP accepting jobs: ${printer.ipp.acceptingJobs ?: "не предоставлено"}")
                    appendLine("IPP resolutions: ${printer.reportedResolutions?.value?.joinToString { it.displayName } ?: "не предоставлены"}")
                    appendLine("IPP media sources: ${printer.reportedMediaSourceOptions?.value?.joinToString { it.rawKeyword } ?: "не предоставлены"}")
                    appendLine("IPP media types: ${printer.reportedMediaTypeOptions?.value?.joinToString { it.rawKeyword } ?: "не предоставлены"}")
                    appendLine("IPP output bins: ${printer.reportedOutputBinOptions?.value?.joinToString { it.rawKeyword } ?: "не предоставлены"}")
                }
                appendLine("Выбранный backend: ${_state.value.backend.selected.title}")
                appendLine("Статус USB Printer Class: ${printer.portStatus?.userMessage ?: "Status unavailable"} (${printer.portStatus?.rawValue?.let { "0x${it.toString(16)}" } ?: "—"})")
                appendLine("IEEE-1284 Device ID: ${printer.rawDeviceId?.redactDeviceId() ?: "не предоставлен"}")
                appendLine("Эффективные возможности:")
                appendLine(_state.value.effectiveCapabilities.capabilitySummary.ifBlank { "  Не определены" })
                _state.value.effectiveCapabilities.limitations.forEach { appendLine("Ограничение: $it") }
                _state.value.printerOverride?.let { appendLine("Экспериментальное переопределение: активно") }
            }
        }
    }

    private fun loadPrinterLocalState(deviceKey: String) = viewModelScope.launch {
        val override = container.preferences.overrideFor(deviceKey)
        val profile = container.preferences.verifiedProfileFor(deviceKey)
        _state.update { current ->
            val currentKey = (current.usb as? UsbPrinterState.Ready)?.printer?.deviceKey
            if (currentKey == deviceKey) refreshed(current.copy(printerOverride = override, verifiedPrinterProfile = profile)) else current
        }
        refreshPreview(_state.value)
    }

    private fun refreshed(current: MainUiState): MainUiState {
        val decision = decision(current.usb, current.document, current.settings, current.printerOverride)
        val effective = (current.usb as? UsbPrinterState.Ready)?.let { BackendRegistry.effectiveFor(decision.selected, it.printer.capabilities, current.printerOverride) }
            ?: EffectivePrintCapabilities.NONE
        return current.copy(backend = decision, effectiveCapabilities = effective)
    }

    private fun refreshPreview(snapshot: MainUiState = _state.value) {
        val document = snapshot.document ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            try {
                val preview = container.documents.renderPrintPreview(
                    document,
                    snapshot.settings,
                    previewDpi(snapshot),
                    snapshot.effectiveCapabilities.hardwareMargins?.value ?: ru.usbprint.domain.model.HardwareMarginsMm.ZERO
                )
                _state.update { current ->
                    if (
                        current.document?.uri == document.uri && current.settings == snapshot.settings &&
                        current.backend.selected == snapshot.backend.selected && previewDpi(current) == previewDpi(snapshot) &&
                        current.effectiveCapabilities.hardwareMargins?.value == snapshot.effectiveCapabilities.hardwareMargins?.value
                    ) current.copy(preview = preview) else current
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update { current -> current.copy(error = (throwable as? PrintException)?.error ?: AppError.RENDER_ERROR) }
            }
        }
    }

    private fun previewDpi(state: MainUiState): Int = state.settings.selectedResolution?.horizontalDpi
        ?: state.effectiveCapabilities.resolutions?.value?.filter { it.horizontalDpi == it.verticalDpi }?.minByOrNull { it.horizontalDpi }?.horizontalDpi
        ?: ru.usbprint.printing.PrintLayoutEngine.DEFAULT_DPI

    private fun diagnosticJson(text: String): String = buildString {
        append("{\n  \"application\": \"USB Print\",\n  \"version\": \"")
        append(ru.usbprint.BuildConfig.VERSION_NAME)
        append("\",\n  \"generatedAtEpochMs\": ")
        append(System.currentTimeMillis())
        append(",\n  \"report\": \"")
        append(text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"))
        append("\"\n}\n")
    }

    private fun String.redactDeviceId(): String = replace(
        Regex("(?i)(SN|SERN|SERIALNUMBER|SERIAL|DEVICEID):[^;]*"),
        "$1:[скрыт]"
    )

    private fun decision(usb: UsbPrinterState, document: DocumentRef?, settings: PrintSettings, override: ExperimentalPrinterOverride? = null): BackendDecision =
        (usb as? UsbPrinterState.Ready)?.let { BackendRegistry.select(it.printer.capabilities, document, settings, override) }
            ?: BackendDecision(BackendId.NONE, emptyList())

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(container) as T
        }
    }
}
