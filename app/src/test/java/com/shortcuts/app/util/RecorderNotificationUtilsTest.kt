package com.shortcuts.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderNotificationUtilsTest {

    @Test
    fun testFormatBadgeText() {
        assertEquals("", RecorderNotificationPresenter.formatBadgeText(0))
        assertEquals("1", RecorderNotificationPresenter.formatBadgeText(1))
        assertEquals("42", RecorderNotificationPresenter.formatBadgeText(42))
        assertEquals("99+", RecorderNotificationPresenter.formatBadgeText(100))
        assertEquals("99+", RecorderNotificationPresenter.formatBadgeText(1000))
        // Negative test just in case
        assertEquals("", RecorderNotificationPresenter.formatBadgeText(-1))
    }

    @Test
    fun testFormatContentText() {
        assertEquals("Recording — 1 step captured", RecorderNotificationPresenter.formatContentText(1))
        assertEquals("Recording — 0 steps captured", RecorderNotificationPresenter.formatContentText(0))
        assertEquals("Recording — 3 steps captured", RecorderNotificationPresenter.formatContentText(3))
    }

    @Test
    fun testFormatChipText() {
        assertEquals("REC 0", RecorderNotificationPresenter.formatChipText(0))
        assertEquals("REC 1", RecorderNotificationPresenter.formatChipText(1))
        assertEquals("REC 3", RecorderNotificationPresenter.formatChipText(3))
        assertEquals("REC 42", RecorderNotificationPresenter.formatChipText(42))
        assertEquals("REC 99+", RecorderNotificationPresenter.formatChipText(100))
        assertEquals("REC 99+", RecorderNotificationPresenter.formatChipText(1000))
        assertEquals("REC 0", RecorderNotificationPresenter.formatChipText(-1))
    }

    @Test
    fun testDeterminePresentation() {
        assertEquals(RecorderNotificationPresenter.Presentation.BITMAP_BADGE, RecorderNotificationPresenter.determinePresentation(26, false))
        assertEquals(RecorderNotificationPresenter.Presentation.BITMAP_BADGE, RecorderNotificationPresenter.determinePresentation(34, false))
        assertEquals(RecorderNotificationPresenter.Presentation.BITMAP_BADGE, RecorderNotificationPresenter.determinePresentation(35, false))
        
        // API 36 but promotion failed (canPromote = false)
        assertEquals(RecorderNotificationPresenter.Presentation.BITMAP_BADGE, RecorderNotificationPresenter.determinePresentation(36, false))
        
        // API 36 and promotion succeeded
        assertEquals(RecorderNotificationPresenter.Presentation.PROMOTED_CHIP, RecorderNotificationPresenter.determinePresentation(36, true))
        assertEquals(RecorderNotificationPresenter.Presentation.PROMOTED_CHIP, RecorderNotificationPresenter.determinePresentation(37, true))
    }

    @Test
    fun testNotificationThrottler() {
        var currentTime = 1000L
        val updates = mutableListOf<Int>()
        var pendingTask: (() -> Unit)? = null

        val throttler = NotificationThrottler(
            windowMillis = 500L,
            nowMillis = { currentTime },
            scheduleTask = { _, task ->
                pendingTask = task
                "taskToken"
            },
            cancelTask = {
                pendingTask = null
            },
            onUpdate = { count ->
                updates.add(count)
            }
        )

        // Initial change should update immediately
        throttler.onCountChanged(1)
        assertEquals(listOf(1), updates)
        assertEquals(null, pendingTask)

        // Two changes inside the window produce one scheduled update
        currentTime = 1100L
        throttler.onCountChanged(2)
        assertEquals(listOf(1), updates)
        assertEquals(true, pendingTask != null)

        currentTime = 1200L
        throttler.onCountChanged(3)
        assertEquals(listOf(1), updates) // still not updated

        // Simulate time passing and task executing
        currentTime = 1500L
        pendingTask?.invoke()
        assertEquals(listOf(1, 3), updates)

        // A change after the window produces another immediately
        currentTime = 2100L
        throttler.onCountChanged(4)
        assertEquals(listOf(1, 3, 4), updates)

        // Stopping always forces a final update
        currentTime = 2200L
        throttler.onCountChanged(5) // scheduled
        assertEquals(listOf(1, 3, 4), updates)

        currentTime = 2250L
        throttler.forceUpdate(6)
        assertEquals(listOf(1, 3, 4, 6), updates)
        assertEquals(null, pendingTask) // task should be cancelled/cleared
    }
}
