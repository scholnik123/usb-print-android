package ru.usbprint.printing

import ru.usbprint.domain.model.PrintJob
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** In-process hand-off to the foreground service. No document data is serialized or persisted. */
class PrintJobStore {
    private val active = AtomicBoolean(false)
    private val pending = AtomicReference<PrintJob?>(null)
    fun enqueue(job: PrintJob): Boolean = active.compareAndSet(false, true).also { if (it) pending.set(job) }
    fun take(): PrintJob? = pending.getAndSet(null)
    fun finish() { pending.set(null); active.set(false) }
}
