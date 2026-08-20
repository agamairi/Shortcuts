package com.shortcuts.app.service

/** Schedules work that can be cancelled before its grace period expires. */
fun interface GracePeriodScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): GracePeriodCancellation
}

/** Handle returned by [GracePeriodScheduler] for cancelling a pending grace-period check. */
fun interface GracePeriodCancellation {
    fun cancel()
}

/**
 * Confirms an accessibility-service loss only after a reconnect grace period.
 *
 * Android can recycle an enabled accessibility service, so a destroyed service instance is not
 * enough evidence to end a recording. This coordinator stays free of Android APIs so its timing
 * and duplicate-notification behaviour can be unit tested deterministically.
 */
class AccessibilityServiceDisconnectMonitor(
    private val scheduler: GracePeriodScheduler,
    private val isRecording: () -> Boolean,
    private val gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MILLIS
) {
    private val lock = Any()
    private var generation = 0L
    private var pendingCheck: GracePeriodCancellation? = null

    /** Starts or replaces a pending disconnect confirmation for a destroyed service instance. */
    fun onServiceDestroyed(
        isAccessibilityEnabled: () -> Boolean,
        onDisconnectConfirmed: () -> Unit
    ) {
        synchronized(lock) {
            if (!isRecording()) return@synchronized
            pendingCheck?.cancel()
            generation += 1
            val nextGeneration = generation
            pendingCheck = scheduler.schedule(gracePeriodMillis) {
                confirmDisconnect(nextGeneration, isAccessibilityEnabled, onDisconnectConfirmed)
            }
        }
    }

    /** A replacement service instance connected before the grace period expired. */
    fun onServiceConnected() = synchronized(lock) {
        generation += 1
        pendingCheck?.cancel()
        pendingCheck = null
    }

    /** Prevents an old destruction callback from ending a later user-started recording. */
    fun cancelPendingDisconnect() = synchronized(lock) {
        generation += 1
        pendingCheck?.cancel()
        pendingCheck = null
    }

    private fun confirmDisconnect(
        checkGeneration: Long,
        isAccessibilityEnabled: () -> Boolean,
        onDisconnectConfirmed: () -> Unit
    ) {
        val shouldCheckSettings = synchronized(lock) {
            if (checkGeneration != generation || !isRecording()) {
                false
            } else {
                pendingCheck = null
                true
            }
        }
        if (!shouldCheckSettings || isAccessibilityEnabled()) return

        val shouldConfirm = synchronized(lock) {
            checkGeneration == generation && isRecording()
        }
        if (shouldConfirm) onDisconnectConfirmed()
    }

    companion object {
        /** Five seconds comfortably covers the observed one-second framework recycle interval. */
        const val DEFAULT_GRACE_PERIOD_MILLIS = 5_000L
    }
}
