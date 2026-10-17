package com.shortcuts.app.automation.capture

import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.ImeActionKind
import com.shortcuts.app.automation.model.TimedPoint

data class CapturedInteraction(
    val kind: GestureKind,
    val points: List<TimedPoint>,
    val downUptimeMs: Long,
    val upUptimeMs: Long,
    val beforeSnapshot: WindowSetSnapshot,
    val hitNodeId: Int?,
    val hitWindowId: Int?
)

data class CapturedTextCommit(
    val fieldNode: NodeSnapshot,
    val finalText: String,
    val windowSnapshot: WindowSnapshot,
    val committedAtUptimeMs: Long
)

data class CapturedImeAction(
    val fieldNode: NodeSnapshot,
    val kind: ImeActionKind,
    val windowSnapshot: WindowSnapshot,
    val actionAtUptimeMs: Long
)

data class CapturedAppSwitch(
    val fromPackageName: String?,
    val toPackageName: String,
    val observedActivityClass: String?,
    val snapshot: WindowSetSnapshot,
    val switchedAtUptimeMs: Long
)

data class CapturedGlobalAction(
    val kind: String,
    val snapshot: WindowSetSnapshot,
    val actionAtUptimeMs: Long
)
