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
    ACCESSIBILITY_UNAVAILABLE
}

data class RunResult(
    val shortcutName: String,
    val steps: List<StepResult>
) {
    val allSucceeded: Boolean
        get() = steps.isNotEmpty() && steps.all { it is StepResult.Success }

    val firstFailure: StepResult.Failed?
        get() = steps.filterIsInstance<StepResult.Failed>().firstOrNull()

    val firstIncomplete: StepResult?
        get() = steps.firstOrNull { it !is StepResult.Success }
}
