package com.shortcuts.app.automation.diagnostics

import com.shortcuts.app.automation.replay.ReplayState
import com.shortcuts.app.automation.core.matching.ScoreBreakdown
import com.shortcuts.app.automation.core.matching.WindowScoreBreakdown

/** Sanitised replay diagnostics. Text, descriptions, titles and entered values are deliberately absent. */
data class StateEntered(
    override val uptimeMs: Long,
    val stepId: String?,
    val state: ReplayState
) : TraceEvent

data class WindowDecisionRecorded(
    override val uptimeMs: Long,
    val stepId: String,
    val outcome: String,
    val confidence: Double? = null,
    val selectedWindowId: Int? = null,
    val breakdown: WindowScoreBreakdown? = null
) : TraceEvent

data class MatchDecisionRecorded(
    override val uptimeMs: Long,
    val stepId: String,
    val outcome: String,
    val candidateCount: Int,
    val bestScore: Int? = null,
    val secondScore: Int? = null,
    val acceptedNodeId: Int? = null,
    val bestBreakdown: ScoreBreakdown? = null
) : TraceEvent

data class DispatchRecorded(
    override val uptimeMs: Long,
    val stepId: String,
    val method: String,
    val accepted: Boolean,
    val reason: String? = null
) : TraceEvent

data class PostconditionRecorded(
    override val uptimeMs: Long,
    val stepId: String,
    val kind: String,
    val passed: Boolean
) : TraceEvent

data class PreflightWarning(
    override val uptimeMs: Long,
    val stepId: String,
    val warning: String
) : TraceEvent
