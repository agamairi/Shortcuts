package com.shortcuts.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentTapSuggestionTrackerTest {
    private val recorderPackage = "com.shortcuts.app"
    private val targetPackage = "com.google.android.apps.messaging"

    @Test
    fun `window changes during the initial grace period do not suggest manual marking`() {
        val tracker = SilentTapSuggestionTracker()
        tracker.onRecordingStarted(startedAtMillis = 0L)

        assertFalse(tracker.shouldSuggestForWindowStateChange(2_999L, targetPackage, recorderPackage))
        assertTrue(tracker.shouldSuggestForWindowStateChange(3_000L, targetPackage, recorderPackage))
    }

    @Test
    fun `a recently captured click suppresses the suggestion`() {
        val tracker = SilentTapSuggestionTracker()
        tracker.onRecordingStarted(startedAtMillis = 0L)
        tracker.recordCapturedClick(eventTimeMillis = 3_000L)

        assertFalse(tracker.shouldSuggestForWindowStateChange(5_000L, targetPackage, recorderPackage))
        assertTrue(tracker.shouldSuggestForWindowStateChange(5_001L, targetPackage, recorderPackage))
    }

    @Test
    fun `transitions to or from the recorder app do not suggest manual marking`() {
        val tracker = SilentTapSuggestionTracker()
        tracker.onRecordingStarted(startedAtMillis = 0L, initialWindowPackage = recorderPackage)

        assertFalse(tracker.shouldSuggestForWindowStateChange(3_000L, targetPackage, recorderPackage))
        assertFalse(tracker.shouldSuggestForWindowStateChange(4_000L, recorderPackage, recorderPackage))
        assertFalse(tracker.shouldSuggestForWindowStateChange(5_000L, targetPackage, recorderPackage))
        assertTrue(tracker.shouldSuggestForWindowStateChange(6_000L, targetPackage, recorderPackage))
    }

    @Test
    fun `qualifying window changes are throttled`() {
        val tracker = SilentTapSuggestionTracker()
        tracker.onRecordingStarted(startedAtMillis = 0L)

        assertTrue(tracker.shouldSuggestForWindowStateChange(3_000L, targetPackage, recorderPackage))
        assertFalse(tracker.shouldSuggestForWindowStateChange(14_999L, targetPackage, recorderPackage))
        assertTrue(tracker.shouldSuggestForWindowStateChange(15_000L, targetPackage, recorderPackage))
    }

    @Test
    fun `stopped recording never suggests manual marking`() {
        val tracker = SilentTapSuggestionTracker()
        tracker.onRecordingStarted(startedAtMillis = 0L)
        tracker.onRecordingStopped()

        assertFalse(tracker.shouldSuggestForWindowStateChange(3_000L, targetPackage, recorderPackage))
    }
}
