package com.shortcuts.app.service

import com.shortcuts.app.util.AccessibilityStatusChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceDisconnectMonitorTest {
    @Test
    fun `destroy then reconnect inside grace period keeps recording steps and sends no notification`() {
        val harness = RecordingHarness(enabledServices = null)

        harness.destroyService()
        assertEquals(
            AccessibilityServiceDisconnectMonitor.DEFAULT_GRACE_PERIOD_MILLIS,
            harness.scheduler.lastScheduledDelayMillis
        )
        harness.connectService()
        harness.scheduler.runPendingTasks()

        assertTrue(harness.owner.isRecording.value)
        assertEquals(1, harness.owner.recordedActions.value.size)
        assertEquals("Continue", harness.owner.recordedActions.value.single().targetText)
        assertEquals(0, harness.disconnectNotificationCount)
    }

    @Test
    fun `destroy with no reconnect and absent settings component stops and reports disconnect`() {
        val harness = RecordingHarness(enabledServices = null)

        harness.destroyService()
        harness.scheduler.runPendingTasks()

        assertFalse(harness.owner.isRecording.value)
        assertEquals(1, harness.owner.recordedActions.value.size)
        assertEquals(1, harness.disconnectNotificationCount)
    }

    @Test
    fun `destroy with no reconnect but enabled settings keeps captured steps`() {
        val harness = RecordingHarness(enabledServices = ENABLED_SERVICE_COMPONENT)

        harness.destroyService()
        harness.scheduler.runPendingTasks()

        assertTrue(harness.owner.isRecording.value)
        assertEquals(1, harness.owner.recordedActions.value.size)
        assertEquals("Continue", harness.owner.recordedActions.value.single().targetText)
        assertEquals(0, harness.disconnectNotificationCount)
    }

    @Test
    fun `rapid repeated recycles do not produce repeated disconnect notifications`() {
        val harness = RecordingHarness(enabledServices = null)

        harness.destroyService()
        harness.connectService()
        harness.destroyService()
        harness.connectService()
        harness.scheduler.runPendingTasks()

        assertTrue(harness.owner.isRecording.value)
        assertEquals(0, harness.disconnectNotificationCount)

        harness.destroyService()
        harness.scheduler.runPendingTasks()
        harness.destroyService()
        harness.scheduler.runPendingTasks()

        assertFalse(harness.owner.isRecording.value)
        assertEquals(1, harness.disconnectNotificationCount)
    }

    private class RecordingHarness(var enabledServices: String?) {
        val scheduler = FakeGracePeriodScheduler()
        val owner = RecorderSessionOwner()
        var disconnectNotificationCount = 0
            private set

        private val monitor = AccessibilityServiceDisconnectMonitor(
            scheduler = scheduler,
            isRecording = { owner.isRecording.value }
        )

        init {
            owner.start()
            owner.processEvent(
                RecorderEvent(
                    eventType = RecorderEventType.CLICK,
                    packageName = "com.example.otherapp",
                    sourceText = "Continue",
                    sourceContentDescription = null,
                    sourceViewId = "com.example.otherapp:id/continue",
                    enteredText = ""
                ),
                MY_PACKAGE
            )
        }

        fun destroyService() {
            monitor.onServiceDestroyed(
                isAccessibilityEnabled = {
                    AccessibilityStatusChecker.isAccessibilityServiceEnabledFromSettingString(
                        enabledServices
                    )
                },
                onDisconnectConfirmed = {
                    owner.stop()
                    disconnectNotificationCount += 1
                }
            )
        }

        fun connectService() {
            monitor.onServiceConnected()
        }
    }

    private class FakeGracePeriodScheduler : GracePeriodScheduler {
        private val tasks = mutableListOf<PendingTask>()
        var lastScheduledDelayMillis = -1L
            private set

        override fun schedule(delayMillis: Long, task: () -> Unit): GracePeriodCancellation {
            lastScheduledDelayMillis = delayMillis
            val pending = PendingTask(task)
            tasks += pending
            return GracePeriodCancellation { pending.cancelled = true }
        }

        fun runPendingTasks() {
            val pendingTasks = tasks.toList()
            tasks.clear()
            pendingTasks.filterNot { it.cancelled }.forEach { it.task() }
        }

        private class PendingTask(
            val task: () -> Unit,
            var cancelled: Boolean = false
        )
    }

    private companion object {
        const val MY_PACKAGE = "com.shortcuts.app"
        const val ENABLED_SERVICE_COMPONENT =
            "com.shortcuts.app/.service.AutomationAccessibilityService"
    }
}
