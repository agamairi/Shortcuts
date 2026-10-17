package com.shortcuts.app.diagnostics

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.shortcuts.app.automation.diagnostics.ActionRetryRecorded
import com.shortcuts.app.automation.diagnostics.DispatchRecorded
import com.shortcuts.app.automation.diagnostics.ExpectedScreenRecorded
import com.shortcuts.app.automation.diagnostics.MatchDecisionRecorded
import com.shortcuts.app.automation.diagnostics.PostconditionRecorded
import com.shortcuts.app.automation.diagnostics.PreflightWarning
import com.shortcuts.app.automation.diagnostics.StateEntered
import com.shortcuts.app.automation.diagnostics.TraceEvent
import com.shortcuts.app.automation.diagnostics.WindowDecisionRecorded

/**
 * Pure compaction for [TraceEvent] lists produced by Engine v2 replay runs.
 * Collapses polling bursts into single summarized entries and sanitizes trace maps.
 */
object ReplayTraceCompactor {

    private val gson: Gson = GsonBuilder().create()

    private sealed class CollapsibleRun {
        data class WindowDecision(
            val step: Any?,
            val outcome: String,
            val firstUp: Long,
            var lastUp: Long,
            var count: Int,
            val confidence: Double?,
            val selectedWindowId: Int?,
            val breakdown: Any?
        ) : CollapsibleRun()

        data class MatchDecision(
            val step: Any?,
            val outcome: String,
            val candidateCount: Int,
            val firstUp: Long,
            var lastUp: Long,
            var count: Int,
            val bestScore: Int?,
            val secondScore: Int?,
            val acceptedNodeId: Int?,
            val bestBreakdown: Any?
        ) : CollapsibleRun()

        data class Postcondition(
            val step: Any?,
            val kind: String,
            val passed: Boolean,
            val firstUp: Long,
            var lastUp: Long,
            var count: Int
        ) : CollapsibleRun()
    }

    fun compact(events: List<TraceEvent>): List<Map<String, Any?>> {
        val result = mutableListOf<Map<String, Any?>>()
        val stepMap = mutableMapOf<String, Int>()

        fun resolveStep(stepId: String?): Any? =
            if (stepId != null) (stepMap[stepId] ?: stepId) else null

        var currentRun: CollapsibleRun? = null

        fun flushCurrentRun() {
            val run = currentRun ?: return
            currentRun = null
            when (run) {
                is CollapsibleRun.WindowDecision -> {
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "window_decision"
                    run.step?.let { map["step"] = it }
                    map["outcome"] = run.outcome
                    if (run.count > 1) {
                        map["count"] = run.count
                        map["firstUp"] = run.firstUp
                        map["lastUp"] = run.lastUp
                    } else {
                        map["up"] = run.firstUp
                    }
                    run.confidence?.let { map["confidence"] = it }
                    run.selectedWindowId?.let { map["selectedWindowId"] = it }
                    run.breakdown?.let { map["breakdown"] = it }
                    result.add(map)
                }
                is CollapsibleRun.MatchDecision -> {
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "match_decision"
                    run.step?.let { map["step"] = it }
                    map["outcome"] = run.outcome
                    map["candidateCount"] = run.candidateCount
                    if (run.count > 1) {
                        map["count"] = run.count
                        map["firstUp"] = run.firstUp
                        map["lastUp"] = run.lastUp
                    } else {
                        map["up"] = run.firstUp
                    }
                    run.bestScore?.let { map["bestScore"] = it }
                    run.secondScore?.let { map["secondScore"] = it }
                    run.acceptedNodeId?.let { map["acceptedNodeId"] = it }
                    run.bestBreakdown?.let { map["bestBreakdown"] = it }
                    result.add(map)
                }
                is CollapsibleRun.Postcondition -> {
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "postcondition"
                    run.step?.let { map["step"] = it }
                    map["kind"] = run.kind
                    map["passed"] = run.passed
                    if (run.count > 1) {
                        map["count"] = run.count
                        map["firstUp"] = run.firstUp
                        map["lastUp"] = run.lastUp
                    } else {
                        map["up"] = run.firstUp
                    }
                    result.add(map)
                }
            }
        }

        for (event in events) {
            when (event) {
                is TraceEvent.StepStarted -> {
                    stepMap[event.stepId] = event.stepIndex
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "step_started"
                    map["up"] = event.uptimeMs
                    map["step"] = event.stepIndex
                    map["stepId"] = event.stepId
                    result.add(map)
                }
                is WindowDecisionRecorded -> {
                    val resolved = resolveStep(event.stepId)
                    val existing = currentRun as? CollapsibleRun.WindowDecision
                    if (existing != null && existing.step == resolved && existing.outcome == event.outcome) {
                        existing.count++
                        existing.lastUp = event.uptimeMs
                    } else {
                        flushCurrentRun()
                        val breakdownMap = if (event.outcome != "accepted" && event.outcome != "selected" && event.breakdown != null) {
                            runCatching { gson.fromJson(gson.toJson(event.breakdown), Map::class.java) }.getOrNull()
                        } else null
                        currentRun = CollapsibleRun.WindowDecision(
                            step = resolved,
                            outcome = event.outcome,
                            firstUp = event.uptimeMs,
                            lastUp = event.uptimeMs,
                            count = 1,
                            confidence = event.confidence,
                            selectedWindowId = event.selectedWindowId,
                            breakdown = breakdownMap
                        )
                    }
                }
                is MatchDecisionRecorded -> {
                    val resolved = resolveStep(event.stepId)
                    val existing = currentRun as? CollapsibleRun.MatchDecision
                    if (existing != null && existing.step == resolved && existing.outcome == event.outcome && existing.candidateCount == event.candidateCount) {
                        existing.count++
                        existing.lastUp = event.uptimeMs
                    } else {
                        flushCurrentRun()
                        val breakdownMap = if (event.outcome != "accepted" && event.outcome != "selected" && event.bestBreakdown != null) {
                            runCatching { gson.fromJson(gson.toJson(event.bestBreakdown), Map::class.java) }.getOrNull()
                        } else null
                        currentRun = CollapsibleRun.MatchDecision(
                            step = resolved,
                            outcome = event.outcome,
                            candidateCount = event.candidateCount,
                            firstUp = event.uptimeMs,
                            lastUp = event.uptimeMs,
                            count = 1,
                            bestScore = event.bestScore,
                            secondScore = event.secondScore,
                            acceptedNodeId = event.acceptedNodeId,
                            bestBreakdown = breakdownMap
                        )
                    }
                }
                is PostconditionRecorded -> {
                    val resolved = resolveStep(event.stepId)
                    val existing = currentRun as? CollapsibleRun.Postcondition
                    if (existing != null && existing.step == resolved && existing.kind == event.kind && existing.passed == event.passed) {
                        existing.count++
                        existing.lastUp = event.uptimeMs
                    } else {
                        flushCurrentRun()
                        currentRun = CollapsibleRun.Postcondition(
                            step = resolved,
                            kind = event.kind,
                            passed = event.passed,
                            firstUp = event.uptimeMs,
                            lastUp = event.uptimeMs,
                            count = 1
                        )
                    }
                }
                is TraceEvent.RunStarted -> {
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "run_started"
                    map["up"] = event.uptimeMs
                    map["runId"] = event.runId
                    map["workflowId"] = event.workflowId
                    result.add(map)
                }
                is TraceEvent.StepFinished -> {
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "step_finished"
                    map["up"] = event.uptimeMs
                    resolveStep(event.stepId)?.let { map["step"] = it }
                    map["dispatchAccepted"] = event.dispatchAccepted
                    map["verified"] = event.verified
                    result.add(map)
                }
                is TraceEvent.RunFinished -> {
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "run_finished"
                    map["up"] = event.uptimeMs
                    map["completedStepCount"] = event.completedStepCount
                    event.failure?.let { failure ->
                        map["failureCode"] = failure.code.name
                        map["failureMessage"] = failure.message
                        resolveStep(failure.stepId)?.let { map["step"] = it }
                    }
                    result.add(map)
                }
                is StateEntered -> {
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "state_entered"
                    map["up"] = event.uptimeMs
                    resolveStep(event.stepId)?.let { map["step"] = it }
                    map["state"] = event.state.name
                    result.add(map)
                }
                is DispatchRecorded -> {
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "dispatch"
                    map["up"] = event.uptimeMs
                    resolveStep(event.stepId)?.let { map["step"] = it }
                    map["method"] = event.method
                    map["accepted"] = event.accepted
                    event.reason?.let { map["reason"] = it }
                    result.add(map)
                }
                is PreflightWarning -> {
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "preflight_warning"
                    map["up"] = event.uptimeMs
                    resolveStep(event.stepId)?.let { map["step"] = it }
                    map["warning"] = event.warning
                    result.add(map)
                }
                is ExpectedScreenRecorded -> {
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "expected_screen"
                    map["up"] = event.uptimeMs
                    resolveStep(event.stepId)?.let { map["step"] = it }
                    map["fingerprintSatisfied"] = event.fingerprintSatisfied
                    event.similarity?.let { map["similarity"] = it }
                    result.add(map)
                }
                is ActionRetryRecorded -> {
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = "retry"
                    map["up"] = event.uptimeMs
                    resolveStep(event.stepId)?.let { map["step"] = it }
                    map["retryReason"] = event.retryReason
                    map["attempt"] = event.attempt
                    result.add(map)
                }
                else -> {
                    // Fallback for any other TraceEvent
                    flushCurrentRun()
                    val map = LinkedHashMap<String, Any?>()
                    map["type"] = event.javaClass.simpleName
                    map["up"] = event.uptimeMs
                    result.add(map)
                }
            }
        }

        flushCurrentRun()
        return result
    }
}
