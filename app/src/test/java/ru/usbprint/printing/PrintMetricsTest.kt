package ru.usbprint.printing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.AppError
import ru.usbprint.domain.model.BackendId
import ru.usbprint.domain.model.PrintJobStatus
import ru.usbprint.usb.UsbTransport

class PrintMetricsTest {
    @Test fun progressUsesRealUnitsAndUnknownTotalsStayIndeterminate() {
        val bytes = PrintProgressUpdate.bytes(PrintJobStatus.SENDING, 1_024, 4_096)
        val unknown = PrintProgressUpdate.bytes(PrintJobStatus.SENDING, 1_024, null)
        val sheets = PrintProgressUpdate.sheets(PrintJobStatus.GENERATING_PAYLOAD, 4, 20)

        assertEquals(25, bytes.percent)
        assertEquals("1.0 КБ / 4.0 КБ", bytes.detail)
        assertNull(unknown.percent)
        assertEquals("Передано 1.0 КБ", unknown.detail)
        assertEquals(20, sheets.percent)
        assertEquals("Лист 4 / 20", sheets.detail)
    }

    @Test fun collectorRecordsDurationsCountersAndPeakWithoutPrivateMetadata() {
        var now = 0L
        val collector = PrintMetricsCollector("12345678-private-tail", BackendId.IPP_PWG, startedAtEpochMs = 1L) { now }
        now = 5_000_000L
        collector.markPrepared()
        collector.addRenderNanos(3_000_000L)
        collector.addEncodeNanos(4_000_000L)
        collector.addGeneratedBytes(1_000L)
        collector.recordUsbWrite(900L, 2_000_000L)
        collector.recordPageRendered(3)
        collector.recordPhysicalSheet(2)
        collector.allocateRasterBuffer(100L)
        collector.releaseRasterBuffer(100L)
        collector.allocateRasterBuffer(250L)
        now = 10_000_000L
        collector.beginIppWait()
        now = 17_000_000L
        collector.endIppWait()
        now = 25_000_000L

        val metrics = collector.finish(PrintJobStatus.ERROR, AppError.IPP_JOB_REJECTED)

        assertEquals("12345678", metrics.jobId)
        assertEquals(25L, metrics.totalTimeMs)
        assertEquals(5L, metrics.prepareTimeMs)
        assertEquals(3L, metrics.renderTimeMs)
        assertEquals(4L, metrics.encodeTimeMs)
        assertEquals(2L, metrics.usbWriteTimeMs)
        assertEquals(7L, metrics.ippWaitTimeMs)
        assertEquals(1_000L, metrics.bytesGenerated)
        assertEquals(900L, metrics.bytesSent)
        assertEquals(3, metrics.pagesRendered)
        assertEquals(2, metrics.physicalSheetsGenerated)
        assertEquals(250L, metrics.peakRasterBufferBytes)
        assertTrue(!metrics.diagnosticLine().contains("private-tail"))
    }

    @Test fun metricsHistoryKeepsOnlyTheConfiguredNumberOfJobs() {
        val store = PrintJobMetricsStore(maxJobs = 2)
        listOf("one", "two", "three").forEach { id ->
            store.record(PrintMetricsCollector(id, BackendId.PWG_RASTER, startedAtEpochMs = 1L) { 0L }.finish(PrintJobStatus.SENT))
        }
        assertEquals(listOf("two", "three"), store.history.value.map { it.jobId })
    }

    @Test fun transportCountsOnlyCompletedWritesButAlwaysMeasuresElapsedTime() = runBlocking {
        var now = 0L
        val sink = RecordingMetricsSink()
        val delegate = FakeTransport { now += 3_000_000L }
        val transport = MetricsUsbTransport(delegate, sink) { now }

        transport.write(ByteArray(17))
        val failed = MetricsUsbTransport(FakeTransport { now += 2_000_000L; error("write failed") }, sink) { now }
        assertTrue(runCatching { failed.write(ByteArray(9)) }.isFailure)

        assertEquals(17L, sink.bytes)
        assertEquals(5_000_000L, sink.nanos)
    }

    private class RecordingMetricsSink : PrintMetricsSink {
        var bytes = 0L
        var nanos = 0L
        override fun recordUsbWrite(bytes: Long, elapsedNanos: Long) { this.bytes += bytes; nanos += elapsedNanos }
    }

    private class FakeTransport(private val writeAction: () -> Unit) : UsbTransport {
        override suspend fun open() = Unit
        override suspend fun write(bytes: ByteArray, timeoutMs: Int) = writeAction()
        override suspend fun read(maxBytes: Int, timeoutMs: Int) = ByteArray(0)
        override suspend fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, timeoutMs: Int) = 0
        override val isConnected = true
        override fun close() = Unit
    }
}
