package com.shortcuts.app.diagnostics

import com.shortcuts.app.automation.diagnostics.MatchDecisionRecorded
import com.shortcuts.app.automation.diagnostics.PostconditionRecorded
import com.shortcuts.app.automation.diagnostics.TraceEvent
import com.shortcuts.app.automation.diagnostics.WindowDecisionRecorded
import com.shortcuts.app.automation.replay.ReplayFailure
import com.shortcuts.app.automation.replay.ReplayFailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayTraceCompactorTest {

    @Test
    fun `40 identical not_found window decisions collapse to one entry with count 40`() {
        val events = mutableListOf<TraceEvent>()
        events.add(TraceEvent.StepStarted(uptimeMs = 1_000L, stepId = "step-1", stepIndex = 0))

        for (i in 0 until 40) {
            events.add(
                WindowDecisionRecorded(
                    uptimeMs = 1_000L + (i * 50L),
                    stepId = "step-1",
                    outcome = "not_found"
                )
            )
        }

        val compacted = ReplayTraceCompactor.compact(events)

        assertEquals(2, compacted.size)

        val stepStarted = compacted[0]
        assertEquals("step_started", stepStarted["type"])
        assertEquals(0, stepStarted["step"])
        assertEquals("step-1", stepStarted["stepId"])

        val windowDecision = compacted[1]
        assertEquals("window_decision", windowDecision["type"])
        assertEquals(0, windowDecision["step"])
        assertEquals("not_found", windowDecision["outcome"])
        assertEquals(40, windowDecision["count"])
        assertEquals(1_000L, windowDecision["firstUp"])
        assertEquals(1_000L + (39 * 50L), windowDecision["lastUp"])
    }

    @Test
    fun `failure code and message survive from RunFinished`() {
        val events = listOf(
            TraceEvent.StepStarted(uptimeMs = 1_000L, stepId = "step-fail", stepIndex = 2),
            TraceEvent.RunFinished(
                uptimeMs = 4_000L,
                completedStepCount = 2,
                failure = ReplayFailure(
                    code = ReplayFailureCode.WINDOW_NOT_FOUND,
                    stepId = "step-fail",
                    message = "Target window com.example.app did not appear"
                )
            )
        )

        val compacted = ReplayTraceCompactor.compact(events)
        assertEquals(2, compacted.size)

        val runFinished = compacted[1]
        assertEquals("run_finished", runFinished["type"])
        assertEquals(4_000L, runFinished["up"])
        assertEquals(2, runFinished["completedStepCount"])
        assertEquals("WINDOW_NOT_FOUND", runFinished["failureCode"])
        assertEquals("Target window com.example.app did not appear", runFinished["failureMessage"])
        assertEquals(2, runFinished["step"])
    }

    @Test
    fun `step index resolved from preceding StepStarted`() {
        val events = listOf(
            TraceEvent.StepStarted(uptimeMs = 500L, stepId = "uuid-abc-123", stepIndex = 4),
            PostconditionRecorded(
                uptimeMs = 600L,
                stepId = "uuid-abc-123",
                kind = "activity_match",
                passed = true
            )
        )

        val compacted = ReplayTraceCompactor.compact(events)
        assertEquals(2, compacted.size)

        val postcondition = compacted[1]
        assertEquals("postcondition", postcondition["type"])
        assertEquals(4, postcondition["step"])
        assertEquals("activity_match", postcondition["kind"])
        assertEquals(true, postcondition["passed"])
        assertEquals(600L, postcondition["up"])
        assertNull(postcondition["count"])
    }

    @Test
    fun `different outcomes do not collapse into same window decision entry`() {
        val events = listOf(
            TraceEvent.StepStarted(uptimeMs = 100L, stepId = "step-1", stepIndex = 0),
            WindowDecisionRecorded(uptimeMs = 150L, stepId = "step-1", outcome = "evaluating"),
            WindowDecisionRecorded(uptimeMs = 200L, stepId = "step-1", outcome = "evaluating"),
            WindowDecisionRecorded(uptimeMs = 250L, stepId = "step-1", outcome = "not_found")
        )

        val compacted = ReplayTraceCompactor.compact(events)
        assertEquals(3, compacted.size)

        val firstRun = compacted[1]
        assertEquals("window_decision", firstRun["type"])
        assertEquals("evaluating", firstRun["outcome"])
        assertEquals(2, firstRun["count"])

        val secondRun = compacted[2]
        assertEquals("window_decision", secondRun["type"])
        assertEquals("not_found", secondRun["outcome"])
        assertEquals(250L, secondRun["up"])
        assertNull(secondRun["count"])
    }

    @Test
    fun `consecutive match decisions collapse when outcome and candidate count match`() {
        val events = listOf(
            MatchDecisionRecorded(
                uptimeMs = 100L,
                stepId = "s1",
                outcome = "no_match",
                candidateCount = 3,
                bestScore = 20
            ),
            MatchDecisionRecorded(
                uptimeMs = 150L,
                stepId = "s1",
                outcome = "no_match",
                candidateCount = 3,
                bestScore = 20
            )
        )

        val compacted = ReplayTraceCompactor.compact(events)
        assertEquals(1, compacted.size)
        assertEquals("match_decision", compacted[0]["type"])
        assertEquals("no_match", compacted[0]["outcome"])
        assertEquals(3, compacted[0]["candidateCount"])
        assertEquals(2, compacted[0]["count"])
        assertEquals(100L, compacted[0]["firstUp"])
        assertEquals(150L, compacted[0]["lastUp"])
    }
}
