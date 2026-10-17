package com.shortcuts.app.automation.diagnostics

data class ExpectedScreenRecorded(
    override val uptimeMs: Long,
    val stepId: String,
    val fingerprintSatisfied: Boolean,
    val similarity: Double? = null
) : TraceEvent

data class ActionRetryRecorded(
    override val uptimeMs: Long,
    val stepId: String,
    val retryReason: String,
    val attempt: Int = 1
) : TraceEvent {
    val retryAttempt: Int get() = attempt
}

typealias RetryRecorded = ActionRetryRecorded
