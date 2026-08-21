package com.shortcuts.app.service

import com.shortcuts.app.data.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderSessionOwnerTest {
    private val myPackage = "com.shortcuts.app"
    private val launcherPackage = "com.example.launcher"

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

    @Test
    fun `launcher click followed by a different app change records only an app intent`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(launcherClickEvent(), myPackage, launcherPackage)
        recorder.processEvent(appChangeEvent("com.duolingo"), myPackage, launcherPackage)

        val actions = recorder.recordedActions.value
        assertEquals(1, actions.size)
        assertEquals(ActionType.APP_INTENT, actions.single().actionType)
        assertEquals("com.duolingo", actions.single().packageName)
    }

    @Test
    fun `window changes within the same app do not create an extra app intent`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(appChangeEvent("com.example.otherapp"), myPackage, launcherPackage)
        recorder.processEvent(appChangeEvent("com.example.otherapp"), myPackage, launcherPackage)

        assertEquals(1, recorder.recordedActions.value.size)
        assertEquals(ActionType.APP_INTENT, recorder.recordedActions.value.single().actionType)
    }

    @Test
    fun `window changes to the recorder or system UI are ignored`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(appChangeEvent(myPackage), myPackage, launcherPackage)
        recorder.processEvent(appChangeEvent("com.android.systemui"), myPackage, launcherPackage)

        assertTrue(recorder.recordedActions.value.isEmpty())
    }

    @Test
    fun `launcher click without an app change remains a normal tap`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(launcherClickEvent(), myPackage, launcherPackage)

        val action = recorder.recordedActions.value.single()
        assertEquals(ActionType.UI_AUTOMATION, action.actionType)
        assertEquals("TAP", action.uiActionType)
        assertEquals("Duolingo", action.targetText)
    }

    @Test
    fun `going home alone does not create a recording step`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(appChangeEvent(launcherPackage), myPackage, launcherPackage)

        assertTrue(recorder.recordedActions.value.isEmpty())
    }

    @Test
    fun `returning home then to the same app does not create an app intent`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(appChangeEvent("com.example.otherapp"), myPackage, launcherPackage)
        recorder.processEvent(appChangeEvent(launcherPackage), myPackage, launcherPackage)
        recorder.processEvent(appChangeEvent("com.example.otherapp"), myPackage, launcherPackage)

        assertEquals(1, recorder.recordedActions.value.size)
        assertEquals("com.example.otherapp", recorder.recordedActions.value.single().packageName)
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

    private fun launcherClickEvent() = clickEvent().copy(
        packageName = launcherPackage,
        sourceText = "Duolingo",
        sourceViewId = "com.example.launcher:id/icon"
    )

    private fun appChangeEvent(packageName: String) = RecorderEvent(
        eventType = RecorderEventType.APP_CHANGE,
        packageName = packageName,
        sourceText = null,
        sourceContentDescription = null,
        sourceViewId = null,
        enteredText = ""
    )
}
