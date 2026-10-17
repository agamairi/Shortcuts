package com.shortcuts.app.automation.diagnostics

import com.shortcuts.app.automation.replay.ReplayFailure

interface TraceSink {
    fun append(event: TraceEvent)
}

sealed interface TraceEvent {
    val uptimeMs: Long

    data class RunStarted(
        override val uptimeMs: Long,
        val runId: String,
        val workflowId: String
    ) : TraceEvent

    data class StepStarted(
        override val uptimeMs: Long,
        val stepId: String,
        val stepIndex: Int
    ) : TraceEvent

    data class StepFinished(
        override val uptimeMs: Long,
        val stepId: String,
        val dispatchAccepted: Boolean,
        val verified: Boolean
    ) : TraceEvent

    data class RunFinished(
        override val uptimeMs: Long,
        val completedStepCount: Int,
        val failure: ReplayFailure?
    ) : TraceEvent
}
