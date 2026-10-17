package com.shortcuts.app.automation.core.tree

import com.shortcuts.app.automation.model.NodeAction

data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Long get() = width.toLong() * height.toLong()

    fun contains(x: Int, y: Int): Boolean =
        x >= left && x < right && y >= top && y < bottom
}

data class NodeSnapshot(
    val nodeId: Int,
    val parentId: Int?,
    val childIds: List<Int>,
    val className: String,
    val resourceId: String?,
    val text: String?,
    val contentDescription: String?,
    val hintText: String?,
    val stateDescription: String?,
    val boundsInScreen: IntRect,
    val visibleToUser: Boolean,
    val enabled: Boolean,
    val clickable: Boolean,
    val longClickable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val editable: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val selected: Boolean,
    val password: Boolean,
    val actions: Set<NodeAction>,
    val collectionRow: Int?,
    val collectionColumn: Int?,
    val drawingOrder: Int,
    val windowId: Int,
    val packageName: String
) {
    val isActionable: Boolean
        get() = clickable || longClickable || editable || checkable ||
            NodeAction.CLICK in actions || NodeAction.LONG_CLICK in actions || NodeAction.SET_TEXT in actions
}
