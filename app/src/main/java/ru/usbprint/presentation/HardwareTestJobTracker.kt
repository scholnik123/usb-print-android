package ru.usbprint.presentation

import ru.usbprint.domain.model.PrintJobStatus

/** Keeps transport completion separate from the user's later physical-page observation. */
internal class HardwareTestJobTracker {
    private var activeJobId: String? = null

    fun started(jobId: String) {
        activeJobId = jobId
    }

    fun startFailed(jobId: String) {
        if (activeJobId == jobId) activeJobId = null
    }

    /** Returns true only when the matching test job should ask for a physical observation. */
    fun onExecution(jobId: String?, status: PrintJobStatus): Boolean {
        if (jobId == null || jobId != activeJobId) return false
        return when (status) {
            PrintJobStatus.SENT, PrintJobStatus.ERROR -> {
                activeJobId = null
                true
            }
            PrintJobStatus.CANCELLED -> {
                activeJobId = null
                false
            }
            else -> false
        }
    }
}
