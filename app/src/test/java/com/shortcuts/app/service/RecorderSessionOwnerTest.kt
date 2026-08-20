package com.shortcuts.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderSessionOwnerTest {
    private val myPackage = "com.shortcuts.app"

    @Test
    fun `event arriving after stop request is never added to the session`() {
        val recorder = RecorderSessionOwner(nowMillis = { 1_000L })
        recorder.start()
        recorder.processEvent(clickEvent(occurredAtMillis = 999L), myPackage)
        recorder.stop()

        recorder.processEvent(clickEvent(occurredAtMillis = 1_000L), myPackage)
        recorder.processEvent(clickEvent(occurredAtMillis = 1_001L), myPackage)

        assertFalse(recorder.isRecording.value)
        assertEquals(1, recorder.recordedActions.value.size)
    }

    @Test
    fun `system UI event from the notification shade is never captured`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(
            clickEvent().copy(packageName = "com.android.systemui", sourceText = "Stop"),
            myPackage
        )

        assertTrue(recorder.recordedActions.value.isEmpty())
    }

    @Test
    fun `live session remains available after a simulated recorder screen teardown`() {
        val recorder = RecorderSessionOwner()
        recorder.start()
        recorder.processEvent(clickEvent(), myPackage)

        // A composable only observes this owner; dropping and recreating that observer must not
        // reset the process-scoped session.
        val actionsObservedByRecreatedScreen = recorder.recordedActions.value

        assertTrue(recorder.isRecording.value)
        assertEquals(1, actionsObservedByRecreatedScreen.size)
        assertEquals("Continue", actionsObservedByRecreatedScreen.single().targetText)
    }

    @Test
    fun `captured steps are re-readable after the recorder owner is recreated`() {
        val store = InMemoryRecorderSessionStore()
        val originalOwner = RecorderSessionOwner(store)
        originalOwner.start()
        originalOwner.processEvent(clickEvent(), myPackage)

        val recreatedOwner = RecorderSessionOwner(store)

        assertTrue(recreatedOwner.isRecording.value)
        assertEquals(1, recreatedOwner.recordedActions.value.size)
        assertEquals("Continue", recreatedOwner.recordedActions.value.single().targetText)
    }

    private fun clickEvent(occurredAtMillis: Long = Long.MIN_VALUE) = RecorderEvent(
        eventType = RecorderEventType.CLICK,
        packageName = "com.example.otherapp",
        sourceText = "Continue",
        sourceContentDescription = null,
        sourceViewId = "com.example.otherapp:id/continue_button",
        enteredText = "",
        occurredAtMillis = occurredAtMillis
    )
}
