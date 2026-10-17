package com.shortcuts.app.automation.capture

import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.model.WorkflowV2

interface CaptureEngine {
    fun start(context: CaptureScreenContext)
    fun accept(event: CapturedInteraction)
    fun accept(event: CapturedTextCommit)
    fun accept(event: CapturedImeAction)
    fun accept(event: CapturedAppSwitch)
    fun accept(event: CapturedGlobalAction)
    fun updateScreen(snapshot: WindowSetSnapshot)
    fun finish(): WorkflowV2
}

data class CaptureScreenContext(
    val workflowId: String,
    val initialSnapshot: WindowSetSnapshot
)
