package ru.usbprint.printing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.PrintJobStatus
import ru.usbprint.usb.UsbTransport
import java.util.Locale

enum class PrintProgressUnit { BYTES, PAGES, SHEETS }

/** A progress observation backed by actual bytes or completed rendering units. Null total means indeterminate. */
data class PrintProgressUpdate(
    val status: PrintJobStatus,
    val completed: Long? = null,
    val total: Long? = null,
    val unit: PrintProgressUnit? = null
) {
    init {
        require(completed == null || completed >= 0L)
        require(total == null || total > 0L)
        require((completed == null) == (unit == null))
        require(total == null || completed != null && completed <= total)
    }

    val percent: Int? get() = total?.let { ((completed!!.toDouble() / it) * 100.0).toInt().coerceIn(0, 100) }
    val detail: String? get() = when (unit) {
        PrintProgressUnit.BYTES -> if (total == null) "Передано ${formatMetricBytes(completed!!)}" else "${formatMetricBytes(completed!!)} / ${formatMetricBytes(total)}"
        PrintProgressUnit.PAGES -> if (total == null) "Страниц: $completed" else "Страница $completed / $total"
        PrintProgressUnit.SHEETS -> if (total == null) "Листов: $completed" else "Лист $completed / $total"
        null -> null
    }

    companion object {
        fun indeterminate(status: PrintJobStatus) = PrintProgressUpdate(status)
        fun bytes(status: PrintJobStatus, completed: Long, total: Long?) = PrintProgressUpdate(status, completed, total, PrintProgressUnit.BYTES)
        fun pages(status: PrintJobStatus, completed: Int, total: Int) = PrintProgressUpdate(status, completed.toLong(), total.toLong(), PrintProgressUnit.PAGES)
        fun sheets(status: PrintJobStatus, completed: Int, total: Int) = PrintProgressUpdate(status, completed.toLong(), total.toLong(), PrintProgressUnit.SHEETS)
    }
}

data class PrintJobMetrics(
    val jobId: String,
    val backend: BackendId,
    val terminalStatus: PrintJobStatus,
    val error: AppError?,
    val startedAtEpochMs: Long,
    val totalTimeMs: Long,
    val prepareTimeMs: Long,
    val renderTimeMs: Long?,
    val encodeTimeMs: Long?,
    val usbWriteTimeMs: Long,
    val ippWaitTimeMs: Long?,
    val bytesGenerated: Long?,
    val bytesSent: Long,
    val pagesRendered: Int?,
    val physicalSheetsGenerated: Int?,
    val peakRasterBufferBytes: Long?
) {
    init {
        require(jobId.isNotBlank() && jobId.length <= 8)
        require(backend != BackendId.NONE)
        require(terminalStatus in setOf(PrintJobStatus.SENT, PrintJobStatus.CANCELLED, PrintJobStatus.ERROR))
        require(startedAtEpochMs > 0L)
        require(listOf(totalTimeMs, prepareTimeMs, usbWriteTimeMs).all { it >= 0L })
    }

    fun diagnosticLine(): String = buildString {
        append("job=").append(jobId)
        append(" backend=").append(backend.name)
        append(" status=").append(terminalStatus.name)
        error?.let { append(" error=").append(it.name) }
        append(" totalMs=").append(totalTimeMs)
        append(" prepareMs=").append(prepareTimeMs)
        renderTimeMs?.let { append(" renderMs=").append(it) }
        encodeTimeMs?.let { append(" encodeMs=").append(it) }
        append(" usbWriteMs=").append(usbWriteTimeMs)
        ippWaitTimeMs?.let { append(" ippWaitMs=").append(it) }
        bytesGenerated?.let { append(" bytesGenerated=").append(it) }
        append(" bytesSent=").append(bytesSent)
        pagesRendered?.let { append(" pagesRendered=").append(it) }
        physicalSheetsGenerated?.let { append(" physicalSheets=").append(it) }
        peakRasterBufferBytes?.let { append(" peakRasterBufferBytes=").append(it) }
    }
}

/** Mutable per-job accumulator. It contains technical counters only, never document or device identity. */
class PrintMetricsCollector(
    jobId: String,
    private val backend: BackendId,
    private val startedAtEpochMs: Long = System.currentTimeMillis(),
    private val nanoTime: () -> Long = System::nanoTime
) : PrintMetricsSink {
    private val safeJobId = jobId.take(8).ifBlank { "unknown" }
    private val startedNanos = nanoTime()
    private var preparedNanos: Long? = null
    private var renderNanos = 0L
    private var encodeNanos = 0L
    private var usbWriteNanos = 0L
    private var ippWaitNanos = 0L
    private var ippWaitStartedNanos: Long? = null
    private var generatedBytes = 0L
    private var sentBytes = 0L
    private var renderedPages = 0
    private var generatedSheets = 0
    private var currentRasterBytes = 0L
    private var peakRasterBytes = 0L
    private var sawRender = false
    private var sawEncode = false
    private var sawGeneration = false
    private var sawPages = false
    private var sawSheets = false
    private var sawRasterBuffer = false

    @Synchronized fun markPrepared() { if (preparedNanos == null) preparedNanos = elapsedSinceStart() }
    @Synchronized override fun addRenderNanos(value: Long) { require(value >= 0L); renderNanos = saturatedAdd(renderNanos, value); sawRender = true }
    @Synchronized override fun addEncodeNanos(value: Long) { require(value >= 0L); encodeNanos = saturatedAdd(encodeNanos, value); sawEncode = true }
    @Synchronized override fun addGeneratedBytes(value: Long) { require(value >= 0L); generatedBytes = saturatedAdd(generatedBytes, value); sawGeneration = true }
    @Synchronized override fun recordPageRendered(count: Int) { require(count >= 0); renderedPages = saturatedAdd(renderedPages, count); sawPages = true }
    @Synchronized override fun recordPhysicalSheet(count: Int) { require(count >= 0); generatedSheets = saturatedAdd(generatedSheets, count); sawSheets = true }
    @Synchronized override fun allocateRasterBuffer(bytes: Long) {
        require(bytes >= 0L)
        currentRasterBytes = saturatedAdd(currentRasterBytes, bytes)
        peakRasterBytes = maxOf(peakRasterBytes, currentRasterBytes)
        sawRasterBuffer = true
    }
    @Synchronized override fun releaseRasterBuffer(bytes: Long) { require(bytes >= 0L); currentRasterBytes = (currentRasterBytes - bytes).coerceAtLeast(0L) }
    @Synchronized override fun recordUsbWrite(bytes: Long, elapsedNanos: Long) {
        require(bytes >= 0L && elapsedNanos >= 0L)
        sentBytes = saturatedAdd(sentBytes, bytes)
        usbWriteNanos = saturatedAdd(usbWriteNanos, elapsedNanos)
    }
    @Synchronized override fun beginIppWait() { if (ippWaitStartedNanos == null) ippWaitStartedNanos = nanoTime() }
    @Synchronized override fun endIppWait() {
        ippWaitStartedNanos?.let { ippWaitNanos = saturatedAdd(ippWaitNanos, (nanoTime() - it).coerceAtLeast(0L)) }
        ippWaitStartedNanos = null
    }

    @Synchronized fun finish(status: PrintJobStatus, error: AppError? = null): PrintJobMetrics {
        endIppWait()
        val totalNanos = elapsedSinceStart()
        return PrintJobMetrics(
            jobId = safeJobId,
            backend = backend,
            terminalStatus = status,
            error = error,
            startedAtEpochMs = startedAtEpochMs,
            totalTimeMs = nanosToMillis(totalNanos),
            prepareTimeMs = nanosToMillis(preparedNanos ?: totalNanos),
            renderTimeMs = renderNanos.takeIf { sawRender }?.let(::nanosToMillis),
            encodeTimeMs = encodeNanos.takeIf { sawEncode }?.let(::nanosToMillis),
            usbWriteTimeMs = nanosToMillis(usbWriteNanos),
            ippWaitTimeMs = ippWaitNanos.takeIf { it > 0L }?.let(::nanosToMillis),
            bytesGenerated = generatedBytes.takeIf { sawGeneration },
            bytesSent = sentBytes,
            pagesRendered = renderedPages.takeIf { sawPages },
            physicalSheetsGenerated = generatedSheets.takeIf { sawSheets },
            peakRasterBufferBytes = peakRasterBytes.takeIf { sawRasterBuffer }
        )
    }

    private fun elapsedSinceStart() = (nanoTime() - startedNanos).coerceAtLeast(0L)
    private fun nanosToMillis(value: Long) = value / 1_000_000L
    private fun saturatedAdd(left: Long, right: Long) = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
    private fun saturatedAdd(left: Int, right: Int) = if (Int.MAX_VALUE - left < right) Int.MAX_VALUE else left + right
}

interface PrintMetricsSink {
    fun addRenderNanos(value: Long) = Unit
    fun addEncodeNanos(value: Long) = Unit
    fun addGeneratedBytes(value: Long) = Unit
    fun recordPageRendered(count: Int = 1) = Unit
    fun recordPhysicalSheet(count: Int = 1) = Unit
    fun allocateRasterBuffer(bytes: Long) = Unit
    fun releaseRasterBuffer(bytes: Long) = Unit
    fun recordUsbWrite(bytes: Long, elapsedNanos: Long) = Unit
    fun beginIppWait() = Unit
    fun endIppWait() = Unit

    companion object { val NONE: PrintMetricsSink = object : PrintMetricsSink {} }
}

inline fun <T> PrintMetricsSink.measureRender(block: () -> T): T {
    val start = System.nanoTime()
    return try { block() } finally { addRenderNanos((System.nanoTime() - start).coerceAtLeast(0L)) }
}

inline fun <T> PrintMetricsSink.measureEncode(block: () -> T): T {
    val start = System.nanoTime()
    return try { block() } finally { addEncodeNanos((System.nanoTime() - start).coerceAtLeast(0L)) }
}

class PrintJobMetricsStore(private val maxJobs: Int = 20) {
    init { require(maxJobs > 0) }
    private val _history = MutableStateFlow<List<PrintJobMetrics>>(emptyList())
    val history = _history.asStateFlow()
    fun record(metrics: PrintJobMetrics) { _history.update { (it + metrics).takeLast(maxJobs) } }
}

/** Counts completed USB writes and their real elapsed time without changing transport semantics. */
class MetricsUsbTransport(
    private val delegate: UsbTransport,
    private val metrics: PrintMetricsSink,
    private val nanoTime: () -> Long = System::nanoTime
) : UsbTransport {
    override suspend fun open() = delegate.open()
    override suspend fun write(bytes: ByteArray, timeoutMs: Int) {
        val started = nanoTime()
        var completed = false
        try { delegate.write(bytes, timeoutMs); completed = true }
        finally { metrics.recordUsbWrite(if (completed) bytes.size.toLong() else 0L, (nanoTime() - started).coerceAtLeast(0L)) }
    }
    override suspend fun read(maxBytes: Int, timeoutMs: Int) = delegate.read(maxBytes, timeoutMs)
    override suspend fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, timeoutMs: Int) =
        delegate.controlTransfer(requestType, request, value, index, buffer, timeoutMs)
    override val isConnected: Boolean get() = delegate.isConnected
    override fun close() = delegate.close()
}

private fun formatMetricBytes(value: Long): String = when {
    value >= 1_048_576L -> String.format(Locale.ROOT, "%.1f МБ", value / 1_048_576.0)
    value >= 1_024L -> String.format(Locale.ROOT, "%.1f КБ", value / 1_024.0)
    else -> "$value Б"
}
