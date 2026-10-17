package com.shortcuts.app.automation.android

import com.shortcuts.app.automation.android.replay.mapFailureCodeToReason
import com.shortcuts.app.automation.android.replay.mapReplayResultToRunResult
import com.shortcuts.app.automation.android.replay.sha256TitleTokenizer
import com.shortcuts.app.automation.android.replay.userFacingMessageForFailure
import com.shortcuts.app.automation.model.RecordedDevice
import com.shortcuts.app.automation.model.Step
import com.shortcuts.app.automation.model.WaitStep
import com.shortcuts.app.automation.model.WorkflowV2
import com.shortcuts.app.automation.replay.ReplayFailure
import com.shortcuts.app.automation.replay.ReplayFailureCode
import com.shortcuts.app.automation.replay.ReplayResult
import com.shortcuts.app.service.FailureReason
import com.shortcuts.app.service.StepResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayResultMappingTest {

    private val device = RecordedDevice(
        manufacturer = "Google",
        model = "Pixel 8",
        sdkInt = 34,
        displayWidthPx = 1080,
        displayHeightPx = 2400,
        densityDpi = 420,
        rotation = 0
    )

    private fun testWorkflow(steps: List<Step>): WorkflowV2 = WorkflowV2(
        workflowId = "test-wf-1",
        recordedDevice = device,
        steps = steps
    )

    @Test
    fun `successful replay result maps to all StepResult Success`() {
        val steps = listOf(
            WaitStep(id = "step-1", title = "Wait 1", durationMs = 100),
            WaitStep(id = "step-2", title = "Wait 2", durationMs = 200),
            WaitStep(id = "step-3", title = "Wait 3", durationMs = 300)
        )
        val workflow = testWorkflow(steps)
        val replayResult = ReplayResult.Success(completedStepCount = 3)

        val runResult = mapReplayResultToRunResult(workflow, replayResult, "My Shortcut")

        assertEquals("My Shortcut", runResult.shortcutName)
        assertEquals(3, runResult.steps.size)
        assertTrue(runResult.allSucceeded)
        assertTrue(runResult.steps.all { it is StepResult.Success })
    }

    @Test
    fun `failed replay result on first step marks first failed and remainder skipped`() {
        val steps = listOf(
            WaitStep(id = "step-1", title = "Open Settings", durationMs = 100),
            WaitStep(id = "step-2", title = "Tap Wifi", durationMs = 200),
            WaitStep(id = "step-3", title = "Toggle", durationMs = 300)
        )
        val workflow = testWorkflow(steps)
        val failure = ReplayFailure(
            code = ReplayFailureCode.EXPECTED_SCREEN_TIMEOUT,
            stepId = "step-1",
            message = "internal timeout"
        )
        val replayResult = ReplayResult.Failed(completedStepCount = 0, failure = failure)

        val runResult = mapReplayResultToRunResult(workflow, replayResult, "My Shortcut")

        assertEquals(3, runResult.steps.size)
        val first = runResult.steps[0] as StepResult.Failed
        assertEquals(FailureReason.INVALID_STATE, first.reason)
        assertEquals("Timed out waiting for the screen expected by Open Settings.", first.userMessage)

        val second = runResult.steps[1] as StepResult.Skipped
        assertEquals("Skipped because step 1 did not complete", second.why)

        val third = runResult.steps[2] as StepResult.Skipped
        assertEquals("Skipped because step 1 did not complete", third.why)
    }

    @Test
    fun `failed replay result on middle step preserves preceding successes`() {
        val steps = listOf(
            WaitStep(id = "step-1", title = "Step One", durationMs = 100),
            WaitStep(id = "step-2", title = "Step Two", durationMs = 200),
            WaitStep(id = "step-3", title = "Step Three", durationMs = 300)
        )
        val workflow = testWorkflow(steps)
        val failure = ReplayFailure(
            code = ReplayFailureCode.TARGET_LOW_CONFIDENCE,
            stepId = "step-2",
            message = "score 40 < 75"
        )
        val replayResult = ReplayResult.Failed(completedStepCount = 1, failure = failure)

        val runResult = mapReplayResultToRunResult(workflow, replayResult, "Chain")

        assertEquals(3, runResult.steps.size)
        assertTrue(runResult.steps[0] is StepResult.Success)

        val second = runResult.steps[1] as StepResult.Failed
        assertEquals(FailureReason.UI_AUTOMATION_FAILED, second.reason)
        assertEquals("Could not confidently locate the target for Step Two.", second.userMessage)

        val third = runResult.steps[2] as StepResult.Skipped
        assertEquals("Skipped because step 2 did not complete", third.why)
    }

    @Test
    fun `cancelled replay result uses cancellation skip message`() {
        val steps = listOf(
            WaitStep(id = "step-1", title = "Launch", durationMs = 100),
            WaitStep(id = "step-2", title = "Interact", durationMs = 200)
        )
        val workflow = testWorkflow(steps)
        val failure = ReplayFailure(
            code = ReplayFailureCode.CANCELLED,
            stepId = "step-1",
            message = "User stopped"
        )
        val replayResult = ReplayResult.Failed(completedStepCount = 0, failure = failure)

        val runResult = mapReplayResultToRunResult(workflow, replayResult, "Cancelled Run")

        val first = runResult.steps[0] as StepResult.Failed
        assertEquals("Shortcut run was stopped.", first.userMessage)

        val second = runResult.steps[1] as StepResult.Skipped
        assertEquals("Skipped because the shortcut was stopped", second.why)
    }

    @Test
    fun `fallback step label when step title is blank`() {
        val msg = userFacingMessageForFailure(ReplayFailureCode.WINDOW_NOT_FOUND, null, 2)
        assertEquals("Could not find the expected window for step 3.", msg)

        val msgBlank = userFacingMessageForFailure(ReplayFailureCode.WINDOW_NOT_FOUND, "  ", 0)
        assertEquals("Could not find the expected window for step 1.", msgBlank)
    }

    @Test
    fun `user facing messages cover all ReplayFailureCodes`() {
        for (code in ReplayFailureCode.values()) {
            val msg = userFacingMessageForFailure(code, "TargetStep", 0)
            assertTrue(msg.isNotBlank())
            assertTrue(!msg.contains("internal") && !msg.contains("exception"))
        }
    }

    @Test
    fun `failure code to reason mappings cover all codes accurately`() {
        assertEquals(FailureReason.INVALID_STATE, mapFailureCodeToReason(ReplayFailureCode.WINDOW_NOT_FOUND))
        assertEquals(FailureReason.INVALID_STATE, mapFailureCodeToReason(ReplayFailureCode.WINDOW_AMBIGUOUS))
        assertEquals(FailureReason.INVALID_STATE, mapFailureCodeToReason(ReplayFailureCode.EXPECTED_SCREEN_TIMEOUT))
        assertEquals(FailureReason.UI_AUTOMATION_FAILED, mapFailureCodeToReason(ReplayFailureCode.TARGET_LOW_CONFIDENCE))
        assertEquals(FailureReason.UI_AUTOMATION_FAILED, mapFailureCodeToReason(ReplayFailureCode.TARGET_AMBIGUOUS))
        assertEquals(FailureReason.UI_AUTOMATION_FAILED, mapFailureCodeToReason(ReplayFailureCode.TARGET_NOT_FOUND_AFTER_SCROLL))
        assertEquals(FailureReason.UI_AUTOMATION_FAILED, mapFailureCodeToReason(ReplayFailureCode.SCROLL_CONTAINER_NOT_FOUND))
        assertEquals(FailureReason.UI_AUTOMATION_FAILED, mapFailureCodeToReason(ReplayFailureCode.SCROLL_NO_PROGRESS))
        assertEquals(FailureReason.UI_AUTOMATION_FAILED, mapFailureCodeToReason(ReplayFailureCode.VERIFICATION_FAILED))
        assertEquals(FailureReason.UI_AUTOMATION_FAILED, mapFailureCodeToReason(ReplayFailureCode.DISPATCH_REJECTED))
        assertEquals(FailureReason.UNSUPPORTED_TARGET, mapFailureCodeToReason(ReplayFailureCode.UNVERIFIABLE_STEP))
        assertEquals(FailureReason.UNSUPPORTED_TARGET, mapFailureCodeToReason(ReplayFailureCode.DECODE_FAILED))
        assertEquals(FailureReason.INVALID_STATE, mapFailureCodeToReason(ReplayFailureCode.CANCELLED))
        assertEquals(FailureReason.ACCESSIBILITY_UNAVAILABLE, mapFailureCodeToReason(ReplayFailureCode.ACCESSIBILITY_UNAVAILABLE))
    }

    @Test
    fun `sha256TitleTokenizer produces deterministic lowercase hex hash`() {
        val hash1 = sha256TitleTokenizer("Settings")
        val hash2 = sha256TitleTokenizer("Settings")
        assertEquals(hash1, hash2)
        assertEquals(64, hash1.length)
        assertTrue(hash1.matches(Regex("^[0-9a-f]{64}$")))
    }
}
