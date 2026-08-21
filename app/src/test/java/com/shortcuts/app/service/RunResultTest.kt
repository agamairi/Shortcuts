package com.shortcuts.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunResultTest {

    @Test
    fun `one successful step names the shortcut exactly`() {
        val summary = RunResult("Morning", listOf(StepResult.Success)).userSummary()

        assertEquals("Ran \"Morning\"", summary)
    }

    @Test
    fun `several successful steps report their total count`() {
        val summary = RunResult(
            "Morning",
            listOf(StepResult.Success, StepResult.Success, StepResult.Success)
        ).userSummary()

        assertTrue(summary.contains("3 steps"))
    }

    @Test
    fun `failed step summary includes its one-based position label and user message`() {
        val summary = RunResult(
            "Morning",
            listOf(StepResult.Success, StepResult.Failed(FailureReason.NETWORK_ERROR, "Server is unavailable"))
        ).userSummary { index -> if (index == 1) "Send request" else null }

        assertEquals("Step 2 (Send request) failed: Server is unavailable", summary)
    }

    @Test
    fun `permission-needed summary includes the step and permission`() {
        val summary = RunResult(
            "Morning",
            listOf(StepResult.NeedsPermission("POST_NOTIFICATIONS", null))
        ).userSummary { "Notify" }

        assertEquals("Step 1 (Notify) needs POST_NOTIFICATIONS permission", summary)
    }

    @Test
    fun `skipped summary includes the step and reason`() {
        val summary = RunResult(
            "Morning",
            listOf(StepResult.Skipped("A previous step failed"))
        ).userSummary { "Open app" }

        assertEquals("Step 1 (Open app) was skipped: A previous step failed", summary)
    }

    @Test
    fun `null or blank step labels are omitted without extra punctuation or spaces`() {
        val result = RunResult(
            "Morning",
            listOf(StepResult.Failed(FailureReason.NETWORK_ERROR, "Server is unavailable"))
        )

        assertEquals(
            "Step 1 failed: Server is unavailable",
            result.userSummary { null }
        )
        assertEquals(
            "Step 1 failed: Server is unavailable",
            result.userSummary { "   " }
        )
    }

    @Test
    fun `summary reports the first non-success step when later steps also fail`() {
        val summary = RunResult(
            "Morning",
            listOf(
                StepResult.Success,
                StepResult.NeedsPermission("CAMERA", null),
                StepResult.Failed(FailureReason.NETWORK_ERROR, "Server is unavailable")
            )
        ).userSummary { index -> "Action ${index + 1}" }

        assertEquals("Step 2 (Action 2) needs CAMERA permission", summary)
    }
}
