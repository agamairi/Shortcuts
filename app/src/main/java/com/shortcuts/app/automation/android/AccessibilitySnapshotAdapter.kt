package com.shortcuts.app.automation.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.shortcuts.app.automation.core.tree.IntRect
import com.shortcuts.app.automation.core.tree.NodeSnapshot
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.WindowSnapshot
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.WindowType
import java.util.concurrent.ConcurrentHashMap

data class NodeLocator(
    val windowId: Int,
    val childPath: List<Int>
)

class AccessibilitySnapshotAdapter(
    val service: AccessibilityService
) : WindowSnapshotSource {

    private val nodePathMap = ConcurrentHashMap<Int, NodeLocator>()
    @Volatile private var _lastSnapshot: WindowSetSnapshot? = null
    val lastSnapshot: WindowSetSnapshot? get() = _lastSnapshot

    override fun snapshot(): WindowSetSnapshot? {
        val windows = runCatching { service.windows }.getOrNull() ?: return null
        val uptimeMs = SystemClock.uptimeMillis()

        val displayMetrics = service.resources.displayMetrics
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { service.display?.rotation }.getOrNull() ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }

        val sortedWindows = windows.sortedByDescending { it.layer }
        val snapshotNodePaths = ConcurrentHashMap<Int, NodeLocator>()
        var nextNodeId = 1
        var totalNodes = 0
        val windowSnapshots = mutableListOf<WindowSnapshot>()

        for (window in sortedWindows) {
            val root = runCatching { window.root }.getOrNull() ?: continue
            val nodesInWindow = mutableMapOf<Int, NodeSnapshot>()

            val windowRect = Rect()
            window.getBoundsInScreen(windowRect)
            val windowBounds = IntRect(windowRect.left, windowRect.top, windowRect.right, windowRect.bottom)
            val windowType = mapWindowType(window.type)
            val windowPackageName = root.packageName?.toString().orEmpty()

            val rootId = walkNode(
                info = root,
                parentId = null,
                path = emptyList(),
                depth = 0,
                windowId = window.id,
                windowPackageName = windowPackageName,
                nodes = nodesInWindow,
                nodePathMap = snapshotNodePaths,
                idGenerator = { nextNodeId++ },
                totalNodeCounter = { totalNodes++ },
                canAddMore = { totalNodes < MAX_NODES }
            )

            if (rootId != null) {
                windowSnapshots.add(
                    WindowSnapshot(
                        windowId = window.id,
                        type = windowType,
                        layer = window.layer,
                        isActive = window.isActive,
                        isFocused = window.isFocused,
                        title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) window.title?.toString() else null,
                        packageName = windowPackageName,
                        boundsInScreen = windowBounds,
                        rootId = rootId,
                        nodes = nodesInWindow
                    )
                )
            }
        }

        nodePathMap.clear()
        nodePathMap.putAll(snapshotNodePaths)

        val windowSet = WindowSetSnapshot(
            capturedAtUptimeMs = uptimeMs,
            displayWidth = displayMetrics.widthPixels,
            displayHeight = displayMetrics.heightPixels,
            rotation = rotation,
            density = displayMetrics.density,
            windows = windowSnapshots
        )
        _lastSnapshot = windowSet
        return windowSet
    }

    private fun walkNode(
        info: AccessibilityNodeInfo,
        parentId: Int?,
        path: List<Int>,
        depth: Int,
        windowId: Int,
        windowPackageName: String,
        nodes: MutableMap<Int, NodeSnapshot>,
        nodePathMap: ConcurrentHashMap<Int, NodeLocator>,
        idGenerator: () -> Int,
        totalNodeCounter: () -> Unit,
        canAddMore: () -> Boolean
    ): Int? {
        if (!canAddMore() || depth > MAX_DEPTH) {
            info.safeRecycle()
            return null
        }
        totalNodeCounter()
        val nodeId = idGenerator()
        nodePathMap[nodeId] = NodeLocator(windowId, path)

        val nodeRect = Rect()
        info.getBoundsInScreen(nodeRect)
        val boundsInScreen = IntRect(nodeRect.left, nodeRect.top, nodeRect.right, nodeRect.bottom)

        val actionIds = info.actionList.map { it.id }
        val actions = mapActionIdsToNodeActions(actionIds)

        val childCount = info.childCount
        val childIds = mutableListOf<Int>()
        for (i in 0 until childCount) {
            if (!canAddMore()) break
            val child = runCatching { info.getChild(i) }.getOrNull() ?: continue
            val childId = walkNode(
                info = child,
                parentId = nodeId,
                path = path + i,
                depth = depth + 1,
                windowId = windowId,
                windowPackageName = windowPackageName,
                nodes = nodes,
                nodePathMap = nodePathMap,
                idGenerator = idGenerator,
                totalNodeCounter = totalNodeCounter,
                canAddMore = canAddMore
            )
            if (childId != null) {
                childIds.add(childId)
            }
        }

        val snapshot = NodeSnapshot(
            nodeId = nodeId,
            parentId = parentId,
            childIds = childIds,
            className = info.className?.toString() ?: "",
            resourceId = info.viewIdResourceName,
            text = info.text?.toString(),
            contentDescription = info.contentDescription?.toString(),
            hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) info.hintText?.toString() else null,
            stateDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) info.stateDescription?.toString() else null,
            boundsInScreen = boundsInScreen,
            visibleToUser = info.isVisibleToUser,
            enabled = info.isEnabled,
            clickable = info.isClickable,
            longClickable = info.isLongClickable,
            checkable = info.isCheckable,
            checked = info.isChecked,
            editable = info.isEditable,
            focusable = info.isFocusable,
            focused = info.isFocused,
            scrollable = info.isScrollable,
            selected = info.isSelected,
            password = info.isPassword,
            actions = actions,
            collectionRow = info.collectionItemInfo?.rowIndex,
            collectionColumn = info.collectionItemInfo?.columnIndex,
            drawingOrder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) info.drawingOrder else 0,
            windowId = windowId,
            packageName = info.packageName?.toString() ?: windowPackageName
        )
        nodes[nodeId] = snapshot
        info.safeRecycle()
        return nodeId
    }

    fun findLiveNode(nodeId: Int): AccessibilityNodeInfo? {
        val locator = nodePathMap[nodeId] ?: return null
        return findLiveNode(locator.windowId, locator.childPath)
    }

    fun findLiveNode(windowId: Int, path: List<Int>): AccessibilityNodeInfo? {
        val windows = runCatching { service.windows }.getOrNull() ?: return null
        val window = windows.firstOrNull { it.id == windowId } ?: return null
        var current = runCatching { window.root }.getOrNull() ?: return null

        for (childIndex in path) {
            val next = if (childIndex in 0 until current.childCount) {
                runCatching { current.getChild(childIndex) }.getOrNull()
            } else null
            current.safeRecycle()
            if (next == null) return null
            current = next
        }
        return current
    }

    companion object {
        const val MAX_NODES = 3000
        const val MAX_DEPTH = 60

        fun mapWindowType(type: Int): WindowType = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowType.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowType.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowType.SYSTEM
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowType.ACCESSIBILITY_OVERLAY
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowType.SPLIT_SCREEN_DIVIDER
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && type == 6) {
                    WindowType.MAGNIFICATION_OVERLAY
                } else {
                    WindowType.UNKNOWN
                }
            }
        }

        fun mapActionIdToNodeAction(actionId: Int): NodeAction? = when (actionId) {
            AccessibilityNodeInfo.ACTION_CLICK -> NodeAction.CLICK
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> NodeAction.LONG_CLICK
            AccessibilityNodeInfo.ACTION_SET_TEXT -> NodeAction.SET_TEXT
            AccessibilityNodeInfo.ACTION_FOCUS -> NodeAction.FOCUS
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS -> NodeAction.CLEAR_FOCUS
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> NodeAction.SCROLL_FORWARD
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> NodeAction.SCROLL_BACKWARD
            android.R.id.accessibilityActionScrollUp -> NodeAction.SCROLL_UP
            android.R.id.accessibilityActionScrollDown -> NodeAction.SCROLL_DOWN
            android.R.id.accessibilityActionScrollLeft -> NodeAction.SCROLL_LEFT
            android.R.id.accessibilityActionScrollRight -> NodeAction.SCROLL_RIGHT
            AccessibilityNodeInfo.ACTION_EXPAND -> NodeAction.EXPAND
            AccessibilityNodeInfo.ACTION_COLLAPSE -> NodeAction.COLLAPSE
            AccessibilityNodeInfo.ACTION_SELECT -> NodeAction.SELECT
            android.R.id.accessibilityActionSetProgress -> NodeAction.SET_PROGRESS
            android.R.id.accessibilityActionImeEnter -> NodeAction.IME_ENTER
            else -> null
        }

        fun mapActionIdsToNodeActions(actionIds: Collection<Int>): Set<NodeAction> {
            val result = mutableSetOf<NodeAction>()
            for (id in actionIds) {
                mapActionIdToNodeAction(id)?.let { result.add(it) }
            }
            return result
        }

        internal fun AccessibilityNodeInfo.safeRecycle() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                runCatching { @Suppress("DEPRECATION") recycle() }
            }
        }
    }
}
