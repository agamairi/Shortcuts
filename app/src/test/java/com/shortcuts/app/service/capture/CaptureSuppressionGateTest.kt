package com.shortcuts.app.service.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSuppressionGateTest {
    @Test fun `relay suppresses duplicate gestures until 250ms after completion`() {
        val gate = CaptureSuppressionGate(true)
        gate.onRelayStarted(1_000)
        assertFalse(gate.shouldProcess(PassiveCaptureEvent.CLICK, 1_000))
        assertFalse(gate.shouldProcess(PassiveCaptureEvent.LONG_CLICK, 1_100))
        assertFalse(gate.shouldProcess(PassiveCaptureEvent.SCROLL, 1_400))
        gate.onRelayFinished(1_500)
        assertFalse(gate.shouldProcess(PassiveCaptureEvent.CLICK, 1_750))
        assertTrue(gate.shouldProcess(PassiveCaptureEvent.CLICK, 1_751))
    }
    @Test fun `IME clicks always pass through suppression`() {
        val gate = CaptureSuppressionGate(true)
        gate.onRelayStarted(1_000)
        assertTrue(gate.shouldProcess(PassiveCaptureEvent.CLICK, 1_100, fromActiveIme = true))
    }
    @Test fun `text focus and window events always pass through suppression`() {
        val gate = CaptureSuppressionGate(true)
        gate.onRelayStarted(1_000)
        assertTrue(gate.shouldProcess(PassiveCaptureEvent.TEXT, 1_100))
        assertTrue(gate.shouldProcess(PassiveCaptureEvent.FOCUS, 1_100))
        assertTrue(gate.shouldProcess(PassiveCaptureEvent.WINDOW, 1_100))
    }
    @Test fun `passive mode keeps clicks`() = assertTrue(CaptureSuppressionGate(false).shouldProcess(PassiveCaptureEvent.CLICK, 1_000))
}
