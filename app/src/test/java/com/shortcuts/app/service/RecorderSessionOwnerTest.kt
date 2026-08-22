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
    fun `repeated window changes within an app launched from home do not create an extra app intent`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(appChangeEvent(launcherPackage), myPackage, launcherPackage)
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
    fun `first foreground package only establishes baseline rather than becoming an app launch`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(appChangeEvent("com.example.otherapp"), myPackage, launcherPackage)

        assertTrue(recorder.recordedActions.value.isEmpty())
    }

    @Test
    fun `IME foreground events and keyboard clicks are ignored during recording`() {
        val recorder = RecorderSessionOwner()
        val imePackage = "com.example.keyboard"
        recorder.start()

        recorder.processEvent(appChangeEvent("com.example.otherapp"), myPackage, launcherPackage)
        recorder.processEvent(
            appChangeEvent(imePackage),
            myPackage,
            launcherPackage,
            transientPackages = setOf(imePackage)
        )
        recorder.processEvent(
            clickEvent().copy(packageName = imePackage, sourceText = "a"),
            myPackage,
            launcherPackage,
            transientPackages = setOf(imePackage)
        )
        recorder.processEvent(clickEvent().copy(sourceText = "Submit"), myPackage, launcherPackage)

        val actions = recorder.recordedActions.value
        assertEquals(1, actions.size)
        assertEquals("Submit", actions.single().targetText)
    }

    @Test
    fun `rapid foreground churn without a launcher transition does not create app intents`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(appChangeEvent("com.example.left"), myPackage, launcherPackage)
        recorder.processEvent(appChangeEvent("com.example.right"), myPackage, launcherPackage)
        recorder.processEvent(appChangeEvent("com.example.left"), myPackage, launcherPackage)

        assertTrue(recorder.recordedActions.value.isEmpty())
    }

    @Test
    fun `permission interstitial between launcher tap and app UI does not corrupt app launch`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(launcherClickEvent(), myPackage, launcherPackage)
        recorder.processEvent(
            appChangeEvent("com.android.permissioncontroller"),
            myPackage,
            launcherPackage
        )
        recorder.processEvent(
            clickEvent().copy(
                packageName = "com.android.permissioncontroller",
                sourceText = "Allow"
            ),
            myPackage,
            launcherPackage
        )
        recorder.processEvent(appChangeEvent("com.duolingo"), myPackage, launcherPackage)

        val actions = recorder.recordedActions.value
        assertEquals(1, actions.size)
        assertEquals(ActionType.APP_INTENT, actions.single().actionType)
        assertEquals("com.duolingo", actions.single().packageName)
    }

    @Test
    fun `rapid duplicate clicks on the same target are recorded once`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(clickEvent(occurredAtMillis = 1_000L), myPackage)
        recorder.processEvent(clickEvent(occurredAtMillis = 1_100L), myPackage)

        assertEquals(1, recorder.recordedActions.value.size)
    }

    @Test
    fun `separate taps on the same target outside debounce window are both retained`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(clickEvent(occurredAtMillis = 1_000L), myPackage)
        recorder.processEvent(clickEvent(occurredAtMillis = 1_400L), myPackage)

        assertEquals(2, recorder.recordedActions.value.size)
    }

    @Test
    fun `scroll burst is finalized as one action after it is flushed`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(scrollEvent(fromIndex = 0, toIndex = 4, occurredAtMillis = 1_000L), myPackage)
        recorder.processEvent(scrollEvent(fromIndex = 4, toIndex = 10, occurredAtMillis = 1_100L), myPackage)

        assertTrue(recorder.recordedActions.value.isEmpty())
        recorder.flushPendingScroll()

        val actions = recorder.recordedActions.value
        assertEquals(1, actions.size)
        assertEquals("SCROLL", actions.single().uiActionType)
        assertEquals("FORWARD", actions.single().scrollDirection)
    }

    @Test
    fun `quiet period separates scroll bursts`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(scrollEvent(fromIndex = 0, toIndex = 4, occurredAtMillis = 1_000L), myPackage)
        recorder.processEvent(scrollEvent(fromIndex = 4, toIndex = 8, occurredAtMillis = 1_300L), myPackage)
        recorder.flushPendingScroll()

        assertEquals(2, recorder.recordedActions.value.size)
    }

    @Test
    fun `stopping recording commits an in-flight scroll burst`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(scrollEvent(fromIndex = 0, toIndex = 4, occurredAtMillis = 1_000L), myPackage)
        recorder.stop()

        assertEquals(1, recorder.recordedActions.value.size)
        assertEquals("SCROLL", recorder.recordedActions.value.single().uiActionType)
    }

    @Test
    fun `backward scroll is recorded using backward direction from accessibility indices`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(scrollEvent(fromIndex = 12, toIndex = 4, occurredAtMillis = 1_000L), myPackage)
        recorder.flushPendingScroll()

        assertEquals("BACKWARD", recorder.recordedActions.value.single().scrollDirection)
    }

    @Test
    fun `scroll direction uses a negative scroll delta when indices are unavailable`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(
            scrollEvent(fromIndex = null, toIndex = null, occurredAtMillis = 1_000L).copy(scrollDeltaY = -48),
            myPackage
        )
        recorder.flushPendingScroll()

        assertEquals("BACKWARD", recorder.recordedActions.value.single().scrollDirection)
    }

    @Test
    fun `scroll direction falls back to scroll position changes when no delta or indices exist`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(
            scrollEvent(fromIndex = null, toIndex = null, occurredAtMillis = 1_000L).copy(scrollY = 300),
            myPackage
        )
        recorder.processEvent(
            scrollEvent(fromIndex = null, toIndex = null, occurredAtMillis = 1_100L).copy(scrollY = 120),
            myPackage
        )
        recorder.flushPendingScroll()

        assertEquals("BACKWARD", recorder.recordedActions.value.single().scrollDirection)
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
    fun `returning home then to the app that was foreground when recording began does not create an app intent`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(appChangeEvent("com.example.otherapp"), myPackage, launcherPackage)
        recorder.processEvent(appChangeEvent(launcherPackage), myPackage, launcherPackage)
        recorder.processEvent(appChangeEvent("com.example.otherapp"), myPackage, launcherPackage)

        assertTrue(recorder.recordedActions.value.isEmpty())
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

    private fun scrollEvent(
        fromIndex: Int?,
        toIndex: Int?,
        occurredAtMillis: Long
    ) = RecorderEvent(
        eventType = RecorderEventType.SCROLL,
        packageName = "com.example.otherapp",
        sourceText = "Feed",
        sourceContentDescription = null,
        sourceViewId = "com.example.otherapp:id/feed",
        enteredText = "",
        fromIndex = fromIndex,
        toIndex = toIndex,
        occurredAtMillis = occurredAtMillis
    )
}
