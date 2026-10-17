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
        assertEquals(1, capturedSwitches.size)
        assertEquals("com.example.app.MainActivity", capturedSwitches[0].observedActivityClass)

        // 2. Dialog appears (e.g. AlertDialog)
        V2CaptureBridge.onAppSwitch("com.example.app", "androidx.appcompat.app.AlertDialog", 2_000L)
        assertEquals(2, capturedSwitches.size)
        // Dialog class must be rejected and last real activity retained
        assertEquals("com.example.app.MainActivity", capturedSwitches[1].observedActivityClass)
    }
}
