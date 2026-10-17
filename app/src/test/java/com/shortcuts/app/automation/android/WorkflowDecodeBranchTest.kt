package com.shortcuts.app.automation.android

import android.content.Context
import com.shortcuts.app.automation.android.replay.EngineV2RunRuntime
import com.shortcuts.app.automation.model.DecodeResult
import com.shortcuts.app.automation.model.ElementSelector
import com.shortcuts.app.automation.model.LegacyActionStep
import com.shortcuts.app.automation.model.NormalizedRect
import com.shortcuts.app.automation.model.RecordedDevice
import com.shortcuts.app.automation.model.StepSafety
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.model.UiStep
import com.shortcuts.app.automation.model.VerificationSpec
import com.shortcuts.app.automation.model.WaitStep
import com.shortcuts.app.automation.model.WindowSelector
import com.shortcuts.app.automation.model.WindowType
import com.shortcuts.app.automation.model.WorkflowCodec
import com.shortcuts.app.automation.model.WorkflowV2
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.service.ExecutionCancellation
import com.shortcuts.app.service.FailureReason
import com.shortcuts.app.service.ShortcutIssueCategory
import com.shortcuts.app.service.ShortcutPreflight
import com.shortcuts.app.service.ShortcutPreflightEnvironment
import com.shortcuts.app.service.ShortcutPreflightResolution
import com.shortcuts.app.service.ShortcutRunSafety
import com.shortcuts.app.service.StepResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowDecodeBranchTest {

    private val sampleV2Json = """
        {
            "schemaVersion": 2,
            "workflowId": "wf-v2-test",
            "recordedDevice": {
                "manufacturer": "Google",
                "model": "Pixel 8",
                "sdkInt": 34,
                "displayWidthPx": 1080,
                "displayHeightPx": 2400,
                "densityDpi": 420,
                "rotation": 0
            },
            "steps": [
                {
                    "type": "wait",
                    "id": "step-wait",
                    "durationMs": 500,
                    "timeoutMs": 500
                }
            ]
        }
    """.trimIndent()

    private val sampleLegacyArrayJson = """
        [
            {
                "actionType": "WAIT",
                "delayMillis": 500
            }
        ]
    """.trimIndent()

    private val sampleMalformedJson = "{ invalid json"

    @Test
    fun `v2 json decodes to Success with migratedFromLegacy false`() {
        val codec = WorkflowCodec()
        val result = codec.decode(sampleV2Json)

        assertTrue(result is DecodeResult.Success)
        val success = result as DecodeResult.Success
        assertFalse(success.migratedFromLegacy)
        assertEquals("wf-v2-test", success.workflow.workflowId)
        assertEquals(1, success.workflow.steps.size)
        assertTrue(success.workflow.steps[0] is WaitStep)
    }

    @Test
    fun `legacy action array decodes to Success with migratedFromLegacy true`() {
        val codec = WorkflowCodec()
        val result = codec.decode(sampleLegacyArrayJson)

        assertTrue(result is DecodeResult.Success)
        val success = result as DecodeResult.Success
        assertTrue(success.migratedFromLegacy)
    }

    @Test
    fun `malformed json decodes to Failure`() {
        val codec = WorkflowCodec()
        val result = codec.decode(sampleMalformedJson)

        assertTrue(result is DecodeResult.Failure)
    }

    @Test
    fun `EngineV2RunRuntime tryRun returns null for legacy array to preserve old path`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val runtime = EngineV2RunRuntime(context)
        val cancellation = object : ExecutionCancellation {
            override val isCancellationRequested: Boolean = false
            override fun enterExecution() = Unit
            override fun leaveExecution() = Unit
        }

        val result = runtime.tryRun(sampleLegacyArrayJson, "Test Legacy", cancellation)
        assertNull("Legacy array must return null so AutomationExecutionService branches to ActionExecutorService", result)
    }

    @Test
    fun `EngineV2RunRuntime tryRun returns readable failed RunResult for malformed json`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val runtime = EngineV2RunRuntime(context)
        val cancellation = object : ExecutionCancellation {
            override val isCancellationRequested: Boolean = false
            override fun enterExecution() = Unit
            override fun leaveExecution() = Unit
        }

        val result = runtime.tryRun(sampleMalformedJson, "Bad Shortcut", cancellation)
        assertNotNull(result)
        assertEquals("Bad Shortcut", result!!.shortcutName)
        assertFalse(result.allSucceeded)
        assertEquals(1, result.steps.size)
        val firstStep = result.steps[0] as StepResult.Failed
        assertEquals(FailureReason.INVALID_STATE, firstStep.reason)
        assertEquals("This shortcut could not be read.", firstStep.userMessage)
    }

    @Test
    fun `ShortcutRunSafety confirmationEffects for v2 workflow`() {
        val window = WindowSelector(
            packageName = "com.example.app",
            observedActivityClass = null,
            windowType = WindowType.APPLICATION,
            titleToken = null,
            boundsOnDisplay = NormalizedRect(0f, 0f, 1f, 1f),
            displayId = 0,
            fingerprint = null
        )
        val element = ElementSelector(
            strategies = emptyList(),
            expectedClass = null,
            expectedRole = null,
            ancestorChain = emptyList(),
            siblingIndex = null,
            rowContext = null,
            boundsInWindow = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f),
            rawPoint = null,
            requiredActions = emptySet()
        )

        val reversibleStep = UiStep(
            id = "s1",
            operation = UiOperation.TAP,
            expectedWindow = window,
            element = element,
            gesture = null,
            verification = VerificationSpec(emptyList(), allowBestEffort = true),
            safety = StepSafety.REVERSIBLE
        )

        val irreversibleStep = UiStep(
            id = "s2",
            operation = UiOperation.TAP,
            expectedWindow = window,
            element = element,
            gesture = null,
            verification = VerificationSpec(emptyList(), allowBestEffort = true),
            safety = StepSafety.IRREVERSIBLE
        )

        val legacyActionStep = LegacyActionStep(
            id = "s3",
            action = Action(ActionType.SEND_MESSAGE, target = "+14165551212")
        )

        val wfReversibleOnly = WorkflowV2(
            workflowId = "wf-rev",
            recordedDevice = mockk(relaxed = true),
            steps = listOf(reversibleStep)
        )
        assertTrue(
            "Reversible UI step should not trigger confirmation effects",
            ShortcutRunSafety.confirmationEffects(wfReversibleOnly).isEmpty()
        )

        val wfIrreversible = WorkflowV2(
            workflowId = "wf-irrev",
            recordedDevice = mockk(relaxed = true),
            steps = listOf(reversibleStep, irreversibleStep, legacyActionStep)
        )
        val effects = ShortcutRunSafety.confirmationEffects(wfIrreversible)
        assertEquals(2, effects.size)
        assertTrue(effects.contains("interact with another app's screen"))
        assertTrue(effects.contains("prepare a message to another person"))
    }

    @Test
    fun `ShortcutPreflight detects when v2 UiStep requires accessibility service`() {
        val env = mockk<ShortcutPreflightEnvironment>()
        every { env.isAccessibilityServiceActive() } returns false

        val preflight = ShortcutPreflight(env)
        val window = WindowSelector(
            packageName = "com.example.app",
            observedActivityClass = null,
            windowType = WindowType.APPLICATION,
            titleToken = null,
            boundsOnDisplay = NormalizedRect(0f, 0f, 1f, 1f),
            displayId = 0,
            fingerprint = null
        )
        val element = ElementSelector(
            strategies = emptyList(),
            expectedClass = null,
            expectedRole = null,
            ancestorChain = emptyList(),
            siblingIndex = null,
            rowContext = null,
            boundsInWindow = NormalizedRect(0.1f, 0.1f, 0.9f, 0.9f),
            rawPoint = null,
            requiredActions = emptySet()
        )
        val uiStep = UiStep(
            id = "s1",
            operation = UiOperation.TAP,
            expectedWindow = window,
            element = element,
            gesture = null,
            verification = VerificationSpec(emptyList(), allowBestEffort = true)
        )

        val wfWithUi = WorkflowV2(
            workflowId = "wf-ui",
            recordedDevice = mockk(relaxed = true),
            steps = listOf(uiStep)
        )

        val report = preflight.check(wfWithUi)
        assertFalse(report.canRun)
        assertEquals(1, report.issues.size)
        val issue = report.issues[0]
        assertEquals(ShortcutIssueCategory.SYSTEM_SETTING_REQUIRED, issue.category)
        assertEquals(ShortcutPreflightResolution.ACCESSIBILITY_SETTINGS, issue.resolution)

        // Workflow with only wait steps does not need accessibility
        val wfWaitOnly = WorkflowV2(
            workflowId = "wf-wait",
            recordedDevice = mockk(relaxed = true),
            steps = listOf(WaitStep(id = "w1", durationMs = 100))
        )
        val waitReport = preflight.check(wfWaitOnly)
        assertTrue(waitReport.canRun)
    }
}
