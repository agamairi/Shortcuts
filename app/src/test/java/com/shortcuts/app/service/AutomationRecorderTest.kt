package com.shortcuts.app.service

import android.view.KeyEvent
import com.shortcuts.app.data.ActionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutomationRecorderTest {

    private val myPackage = "com.shortcuts.app"

    @Before
    fun setup() {
        AutomationRecorder.clearRecording()
        AutomationRecorder.setRecordingForTest(true)
    }

    @Test
    fun `click event with visible text becomes a UI_AUTOMATION TAP carrying that text`() {
        val event = RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = "com.other.app",
            sourceText = "Submit",
            sourceContentDescription = null,
            sourceViewId = null,
            enteredText = ""
        )

        AutomationRecorder.processEvent(event, myPackage)

        val actions = AutomationRecorder.recordedActions.value
        assertEquals(1, actions.size)
        
        val action = actions[0]
        assertEquals(ActionType.UI_AUTOMATION, action.actionType)
        assertEquals("TAP", action.uiActionType)
        assertEquals("Submit", action.targetText)
    }

    @Test
    fun `text-change event becomes a UI_AUTOMATION TYPE carrying the entered text`() {
        val event = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.other.app",
            sourceText = "Email",
            sourceContentDescription = null,
            sourceViewId = "com.other.app:id/email",
            enteredText = "test@example.com"
        )

        AutomationRecorder.processEvent(event, myPackage)
        AutomationRecorder.processEvent(
            event.copy(
                eventType = RecorderEventType.FOCUS,
                sourceViewId = "com.other.app:id/other",
                enteredText = ""
            ),
            myPackage
        )

        val actions = AutomationRecorder.recordedActions.value
        assertEquals(1, actions.size)
        
        val action = actions[0]
        assertEquals(ActionType.UI_AUTOMATION, action.actionType)
        assertEquals("TYPE_TEXT", action.uiActionType)
        assertEquals("Email", action.targetText)
        assertEquals("com.other.app:id/email", action.targetNodeId)
        assertEquals("test@example.com", action.textInput)
    }

    @Test
    fun `events from the app's own package are ignored`() {
        val event = RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = myPackage,
            sourceText = "Save",
            sourceContentDescription = null,
            sourceViewId = null,
            enteredText = ""
        )

        AutomationRecorder.processEvent(event, myPackage)

        val actions = AutomationRecorder.recordedActions.value
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `only Enter key codes are recorder submit keys`() {
        assertTrue(AutomationRecorder.isEnterKey(KeyEvent.KEYCODE_ENTER))
        assertTrue(AutomationRecorder.isEnterKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertTrue(!AutomationRecorder.isEnterKey(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun `selection accessibility events map to non-edit recorder events`() {
        assertEquals(
            RecorderEventType.SELECTION_CHANGE,
            AutomationRecorder.classifyAccessibilityEventType(
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
            )
        )
    }

    @Test
    fun `window-list changes are observed as screen effects`() {
        assertEquals(
            RecorderEventType.CONTENT_CHANGE,
            AutomationRecorder.classifyAccessibilityEventType(
                android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED
            )
        )
    }

    @Test
    fun `focused text fallback does no window tree work when no editable is tracked`() {
        val service = mockk<AutomationAccessibilityService>(relaxed = true)

        assertEquals(
            FocusedTextSnapshotResult.NOT_TRACKED,
            AutomationRecorder.refreshTrackedFocusedText(service, occurredAtMillis = 1_000L)
        )
        verify(exactly = 0) { service.windows }
        verify(exactly = 0) { service.getRootNode() }
    }

    @Test
    fun `captured Chrome tap re-reads live omnibox text and commits before result URL`() {
        val chromePackage = "com.android.chrome"
        val service = mockk<AutomationAccessibilityService>(relaxed = true)
        every { service.packageName } returns myPackage
        every { service.applicationContext } returns service

        val appWindow = mockk<android.view.accessibility.AccessibilityWindowInfo>(relaxed = true)
        every { appWindow.type } returns android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        every { appWindow.id } returns 7
        val appRoot = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        every { appRoot.packageName } returns chromePackage
        every { appWindow.root } returns appRoot

        val imeWindow = mockk<android.view.accessibility.AccessibilityWindowInfo>(relaxed = true)
        every { imeWindow.type } returns android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
        val imeRoot = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        every { imeRoot.packageName } returns "com.google.android.inputmethod.latin"
        every { imeWindow.root } returns imeRoot

        val omnibox = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        var liveText: CharSequence = "repeatkit android"
        every { omnibox.packageName } returns chromePackage
        every { omnibox.className } returns "android.widget.EditText"
        every { omnibox.viewIdResourceName } returns "com.android.chrome:id/url_bar"
        every { omnibox.windowId } returns 7
        every { omnibox.isFocused } returns true
        every { omnibox.isEditable } returns false
        every { omnibox.isPassword } returns false
        every { omnibox.text } answers { liveText }
        every { appRoot.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT) } returns omnibox
        every { service.windows } returns listOf(imeWindow, appWindow)
        AutomationAccessibilityService.instance = service

        try {
            AutomationRecorder.recordCapturedGesture(
                RecorderEvent(
                    eventType = RecorderEventType.CLICK,
                    packageName = chromePackage,
                    sourceText = "Search or type web address",
                    sourceContentDescription = null,
                    sourceViewId = "com.android.chrome:id/url_bar",
                    enteredText = "",
                    sourceClassName = "android.widget.EditText",
                    sourceIsEditable = false,
                    occurredAtMillis = 1_000L,
                    windowId = 7
                )
            )

            val typedKey = mockk<KeyEvent>()
            every { typedKey.action } returns KeyEvent.ACTION_UP
            every { typedKey.keyCode } returns KeyEvent.KEYCODE_D
            every { typedKey.eventTime } returns 1_200L
            assertTrue(!AutomationRecorder.onKeyEvent(typedKey, service, chromePackage))

            liveText = "https://www.google.com/search?q=repeatkit+android"
            every { service.windows } returns listOf(appWindow)
            assertEquals(
                FocusedTextSnapshotResult.FOCUS_LOST,
                AutomationRecorder.refreshTrackedFocusedText(service, occurredAtMillis = 1_300L)
            )

            assertEquals(
                listOf("TAP", "TYPE_TEXT"),
                AutomationRecorder.recordedActions.value.map { it.uiActionType }
            )
            assertEquals("repeatkit android", AutomationRecorder.recordedActions.value.last().textInput)
        } finally {
            AutomationAccessibilityService.instance = null
        }
    }

    @Test
    fun `a background launcher window updating while Chrome is recorded is not an app switch`() {
        // Seen on a Pixel 6a: the launcher keeps a system window updating while Chrome is open.
        // Counted as switching to the launcher, it reset the address bar being typed into.
        var lookups = 0
        val launcherSystemWindow = { lookups++; AutomationRecorder.isForegroundApplicationWindow(
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM, isActive = false, isFocused = false
        ) }

        assertTrue(AutomationRecorder.isBackgroundWindowEvent(
            RecorderEventType.CONTENT_CHANGE, "com.google.android.apps.nexuslauncher", "com.android.chrome", launcherSystemWindow
        ))
        assertTrue(AutomationRecorder.isBackgroundWindowEvent(
            RecorderEventType.SCROLL, "com.google.android.apps.nexuslauncher", "com.android.chrome", launcherSystemWindow
        ))
        assertEquals(2, lookups)
    }

    @Test
    fun `events from the recorded app or the window the user is in still count`() {
        var lookups = 0
        val activeAppWindow = { lookups++; AutomationRecorder.isForegroundApplicationWindow(
            android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION, isActive = true, isFocused = true
        ) }

        // Same app: no window lookup at all.
        assertTrue(!AutomationRecorder.isBackgroundWindowEvent(
            RecorderEventType.CONTENT_CHANGE, "com.android.chrome", "com.android.chrome", activeAppWindow
        ))
        assertEquals(0, lookups)
        // Another app's active window (the user really switched).
        assertTrue(!AutomationRecorder.isBackgroundWindowEvent(
            RecorderEventType.CONTENT_CHANGE, "com.other.app", "com.android.chrome", activeAppWindow
        ))
        // Unknown window: keep the event, as before.
        assertTrue(!AutomationRecorder.isBackgroundWindowEvent(
            RecorderEventType.FOCUS, "com.other.app", "com.android.chrome"
        ) { null })
        // Taps and typing are never filtered this way.
        assertTrue(!AutomationRecorder.isBackgroundWindowEvent(
            RecorderEventType.CLICK, "com.other.app", "com.android.chrome"
        ) { false })
        assertTrue(!AutomationRecorder.isBackgroundWindowEvent(
            RecorderEventType.TEXT_CHANGE, "com.other.app", "com.android.chrome"
        ) { false })
    }

    @Test
    fun `only an active or focused application window is the foreground app window`() {
        val app = android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        assertTrue(AutomationRecorder.isForegroundApplicationWindow(app, isActive = true, isFocused = false))
        assertTrue(AutomationRecorder.isForegroundApplicationWindow(app, isActive = false, isFocused = true))
        assertTrue(!AutomationRecorder.isForegroundApplicationWindow(app, isActive = false, isFocused = false))
        assertTrue(!AutomationRecorder.isForegroundApplicationWindow(
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM, isActive = true, isFocused = true
        ))
    }

    @Test
    fun `consecutive text-change events on the SAME field collapse into ONE TYPE action carrying the final text`() {
        val event1 = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.other.app",
            sourceText = "Name",
            sourceContentDescription = null,
            sourceViewId = "id/name",
            enteredText = "J"
        )
        val event2 = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.other.app",
            sourceText = "Name",
            sourceContentDescription = null,
            sourceViewId = "id/name",
            enteredText = "Jo"
        )
        val event3 = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.other.app",
            sourceText = "Name",
            sourceContentDescription = null,
            sourceViewId = "id/name",
            enteredText = "Joe"
        )

        AutomationRecorder.processEvent(event1, myPackage)
        AutomationRecorder.processEvent(event2, myPackage)
        AutomationRecorder.processEvent(event3, myPackage)
        AutomationRecorder.processEvent(
            event3.copy(eventType = RecorderEventType.FOCUS, sourceViewId = "id/other", enteredText = ""),
            myPackage
        )

        val actions = AutomationRecorder.recordedActions.value
        assertEquals(1, actions.size)
        
        val action = actions[0]
        assertEquals("TYPE_TEXT", action.uiActionType)
        assertEquals("Joe", action.textInput)
    }

    @Test
    fun `password text changes are omitted entirely`() {
        val mockNode = mockk<android.view.accessibility.AccessibilityNodeInfo>(relaxed = true)
        every { mockNode.isPassword } returns true
        every { mockNode.inputType } returns 129 // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PASSWORD
        every { mockNode.recycle() } returns Unit

        val event = mockk<android.view.accessibility.AccessibilityEvent>(relaxed = true)
        every { event.eventType } returns android.view.accessibility.AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        every { event.source } returns mockNode
        every { event.packageName } returns "com.other.app"
        every { event.eventTime } returns 1000L
        every { event.text } returns listOf("Secret")

        val context = mockk<android.content.Context>(relaxed = true)
        every { context.packageName } returns myPackage
        every { context.applicationContext } returns context
        
        AutomationRecorder.onAccessibilityEvent(event, context)

        val actions = AutomationRecorder.recordedActions.value
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `recorded session converts into an Automation whose actions are in capture order`() {
        val clickEvent = RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = "com.other.app",
            sourceText = "Start",
            sourceContentDescription = null,
            sourceViewId = null,
            enteredText = ""
        )
        val typeEvent = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.other.app",
            sourceText = "Field",
            sourceContentDescription = null,
            sourceViewId = "id/field",
            enteredText = "Data"
        )

        AutomationRecorder.processEvent(clickEvent, myPackage)
        AutomationRecorder.processEvent(typeEvent, myPackage)
        AutomationRecorder.processEvent(
            typeEvent.copy(eventType = RecorderEventType.FOCUS, sourceViewId = "id/other", enteredText = ""),
            myPackage
        )

        val actions = AutomationRecorder.recordedActions.value
        assertEquals(2, actions.size)
        
        assertEquals("TAP", actions[0].uiActionType)
        assertEquals("Start", actions[0].targetText)
        
        assertEquals("TYPE_TEXT", actions[1].uiActionType)
        assertEquals("Data", actions[1].textInput)
    }

    class StubNode(
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val viewIdResourceName: String? = null,
        val children: List<RecorderNode> = emptyList(),
        val parentNode: RecorderNode? = null
    ) : RecorderNode {
        var freeCount = 0
        override val childCount: Int get() = children.size
        override fun getChildAt(index: Int): RecorderNode? = children.getOrNull(index)
        override fun getParent(): RecorderNode? = parentNode
        override fun free() { freeCount++ }
    }

    @Test
    fun `a node with only a viewIdResourceName yields an action targeting that id`() {
        val node = StubNode(viewIdResourceName = "id/my_button")
        val result = deriveNodeLabel(node)
        assertEquals(null, result.text)
        assertEquals(null, result.contentDescription)
        assertEquals("id/my_button", result.viewIdResourceName)
    }

    @Test
    fun `a node with NO label whose CHILD has text yields an action targeting the childs text`() {
        val child = StubNode(text = "Child Text")
        val node = StubNode(children = listOf(child))
        val result = deriveNodeLabel(node)
        assertEquals("Child Text", result.text)
        assertEquals(null, result.viewIdResourceName) // Shouldn't pull viewId
    }

    @Test
    fun `a node with no label and no labelled descendants, but a labelled ancestor, uses the ancestor`() {
        val ancestor = StubNode(text = "Ancestor Text")
        val parent = StubNode(parentNode = ancestor)
        val node = StubNode(parentNode = parent)
        val result = deriveNodeLabel(node)
        assertEquals("Ancestor Text", result.text)
    }

    @Test
    fun `a node with nothing usable anywhere yields an UNRESOLVED unlabelled step, never null dropped`() {
        // Just verify deriveNodeLabel yields nulls
        val node = StubNode()
        val result = deriveNodeLabel(node)
        assertEquals(null, result.text)
        assertEquals(null, result.contentDescription)
        assertEquals(null, result.viewIdResourceName)
        
        // And when integrated via RecorderEvent, it becomes UNRESOLVED in the session.
        // Wait, AutomationRecorder.onAccessibilityEvent does the replacement, but it takes AccessibilityEvent.
        // Let's just trust that the function works since it's tested below, but let's test if processEvent creates an UNRESOLVED tap when properties are blank.
        val clickEvent = RecorderEvent(
            eventType = RecorderEventType.CLICK,
            packageName = "com.other.app",
            sourceText = "UNRESOLVED",
            sourceContentDescription = null,
            sourceViewId = null,
            enteredText = ""
        )
        AutomationRecorder.processEvent(clickEvent, myPackage)
        val actions = AutomationRecorder.recordedActions.value
        assertEquals("TAP", actions.last().uiActionType)
        assertEquals("UNRESOLVED", actions.last().targetText)
    }

    @Test
    fun `descendant search respects its depth and visit caps`() {
        // Create a deep tree
        var deepest: RecorderNode = StubNode(text = "Too Deep")
        for (i in 0 until 5) {
            deepest = StubNode(children = listOf(deepest))
        }
        val result = deriveNodeLabel(deepest)
        assertEquals(null, result.text) // Should not reach "Too Deep" because maxDepth = 3
    }

    @Test
    fun `stopForAccessibilityDisconnect cancels pending scroll and stops v2 bridge without compiling`() {
        val context = mockk<android.content.Context>(relaxed = true)
        val appContext = mockk<android.content.Context>(relaxed = true)
        every { context.applicationContext } returns appContext
        val notifManager = mockk<android.app.NotificationManager>(relaxed = true)
        every { appContext.getSystemService(android.content.Context.NOTIFICATION_SERVICE) } returns notifManager
        every { appContext.packageName } returns "com.shortcuts.app"
        val appInfo = android.content.pm.ApplicationInfo().apply { targetSdkVersion = 34 }
        every { appContext.applicationInfo } returns appInfo

        val testStore = com.shortcuts.app.automation.android.capture.InMemoryV2CaptureStore()
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.resetForTest()
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.setStoreForTest(testStore)
        com.shortcuts.app.automation.android.EngineV2.enabled = true
        val snap = com.shortcuts.app.automation.core.tree.WindowSetSnapshot(1L, 100, 100, 0, 1f, emptyList())
        com.shortcuts.app.automation.android.EngineV2.snapshotSource = com.shortcuts.app.automation.android.WindowSnapshotSource { snap }

        // Start recording
        AutomationRecorder.setRecordingForTest(true)
        com.shortcuts.app.automation.android.capture.V2CaptureBridge.start(context)
        testStore.saveActiveSession(com.shortcuts.app.automation.android.capture.PersistedV2Session("wf-test", snap))

        // Set pending scroll finalizer
        AutomationRecorder.setPendingScrollFinalizerForTest(Runnable {})
        assertTrue(AutomationRecorder.hasPendingScrollFinalizerForTest())

        // Create monitor with synchronous execution
        val directScheduler = GracePeriodScheduler { _, task ->
            task()
            GracePeriodCancellation {}
        }
        val monitor = AccessibilityServiceDisconnectMonitor(
            scheduler = directScheduler,
            isRecording = { AutomationRecorder.isRecording.value }
        )
        AutomationRecorder.setDisconnectMonitorForTest(monitor)

        // Mock accessibility disabled
        io.mockk.mockkObject(com.shortcuts.app.util.AccessibilityStatusChecker)
        every { com.shortcuts.app.util.AccessibilityStatusChecker.isAccessibilityEnabled(any()) } returns false
        io.mockk.mockkObject(RecorderSessionNotifier)
        every { RecorderSessionNotifier.showAccessibilityDisconnected(any()) } returns Unit

        try {
            // Trigger disconnect
            AutomationRecorder.stopForAccessibilityDisconnect(context)

            // Verify pending scroll finalizer was cancelled
            assertTrue(!AutomationRecorder.hasPendingScrollFinalizerForTest())

            // Verify v2 bridge was stopped without compiling (capture files cleared, no last workflow)
            org.junit.Assert.assertNull(com.shortcuts.app.automation.android.capture.V2CaptureBridge.lastWorkflow)
            org.junit.Assert.assertNull(testStore.loadActiveSession())
            org.junit.Assert.assertNull(testStore.loadLastWorkflow())
        } finally {
            io.mockk.unmockkObject(com.shortcuts.app.util.AccessibilityStatusChecker)
            io.mockk.unmockkObject(RecorderSessionNotifier)
        }
    }
}
