package com.shortcuts.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderSessionStopReasonTest {

    @Test
    fun `only an explicitly finished recording shows the finished shortcut notification`() {
        assertTrue(RecorderSessionStopReason.USER_FINISHED.shouldShowFinishedNotification())
        assertFalse(RecorderSessionStopReason.ACCESSIBILITY_DISCONNECTED.shouldShowFinishedNotification())
        assertFalse(RecorderSessionStopReason.DISCARDED.shouldShowFinishedNotification())
    }
}
