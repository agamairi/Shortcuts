package com.shortcuts.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.os.bundleOf
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType

open class AutomationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomationAccService"

        @Volatile
        var instance: AutomationAccessibilityService? = null
            internal set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AutomationAccessibilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "AutomationAccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Handle UI Automation feedback here
    }

    override fun onInterrupt() {
        Log.d(TAG, "Interrupted")
    }

    open fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Executes a given UI automation action.
     * Returns true if the action was successfully performed, false otherwise.
     */
    fun executeAction(action: Action): Boolean {
        if (action.actionType != ActionType.UI_AUTOMATION) {
            Log.w(TAG, "Action type is not UI_AUTOMATION: ${action.actionType}")
            return false
        }

        return when {
            isGlobalNavigationAction(action) -> handleGlobalNavigation(action)
            isTextEntryAction(action) -> handleTextEntry(action)
            isScrollAction(action) -> handleScroll(action)
            isClickAction(action) -> handleClickNode(action)
            else -> {
                Log.e(TAG, "Unknown or unsupported uiActionType: '${action.uiActionType}'")
                false
            }
        }
    }

    private fun isGlobalNavigationAction(action: Action): Boolean {
        val global = action.globalAction?.trim()
        if (!global.isNullOrEmpty()) {
            return true
        }

        val uiType = action.uiActionType?.trim()?.uppercase()
        if (uiType != null) {
            val validGlobalUiTypes = setOf(
                "GLOBAL_ACTION", "GLOBAL_NAVIGATION", "GLOBAL", "SYSTEM_NAV",
                "GLOBAL_ACTION_BACK", "GLOBAL_ACTION_HOME", "GLOBAL_ACTION_RECENTS",
                "GLOBAL_ACTION_NOTIFICATIONS", "GLOBAL_ACTION_QUICK_SETTINGS",
                "GLOBAL_ACTION_POWER_DIALOG", "GLOBAL_ACTION_LOCK_SCREEN", "GLOBAL_ACTION_TAKE_SCREENSHOT"
            )
            if (uiType in validGlobalUiTypes || uiType.startsWith("GLOBAL_ACTION_")) {
                return true
            }
        }

        val target = action.target?.trim()?.uppercase()
        if (target != null && target.startsWith("GLOBAL_ACTION_")) {
            return true
        }

        val intent = action.intentAction?.trim()?.uppercase()
        if (intent != null && intent.startsWith("GLOBAL_ACTION_")) {
            return true
        }

        return false
    }

    private fun parseGlobalActionId(key: String?): Int? {
        if (key.isNullOrBlank()) return null
        val upperKey = key.trim().uppercase()
        return when {
            upperKey == "BACK" || upperKey == "GLOBAL_ACTION_BACK" || upperKey == "1" -> GLOBAL_ACTION_BACK
            upperKey == "HOME" || upperKey == "GLOBAL_ACTION_HOME" || upperKey == "2" -> GLOBAL_ACTION_HOME
            upperKey == "RECENTS" || upperKey == "GLOBAL_ACTION_RECENTS" || upperKey == "3" -> GLOBAL_ACTION_RECENTS
            upperKey == "NOTIFICATIONS" || upperKey == "GLOBAL_ACTION_NOTIFICATIONS" || upperKey == "4" -> GLOBAL_ACTION_NOTIFICATIONS
            upperKey == "QUICK_SETTINGS" || upperKey == "GLOBAL_ACTION_QUICK_SETTINGS" || upperKey == "5" -> GLOBAL_ACTION_QUICK_SETTINGS
            upperKey == "POWER_DIALOG" || upperKey == "GLOBAL_ACTION_POWER_DIALOG" || upperKey == "6" -> GLOBAL_ACTION_POWER_DIALOG
            upperKey == "LOCK_SCREEN" || upperKey == "GLOBAL_ACTION_LOCK_SCREEN" || upperKey == "8" -> GLOBAL_ACTION_LOCK_SCREEN
            upperKey == "TAKE_SCREENSHOT" || upperKey == "GLOBAL_ACTION_TAKE_SCREENSHOT" || upperKey == "9" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> null
        }
    }

    private fun handleGlobalNavigation(action: Action): Boolean {
        val key = action.globalAction
            ?: action.uiActionType
            ?: action.target
            ?: action.intentAction
            ?: ""

        val actionId = parseGlobalActionId(key)
        if (actionId == null) {
            Log.w(TAG, "Unrecognized or invalid global navigation action key: '$key'")
            return false
        }

        Log.d(TAG, "Executing performGlobalAction with actionId: $actionId for key: $key")
        return performGlobalAction(actionId)
    }

    private fun isTextEntryAction(action: Action): Boolean {
        val uiType = action.uiActionType?.trim()?.uppercase()
        val textEntryTypes = setOf("TYPE_TEXT", "SET_TEXT", "TEXT_ENTRY", "TYPE", "INPUT_TEXT")
        return action.textInput != null || (uiType != null && uiType in textEntryTypes)
    }

    private fun handleTextEntry(action: Action): Boolean {
        val textToSet = action.textInput ?: ""
        val targetNode = findTargetNode(action)
        val editableNode = findEditableNode(targetNode) ?: targetNode

        if (editableNode == null) {
            Log.w(TAG, "No suitable target node found for text entry")
            return false
        }

        val arguments = bundleOf(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to textToSet
        )

        Log.d(TAG, "Executing ACTION_SET_TEXT with text: '$textToSet' on node: ${editableNode.viewIdResourceName}")
        return editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun isScrollAction(action: Action): Boolean {
        val uiType = action.uiActionType?.trim()?.uppercase()
        val target = action.target?.trim()?.uppercase()
        val scrollTypes = setOf("SCROLL", "SCROLL_FORWARD", "SCROLL_BACKWARD")
        return (uiType != null && uiType in scrollTypes) ||
                action.scrollDirection != null ||
                (target != null && target in scrollTypes)
    }

    private fun handleScroll(action: Action): Boolean {
        val direction = action.scrollDirection?.uppercase()
            ?: action.uiActionType?.uppercase()
            ?: action.target?.uppercase()
            ?: ""

        val scrollActionId = if (direction == "BACKWARD" || direction == "SCROLL_BACKWARD" || direction == "PREVIOUS" || direction == "UP") {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }

        var targetNode = findTargetNode(action)
        if (targetNode == null || !targetNode.isScrollable) {
            targetNode = findScrollableNode(getRootNode()) ?: targetNode
        }

        if (targetNode == null) {
            Log.w(TAG, "No scrollable node found")
            return false
        }

        Log.d(TAG, "Executing scroll actionId $scrollActionId on node: ${targetNode.viewIdResourceName}")
        return targetNode.performAction(scrollActionId)
    }

    private fun isClickAction(action: Action): Boolean {
        val uiType = action.uiActionType?.trim()?.uppercase()
        val clickTypes = setOf("CLICK", "TAP", "PRESS")
        if (uiType != null && uiType in clickTypes) {
            return true
        }
        if (uiType.isNullOrEmpty()) {
            return !action.targetNodeId.isNullOrEmpty() ||
                    !action.targetText.isNullOrEmpty() ||
                    !action.target.isNullOrEmpty()
        }
        return false
    }

    private fun handleClickNode(action: Action): Boolean {
        val targetNode = findTargetNode(action)
        if (targetNode == null) {
            Log.w(TAG, "Target node not found for click action: targetNodeId=${action.targetNodeId}, targetText=${action.targetText}")
            return false
        }

        // Find clickable node (either target node or closest clickable parent up to maxParentDepth)
        var clickableNode: AccessibilityNodeInfo? = targetNode
        var depth = 0
        val maxParentDepth = 25
        val visited = mutableSetOf<AccessibilityNodeInfo>()

        while (clickableNode != null && !clickableNode.isClickable && depth < maxParentDepth) {
            if (!visited.add(clickableNode)) {
                Log.w(TAG, "Cycle detected in parent hierarchy at depth $depth")
                break
            }
            clickableNode = clickableNode.parent
            depth++
        }

        val nodeToClick = if (clickableNode != null && clickableNode.isClickable) clickableNode else targetNode
        Log.d(TAG, "Executing ACTION_CLICK on node: ${nodeToClick.viewIdResourceName}")
        return nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /**
     * Attempts to locate target node using:
     * 1. View ID lookup
     * 2. Text lookup
     * 3. Robust recursive node traversal checking viewId, text, and contentDescription
     */
    fun findTargetNode(action: Action): AccessibilityNodeInfo? {
        val root = getRootNode() ?: return null

        // 1. Search by View ID
        action.targetNodeId?.takeIf { it.isNotEmpty() }?.let { viewId ->
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                return nodes[0]
            }
        }

        // 2. Search by Text
        val searchTerms = listOfNotNull(action.targetText, action.targetNodeId, action.target)
            .filter { it.isNotEmpty() }

        for (term in searchTerms) {
            val nodes = root.findAccessibilityNodeInfosByText(term)
            if (!nodes.isNullOrEmpty()) {
                return nodes[0]
            }
        }

        // 3. Fallback: Robust Recursive Traversal
        for (term in searchTerms) {
            val traversedNode = findNodeByTraversal(root, term)
            if (traversedNode != null) {
                return traversedNode
            }
        }

        return null
    }

    internal fun findNodeByTraversal(
        node: AccessibilityNodeInfo,
        searchTerm: String,
        depth: Int = 0,
        maxDepth: Int = 20
    ): AccessibilityNodeInfo? {
        if (depth > maxDepth) {
            return null
        }

        val termLower = searchTerm.lowercase()

        val viewIdMatch = node.viewIdResourceName?.lowercase()?.contains(termLower) == true
        val textMatch = node.text?.toString()?.lowercase()?.contains(termLower) == true
        val descMatch = node.contentDescription?.toString()?.lowercase()?.contains(termLower) == true

        if (viewIdMatch || textMatch || descMatch) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByTraversal(child, searchTerm, depth + 1, maxDepth)
            if (result != null) {
                return result
            }
        }

        return null
    }

    internal fun findEditableNode(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        maxDepth: Int = 20
    ): AccessibilityNodeInfo? {
        if (node == null || depth > maxDepth) {
            return null
        }
        if (node.isEditable || node.isFocusable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child, depth + 1, maxDepth)
            if (result != null) {
                return result
            }
        }

        return null
    }

    internal fun findScrollableNode(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        maxDepth: Int = 20
    ): AccessibilityNodeInfo? {
        if (node == null || depth > maxDepth) {
            return null
        }
        if (node.isScrollable) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child, depth + 1, maxDepth)
            if (result != null) {
                return result
            }
        }

        return null
    }

    fun performClickOnNode(viewIdResourceName: String) {
        val rootNode = getRootNode() ?: return
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewIdResourceName)
        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked $viewIdResourceName")
                    break
                }
            }
        }
    }
}

