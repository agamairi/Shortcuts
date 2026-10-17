package com.shortcuts.app.automation.diagnostics

data class ExpectedScreenRecorded(
    override val uptimeMs: Long,
    val stepId: String,
    val fingerprintSatisfied: Boolean,
    val similarity: Double? = null
) : TraceEvent
