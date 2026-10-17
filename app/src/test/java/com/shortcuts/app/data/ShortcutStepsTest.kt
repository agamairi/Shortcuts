package com.shortcuts.app.data

import com.shortcuts.app.automation.model.CapturedGesture
import com.shortcuts.app.automation.model.CapturedPoint
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LaunchAppStep
import com.shortcuts.app.automation.model.LaunchPolicy
import com.shortcuts.app.automation.model.LaunchTarget
import com.shortcuts.app.automation.model.LegacyActionStep
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.LocatorStability
import com.shortcuts.app.automation.model.LocatorStrategy
import com.shortcuts.app.automation.model.NormalizedPoint
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.RecordedDevice
import com.shortcuts.app.automation.model.RevealDirection
import com.shortcuts.app.automation.model.RevealPolicy
import com.shortcuts.app.automation.model.ScreenFingerprint
import com.shortcuts.app.automation.model.StepSafety
import com.shortcuts.app.automation.model.TimedPoint
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.VerificationPostcondition
import com.shortcuts.app.automation.model.VerificationSpec
import com.shortcuts.app.automation.model.WaitStep
import com.shortcuts.app.automation.model.WindowSelector
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.automation.model.WorkflowCodec
import com.shortcuts.app.automation.model.WorkflowV2
import com.shortcuts.app.ui.screens.ManualBuilderUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShortcutStepsTest {

    private val codec = WorkflowCodec()

    private fun sampleDevice() = RecordedDevice(
        manufacturer = "Google",
        model = "Pixel 8",
        sdkInt = 34,
        displayWidthPx = 1080,
        displayHeightPx = 2400,
        densityDpi = 420,
        rotation = 0
    )

    private fun sampleFingerprint(pkg: String = "com.example.app") = ScreenFingerprint(
        version = 1,
        packageName = pkg,
        activityClass = "$pkg.MainActivity",
        windowType = WindowType.APPLICATION,
        structuralTokens = setOf(1L, 2L),
        landmarkTokens = setOf(10L),
        structureHash = 42L
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

    private fun sampleElement(
        text: String? = "Submit",
        desc: String? = "Submit Button",
        resId: String? = "com.example.app:id/submit_btn",
        className: String? = "android.widget.Button"
    ): com.shortcuts.app.automation.model.ElementSelector {
        var rank = 0
        val strategies = buildList {
            resId?.let { add(LocatorStrategy(rank++, LocatorKind.RESOURCE_ID_EXACT, it, LocatorStability.STABLE)) }
            text?.let { add(LocatorStrategy(rank++, LocatorKind.TEXT_EXACT, it, LocatorStability.VOLATILE)) }
            desc?.let { add(LocatorStrategy(rank++, LocatorKind.CONTENT_DESCRIPTION_EXACT, it, LocatorStability.LIKELY_STABLE)) }
        }
        return com.shortcuts.app.automation.model.ElementSelector(
            strategies = strategies,
            expectedClass = className,
            expectedRole = null,
            ancestorChain = emptyList(),
            siblingIndex = null,
            rowContext = null,
            boundsInWindow = NormalizedRect(0.1f, 0.2f, 0.9f, 0.3f),
            rawPoint = CapturedPoint(
                displayPx = IntPoint(540, 600),
                pointInWindow = NormalizedPoint(0.5f, 0.25f),
                pointInNode = null,
                displayWidthPx = 1080,
                displayHeightPx = 2400,
                rotation = 0,
                densityDpi = 420
            ),
            requiredActions = emptySet()
        )
    }

    // 1. Projection of every step type
    @Test
    fun `project LaunchAppStep with Launcher, Component, and DeepLink targets`() {
        val stepLauncher = LaunchAppStep(
            id = "launch-launcher",
            target = LaunchTarget.Launcher("com.example.launcher"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("com.example.launcher")
        )
        val stepComponent = LaunchAppStep(
            id = "launch-component",
            target = LaunchTarget.Component("com.example.component", "com.example.component.Main"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("com.example.component")
        )
        val stepDeepLink = LaunchAppStep(
            id = "launch-deeplink",
            target = LaunchTarget.DeepLink("com.example.deeplink", "myapp://home"),
            policy = LaunchPolicy.DEEP_LINK,
            expectedWindow = sampleWindow("com.example.deeplink")
        )

        val workflow = WorkflowV2(
            workflowId = "wf-launch",
            recordedDevice = sampleDevice(),
            steps = listOf(stepLauncher, stepComponent, stepDeepLink)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals(3, doc.actions.size)

        val a1 = doc.actions[0]
        assertEquals(ActionType.APP_INTENT, a1.actionType)
        assertEquals("com.example.launcher", a1.packageName)
        assertEquals("com.example.launcher", a1.targetPackageName)
        assertEquals("launch-launcher", a1.v2StepId)

        val a2 = doc.actions[1]
        assertEquals(ActionType.APP_INTENT, a2.actionType)
        assertEquals("com.example.component", a2.packageName)
        assertEquals("launch-component", a2.v2StepId)

        val a3 = doc.actions[2]
        assertEquals(ActionType.APP_INTENT, a3.actionType)
        assertEquals("com.example.deeplink", a3.packageName)
        assertEquals("launch-deeplink", a3.v2StepId)
    }

    @Test
    fun `project UiStep TAP and LONG_PRESS carries semantic selectors, bounds, coordinates, and null delayMillis`() {
        val tapStep = UiStep(
            id = "ui-tap",
            operation = UiOperation.TAP,
            expectedWindow = sampleWindow("com.example.app"),
            element = sampleElement(text = "Save", desc = "Save button", resId = "com.example.app:id/save", className = "android.widget.Button"),
            gesture = null,
            verification = VerificationSpec(emptyList()),
            safety = StepSafety.REVERSIBLE,
            capturedDelayFromPreviousMs = 2500L // Next step's delay must NOT be projected
        )
        val longPressStep = UiStep(
            id = "ui-long-press",
            operation = UiOperation.LONG_PRESS,
            expectedWindow = sampleWindow("com.example.app"),
            element = sampleElement(text = "Item", desc = null, resId = null, className = "android.widget.TextView"),
            gesture = null,
            verification = VerificationSpec(emptyList())
        )

        val workflow = WorkflowV2(
            workflowId = "wf-ui-tap",
            recordedDevice = sampleDevice(),
            steps = listOf(tapStep, longPressStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals(2, doc.actions.size)

        val tapAction = doc.actions[0]
        assertEquals(ActionType.UI_AUTOMATION, tapAction.actionType)
        assertEquals("TAP", tapAction.uiActionType)
        assertEquals("Save", tapAction.targetText)
        assertEquals("Save button", tapAction.targetContentDescription)
        assertEquals("com.example.app:id/save", tapAction.targetNodeId)
        assertEquals("Save", tapAction.target)
        assertEquals("android.widget.Button", tapAction.targetClassName)
        assertEquals("com.example.app", tapAction.targetPackageName)
        assertEquals(540, tapAction.screenX)
        assertEquals(600, tapAction.screenY)
        assertEquals("ui-tap", tapAction.v2StepId)
        assertNull(tapAction.delayMillis) // UI step delayMillis remains null

        val lpAction = doc.actions[1]
        assertEquals(ActionType.UI_AUTOMATION, lpAction.actionType)
        assertEquals("LONG_PRESS", lpAction.uiActionType)
        assertEquals("Item", lpAction.targetText)
        assertEquals("ui-long-press", lpAction.v2StepId)
        assertNull(lpAction.delayMillis)
    }

    @Test
    fun `project UiStep SET_TEXT, IME_ACTION, GLOBAL_ACTION`() {
        val setTextStep = UiStep(
            id = "ui-set-text",
            operation = UiOperation.SET_TEXT("Secret message"),
            expectedWindow = sampleWindow("com.example.app"),
            element = sampleElement(text = "", desc = "Search box", resId = "com.example.app:id/search", className = "android.widget.EditText"),
            gesture = null,
            verification = VerificationSpec(emptyList())
        )
        val imeStep = UiStep(
            id = "ui-ime",
            operation = UiOperation.IME_ACTION(ImeActionKind.DONE),
            expectedWindow = sampleWindow("com.example.app"),
            element = null,
            gesture = null,
            verification = VerificationSpec(emptyList())
        )
        val globalStep = UiStep(
            id = "ui-global",
            operation = UiOperation.GLOBAL_ACTION("GLOBAL_ACTION_BACK"),
            expectedWindow = sampleWindow("com.example.app"),
            element = null,
            gesture = null,
            verification = VerificationSpec(emptyList())
        )

        val workflow = WorkflowV2(
            workflowId = "wf-ui-ops",
            recordedDevice = sampleDevice(),
            steps = listOf(setTextStep, imeStep, globalStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals(3, doc.actions.size)

        val textAction = doc.actions[0]
        assertEquals(ActionType.UI_AUTOMATION, textAction.actionType)
        assertEquals("TYPE_TEXT", textAction.uiActionType)
        assertEquals("Secret message", textAction.textInput)
        assertEquals("ui-set-text", textAction.v2StepId)

        val imeAction = doc.actions[1]
        assertEquals(ActionType.UI_AUTOMATION, imeAction.actionType)
        assertEquals("PRESS_ENTER", imeAction.uiActionType)
        assertEquals("ui-ime", imeAction.v2StepId)

        val globalAction = doc.actions[2]
        assertEquals(ActionType.UI_AUTOMATION, globalAction.actionType)
        assertEquals("GLOBAL_ACTION", globalAction.uiActionType)
        assertEquals("GLOBAL_ACTION_BACK", globalAction.globalAction)
        assertEquals("ui-global", globalAction.v2StepId)
    }

    @Test
    fun `project UiStep SWIPE computes scrollDirection from gesture vector and fallback to reveal`() {
        // Vertical swipe up: dy < 0
        val swipeUpGesture = CapturedGesture(
            kind = GestureKind.SWIPE,
            durationMs = 300,
            points = listOf(
                TimedPoint(IntPoint(500, 1500), 0, NormalizedPoint(0.5f, 0.75f)),
                TimedPoint(IntPoint(500, 500), 300, NormalizedPoint(0.5f, 0.25f))
            )
        )
        val swipeUpStep = UiStep(
            id = "swipe-up",
            operation = UiOperation.SWIPE,
            expectedWindow = sampleWindow("com.example.app"),
            element = null,
            gesture = swipeUpGesture,
            verification = VerificationSpec(emptyList())
        )

        // Horizontal swipe right: dx > 0
        val swipeRightGesture = CapturedGesture(
            kind = GestureKind.SWIPE,
            durationMs = 200,
            points = listOf(
                TimedPoint(IntPoint(200, 800), 0, NormalizedPoint(0.2f, 0.4f)),
                TimedPoint(IntPoint(800, 800), 200, NormalizedPoint(0.8f, 0.4f))
            )
        )
        val swipeRightStep = UiStep(
            id = "swipe-right",
            operation = UiOperation.SWIPE,
            expectedWindow = sampleWindow("com.example.app"),
            element = null,
            gesture = swipeRightGesture,
            verification = VerificationSpec(emptyList())
        )

        // Swipe with no gesture points falls back to reveal direction
        val swipeRevealStep = UiStep(
            id = "swipe-reveal",
            operation = UiOperation.SWIPE,
            expectedWindow = sampleWindow("com.example.app"),
            element = null,
            gesture = null,
            reveal = RevealPolicy(direction = RevealDirection.FORWARD),
            verification = VerificationSpec(emptyList())
        )

        val workflow = WorkflowV2(
            workflowId = "wf-swipes",
            recordedDevice = sampleDevice(),
            steps = listOf(swipeUpStep, swipeRightStep, swipeRevealStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals(3, doc.actions.size)

        assertEquals("SCROLL", doc.actions[0].uiActionType)
        assertEquals("UP", doc.actions[0].scrollDirection)

        assertEquals("SCROLL", doc.actions[1].uiActionType)
        assertEquals("RIGHT", doc.actions[1].scrollDirection)

        assertEquals("SCROLL", doc.actions[2].uiActionType)
        assertEquals("FORWARD", doc.actions[2].scrollDirection)
    }

    @Test
    fun `project WaitStep and LegacyActionStep`() {
        val waitStep = WaitStep(id = "wait-1", durationMs = 1500L)
        val legacyWrapped = Action(
            actionType = ActionType.HTTP_REQUEST,
            url = "https://example.com/api",
            method = "POST"
        )
        val legacyStep = LegacyActionStep(id = "legacy-1", action = legacyWrapped)

        val workflow = WorkflowV2(
            workflowId = "wf-wait-legacy",
            recordedDevice = sampleDevice(),
            steps = listOf(waitStep, legacyStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals(2, doc.actions.size)

        val waitAction = doc.actions[0]
        assertEquals(ActionType.WAIT, waitAction.actionType)
        assertEquals(1500L, waitAction.delayMillis)
        assertEquals("wait-1", waitAction.v2StepId)

        val legacyAction = doc.actions[1]
        assertEquals(ActionType.HTTP_REQUEST, legacyAction.actionType)
        assertEquals("https://example.com/api", legacyAction.url)
        assertEquals("POST", legacyAction.method)
        assertEquals("legacy-1", legacyAction.v2StepId)
    }

    // 2. Encode round-trip with no edits is byte-identical to original JSON
    @Test
    fun `encode round-trip with no edits is byte-identical to the original JSON`() {
        val workflow = WorkflowV2(
            workflowId = "wf-byte-identical",
            recordedDevice = sampleDevice(),
            steps = listOf(
                LaunchAppStep(
                    id = "step-launch",
                    target = LaunchTarget.Launcher("com.example.app"),
                    policy = LaunchPolicy.RESET_TO_ROOT,
                    expectedWindow = sampleWindow("com.example.app")
                ),
                UiStep(
                    id = "step-tap",
                    operation = UiOperation.TAP,
                    expectedWindow = sampleWindow("com.example.app"),
                    element = sampleElement(text = "Login"),
                    gesture = null,
                    verification = VerificationSpec(emptyList())
                ),
                WaitStep(id = "step-wait", durationMs = 800L)
            )
        )
        val originalJson = codec.encode(workflow)
        val doc = ShortcutSteps.decode(originalJson)
        assertTrue(doc is ShortcutDocument.V2)

        val encodedJson = ShortcutSteps.encode(doc, doc.actions)
        assertEquals(originalJson, encodedJson)
    }

    // 3. Delete, reorder, add legacy action, and delay edits produce expected WorkflowV2
    @Test
    fun `delete step removes corresponding v2 step from workflow`() {
        val s1 = LaunchAppStep(id = "s1", target = LaunchTarget.Launcher("app1"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app1"))
        val s2 = WaitStep(id = "s2", durationMs = 1000L)
        val s3 = LaunchAppStep(id = "s3", target = LaunchTarget.Launcher("app3"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app3"))

        val workflow = WorkflowV2(workflowId = "wf-delete", recordedDevice = sampleDevice(), steps = listOf(s1, s2, s3))
        val originalJson = codec.encode(workflow)
        val doc = ShortcutSteps.decode(originalJson) as ShortcutDocument.V2

        // User deletes step 2 (WaitStep)
        val editedActions = listOf(doc.actions[0], doc.actions[2])
        val encodedJson = ShortcutSteps.encode(doc, editedActions)

        val decodedResult = codec.decode(encodedJson) as com.shortcuts.app.automation.model.DecodeResult.Success
        val newSteps = decodedResult.workflow.steps
        assertEquals(2, newSteps.size)
        assertEquals("s1", newSteps[0].id)
        assertEquals("s3", newSteps[1].id)
    }

    @Test
    fun `reorder steps preserves original step instances in new order`() {
        val s1 = LaunchAppStep(id = "s1", target = LaunchTarget.Launcher("app1"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app1"))
        val s2 = UiStep(id = "s2", operation = UiOperation.TAP, expectedWindow = sampleWindow("app1"), element = sampleElement(text = "Button"), gesture = null, verification = VerificationSpec(emptyList()))
        val s3 = WaitStep(id = "s3", durationMs = 500L)

        val workflow = WorkflowV2(workflowId = "wf-reorder", recordedDevice = sampleDevice(), steps = listOf(s1, s2, s3))
        val originalJson = codec.encode(workflow)
        val doc = ShortcutSteps.decode(originalJson) as ShortcutDocument.V2

        // Reorder: s3, s1, s2
        val editedActions = listOf(doc.actions[2], doc.actions[0], doc.actions[1])
        val encodedJson = ShortcutSteps.encode(doc, editedActions)

        val decodedResult = codec.decode(encodedJson) as com.shortcuts.app.automation.model.DecodeResult.Success
        val newSteps = decodedResult.workflow.steps
        assertEquals(3, newSteps.size)
        assertEquals("s3", newSteps[0].id)
        assertTrue(newSteps[0] is WaitStep)
        assertEquals("s1", newSteps[1].id)
        assertTrue(newSteps[1] is LaunchAppStep)
        assertEquals("s2", newSteps[2].id)
        assertTrue(newSteps[2] is UiStep)
        assertEquals("Button", (newSteps[2] as UiStep).element?.strategies?.firstOrNull { it.kind == LocatorKind.TEXT_EXACT }?.value)
    }

    @Test
    fun `adding legacy action in editor wraps it as LegacyActionStep`() {
        val s1 = LaunchAppStep(id = "s1", target = LaunchTarget.Launcher("app1"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app1"))
        val workflow = WorkflowV2(workflowId = "wf-add", recordedDevice = sampleDevice(), steps = listOf(s1))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        val newToggleAction = Action(
            actionType = ActionType.SYSTEM_TOGGLE,
            target = "flashlight",
            state = "on"
        )
        val editedActions = listOf(doc.actions[0], newToggleAction)
        val encodedJson = ShortcutSteps.encode(doc, editedActions)

        val decodedResult = codec.decode(encodedJson) as com.shortcuts.app.automation.model.DecodeResult.Success
        val newSteps = decodedResult.workflow.steps
        assertEquals(2, newSteps.size)
        assertEquals("s1", newSteps[0].id)
        assertTrue(newSteps[1] is LegacyActionStep)
        val legacyStep = newSteps[1] as LegacyActionStep
        assertEquals(ActionType.SYSTEM_TOGGLE, legacyStep.action.actionType)
        assertEquals("flashlight", legacyStep.action.target)
        assertEquals("on", legacyStep.action.state)
        assertTrue(legacyStep.id.isNotBlank())
    }

    @Test
    fun `editing delay on UI step inserts a WaitStep after it and editing WaitStep updates duration`() {
        val s1 = UiStep(id = "s1", operation = UiOperation.TAP, expectedWindow = sampleWindow("app1"), element = sampleElement(text = "Go"), gesture = null, verification = VerificationSpec(emptyList()))
        val s2 = WaitStep(id = "s2", durationMs = 200L)
        val workflow = WorkflowV2(workflowId = "wf-delay", recordedDevice = sampleDevice(), steps = listOf(s1, s2))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        // User sets delayMillis on the UI action and changes delayMillis on the WaitStep
        val editedS1Action = doc.actions[0].copy(delayMillis = 750L)
        val editedS2Action = doc.actions[1].copy(delayMillis = 1200L)
        val encodedJson = ShortcutSteps.encode(doc, listOf(editedS1Action, editedS2Action))

        val decodedResult = codec.decode(encodedJson) as com.shortcuts.app.automation.model.DecodeResult.Success
        val newSteps = decodedResult.workflow.steps
        // Expected: s1 (UiStep), then inserted WaitStep(750ms), then s2 (WaitStep with updated 1200ms)
        assertEquals(3, newSteps.size)
        assertEquals("s1", newSteps[0].id)
        assertTrue(newSteps[0] is UiStep)

        assertTrue(newSteps[1] is WaitStep)
        assertEquals(750L, (newSteps[1] as WaitStep).durationMs)

        assertEquals("s2", newSteps[2].id)
        assertTrue(newSteps[2] is WaitStep)
        assertEquals(1200L, (newSteps[2] as WaitStep).durationMs)
    }

    // 4. Legacy JSON paths are byte-identical to today
    @Test
    fun `legacy JSON decoding and encoding is byte-identical to ActionConverter`() {
        val actions = listOf(
            Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI", state = "ON"),
            Action(actionType = ActionType.APP_INTENT, packageName = "com.spotify.music"),
            Action(actionType = ActionType.WAIT, delayMillis = 1000L)
        )
        val legacyJson = ActionConverter().fromActionList(actions)

        val doc = ShortcutSteps.decode(legacyJson)
        assertTrue(doc is ShortcutDocument.Legacy)
        assertEquals(actions, doc.actions)

        val reEncoded = ShortcutSteps.encode(doc, doc.actions)
        assertEquals(legacyJson, reEncoded)

        // With edits:
        val edited = listOf(actions[0], actions[2])
        val expectedEditedJson = ActionConverter().fromActionList(edited)
        val actualEditedJson = ShortcutSteps.encode(doc, edited)
        assertEquals(expectedEditedJson, actualEditedJson)
    }

    // 5. stepCount for both formats
    @Test
    fun `stepCount helper accurately counts legacy, v2, empty, and invalid inputs`() {
        val legacyJson = ActionConverter().fromActionList(
            listOf(
                Action(actionType = ActionType.SYSTEM_TOGGLE),
                Action(actionType = ActionType.APP_INTENT)
            )
        )
        assertEquals(2, ShortcutSteps.stepCount(legacyJson))

        val v2Workflow = WorkflowV2(
            workflowId = "wf-count",
            recordedDevice = sampleDevice(),
            steps = listOf(
                LaunchAppStep(id = "1", target = LaunchTarget.Launcher("app"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app")),
                WaitStep(id = "2", durationMs = 100L),
                WaitStep(id = "3", durationMs = 200L)
            )
        )
        val v2Json = codec.encode(v2Workflow)
        assertEquals(3, ShortcutSteps.stepCount(v2Json))

        assertEquals(0, ShortcutSteps.stepCount(null))
        assertEquals(0, ShortcutSteps.stepCount(""))
        assertEquals(0, ShortcutSteps.stepCount("   "))
        assertEquals(0, ShortcutSteps.stepCount("[]"))
        assertEquals(0, ShortcutSteps.stepCount("{ corrupt json"))
        assertEquals(0, ShortcutSteps.stepCount("{\"unknownField\": true}"))
    }

    // 6. Refusing to save over unreadable JSON
    @Test
    fun `unreadable JSON decodes to Unreadable and refuses to encode or save`() {
        val corruptJson = "{ not valid json at all"
        val doc = ShortcutSteps.decode(corruptJson)
        assertTrue(doc is ShortcutDocument.Unreadable)
        val unreadableDoc = doc as ShortcutDocument.Unreadable
        assertTrue(unreadableDoc.reason.isNotBlank())
        assertEquals(0, unreadableDoc.actions.size)
        assertEquals(0, ShortcutSteps.stepCount(corruptJson))

        try {
            ShortcutSteps.encode(unreadableDoc, emptyList())
            fail("Expected IllegalArgumentException when attempting to encode unreadable document")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("Cannot encode unreadable shortcut document") == true)
        }

        try {
            ManualBuilderUtils.buildManualAutomation(
                name = "Corrupt test",
                actions = emptyList(),
                colorKey = "TEAL",
                document = unreadableDoc
            )
            fail("Expected IllegalArgumentException when building automation with unreadable document")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message?.contains("Cannot encode unreadable shortcut document") == true)
        }
    }

    // 7. Fix #8: Mapping editable fields back to typed v2 steps and rejecting unrepresentable edits

    @Test
    fun `encode maps SET_TEXT textInput edits into UiOperation SET_TEXT`() {
        val originalStep = UiStep(
            id = "ui-edit-text",
            operation = UiOperation.SET_TEXT("old text"),
            expectedWindow = sampleWindow("com.example.app"),
            element = sampleElement(text = "", desc = "Input", resId = "com.example.app:id/input", className = "android.widget.EditText"),
            gesture = null,
            verification = VerificationSpec(emptyList())
        )
        val workflow = WorkflowV2(
            workflowId = "wf-edit-set-text",
            recordedDevice = sampleDevice(),
            steps = listOf(originalStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals("old text", doc.actions[0].textInput)

        val editedAction = doc.actions[0].copy(textInput = "new text updated")
        val encodedJson = ShortcutSteps.encode(doc, listOf(editedAction))

        val decodedResult = codec.decode(encodedJson) as com.shortcuts.app.automation.model.DecodeResult.Success
        val updatedStep = decodedResult.workflow.steps[0] as UiStep
        assertEquals("ui-edit-text", updatedStep.id)
        assertEquals(UiOperation.SET_TEXT("new text updated"), updatedStep.operation)
    }

    @Test
    fun `encode maps LaunchAppStep packageName edits into LaunchTarget Launcher and expectedWindow`() {
        val originalStep = LaunchAppStep(
            id = "launch-step-1",
            target = LaunchTarget.Launcher("com.old.app"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("com.old.app")
        )
        val workflow = WorkflowV2(
            workflowId = "wf-edit-launch",
            recordedDevice = sampleDevice(),
            steps = listOf(originalStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals("com.old.app", doc.actions[0].packageName)

        val editedAction = doc.actions[0].copy(packageName = "com.new.app")
        val encodedJson = ShortcutSteps.encode(doc, listOf(editedAction))

        val decodedResult = codec.decode(encodedJson) as com.shortcuts.app.automation.model.DecodeResult.Success
        val updatedStep = decodedResult.workflow.steps[0] as LaunchAppStep
        assertEquals("launch-step-1", updatedStep.id)
        assertEquals(LaunchTarget.Launcher("com.new.app"), updatedStep.target)
        assertEquals("com.new.app", updatedStep.expectedWindow.packageName)
    }

    @Test
    fun `encode maps WaitStep delayMillis edits into durationMs and timeoutMs`() {
        val originalStep = WaitStep(id = "wait-step-1", durationMs = 1000L, timeoutMs = 1000L)
        val workflow = WorkflowV2(
            workflowId = "wf-edit-wait",
            recordedDevice = sampleDevice(),
            steps = listOf(originalStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals(1000L, doc.actions[0].delayMillis)

        val editedAction = doc.actions[0].copy(delayMillis = 4000L)
        val encodedJson = ShortcutSteps.encode(doc, listOf(editedAction))

        val decodedResult = codec.decode(encodedJson) as com.shortcuts.app.automation.model.DecodeResult.Success
        val updatedStep = decodedResult.workflow.steps[0] as WaitStep
        assertEquals("wait-step-1", updatedStep.id)
        assertEquals(4000L, updatedStep.durationMs)
        assertEquals(4000L, updatedStep.timeoutMs)
    }

    @Test
    fun `encode LaunchAppStep with delayMillis inserts trailing WaitStep`() {
        val originalStep = LaunchAppStep(
            id = "launch-with-delay",
            target = LaunchTarget.Launcher("com.example.app"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("com.example.app")
        )
        val workflow = WorkflowV2(
            workflowId = "wf-launch-delay",
            recordedDevice = sampleDevice(),
            steps = listOf(originalStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        val editedAction = doc.actions[0].copy(delayMillis = 650L)
        val encodedJson = ShortcutSteps.encode(doc, listOf(editedAction))

        val decodedResult = codec.decode(encodedJson) as com.shortcuts.app.automation.model.DecodeResult.Success
        assertEquals(2, decodedResult.workflow.steps.size)
        assertTrue(decodedResult.workflow.steps[0] is LaunchAppStep)
        assertTrue(decodedResult.workflow.steps[1] is WaitStep)
        assertEquals(650L, (decodedResult.workflow.steps[1] as WaitStep).durationMs)
    }

    @Test
    fun `encode rejects continueOnError for recorded v2 steps`() {
        val uiStep = UiStep(
            id = "ui-s",
            operation = UiOperation.TAP,
            expectedWindow = sampleWindow("app"),
            element = sampleElement(),
            gesture = null,
            verification = VerificationSpec(emptyList())
        )
        val launchStep = LaunchAppStep(
            id = "launch-s",
            target = LaunchTarget.Launcher("app"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("app")
        )
        val waitStep = WaitStep(id = "wait-s", durationMs = 500L)

        val workflow = WorkflowV2(workflowId = "wf-err", recordedDevice = sampleDevice(), steps = listOf(uiStep, launchStep, waitStep))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        // Testing continueOnError on UiStep
        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0].copy(continueOnError = true), doc.actions[1], doc.actions[2]))
            fail("Expected IllegalArgumentException for continueOnError on UiStep")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("continueOnError has no Engine v2 equivalent") == true)
        }

        // Testing continueOnError on LaunchAppStep
        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0], doc.actions[1].copy(continueOnError = true), doc.actions[2]))
            fail("Expected IllegalArgumentException for continueOnError on LaunchAppStep")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("continueOnError has no Engine v2 equivalent") == true)
        }

        // Testing continueOnError on WaitStep
        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0], doc.actions[1], doc.actions[2].copy(continueOnError = true)))
            fail("Expected IllegalArgumentException for continueOnError on WaitStep")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("continueOnError has no Engine v2 equivalent") == true)
        }
    }

    @Test
    fun `encode rejects actionType changes on recorded v2 steps`() {
        val uiStep = UiStep(id = "ui-s", operation = UiOperation.TAP, expectedWindow = sampleWindow("app"), element = sampleElement(), gesture = null, verification = VerificationSpec(emptyList()))
        val launchStep = LaunchAppStep(id = "launch-s", target = LaunchTarget.Launcher("app"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app"))
        val waitStep = WaitStep(id = "wait-s", durationMs = 500L)

        val workflow = WorkflowV2(workflowId = "wf-types", recordedDevice = sampleDevice(), steps = listOf(uiStep, launchStep, waitStep))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0].copy(actionType = ActionType.APP_INTENT)))
            fail("Expected IllegalArgumentException when changing UiStep actionType")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot change actionType of recorded UiStep") == true)
        }

        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[1].copy(actionType = ActionType.UI_AUTOMATION)))
            fail("Expected IllegalArgumentException when changing LaunchAppStep actionType")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot change actionType of recorded LaunchAppStep") == true)
        }

        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[2].copy(actionType = ActionType.SYSTEM_TOGGLE)))
            fail("Expected IllegalArgumentException when changing WaitStep actionType")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot change actionType of recorded WaitStep") == true)
        }
    }

    @Test
    fun `encode rejects uiActionType changes on recorded UiStep`() {
        val uiStep = UiStep(id = "ui-s", operation = UiOperation.TAP, expectedWindow = sampleWindow("app"), element = sampleElement(), gesture = null, verification = VerificationSpec(emptyList()))
        val workflow = WorkflowV2(workflowId = "wf-ui-act", recordedDevice = sampleDevice(), steps = listOf(uiStep))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0].copy(uiActionType = "TYPE_TEXT")))
            fail("Expected IllegalArgumentException when changing uiActionType")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot change uiActionType of recorded UiStep") == true)
        }
    }

    @Test
    fun `encode rejects target element or selector modifications on recorded UiStep`() {
        val uiStep = UiStep(
            id = "ui-s",
            operation = UiOperation.TAP,
            expectedWindow = sampleWindow("app"),
            element = sampleElement(text = "Original Text", desc = "Original Desc", resId = "app:id/btn", className = "android.widget.Button"),
            gesture = null,
            verification = VerificationSpec(emptyList())
        )
        val workflow = WorkflowV2(workflowId = "wf-selectors", recordedDevice = sampleDevice(), steps = listOf(uiStep))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        val baseAction = doc.actions[0]

        val variations = listOf(
            baseAction.copy(targetText = "Modified Text"),
            baseAction.copy(targetContentDescription = "Modified Desc"),
            baseAction.copy(targetNodeId = "app:id/other"),
            baseAction.copy(targetClassName = "android.widget.TextView"),
            baseAction.copy(targetPackageName = "com.other.app"),
            baseAction.copy(screenX = 999),
            baseAction.copy(screenY = 999),
            baseAction.copy(preferCoordinateReplay = true),
            baseAction.copy(target = "Modified Target")
        )

        for (variant in variations) {
            try {
                ShortcutSteps.encode(doc, listOf(variant))
                fail("Expected IllegalArgumentException for selector modification: $variant")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message?.contains("Target element and selectors are read-only for recorded UiStep") == true)
            }
        }
    }

    @Test
    fun `encode rejects textInput modification on non-SET_TEXT UiStep`() {
        val tapStep = UiStep(id = "ui-tap", operation = UiOperation.TAP, expectedWindow = sampleWindow("app"), element = sampleElement(), gesture = null, verification = VerificationSpec(emptyList()))
        val workflow = WorkflowV2(workflowId = "wf-tap-text", recordedDevice = sampleDevice(), steps = listOf(tapStep))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0].copy(textInput = "invalid input for tap")))
            fail("Expected IllegalArgumentException when setting textInput on TAP UiStep")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Cannot set textInput on non-SET_TEXT UiStep") == true)
        }
    }

    @Test
    fun `encode rejects blank packageName on LaunchAppStep`() {
        val launchStep = LaunchAppStep(id = "launch-1", target = LaunchTarget.Launcher("app"), policy = LaunchPolicy.RESET_TO_ROOT, expectedWindow = sampleWindow("app"))
        val workflow = WorkflowV2(workflowId = "wf-blank-pkg", recordedDevice = sampleDevice(), steps = listOf(launchStep))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0].copy(packageName = "   ")))
            fail("Expected IllegalArgumentException for blank packageName")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Package name cannot be blank for LaunchAppStep") == true)
        }
    }

    @Test
    fun `encode rejects non-positive duration on WaitStep`() {
        val waitStep = WaitStep(id = "wait-1", durationMs = 1000L)
        val workflow = WorkflowV2(workflowId = "wf-non-pos-wait", recordedDevice = sampleDevice(), steps = listOf(waitStep))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0].copy(delayMillis = 0L)))
            fail("Expected IllegalArgumentException for zero duration")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Wait duration must be positive for WaitStep") == true)
        }

        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0].copy(delayMillis = -100L)))
            fail("Expected IllegalArgumentException for negative duration")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Wait duration must be positive for WaitStep") == true)
        }
    }

    @Test
    fun `encode rejects duplicate and unknown v2StepId`() {
        val s1 = WaitStep(id = "s1", durationMs = 500L)
        val workflow = WorkflowV2(workflowId = "wf-dup", recordedDevice = sampleDevice(), steps = listOf(s1))
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        // Duplicate v2StepId
        try {
            ShortcutSteps.encode(doc, listOf(doc.actions[0], doc.actions[0]))
            fail("Expected IllegalArgumentException for duplicate v2StepId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Duplicate v2StepId in edited actions") == true)
        }

        // Unknown v2StepId
        try {
            val fakeAction = doc.actions[0].copy(v2StepId = "non-existent-id")
            ShortcutSteps.encode(doc, listOf(fakeAction))
            fail("Expected IllegalArgumentException for unknown v2StepId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unknown v2StepId in edited actions") == true)
        }
    }

    @Test
    fun `project and round-trip UiStep with INFERRED_IME_ACTION migration warning`() {
        val inferredImeStep = UiStep(
            id = "ime-inferred-1",
            operation = UiOperation.IME_ACTION(ImeActionKind.DONE),
            expectedWindow = sampleWindow("com.example.app"),
            element = null,
            gesture = null,
            verification = VerificationSpec(emptyList()),
            migrationWarnings = listOf("INFERRED_IME_ACTION")
        )
        val s2 = WaitStep(id = "s2", durationMs = 500L)
        val workflow = WorkflowV2(
            workflowId = "wf-inferred",
            recordedDevice = sampleDevice(),
            steps = listOf(inferredImeStep, s2)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals(2, doc.actions.size)

        val imeAction = doc.actions[0]
        assertEquals("Pressed the keyboard's Search/Send key (detected)", imeAction.target)
        assertEquals("Pressed the keyboard's Search/Send key (detected)", imeAction.targetText)
        assertEquals("PRESS_ENTER", imeAction.uiActionType)
        assertEquals("ime-inferred-1", imeAction.v2StepId)

        // Keep it and round-trip encode
        val encodedKept = ShortcutSteps.encode(doc, doc.actions)
        val decodedKept = codec.decode(encodedKept) as com.shortcuts.app.automation.model.DecodeResult.Success
        assertEquals(2, decodedKept.workflow.steps.size)
        assertEquals("ime-inferred-1", decodedKept.workflow.steps[0].id)
        assertEquals(listOf("INFERRED_IME_ACTION"), decodedKept.workflow.steps[0].migrationWarnings)

        // Delete it
        val encodedDeleted = ShortcutSteps.encode(doc, listOf(doc.actions[1]))
        val decodedDeleted = codec.decode(encodedDeleted) as com.shortcuts.app.automation.model.DecodeResult.Success
        assertEquals(1, decodedDeleted.workflow.steps.size)
        assertEquals("s2", decodedDeleted.workflow.steps[0].id)

        // Reorder it
        val encodedReordered = ShortcutSteps.encode(doc, listOf(doc.actions[1], doc.actions[0]))
        val decodedReordered = codec.decode(encodedReordered) as com.shortcuts.app.automation.model.DecodeResult.Success
        assertEquals(2, decodedReordered.workflow.steps.size)
        assertEquals("s2", decodedReordered.workflow.steps[0].id)
        assertEquals("ime-inferred-1", decodedReordered.workflow.steps[1].id)
    }

    // NEW-1 (a): editing recorded SET_TEXT value replaces VerificationPostcondition.TextEquals
    @Test
    fun `editing recorded SET_TEXT value updates TextEquals postcondition`() {
        val initialText = "hello world"
        val updatedText = "goodbye world"

        val uiStep = UiStep(
            id = "set-text-1",
            operation = UiOperation.SET_TEXT(initialText),
            expectedWindow = sampleWindow("com.example.app"),
            element = sampleElement(),
            gesture = null,
            verification = VerificationSpec(
                postconditions = listOf(
                    VerificationPostcondition.TextEquals(initialText)
                )
            )
        )
        val workflow = WorkflowV2(
            workflowId = "wf-set-text",
            recordedDevice = sampleDevice(),
            steps = listOf(uiStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals(1, doc.actions.size)
        assertEquals(initialText, doc.actions[0].textInput)

        val editedAction = doc.actions[0].copy(textInput = updatedText)
        val encoded = ShortcutSteps.encode(doc, listOf(editedAction))
        val decodedResult = codec.decode(encoded) as com.shortcuts.app.automation.model.DecodeResult.Success
        val decodedStep = decodedResult.workflow.steps[0] as UiStep

        assertEquals(UiOperation.SET_TEXT(updatedText), decodedStep.operation)
        assertEquals(1, decodedStep.verification.postconditions.size)
        assertEquals(
            VerificationPostcondition.TextEquals(updatedText),
            decodedStep.verification.postconditions[0]
        )
    }

    // NEW-1 (b): changing recorded LaunchAppStep app resets app-specific expectations
    @Test
    fun `changing LaunchAppStep package resets app-specific expectations`() {
        val originalWindow = WindowSelector(
            packageName = "com.old.app",
            observedActivityClass = "com.old.app.MainActivity",
            windowType = WindowType.APPLICATION,
            titleToken = "Old Title",
            boundsOnDisplay = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f),
            displayId = 0,
            fingerprint = sampleFingerprint("com.old.app")
        )
        val launchStep = LaunchAppStep(
            id = "launch-1",
            title = "Old App",
            target = LaunchTarget.Launcher("com.old.app"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = originalWindow,
            expectedBefore = sampleFingerprint("com.old.app"),
            expectedAfter = sampleFingerprint("com.old.app")
        )
        val workflow = WorkflowV2(
            workflowId = "wf-launch-reset",
            recordedDevice = sampleDevice(),
            steps = listOf(launchStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2
        assertEquals(1, doc.actions.size)
        assertEquals("com.old.app", doc.actions[0].packageName)

        // Change package
        val editedAction = doc.actions[0].copy(packageName = "com.new.app")
        val encoded = ShortcutSteps.encode(doc, listOf(editedAction))
        val decodedResult = codec.decode(encoded) as com.shortcuts.app.automation.model.DecodeResult.Success
        val updatedStep = decodedResult.workflow.steps[0] as LaunchAppStep

        assertEquals(LaunchTarget.Launcher("com.new.app"), updatedStep.target)
        assertEquals("com.new.app", updatedStep.expectedWindow.packageName)
        assertNull(updatedStep.expectedWindow.observedActivityClass)
        assertNull(updatedStep.expectedWindow.titleToken)
        assertNull(updatedStep.expectedWindow.fingerprint)
        assertEquals(NormalizedRect(0f, 0f, 1f, 1f), updatedStep.expectedWindow.boundsOnDisplay)
        assertNull(updatedStep.expectedBefore)
        assertNull(updatedStep.expectedAfter)
    }

    // NEW-1 (c): picking app shortcut / deep link maps to matching LaunchTarget or blocks unrepresentable
    @Test
    fun `picking deep link on LaunchAppStep maps to DeepLink target and resets expectations`() {
        val launchStep = LaunchAppStep(
            id = "launch-dl",
            target = LaunchTarget.Launcher("com.spotify.music"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("com.spotify.music").copy(
                observedActivityClass = "com.spotify.music.MainActivity",
                titleToken = "Spotify",
                fingerprint = sampleFingerprint("com.spotify.music")
            ),
            expectedBefore = sampleFingerprint("com.spotify.music"),
            expectedAfter = sampleFingerprint("com.spotify.music")
        )
        val workflow = WorkflowV2(
            workflowId = "wf-deep-link",
            recordedDevice = sampleDevice(),
            steps = listOf(launchStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        val deepLinkAction = doc.actions[0].copy(
            packageName = "com.spotify.music",
            url = "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            target = "My Playlist"
        )
        val encoded = ShortcutSteps.encode(doc, listOf(deepLinkAction))
        val decodedResult = codec.decode(encoded) as com.shortcuts.app.automation.model.DecodeResult.Success
        val updatedStep = decodedResult.workflow.steps[0] as LaunchAppStep

        assertEquals(LaunchTarget.DeepLink("com.spotify.music", "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"), updatedStep.target)
        assertEquals(LaunchPolicy.DEEP_LINK, updatedStep.policy)
        assertEquals("My Playlist", updatedStep.title)
        assertNull(updatedStep.expectedWindow.observedActivityClass)
        assertNull(updatedStep.expectedWindow.titleToken)
        assertNull(updatedStep.expectedWindow.fingerprint)
        assertNull(updatedStep.expectedBefore)
        assertNull(updatedStep.expectedAfter)

        // Project step round-trip check
        val projected = ShortcutSteps.projectStep(updatedStep)
        assertEquals("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", projected.url)
        assertEquals("My Playlist", projected.target)
    }

    @Test
    fun `picking component shortcut on LaunchAppStep maps to Component target and resets expectations`() {
        val launchStep = LaunchAppStep(
            id = "launch-comp",
            target = LaunchTarget.Launcher("com.example.app"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("com.example.app").copy(
                observedActivityClass = "com.example.app.MainActivity",
                fingerprint = sampleFingerprint("com.example.app")
            ),
            expectedBefore = sampleFingerprint("com.example.app"),
            expectedAfter = sampleFingerprint("com.example.app")
        )
        val workflow = WorkflowV2(
            workflowId = "wf-comp",
            recordedDevice = sampleDevice(),
            steps = listOf(launchStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        val compAction = doc.actions[0].copy(
            packageName = "com.example.app",
            targetClassName = "com.example.app.DirectActivity",
            target = "Direct Activity"
        )
        val encoded = ShortcutSteps.encode(doc, listOf(compAction))
        val decodedResult = codec.decode(encoded) as com.shortcuts.app.automation.model.DecodeResult.Success
        val updatedStep = decodedResult.workflow.steps[0] as LaunchAppStep

        assertEquals(LaunchTarget.Component("com.example.app", "com.example.app.DirectActivity"), updatedStep.target)
        assertEquals(LaunchPolicy.EXPLICIT_EXPORTED_ACTIVITY, updatedStep.policy)
        assertEquals("Direct Activity", updatedStep.title)
        assertNull(updatedStep.expectedWindow.observedActivityClass)
        assertNull(updatedStep.expectedWindow.fingerprint)
        assertNull(updatedStep.expectedBefore)
        assertNull(updatedStep.expectedAfter)

        // Project step round-trip check
        val projected = ShortcutSteps.projectStep(updatedStep)
        assertEquals("com.example.app.DirectActivity", projected.targetClassName)
        assertEquals("Direct Activity", projected.target)
    }

    @Test
    fun `unrepresentable intent action on LaunchAppStep throws IllegalArgumentException with explanation`() {
        val launchStep = LaunchAppStep(
            id = "launch-intent",
            target = LaunchTarget.Launcher("com.example.app"),
            policy = LaunchPolicy.RESET_TO_ROOT,
            expectedWindow = sampleWindow("com.example.app")
        )
        val workflow = WorkflowV2(
            workflowId = "wf-unrep",
            recordedDevice = sampleDevice(),
            steps = listOf(launchStep)
        )
        val doc = ShortcutSteps.decode(codec.encode(workflow)) as ShortcutDocument.V2

        val unrepresentableAction = doc.actions[0].copy(
            intentAction = "android.intent.action.VIEW",
            url = null,
            targetClassName = null
        )

        try {
            ShortcutSteps.encode(doc, listOf(unrepresentableAction))
            fail("Expected IllegalArgumentException for unrepresentable intent action")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("cannot be represented as an Engine v2 launch target") == true)
        }
    }
}
