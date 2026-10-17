package com.shortcuts.app.service.capture

enum class PassiveCaptureEvent { CLICK, LONG_CLICK, SCROLL, TEXT, FOCUS, IME_SUBMIT, KEY, WINDOW }

/**
 * Policy boundary for relay duplicates. Time is supplied by the caller because accessibility
 * event timestamps use uptime and this class must remain deterministic in JVM tests.
 */
class CaptureSuppressionGate(private val overlayMode: Boolean) {
    private var suppressionStartedAt: Long? = null
    private var suppressionEndsAt: Long? = null

    @Synchronized
    fun onRelayStarted(uptimeMs: Long) {
        if (!overlayMode) return
        suppressionStartedAt = uptimeMs
        suppressionEndsAt = null
    }

    @Synchronized
    fun onRelayFinished(uptimeMs: Long) {
        if (!overlayMode || suppressionStartedAt == null) return
        suppressionEndsAt = uptimeMs + SYNTHETIC_EVENT_SUPPRESSION_WINDOW_MS
    }

    @Synchronized
    fun shouldProcess(
        event: PassiveCaptureEvent,
        eventUptimeMs: Long,
        fromActiveIme: Boolean = false
    ): Boolean {
        // The overlay never covers the IME. Its labelled click callbacks are the only way to
        // record Search/Send/Done, so they must reach the IME-submit branch regardless of relay.
        if (fromActiveIme) return true
        if (!overlayMode || event !in RELAY_DUPLICATE_EVENTS) return true

        val startedAt = suppressionStartedAt ?: return true
        val endsAt = suppressionEndsAt
        return eventUptimeMs < startedAt || (endsAt != null && eventUptimeMs > endsAt)
    }

    private companion object {
        val RELAY_DUPLICATE_EVENTS = setOf(
            PassiveCaptureEvent.CLICK,
            PassiveCaptureEvent.LONG_CLICK,
            PassiveCaptureEvent.SCROLL
        )
        const val SYNTHETIC_EVENT_SUPPRESSION_WINDOW_MS = 250L
    }
}
