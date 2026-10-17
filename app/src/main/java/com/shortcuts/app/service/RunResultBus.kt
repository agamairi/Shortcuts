package com.shortcuts.app.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-process completion channel used by the debug control surface to collect run receipts. */
data class RunResultEvent(
    val automationId: Int,
    val runId: String?,
    val result: RunResult
)

object RunResultBus {
    private val _results = MutableSharedFlow<RunResultEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val results: SharedFlow<RunResultEvent> = _results.asSharedFlow()

    fun tryEmit(event: RunResultEvent): Boolean = _results.tryEmit(event)
}
