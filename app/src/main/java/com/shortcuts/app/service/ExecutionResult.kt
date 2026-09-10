package com.shortcuts.app.service

import android.content.Intent

/** A user-facing outcome for one action in a shortcut chain. */
sealed interface StepResult {
    data object Success : StepResult
    data class Failed(val reason: FailureReason, val userMessage: String) : StepResult
    data class NeedsPermission(val permission: String, val settingsIntent: Intent?) : StepResult
    data class Skipped(val why: String) : StepResult
}

enum class FailureReason {
    UNSUPPORTED_TARGET,
    INVALID_STATE,
    PLATFORM_RESTRICTION,
    ACTIVITY_UNAVAILABLE,
    DEVICE_UNAVAILABLE,
    APP_NOT_FOUND,
    NETWORK_ERROR,
    ACCESSIBILITY_UNAVAILABLE,
    UI_AUTOMATION_FAILED
}

data class RunResult(
    val shortcutName: String,
    val steps: List<StepResult>,
    /** Optional human-readable detail for successful steps (for example an HTTP response). */
    val successDetails: List<String?> = emptyList()
) {
    val allSucceeded: Boolean
        get() = steps.isNotEmpty() && steps.all { it is StepResult.Success }

    val firstFailure: StepResult.Failed?
        get() = steps.filterIsInstance<StepResult.Failed>().firstOrNull()

    val firstIncomplete: StepResult?
        get() = steps.firstOrNull { it !is StepResult.Success }

    /** Index of the first step that did not succeed, or -1 when the whole chain ran. */
    val firstIncompleteIndex: Int
        get() = steps.indexOfFirst { it !is StepResult.Success }

    /**
     * The single place a run outcome becomes a sentence, so Test Run and a normal run describe the
     * same failure in the same words. A normal run used to say only "Couldn't run 'X'", which told
     * the user neither which step broke nor why.
     *
     * [describeStep] maps a step index to a short human label (e.g. "Tap Apps"); return null to
     * omit it when the caller has no convenient way to describe the step.
     */
    fun userSummary(describeStep: (Int) -> String? = { null }): String {
        val failedIndex = firstIncompleteIndex
        if (failedIndex == -1) {
            return if (steps.size == 1) {
                "Ran \"$shortcutName\""
            } else {
                "Ran \"$shortcutName\" — all ${steps.size} steps completed"
            }
        }
        val label = describeStep(failedIndex)
            ?.takeIf { it.isNotBlank() }
            ?.let { " ($it)" }
            .orEmpty()
        val stepNumber = failedIndex + 1
        return when (val step = steps[failedIndex]) {
            is StepResult.Failed -> "Step $stepNumber$label failed: ${step.userMessage}"
            is StepResult.NeedsPermission -> "Step $stepNumber$label needs ${step.permission} permission"
            is StepResult.Skipped -> "Step $stepNumber$label was skipped: ${step.why}"
            StepResult.Success -> "Ran \"$shortcutName\""
        }
    }
}
