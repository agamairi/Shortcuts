package com.shortcuts.app.automation.replay

import com.shortcuts.app.automation.diagnostics.TraceSink
import com.shortcuts.app.automation.model.WorkflowV2

interface ReplayEngine {
    suspend fun run(workflow: WorkflowV2, sink: TraceSink): ReplayResult
    fun preflight(workflow: WorkflowV2): List<ReplayFailure> = emptyList()
}

sealed interface ReplayResult {
    data class Success(val completedStepCount: Int) : ReplayResult
    data class Failed(val completedStepCount: Int, val failure: ReplayFailure) : ReplayResult
}

data class ReplayFailure(
    val code: ReplayFailureCode,
    val stepId: String?,
    val message: String
)

enum class ReplayFailureCode {
    WINDOW_NOT_FOUND,
    WINDOW_AMBIGUOUS,
    EXPECTED_SCREEN_TIMEOUT,
    TARGET_LOW_CONFIDENCE,
    TARGET_AMBIGUOUS,
    TARGET_NOT_FOUND_AFTER_SCROLL,
    SCROLL_CONTAINER_NOT_FOUND,
    SCROLL_NO_PROGRESS,
    VERIFICATION_FAILED,
    DISPATCH_REJECTED,
    UNVERIFIABLE_STEP,
    DECODE_FAILED,
    CANCELLED,
    ACCESSIBILITY_UNAVAILABLE
}
