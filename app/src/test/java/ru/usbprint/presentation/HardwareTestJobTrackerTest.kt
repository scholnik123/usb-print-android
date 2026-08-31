package ru.usbprint.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.usbprint.domain.model.PrintJobStatus

class HardwareTestJobTrackerTest {
    @Test fun matchingSentAndErrorRequestExactlyOneObservation() {
        val sent = HardwareTestJobTracker().apply { started("test-sent") }
        assertFalse(sent.onExecution("test-sent", PrintJobStatus.SENDING))
        assertTrue(sent.onExecution("test-sent", PrintJobStatus.SENT))
        assertFalse(sent.onExecution("test-sent", PrintJobStatus.SENT))

        val failed = HardwareTestJobTracker().apply { started("test-error") }
        assertTrue(failed.onExecution("test-error", PrintJobStatus.ERROR))
        assertFalse(failed.onExecution("test-error", PrintJobStatus.ERROR))
    }

    @Test fun unrelatedAndCancelledJobsNeverRequestObservation() {
        val tracker = HardwareTestJobTracker().apply { started("hardware-test") }
        assertFalse(tracker.onExecution("normal-job", PrintJobStatus.SENT))
        assertFalse(tracker.onExecution("hardware-test", PrintJobStatus.CANCELLED))
        assertFalse(tracker.onExecution("hardware-test", PrintJobStatus.SENT))
    }

    @Test fun failedServiceStartClearsPendingTest() {
        val tracker = HardwareTestJobTracker().apply { started("not-started") }
        tracker.startFailed("not-started")
        assertFalse(tracker.onExecution("not-started", PrintJobStatus.ERROR))
    }
}
