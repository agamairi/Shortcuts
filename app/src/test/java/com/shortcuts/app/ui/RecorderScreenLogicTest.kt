package com.shortcuts.app.ui

import com.shortcuts.app.automation.model.DecodeResult
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.LaunchAppStep
import com.shortcuts.app.automation.model.LaunchPolicy
import com.shortcuts.app.automation.model.LaunchTarget
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.RecordedDevice
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.VerificationSpec
import com.shortcuts.app.automation.model.WaitStep
import com.shortcuts.app.automation.model.WindowSelector
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.automation.model.WorkflowCodec
import com.shortcuts.app.automation.model.WorkflowV2
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.ShortcutDocument
import com.shortcuts.app.data.ShortcutSteps
import com.shortcuts.app.ui.screens.ManualBuilderUtils
import com.shortcuts.app.ui.screens.RecorderListOperations
import com.shortcuts.app.ui.screens.RecorderSessionUiState
import com.shortcuts.app.ui.screens.RecorderUiState
import com.shortcuts.app.ui.screens.determineRecorderSessionUiState
import com.shortcuts.app.ui.screens.determineRecorderUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderScreenLogicTest {

    @Test
    fun `recorder session state distinguishes start recording and review`() {
        // live session with N captured steps renders as live-with-N, not idle
        assertEquals(
            RecorderSessionUiState.RECORDING,
            determineRecorderSessionUiState(isRecording = true, recordedActionsCount = 5, editableActionsCount = 0)
        )
        
        // idle state
        assertEquals(
            RecorderSessionUiState.START_RECORDING,
            determineRecorderSessionUiState(isRecording = false, recordedActionsCount = 0, editableActionsCount = 0)
        )
        
        // review state
        assertEquals(
            RecorderSessionUiState.REVIEW,
            determineRecorderSessionUiState(isRecording = false, recordedActionsCount = 2, editableActionsCount = 0)
        )
        
        assertEquals(
            RecorderSessionUiState.REVIEW,
            determineRecorderSessionUiState(isRecording = false, recordedActionsCount = 0, editableActionsCount = 2)
        )
    }

    @Test
    fun `recorder prerequisite state covers consent and accessibility combinations`() {
        assertEquals(RecorderUiState.CONSENT_REQUIRED, determineRecorderUiState(false, false))
        assertEquals(RecorderUiState.CONSENT_REQUIRED, determineRecorderUiState(false, true))
        assertEquals(RecorderUiState.SERVICE_NOT_ENABLED, determineRecorderUiState(true, false))
        assertEquals(RecorderUiState.READY_TO_RECORD, determineRecorderUiState(true, true))
    }

    @Test
    fun testRecorderListOperations() {
        val action1 = Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "1")
        val action2 = Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "2")
        val action3 = Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "3")
        
        val list = mutableListOf(action1, action2, action3)
        
        // Move up boundary
        RecorderListOperations.moveUp(list, 0)
        assertEquals(listOf(action1, action2, action3), list)
        
        // Move up normal
        RecorderListOperations.moveUp(list, 1)
        assertEquals(listOf(action2, action1, action3), list)
        
        // Move down normal
        RecorderListOperations.moveDown(list, 1)
        assertEquals(listOf(action2, action3, action1), list)
        
        // Move down boundary
        RecorderListOperations.moveDown(list, 2)
        assertEquals(listOf(action2, action3, action1), list)
        
        // Remove normal
        RecorderListOperations.remove(list, 1)
        assertEquals(listOf(action2, action1), list)
        
        // Remove boundary out of bounds
        RecorderListOperations.remove(list, 5)
        assertEquals(listOf(action2, action1), list)

        // Insert at beginning, middle, and end
        val action4 = Action(actionType = ActionType.WAIT, delayMillis = 1000)
        RecorderListOperations.insert(list, 1, action4)
        assertEquals(listOf(action2, action4, action1), list)

        val action5 = Action(actionType = ActionType.APP_INTENT, packageName = "com.test")
        RecorderListOperations.insert(list, 0, action5)
        assertEquals(listOf(action5, action2, action4, action1), list)

        val action6 = Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI")
        RecorderListOperations.insert(list, list.size, action6)
        assertEquals(listOf(action5, action2, action4, action1, action6), list)
    }

    private fun sampleDevice() = RecordedDevice(
        manufacturer = "Google",
        model = "Pixel 8",
        sdkInt = 34,
        displayWidthPx = 1080,
        displayHeightPx = 2400,
        densityDpi = 420,
        rotation = 0
    )

    private fun sampleWindow(pkg: String = "com.example.app") = WindowSelector(
        packageName = pkg,
        observedActivityClass = "$pkg.MainActivity",
        windowType = WindowType.APPLICATION,
        titleToken = null,
        boundsOnDisplay = NormalizedRect(0f, 0f, 1f, 1f),
        displayId = 0,
        fingerprint = null
    )

    @Test
    fun `recorder review delete step and encode keeps v2 workflow format`() {
        val codec = WorkflowCodec()
        val s1 = LaunchAppStep(id = "s1", target = LaunchTarget.Launcher("app1"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app1"))
        val s2 = WaitStep(id = "s2", durationMs = 1500L)
        val s3 = LaunchAppStep(id = "s3", target = LaunchTarget.Launcher("app3"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app3"))

        val workflow = WorkflowV2(workflowId = "wf-review-del", recordedDevice = sampleDevice(), steps = listOf(s1, s2, s3))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        val editableActions = doc.actions.toMutableList()
        RecorderListOperations.remove(editableActions, 1) // Remove wait step

        val savedJson = ShortcutSteps.encode(doc, editableActions)
        val decodedResult = codec.decode(savedJson)
        assertTrue(decodedResult is DecodeResult.Success)
        val newSteps = (decodedResult as DecodeResult.Success).workflow.steps
        assertEquals(2, newSteps.size)
        assertEquals("s1", newSteps[0].id)
        assertEquals("s3", newSteps[1].id)
    }

    @Test
    fun `recorder review reorder steps and encode keeps v2 workflow format`() {
        val codec = WorkflowCodec()
        val s1 = LaunchAppStep(id = "s1", target = LaunchTarget.Launcher("app1"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app1"))
        val s2 = WaitStep(id = "s2", durationMs = 500L)
        val s3 = LaunchAppStep(id = "s3", target = LaunchTarget.Launcher("app3"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app3"))

        val workflow = WorkflowV2(workflowId = "wf-review-reorder", recordedDevice = sampleDevice(), steps = listOf(s1, s2, s3))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        val editableActions = doc.actions.toMutableList()
        RecorderListOperations.moveDown(editableActions, 0) // Move s1 down: new order s2, s1, s3

        val savedJson = ShortcutSteps.encode(doc, editableActions)
        val decodedResult = codec.decode(savedJson)
        assertTrue(decodedResult is DecodeResult.Success)
        val newSteps = (decodedResult as DecodeResult.Success).workflow.steps
        assertEquals(3, newSteps.size)
        assertEquals("s2", newSteps[0].id)
        assertEquals("s1", newSteps[1].id)
        assertEquals("s3", newSteps[2].id)
    }

    @Test
    fun `recorder review displays inferred keyboard action and allows deleting it while keeping v2`() {
        val codec = WorkflowCodec()
        val s1 = LaunchAppStep(id = "s1", target = LaunchTarget.Launcher("app1"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app1"))
        val inferredStep = UiStep(
            id = "s-inferred",
            operation = UiOperation.IME_ACTION(ImeActionKind.SEARCH),
            expectedWindow = sampleWindow("app1"),
            element = null,
            gesture = null,
            verification = VerificationSpec(emptyList()),
            migrationWarnings = listOf("INFERRED_IME_ACTION")
        )
        val workflow = WorkflowV2(workflowId = "wf-inferred-review", recordedDevice = sampleDevice(), steps = listOf(s1, inferredStep))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        val editableActions = doc.actions.toMutableList()
        assertEquals(2, editableActions.size)
        assertEquals("Pressed the keyboard's Search/Send key (detected)", editableActions[1].target)
        assertEquals("PRESS_ENTER", editableActions[1].uiActionType)

        // Delete inferred step
        RecorderListOperations.remove(editableActions, 1)

        val savedJson = ShortcutSteps.encode(doc, editableActions)
        val decodedResult = codec.decode(savedJson)
        assertTrue(decodedResult is DecodeResult.Success)
        val newSteps = (decodedResult as DecodeResult.Success).workflow.steps
        assertEquals(1, newSteps.size)
        assertEquals("s1", newSteps[0].id)
    }

    @Test
    fun `recorder review without v2 workflow falls back to legacy JSON encoding`() {
        val legacyActions = listOf(
            Action(actionType = ActionType.APP_INTENT, packageName = "com.app1"),
            Action(actionType = ActionType.WAIT, delayMillis = 1000L),
            Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI")
        )
        val legacyJson = ActionConverter().fromActionList(legacyActions)
        val doc = ShortcutSteps.decode(legacyJson)
        assertTrue(doc is ShortcutDocument.Legacy)

        val editableActions = doc.actions.toMutableList()
        RecorderListOperations.remove(editableActions, 1) // Remove wait
        RecorderListOperations.moveDown(editableActions, 0) // Move app intent down

        val savedJson = ShortcutSteps.encode(doc, editableActions)
        val reDecoded = ShortcutSteps.decode(savedJson)
        assertTrue(reDecoded is ShortcutDocument.Legacy)
        assertEquals(2, reDecoded.actions.size)
        assertEquals(ActionType.SYSTEM_TOGGLE, reDecoded.actions[0].actionType)
        assertEquals(ActionType.APP_INTENT, reDecoded.actions[1].actionType)
    }

    // NEW-5 (d): picking an app shortcut on a recorded step must keep its v2StepId
    @Test
    fun `preserveV2StepId keeps v2StepId when picking app action`() {
        val recordedAction = Action(
            actionType = ActionType.APP_INTENT,
            packageName = "com.old.app",
            v2StepId = "v2-step-42"
        )
        val pickedAction = Action(
            actionType = ActionType.APP_INTENT,
            packageName = "com.spotify.music",
            target = "My Playlist",
            url = "spotify:playlist:123"
        )

        val merged = RecorderListOperations.preserveV2StepId(recordedAction, pickedAction)
        assertEquals("v2-step-42", merged.v2StepId)
        assertEquals("com.spotify.music", merged.packageName)
        assertEquals("spotify:playlist:123", merged.url)

        val unrecordedAction = Action(
            actionType = ActionType.APP_INTENT,
            packageName = "com.manual.app"
        )
        val mergedUnrecorded = RecorderListOperations.preserveV2StepId(unrecordedAction, pickedAction)
        assertNull(mergedUnrecorded.v2StepId)
    }

    // NEW-5 (b): safeEncodeWorkflow returns Result failure instead of crashing
    @Test
    fun `safeEncodeWorkflow catches encode errors and returns Result failure`() {
        val s1 = LaunchAppStep(
            id = "s1",
            target = LaunchTarget.Launcher("com.app"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("com.app")
        )
        val workflow = WorkflowV2(
            workflowId = "wf-safe-encode",
            recordedDevice = sampleDevice(),
            steps = listOf(s1)
        )
        val doc = ShortcutSteps.decode(WorkflowCodec().encode(workflow)) as ShortcutDocument.V2

        // Valid actions encode successfully
        val successResult = ManualBuilderUtils.safeEncodeWorkflow(doc, doc.actions)
        assertTrue(successResult.isSuccess)
        assertNotNull(successResult.getOrNull())

        // Invalid edit: blank package name on LaunchAppStep
        val invalidActions = listOf(doc.actions[0].copy(packageName = ""))
        val failureResult = ManualBuilderUtils.safeEncodeWorkflow(doc, invalidActions)
        assertTrue(failureResult.isFailure)
        assertTrue(failureResult.exceptionOrNull()?.message?.contains("Package name cannot be blank") == true)

        // Non-v2 doc returns null successfully
        val legacyDoc = ShortcutDocument.Legacy(emptyList())
        val legacyResult = ManualBuilderUtils.safeEncodeWorkflow(legacyDoc, emptyList())
        assertTrue(legacyResult.isSuccess)
        assertNull(legacyResult.getOrNull())
    }

    // NEW-5 (b): safeEncodeForSave returns Result failure on invalid v2 edit and success on valid
    @Test
    fun `safeEncodeForSave returns Result failure on invalid v2 edit and success for valid`() {
        val s1 = LaunchAppStep(
            id = "s1",
            target = LaunchTarget.Launcher("com.app"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("com.app")
        )
        val workflow = WorkflowV2(
            workflowId = "wf-safe-save",
            recordedDevice = sampleDevice(),
            steps = listOf(s1)
        )
        val doc = ShortcutSteps.decode(WorkflowCodec().encode(workflow)) as ShortcutDocument.V2

        val success = ManualBuilderUtils.safeEncodeForSave(doc, doc.actions)
        assertTrue(success.isSuccess)

        val invalidActions = listOf(doc.actions[0].copy(packageName = "   "))
        val failure = ManualBuilderUtils.safeEncodeForSave(doc, invalidActions)
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message?.contains("Package name cannot be blank") == true)
    }

    // NEW-5 (c): EditorRunConfirmationDialog preserves actionsJson
    @Test
    fun `PendingEditorRun preserves actionsJson for confirmed execution`() {
        val actions = listOf(Action(actionType = ActionType.APP_INTENT, packageName = "com.test"))
        val effects = listOf("Opens an external application")
        val v2Json = "{\"v2Workflow\": true}"

        val pending = com.shortcuts.app.ui.screens.PendingEditorRun(
            shortcutName = "V2 Shortcut",
            actions = actions,
            effects = effects,
            actionsJson = v2Json
        )

        assertEquals("V2 Shortcut", pending.shortcutName)
        assertEquals(actions, pending.actions)
        assertEquals(effects, pending.effects)
        assertEquals(v2Json, pending.actionsJson)
    }
}
