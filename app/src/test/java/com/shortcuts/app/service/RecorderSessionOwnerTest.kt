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
    fun `launcher to Chrome typing and Enter records the intended replayable sequence`() {
        val recorder = RecorderSessionOwner()
        val chromePackage = "com.android.chrome"
        recorder.start()

        recorder.processEvent(appChangeEvent(launcherPackage).copy(occurredAtMillis = 100L), myPackage, launcherPackage)
        recorder.processEvent(
            launcherClickEvent().copy(sourceText = "Home", occurredAtMillis = 110L),
            myPackage,
            launcherPackage
        )
        recorder.processEvent(
            launcherClickEvent().copy(sourceText = "Chrome", occurredAtMillis = 200L),
            myPackage,
            launcherPackage
        )
        recorder.processEvent(appChangeEvent(chromePackage).copy(occurredAtMillis = 300L), myPackage, launcherPackage)
        recorder.processEvent(
            clickEvent(400L).copy(
                packageName = chromePackage,
                sourceText = "Search or type web address",
                sourceViewId = "com.android.chrome:id/url_bar",
                sourceClassName = "android.widget.EditText"
            ),
            myPackage,
            launcherPackage
        )

        "example.com".indices.forEach { index ->
            val fullText = "example.com".substring(0, index + 1)
            recorder.processEvent(
                RecorderEvent(
                    eventType = RecorderEventType.TEXT_CHANGE,
                    packageName = chromePackage,
                    sourceText = fullText,
                    sourceContentDescription = null,
                    sourceViewId = "com.android.chrome:id/url_bar",
                    enteredText = fullText,
                    sourceClassName = "android.widget.EditText",
                    occurredAtMillis = 500L + index * 40L
                ),
                myPackage,
                launcherPackage
            )
        }
        recorder.processEvent(
            RecorderEvent(
                eventType = RecorderEventType.PRESS_ENTER,
                packageName = chromePackage,
                sourceText = null,
                sourceContentDescription = null,
                sourceViewId = null,
                enteredText = "",
                occurredAtMillis = 1_100L
            ),
            myPackage,
            launcherPackage
        )

        val actions = recorder.recordedActions.value
        assertEquals(4, actions.size)
        assertEquals(ActionType.APP_INTENT, actions[0].actionType)
        assertEquals(chromePackage, actions[0].packageName)
        assertEquals("TAP", actions[1].uiActionType)
        assertEquals("com.android.chrome:id/url_bar", actions[1].targetNodeId)
        assertEquals("TYPE_TEXT", actions[2].uiActionType)
        assertEquals("example.com", actions[2].textInput)
        assertEquals("PRESS_ENTER", actions[3].uiActionType)
        assertEquals("com.android.chrome:id/url_bar", actions[3].targetNodeId)
        assertTrue(actions.none { it.targetText == "Home" })
    }

    @Test
    fun `TYPE_VIEW_TEXT_SELECTION_CHANGED burst alone records no action`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        repeat(8) { index ->
            recorder.processEvent(
                RecorderEvent(
                    eventType = RecorderEventType.SELECTION_CHANGE,
                    packageName = "com.android.chrome",
                    sourceText = "example.com",
                    sourceContentDescription = null,
                    sourceViewId = "com.android.chrome:id/url_bar",
                    enteredText = "example.com",
                    sourceClassName = "android.widget.EditText",
                    occurredAtMillis = 1_000L + index
                ),
                myPackage
            )
        }

        assertTrue(recorder.recordedActions.value.isEmpty())
    }

    @Test
    fun `long press produces one LONG_PRESS action`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(
            clickEvent(1_000L).copy(eventType = RecorderEventType.LONG_PRESS),
            myPackage
        )

        val action = recorder.recordedActions.value.single()
        assertEquals(ActionType.UI_AUTOMATION, action.actionType)
        assertEquals("LONG_PRESS", action.uiActionType)
        assertEquals("com.example.otherapp:id/continue_button", action.targetNodeId)
    }

    @Test
    fun `same-app window transition after typing infers one submit action`() {
        val recorder = RecorderSessionOwner()
        val browserPackage = "com.example.browser"
        recorder.start()
        recorder.processEvent(appChangeEvent(browserPackage), myPackage, launcherPackage)
        recorder.processEvent(
            RecorderEvent(
                eventType = RecorderEventType.TEXT_CHANGE,
                packageName = browserPackage,
                sourceText = "query",
                sourceContentDescription = "Address bar",
                sourceViewId = "com.example.browser:id/address",
                enteredText = "query",
                sourceClassName = "android.widget.EditText",
                occurredAtMillis = 1_000L
            ),
            myPackage,
            launcherPackage
        )

        recorder.processEvent(
            appChangeEvent(browserPackage).copy(occurredAtMillis = 1_050L),
            myPackage,
            launcherPackage
        )

        assertEquals(
            listOf("TYPE_TEXT", "PRESS_ENTER"),
            recorder.recordedActions.value.map { it.uiActionType }
        )
    }

    @Test
    fun `stopping commits final full text without inventing Enter`() {
        val recorder = RecorderSessionOwner()
        recorder.start()
        recorder.processEvent(
            RecorderEvent(
                eventType = RecorderEventType.TEXT_CHANGE,
                packageName = "com.example.notes",
                sourceText = "finished note",
                sourceContentDescription = "Note",
                sourceViewId = "com.example.notes:id/body",
                enteredText = "finished note",
                sourceClassName = "android.widget.EditText",
                occurredAtMillis = 1_000L
            ),
            myPackage
        )

        recorder.stop()

        val action = recorder.recordedActions.value.single()
        assertEquals("TYPE_TEXT", action.uiActionType)
        assertEquals("finished note", action.textInput)
    }

    @Test
    fun `click after typing closes submit inference`() {
        val recorder = RecorderSessionOwner()
        recorder.start()
        recorder.processEvent(
            RecorderEvent(
                eventType = RecorderEventType.TEXT_CHANGE,
                packageName = "com.example.form",
                sourceText = "answer",
                sourceContentDescription = "Answer",
                sourceViewId = "com.example.form:id/answer",
                enteredText = "answer",
                sourceClassName = "android.widget.EditText",
                occurredAtMillis = 1_000L
            ),
            myPackage
        )
        recorder.processEvent(clickEvent(1_100L), myPackage)
        recorder.processEvent(
            RecorderEvent(
                eventType = RecorderEventType.PRESS_ENTER,
                packageName = "com.example.form",
                sourceText = null,
                sourceContentDescription = null,
                sourceViewId = null,
                enteredText = "",
                occurredAtMillis = 1_200L
            ),
            myPackage
        )

        assertEquals(
            listOf("TYPE_TEXT", "TAP"),
            recorder.recordedActions.value.map { it.uiActionType }
        )
    }

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
    fun `coordinate click capture retains the display context needed for safe replay`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        recorder.processEvent(
            clickEvent().copy(
                screenX = 540,
                screenY = 1200,
                displaySnapshot = DisplaySnapshot(1080, 2400, rotation = 0, densityDpi = 420)
            ),
            myPackage
        )

        val action = recorder.recordedActions.value.single()
        assertEquals(1080, action.recordedDisplayWidth)
        assertEquals(2400, action.recordedDisplayHeight)
        assertEquals(0, action.recordedDisplayRotation)
        assertEquals(420, action.recordedDensityDpi)
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

    @Test
    fun `rapid sequential unresolved taps on a list do not infinitely debounce each other`() {
        val recorder = RecorderSessionOwner(nowMillis = { 1000L })
        recorder.start()

        // Tap 1 at 1000ms
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = "com.google.android.apps.messaging",
            sourceText = "UNRESOLVED",
            sourceContentDescription = null,
            sourceViewId = null,
            enteredText = "",
            occurredAtMillis = 1000L
        ), myPackage)

        // Tap 2 at 1200ms (duplicate, should be dropped, but should NOT extend the debounce window)
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = "com.google.android.apps.messaging",
            sourceText = "UNRESOLVED",
            sourceContentDescription = null,
            sourceViewId = null,
            enteredText = "",
            occurredAtMillis = 1200L
        ), myPackage)

        // Tap 3 at 1400ms (should be accepted, as it's 400ms after the original tap 1)
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = "com.google.android.apps.messaging",
            sourceText = "UNRESOLVED",
            sourceContentDescription = null,
            sourceViewId = null,
            enteredText = "",
            occurredAtMillis = 1400L
        ), myPackage)

        // Only Tap 1 and Tap 3 should be recorded
        val actions = recorder.recordedActions.value
        assertEquals(2, actions.size)
        assertEquals("UNRESOLVED", actions[0].targetText)
        assertEquals("UNRESOLVED", actions[1].targetText)
    }

    @Test
    fun `Repro A app launch collapse works when first app event is CLICK`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        // CLICK   nexuslauncher | ImageView  | .../home
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = launcherPackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "home",
            sourceClassName = "android.widget.ImageView",
            occurredAtMillis = 1000L
        ), myPackage, launcherPackage)

        // FOCUS   nexuslauncher | ViewPager  | .../smartspace_card_pager
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.FOCUS,
            packageName = launcherPackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "smartspace_card_pager",
            sourceClassName = "androidx.viewpager.widget.ViewPager",
            occurredAtMillis = 1010L
        ), myPackage, launcherPackage)

        // CLICK   nexuslauncher | TextView   | (Chrome icon, no id)
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = launcherPackage,
            sourceText = "Chrome",
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = null, sourceClassName = "android.widget.TextView",
            occurredAtMillis = 1020L
        ), myPackage, launcherPackage)

        // CLICK   com.android.chrome | EditText | .../url_bar
        val chromePackage = "com.android.chrome"
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = chromePackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "url_bar",
            sourceClassName = "android.widget.EditText",
            occurredAtMillis = 2000L
        ), myPackage, launcherPackage)

        // FOCUS   com.android.chrome | EditText | .../url_bar
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.FOCUS,
            packageName = chromePackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "url_bar",
            sourceClassName = "android.widget.EditText",
            occurredAtMillis = 2010L
        ), myPackage, launcherPackage)

        // FOCUS   com.android.chrome | EditText | .../url_bar
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.FOCUS,
            packageName = chromePackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "url_bar",
            sourceClassName = "android.widget.EditText",
            occurredAtMillis = 2020L
        ), myPackage, launcherPackage)

        // SCROLL  com.android.chrome | ...omnibox_suggestions_dropdown
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.SCROLL,
            packageName = chromePackage,
            sourceText = null, sourceContentDescription = null, enteredText = "", sourceViewId = "omnibox_suggestions_dropdown",
            sourceClassName = "androidx.recyclerview.widget.RecyclerView",
            occurredAtMillis = 2030L
        ), myPackage, launcherPackage)

        recorder.flushPendingText()
        recorder.flushPendingScroll()

        val actions = recorder.recordedActions.value
        assertEquals(ActionType.APP_INTENT, actions.first().actionType)
        assertEquals(chromePackage, actions.first().packageName)
        assertTrue(actions.none { it.packageName == launcherPackage })
    }

    @Test
    fun `Repro B app launch collapse works when first app event is FOCUS`() {
        val recorder = RecorderSessionOwner()
        recorder.start()

        // CLICK   nexuslauncher | ImageView    | .../home
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = launcherPackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "home",
            sourceClassName = "android.widget.ImageView",
            occurredAtMillis = 1000L
        ), myPackage, launcherPackage)

        // CLICK   nexuslauncher | TextView     | (Chrome icon)
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = launcherPackage,
            sourceText = "Chrome",
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = null, sourceClassName = "android.widget.TextView",
            occurredAtMillis = 1010L
        ), myPackage, launcherPackage)

        // FOCUS   com.android.chrome | RecyclerView | .../feed_stream_recycler_view
        val chromePackage = "com.android.chrome"
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.FOCUS,
            packageName = chromePackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "feed_stream_recycler_view",
            sourceClassName = "androidx.recyclerview.widget.RecyclerView",
            occurredAtMillis = 2000L
        ), myPackage, launcherPackage)

        // SCROLL  com.android.chrome | RecyclerView | .../feed_stream_recycler_view
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.SCROLL,
            packageName = chromePackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "feed_stream_recycler_view",
            sourceClassName = "androidx.recyclerview.widget.RecyclerView",
            occurredAtMillis = 2010L
        ), myPackage, launcherPackage)

        // FOCUS   com.android.chrome | EditText | .../url_bar
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.FOCUS,
            packageName = chromePackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "url_bar",
            sourceClassName = "android.widget.EditText",
            occurredAtMillis = 2020L
        ), myPackage, launcherPackage)

        // FOCUS   com.android.chrome | EditText | .../url_bar
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.FOCUS,
            packageName = chromePackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "url_bar",
            sourceClassName = "android.widget.EditText",
            occurredAtMillis = 2030L
        ), myPackage, launcherPackage)

        // CLICK   com.android.chrome | EditText | .../search_box_text
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = chromePackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "search_box_text",
            sourceClassName = "android.widget.EditText",
            occurredAtMillis = 2040L
        ), myPackage, launcherPackage)

        // SCROLL  com.android.chrome | ...omnibox_suggestions_dropdown
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.SCROLL,
            packageName = chromePackage,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "omnibox_suggestions_dropdown",
            sourceClassName = "androidx.recyclerview.widget.RecyclerView",
            occurredAtMillis = 2050L
        ), myPackage, launcherPackage)

        recorder.flushPendingText()
        recorder.flushPendingScroll()

        val actions = recorder.recordedActions.value
        assertEquals(ActionType.APP_INTENT, actions.first().actionType)
        assertEquals(chromePackage, actions.first().packageName)
        assertTrue(actions.none { it.packageName == launcherPackage })
    }

    @Test
    fun `app launch collapse intentionally disabled when launcher package is unresolved`() {
        // As documented in the fix for this issue, if resolveLauncherPackage returns null
        // due to transient device issues, app-launch collapse degrades to tracking raw clicks
        // instead of guessing the launcher. This avoids false-positive collapses of legitimate
        // cross-app links (e.g., clicking a link in Chrome that opens YouTube).
        // The fix is the retry mechanism in resolveLauncherPackage, rather than a risky fallback here.
        val recorder = RecorderSessionOwner()
        recorder.start()
        
        val nullLauncher: String? = null
        val launcherPkg = "com.google.android.apps.nexuslauncher"
        val chromePkg = "com.android.chrome"
        
        // CLICK launcher | ImageView | .../home
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = launcherPkg,
            sourceText = null,
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "home",
            sourceClassName = "android.widget.ImageView",
            occurredAtMillis = 1000L
        ), myPackage, nullLauncher)
        
        // CLICK launcher | TextView | (Chrome icon)
        recorder.processEvent(RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = launcherPkg,
            sourceText = "Chrome",
            sourceContentDescription = null,
            enteredText = "",
            sourceViewId = "icon",
            sourceClassName = "android.widget.TextView",
            occurredAtMillis = 1010L
        ), myPackage, nullLauncher)
        
        // APP_CHANGE com.android.chrome
        recorder.processEvent(appChangeEvent(chromePkg), myPackage, nullLauncher)
        
        val actions = recorder.recordedActions.value
        // It should NOT collapse into APP_INTENT, should retain the raw clicks
        assertTrue(actions.any { it.uiActionType == "TAP" && it.targetNodeId == "home" })
        assertTrue(actions.any { it.uiActionType == "TAP" && it.targetText == "Chrome" })
        assertTrue(actions.none { it.actionType == ActionType.APP_INTENT })
    }
}
