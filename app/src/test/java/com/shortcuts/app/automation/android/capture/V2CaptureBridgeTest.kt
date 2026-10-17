package com.shortcuts.app.automation.android.capture

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.KeyEvent
import com.shortcuts.app.automation.android.EngineV2
import com.shortcuts.app.automation.android.WindowSnapshotSource
import com.shortcuts.app.automation.capture.CaptureScreenContext
import com.shortcuts.app.automation.capture.RecordingCompiler
import com.shortcuts.app.automation.capture.RecordingDeviceMetadata
import com.shortcuts.app.automation.capture.fakeFingerprinter
import com.shortcuts.app.automation.capture.fakeTokenizer
import com.shortcuts.app.automation.capture.screen
import com.shortcuts.app.automation.capture.snapshot
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.automation.model.WorkflowCodec
import com.shortcuts.app.automation.model.WorkflowV2
import com.shortcuts.app.service.RecorderEvent
import com.shortcuts.app.service.RecorderEventType
import com.shortcuts.app.service.capture.TouchAction
import com.shortcuts.app.service.capture.TouchSample
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class V2CaptureBridgeTest {

    private val testStore = InMemoryV2CaptureStore()
    private val appWindow = screen("com.example.app", "Test App", nodeId = 10, bounds = r(100, 200, 300, 400))
    private val initialSnapshot = snapshot(appWindow, 1_000L)

    @Before
    fun setUp() {
        V2CaptureBridge.resetForTest()
        V2CaptureBridge.setStoreForTest(testStore)
        EngineV2.enabled = true
        EngineV2.snapshotSource = WindowSnapshotSource { initialSnapshot }
    }

    @After
    fun tearDown() {
        V2CaptureBridge.clear()
        V2CaptureBridge.resetForTest()
        EngineV2.snapshotSource = null
        EngineV2.enabled = false
    }

    @Test
    fun testDegradedModeWhenNoSnapshotSource() {
        EngineV2.snapshotSource = null

        val context = mockk<Context>(relaxed = true)
        val pm = mockk<PackageManager>(relaxed = true)
        every { context.packageManager } returns pm
        every { context.packageName } returns "com.shortcuts.app"

        // start with null snapshot source: must not throw, logs once, does not start compiler
        V2CaptureBridge.start(context)
        assertNull(V2CaptureBridge.lastWorkflow)
        assertNull(V2CaptureBridge.lastWorkflowJson)

        // Event hooks with null snapshot source: must not throw
        V2CaptureBridge.onGestureClassified(
            kind = GestureKind.TAP,
            samples = listOf(TouchSample(TouchAction.DOWN, 100f, 100f, 1_000L)),
            beforeSnapshot = null,
            ownPackage = "com.shortcuts.app"
        )
        V2CaptureBridge.onTextCommit(
            RecorderEvent(
                eventType = RecorderEventType.TEXT_CHANGE,
                packageName = "com.example.app",
                sourceText = null,
                sourceContentDescription = null,
                sourceViewId = null,
                enteredText = "test",
                occurredAtMillis = 1_000L
            )
        )
        V2CaptureBridge.onImeAction("search", 1_000L)
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.MainActivity", 1_000L)

        val keyEvent = mockk<KeyEvent>()
        every { keyEvent.action } returns KeyEvent.ACTION_UP
        every { keyEvent.keyCode } returns KeyEvent.KEYCODE_BACK
        every { keyEvent.eventTime } returns 1_000L
        V2CaptureBridge.onKeyEvent(keyEvent)

        // Stop: must not throw, lastWorkflow remains null
        V2CaptureBridge.stop()
        assertNull(V2CaptureBridge.lastWorkflow)
        assertNull(V2CaptureBridge.lastWorkflowJson)
    }

    @Test
    fun testDisabledEngineV2DoesNothing() {
        EngineV2.enabled = false

        val context = mockk<Context>(relaxed = true)
        V2CaptureBridge.start(context)
        assertNull(V2CaptureBridge.lastWorkflow)

        V2CaptureBridge.onGestureClassified(
            kind = GestureKind.TAP,
            samples = listOf(TouchSample(TouchAction.DOWN, 100f, 100f, 1_000L)),
            beforeSnapshot = initialSnapshot,
            ownPackage = "com.shortcuts.app"
        )
        assertNull(testStore.loadActiveSession())

        V2CaptureBridge.stop()
        assertNull(V2CaptureBridge.lastWorkflow)
    }

    @Test
    fun testLifecycleWithEventsAndStop() {
        var id = 0
        val testCompiler = RecordingCompiler(
            launcherPackageNames = setOf("com.example.launcher"),
            recorderPackageName = "com.shortcuts.app",
            screenFingerprinter = fakeFingerprinter,
            textTokenizer = fakeTokenizer,
            idGenerator = { "step-${++id}" },
            deviceMetadata = RecordingDeviceMetadata("Google", "Pixel", 36),
            imePackageNames = setOf("com.example.ime")
        )
        testCompiler.start(CaptureScreenContext("workflow-1", initialSnapshot))
        V2CaptureBridge.setCompilerForTest(testCompiler)
        val activeSession = PersistedV2Session("workflow-1", initialSnapshot)
        testStore.saveActiveSession(activeSession)

        // 1. Gesture
        val samples = listOf(
            TouchSample(TouchAction.DOWN, 150f, 250f, 1_100L),
            TouchSample(TouchAction.UP, 150f, 250f, 1_150L)
        )
        V2CaptureBridge.onGestureClassified(
            kind = GestureKind.TAP,
            samples = samples,
            beforeSnapshot = initialSnapshot,
            ownPackage = "com.shortcuts.app"
        )
        V2CaptureBridge.awaitCaptureIdleForTest()

        // 2. Global action
        val keyEvent = mockk<KeyEvent>()
        every { keyEvent.action } returns KeyEvent.ACTION_UP
        every { keyEvent.keyCode } returns KeyEvent.KEYCODE_BACK
        every { keyEvent.eventTime } returns 1_200L
        V2CaptureBridge.onKeyEvent(keyEvent)

        // 3. Stop
        V2CaptureBridge.stop()

        assertNotNull(V2CaptureBridge.lastWorkflow)
        assertNotNull(V2CaptureBridge.lastWorkflowJson)
        val workflow = V2CaptureBridge.lastWorkflow!!
        assertEquals("workflow-1", workflow.workflowId)

        // Active session must be cleared from store, and workflow persisted
        assertNull(testStore.loadActiveSession())
        assertEquals(V2CaptureBridge.lastWorkflowJson, testStore.loadLastWorkflow())

        // 4. Clear
        V2CaptureBridge.clear()
        assertNull(V2CaptureBridge.lastWorkflow)
        assertNull(V2CaptureBridge.lastWorkflowJson)
        assertNull(testStore.loadLastWorkflow())
    }

    @Test
    fun testRestoreActiveSession() {
        val session = PersistedV2Session(
            workflowId = "restored-workflow",
            initialSnapshot = initialSnapshot,
            events = emptyList()
        )
        testStore.saveActiveSession(session)

        val context = mockk<Context>(relaxed = true)
        val pm = mockk<PackageManager>(relaxed = true)
        every { context.packageManager } returns pm
        every { context.packageName } returns "com.shortcuts.app"

        V2CaptureBridge.restore(context)

        // Compiler should now be active, and stop should produce a workflow
        V2CaptureBridge.stop()
        assertNotNull(V2CaptureBridge.lastWorkflow)
        assertEquals("restored-workflow", V2CaptureBridge.lastWorkflow?.workflowId)
    }

    @Test
    fun testRestoreLastWorkflow() {
        val sampleWorkflow = WorkflowV2(
            workflowId = "persisted-wf",
            recordedDevice = com.shortcuts.app.automation.model.RecordedDevice(
                manufacturer = "Google",
                model = "Pixel",
                sdkInt = 36,
                displayWidthPx = 1080,
                displayHeightPx = 2400,
                densityDpi = 420,
                rotation = 0
            ),
            steps = emptyList()
        )
        val json = WorkflowCodec().encode(sampleWorkflow)
        testStore.saveLastWorkflow(json)

        val context = mockk<Context>(relaxed = true)
        V2CaptureBridge.restore(context)

        assertEquals("persisted-wf", V2CaptureBridge.lastWorkflow?.workflowId)
        assertEquals(json, V2CaptureBridge.lastWorkflowJson)
    }

    @Test
    fun testSensitiveTextNotDeliveredToCompiler() {
        val passwordField = NodeSnapshot(
            nodeId = 99,
            parentId = null,
            childIds = emptyList(),
            className = "android.widget.EditText",
            resourceId = "com.example.app:id/password",
            text = null,
            contentDescription = null,
            hintText = null,
            stateDescription = null,
            boundsInScreen = r(50, 50, 400, 150),
            visibleToUser = true,
            enabled = true,
            clickable = true,
            longClickable = true,
            checkable = false,
            checked = false,
            editable = true,
            focusable = true,
            focused = true,
            scrollable = false,
            selected = false,
            password = true,
            actions = setOf(NodeAction.SET_TEXT),
            collectionRow = null,
            collectionColumn = null,
            drawingOrder = 1,
            windowId = 1,
            packageName = "com.example.app"
        )

        val windowWithPassword = WindowSnapshot(
            windowId = 1,
            type = WindowType.APPLICATION,
            layer = 1,
            isActive = true,
            isFocused = true,
            title = "Login",
            packageName = "com.example.app",
            boundsInScreen = r(0, 0, 1080, 2400),
            rootId = 99,
            nodes = mapOf(99 to passwordField)
        )
        val passwordSnapshot = snapshot(windowWithPassword, 1_000L)
        EngineV2.snapshotSource = WindowSnapshotSource { passwordSnapshot }

        val mockCompiler = mockk<RecordingCompiler>(relaxed = true)
        V2CaptureBridge.setCompilerForTest(mockCompiler)

        val event = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.example.app",
            sourceText = null,
            sourceContentDescription = null,
            sourceViewId = "com.example.app:id/password",
            enteredText = "supersecret",
            occurredAtMillis = 1_000L
        )

        V2CaptureBridge.onTextCommit(event)
        V2CaptureBridge.awaitCaptureIdleForTest()

        // Verify compiler.accept(CapturedTextCommit) was NEVER called
        io.mockk.verify(exactly = 0) { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedTextCommit>()) }
    }

    @Test
    fun testDialogClassDoesNotOverwriteLastRealActivity() {
        val capturedSwitches = mutableListOf<com.shortcuts.app.automation.capture.CapturedAppSwitch>()
        val mockCompiler = mockk<RecordingCompiler>(relaxed = true)
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedAppSwitch>()) } answers {
            capturedSwitches.add(firstArg())
        }
        V2CaptureBridge.setCompilerForTest(mockCompiler)

        // 1. App switch to real activity
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.MainActivity", 1_000L)
        V2CaptureBridge.awaitCaptureIdleForTest()
        assertEquals(1, capturedSwitches.size)
        assertEquals("com.example.app.MainActivity", capturedSwitches[0].observedActivityClass)

        // 2. Dialog appears (e.g. AlertDialog)
        V2CaptureBridge.onAppSwitch("com.example.app", "androidx.appcompat.app.AlertDialog", 2_000L)
        V2CaptureBridge.awaitCaptureIdleForTest()
        assertEquals(2, capturedSwitches.size)
        // Dialog class must be rejected and last real activity retained
        assertEquals("com.example.app.MainActivity", capturedSwitches[1].observedActivityClass)
    }

    @Test
    fun testImeWindowStateDoesNotSwitchForegroundOrStealTextAttribution() {
        val field = NodeSnapshot(
            nodeId = 15,
            parentId = null,
            childIds = emptyList(),
            className = "android.widget.EditText",
            resourceId = "com.example.app:id/search",
            text = "repeatkit android",
            contentDescription = null,
            hintText = "Search",
            stateDescription = null,
            boundsInScreen = r(50, 50, 600, 150),
            visibleToUser = true,
            enabled = true,
            clickable = true,
            longClickable = false,
            checkable = false,
            checked = false,
            editable = true,
            focusable = true,
            focused = true,
            scrollable = false,
            selected = false,
            password = false,
            actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER),
            collectionRow = null,
            collectionColumn = null,
            drawingOrder = 1,
            windowId = 1,
            packageName = "com.example.app"
        )
        val app = WindowSnapshot(
            windowId = 1,
            type = WindowType.APPLICATION,
            layer = 1,
            isActive = true,
            isFocused = true,
            title = "Search",
            packageName = "com.example.app",
            boundsInScreen = r(0, 0, 1080, 1800),
            rootId = field.nodeId,
            nodes = mapOf(field.nodeId to field)
        )
        val ime = app.copy(
            windowId = 2,
            type = WindowType.INPUT_METHOD,
            layer = 2,
            isActive = false,
            isFocused = false,
            title = null,
            packageName = "com.example.ime",
            boundsInScreen = r(0, 1800, 1080, 2400),
            rootId = Int.MIN_VALUE,
            nodes = emptyMap()
        )
        val keyboardSnapshot = snapshot(app, 1_000L).copy(windows = listOf(ime, app))
        EngineV2.snapshotSource = WindowSnapshotSource { keyboardSnapshot }
        V2CaptureBridge.setImePackageNamesForTest(setOf("com.enabled.ime"))

        val switches = mutableListOf<com.shortcuts.app.automation.capture.CapturedAppSwitch>()
        val commits = mutableListOf<com.shortcuts.app.automation.capture.CapturedTextCommit>()
        val mockCompiler = mockk<RecordingCompiler>(relaxed = true)
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedAppSwitch>()) } answers {
            switches.add(firstArg())
        }
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedTextCommit>()) } answers {
            commits.add(firstArg())
        }
        V2CaptureBridge.setCompilerForTest(mockCompiler)

        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.SearchActivity", 1_000L)
        V2CaptureBridge.onTextCommit(
            RecorderEvent(
                eventType = RecorderEventType.TEXT_CHANGE,
                packageName = "com.example.app",
                sourceText = null,
                sourceContentDescription = null,
                sourceViewId = field.resourceId,
                enteredText = "repeatkit android",
                sourceClassName = field.className,
                sourceIsEditable = true,
                occurredAtMillis = 1_200L,
                windowId = app.windowId
            )
        )
        V2CaptureBridge.onAppSwitch("com.example.ime", null, 1_300L)
        V2CaptureBridge.onAppSwitch("com.enabled.ime", null, 1_350L)
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.SearchActivity", 1_400L)
        V2CaptureBridge.awaitCaptureIdleForTest()

        assertEquals(2, switches.size)
        assertEquals("com.example.app", switches[1].fromPackageName)
        assertTrue(switches.none { it.toPackageName.endsWith(".ime") })
        assertEquals(1, commits.size)
        assertEquals("com.example.app", commits.single().windowSnapshot.packageName)
    }

    @Test
    fun testChromeStyleLiveTextSurvivesReplacementAndInfersSubmitByEffect() {
        val typedWindow = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.EditText",
                    id = 10,
                    resourceId = "com.example.app:id/url_bar",
                    text = "repeatkit android",
                    hintText = "Search or type URL",
                    bounds = r(20, 40, 1000, 160),
                    focused = true,
                    editable = true,
                    actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER)
                )
            }
        }
        val keyboard = typedWindow.copy(
            windowId = 9,
            type = WindowType.INPUT_METHOD,
            layer = 5,
            isActive = false,
            isFocused = false,
            packageName = "com.example.ime",
            rootId = Int.MIN_VALUE,
            nodes = emptyMap()
        )
        val typed = snapshot(typedWindow, 1_000L).copy(windows = listOf(keyboard, typedWindow))
        val resultsWindow = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.EditText",
                    id = 20,
                    resourceId = "com.example.app:id/url_bar",
                    text = "https://www.google.com/search?q=repeatkit",
                    hintText = "Search or type URL",
                    bounds = r(20, 40, 1000, 160),
                    focused = false,
                    editable = true,
                    actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER)
                )
                node(
                    className = "android.webkit.WebView",
                    id = 21,
                    resourceId = "com.example.app:id/results",
                    bounds = r(0, 180, 1080, 2400)
                )
            }
        }
        val results = snapshot(resultsWindow, 1_500L)
        var current = typed
        EngineV2.snapshotSource = WindowSnapshotSource { current }

        val commits = mutableListOf<com.shortcuts.app.automation.capture.CapturedTextCommit>()
        val imeActions = mutableListOf<com.shortcuts.app.automation.capture.CapturedImeAction>()
        val mockCompiler = mockk<RecordingCompiler>(relaxed = true)
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedTextCommit>()) } answers {
            commits.add(firstArg())
        }
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedImeAction>()) } answers {
            imeActions.add(firstArg())
        }
        V2CaptureBridge.setCompilerForTest(mockCompiler)

        val textEvent = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.example.app",
            sourceText = "repeatkit android",
            sourceContentDescription = null,
            sourceViewId = "com.example.app:id/url_bar",
            enteredText = "repeatkit android",
            sourceClassName = "android.widget.EditText",
            sourceIsEditable = true,
            occurredAtMillis = 1_000L,
            windowId = typedWindow.windowId
        )
        V2CaptureBridge.onTextChanged(textEvent)
        V2CaptureBridge.awaitCaptureIdleForTest()
        current = results
        val replacedEvent = textEvent.copy(
            enteredText = "https://www.google.com/search?q=repeatkit",
            occurredAtMillis = 1_100L
        )
        V2CaptureBridge.onTextChanged(replacedEvent)
        V2CaptureBridge.onTextCommit(
            replacedEvent
        )
        V2CaptureBridge.onScreenChanged(1_500L)
        V2CaptureBridge.awaitCaptureIdleForTest()

        assertEquals("repeatkit android", commits.single().finalText)
        assertEquals("com.example.app", commits.single().windowSnapshot.packageName)
        assertEquals(1, imeActions.size)
        assertTrue(imeActions.single().inferred)
        assertEquals(ImeActionKind.SEARCH, imeActions.single().kind)
    }

    @Test
    fun testInferredImeActionOnResultsActivitySwitch() {
        val searchField = NodeSnapshot(
            nodeId = 15,
            parentId = null,
            childIds = emptyList(),
            className = "android.widget.EditText",
            resourceId = "com.example.app:id/edit_search",
            text = "android",
            contentDescription = null,
            hintText = "Search",
            stateDescription = null,
            boundsInScreen = r(50, 50, 400, 150),
            visibleToUser = true,
            enabled = true,
            clickable = true,
            longClickable = true,
            checkable = false,
            checked = false,
            editable = true,
            focusable = true,
            focused = true,
            scrollable = false,
            selected = false,
            password = false,
            actions = setOf(NodeAction.SET_TEXT),
            collectionRow = null,
            collectionColumn = null,
            drawingOrder = 1,
            windowId = 1,
            packageName = "com.example.app"
        )
        val window = WindowSnapshot(
            windowId = 1,
            type = WindowType.APPLICATION,
            layer = 1,
            isActive = true,
            isFocused = true,
            title = "Search",
            packageName = "com.example.app",
            boundsInScreen = r(0, 0, 1080, 2400),
            rootId = 15,
            nodes = mapOf(15 to searchField)
        )
        val testSnapshot = snapshot(window, 1_000L)
        EngineV2.snapshotSource = WindowSnapshotSource { testSnapshot }

        val eventOrder = mutableListOf<String>()
        val capturedImeActions = mutableListOf<com.shortcuts.app.automation.capture.CapturedImeAction>()
        val mockCompiler = mockk<RecordingCompiler>(relaxed = true)
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedTextCommit>()) } answers {
            eventOrder.add("text_commit")
        }
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedImeAction>()) } answers {
            eventOrder.add("ime_action")
            capturedImeActions.add(firstArg())
        }
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedAppSwitch>()) } answers {
            eventOrder.add("app_switch")
        }
        V2CaptureBridge.setCompilerForTest(mockCompiler)

        // 1. Initial app switch to SearchActivity
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.SearchActivity", 1_000L)
        // 2. Commit text
        val textEvent = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.example.app",
            sourceText = null,
            sourceContentDescription = null,
            sourceViewId = "com.example.app:id/edit_search",
            enteredText = "android",
            occurredAtMillis = 1_500L
        )
        V2CaptureBridge.onTextCommit(textEvent)

        // 3. Activity switch to SearchResultsActivity within 2s
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.SearchResultsActivity", 2_000L)
        V2CaptureBridge.awaitCaptureIdleForTest()

        // Verify order: text_commit -> ime_action -> app_switch
        assertEquals(listOf("app_switch", "text_commit", "ime_action", "app_switch"), eventOrder)
        assertEquals(1, capturedImeActions.size)
        assertEquals(ImeActionKind.SEARCH, capturedImeActions[0].kind)
    }

    @Test
    fun testInferredImeActionOnFieldCleared() {
        val messageField = NodeSnapshot(
            nodeId = 25,
            parentId = null,
            childIds = emptyList(),
            className = "android.widget.EditText",
            resourceId = "com.example.app:id/edit_message",
            text = "hello",
            contentDescription = null,
            hintText = "Message",
            stateDescription = null,
            boundsInScreen = r(50, 50, 400, 150),
            visibleToUser = true,
            enabled = true,
            clickable = true,
            longClickable = true,
            checkable = false,
            checked = false,
            editable = true,
            focusable = true,
            focused = true,
            scrollable = false,
            selected = false,
            password = false,
            actions = setOf(NodeAction.SET_TEXT),
            collectionRow = null,
            collectionColumn = null,
            drawingOrder = 1,
            windowId = 1,
            packageName = "com.example.app"
        )
        val window = WindowSnapshot(
            windowId = 1,
            type = WindowType.APPLICATION,
            layer = 1,
            isActive = true,
            isFocused = true,
            title = "Chat",
            packageName = "com.example.app",
            boundsInScreen = r(0, 0, 1080, 2400),
            rootId = 25,
            nodes = mapOf(25 to messageField)
        )
        val testSnapshot = snapshot(window, 1_000L)
        EngineV2.snapshotSource = WindowSnapshotSource { testSnapshot }

        val capturedImeActions = mutableListOf<com.shortcuts.app.automation.capture.CapturedImeAction>()
        val mockCompiler = mockk<RecordingCompiler>(relaxed = true)
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedImeAction>()) } answers {
            capturedImeActions.add(firstArg())
        }
        V2CaptureBridge.setCompilerForTest(mockCompiler)

        // 1. Initial screen
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.ChatActivity", 1_000L)

        // 2. Commit text
        val textEvent = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.example.app",
            sourceText = null,
            sourceContentDescription = null,
            sourceViewId = "com.example.app:id/edit_message",
            enteredText = "hello",
            occurredAtMillis = 1_500L
        )
        V2CaptureBridge.onTextCommit(textEvent)

        // 3. Field cleared (the sent pattern)
        val clearedEvent = RecorderEvent(
            eventType = RecorderEventType.TEXT_CHANGE,
            packageName = "com.example.app",
            sourceText = null,
            sourceContentDescription = null,
            sourceViewId = "com.example.app:id/edit_message",
            enteredText = "",
            occurredAtMillis = 1_800L
        )
        V2CaptureBridge.onTextChanged(clearedEvent)
        V2CaptureBridge.awaitCaptureIdleForTest()

        assertEquals(1, capturedImeActions.size)
        assertEquals(ImeActionKind.SEND, capturedImeActions[0].kind)
    }

    @Test
    fun testInferredBackIsDeliveredBeforeAppSwitchWithPreviousScreenSnapshot() {
        val home = snapshot(screen("com.example.app", "Home", nodeId = 1), 1_000L)
        val profile = snapshot(screen("com.example.app", "Profile", nodeId = 2), 2_000L)
        val edit = snapshot(screen("com.example.app", "Edit", nodeId = 3), 3_000L)
        var current = home
        EngineV2.snapshotSource = WindowSnapshotSource { current }

        val order = mutableListOf<String>()
        val actions = mutableListOf<com.shortcuts.app.automation.capture.CapturedGlobalAction>()
        val mockCompiler = mockk<RecordingCompiler>(relaxed = true)
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedGlobalAction>()) } answers {
            order += "back"
            actions.add(firstArg())
        }
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedAppSwitch>()) } answers {
            order += "switch"
        }
        V2CaptureBridge.setCompilerForTest(mockCompiler)

        // Snapshots are taken on the capture queue, so let each switch drain before the screen changes.
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.HomeActivity", 1_000L)
        V2CaptureBridge.awaitCaptureIdleForTest()
        current = profile
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.ProfileActivity", 2_000L)
        V2CaptureBridge.awaitCaptureIdleForTest()
        current = edit
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.EditActivity", 3_000L)
        V2CaptureBridge.awaitCaptureIdleForTest()
        current = profile
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.ProfileActivity", 4_000L)
        V2CaptureBridge.awaitCaptureIdleForTest()

        assertEquals(listOf("switch", "switch", "switch", "back", "switch"), order)
        assertEquals(1, actions.size)
        assertTrue(actions.single().inferred)
        assertEquals(edit, actions.single().snapshot)
    }

    @Test
    fun testQueueOrderingPreservedAcrossIngestionMethods() {
        val eventOrder = java.util.Collections.synchronizedList(mutableListOf<String>())
        val mockCompiler = mockk<RecordingCompiler>(relaxed = true)
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedInteraction>()) } answers { eventOrder.add("interaction") }
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedTextCommit>()) } answers { eventOrder.add("text_commit") }
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedImeAction>()) } answers { eventOrder.add("ime_action") }
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedAppSwitch>()) } answers { eventOrder.add("app_switch") }
        every { mockCompiler.accept(any<com.shortcuts.app.automation.capture.CapturedGlobalAction>()) } answers { eventOrder.add("global_action") }
        V2CaptureBridge.setCompilerForTest(mockCompiler)

        val editableWindow = window("com.example.app") {
            node("android.widget.FrameLayout", id = 1) {
                node(
                    className = "android.widget.EditText",
                    id = 10,
                    resourceId = "com.example.app:id/search",
                    text = "Search",
                    bounds = r(100, 200, 300, 400),
                    focused = true,
                    editable = true,
                    actions = setOf(NodeAction.CLICK)
                )
            }
        }
        val orderSnapshot = snapshot(editableWindow, 1_000L)
        EngineV2.snapshotSource = WindowSnapshotSource { orderSnapshot }

        // 1. Gesture
        V2CaptureBridge.onGestureClassified(
            kind = GestureKind.TAP,
            samples = listOf(TouchSample(TouchAction.DOWN, 150f, 250f, 1_100L)),
            beforeSnapshot = orderSnapshot,
            ownPackage = "com.shortcuts.app"
        )
        // 2. Text commit
        V2CaptureBridge.onTextCommit(
            RecorderEvent(
                eventType = RecorderEventType.TEXT_CHANGE,
                packageName = "com.example.app",
                sourceText = null,
                sourceContentDescription = null,
                sourceViewId = "com.example.app:id/search",
                enteredText = "order test",
                occurredAtMillis = 1_200L
            )
        )
        // 3. IME action
        V2CaptureBridge.onImeAction("search", 1_300L)
        // 4. App switch
        V2CaptureBridge.onAppSwitch("com.example.app", "com.example.app.OtherActivity", 1_400L)
        // 5. Key event (BACK)
        val keyEvent = mockk<KeyEvent>()
        every { keyEvent.action } returns KeyEvent.ACTION_UP
        every { keyEvent.keyCode } returns KeyEvent.KEYCODE_BACK
        every { keyEvent.eventTime } returns 1_500L
        V2CaptureBridge.onKeyEvent(keyEvent)

        V2CaptureBridge.awaitCaptureIdleForTest()

        assertEquals(
            listOf("interaction", "text_commit", "ime_action", "app_switch", "global_action"),
            eventOrder
        )
    }

    @Test
    fun testConfirmedActivityFilteringAndCaching() {
        // Ignored UI classes always rejected
        assertTrue(!V2CaptureBridge.isConfirmedActivity("com.example.app", "androidx.appcompat.app.AlertDialog"))
        assertTrue(!V2CaptureBridge.isConfirmedActivity("com.example.app", "android.widget.Toast"))
        assertTrue(!V2CaptureBridge.isConfirmedActivity("com.example.app", "com.google.android.material.snackbar.Snackbar"))
        assertTrue(!V2CaptureBridge.isConfirmedActivity("com.example.app", "android.widget.PopupWindow"))

        // Activity class verified
        assertTrue(V2CaptureBridge.isConfirmedActivity("com.example.app", "com.example.app.MainActivity"))
    }
}
