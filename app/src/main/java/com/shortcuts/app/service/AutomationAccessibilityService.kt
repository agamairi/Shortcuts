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
            else -> handleClickNode(action)
        }
    }

    private fun isGlobalNavigationAction(action: Action): Boolean {
        val uiType = action.uiActionType?.uppercase()
        val global = action.globalAction?.uppercase()
        val target = action.target?.uppercase()
        val intent = action.intentAction?.uppercase()

        val globalKeywords = setOf("BACK", "HOME", "RECENTS", "GLOBAL_ACTION", "GLOBAL_ACTION_BACK", "GLOBAL_ACTION_HOME", "GLOBAL_ACTION_RECENTS")
        return (uiType != null && uiType in globalKeywords) ||
                global != null ||
                (target != null && target in globalKeywords) ||
                (intent != null && intent in globalKeywords)
    }

    private fun handleGlobalNavigation(action: Action): Boolean {
        val key = action.globalAction?.uppercase()
            ?: action.uiActionType?.uppercase()
            ?: action.target?.uppercase()
            ?: action.intentAction?.uppercase()
            ?: ""

        val actionId = when {
            key.contains("HOME") -> GLOBAL_ACTION_HOME
            key.contains("RECENTS") -> GLOBAL_ACTION_RECENTS
            key.contains("BACK") -> GLOBAL_ACTION_BACK
            key == "1" -> GLOBAL_ACTION_BACK
            key == "2" -> GLOBAL_ACTION_HOME
            key == "3" -> GLOBAL_ACTION_RECENTS
            else -> GLOBAL_ACTION_BACK
        }

        Log.d(TAG, "Executing performGlobalAction with actionId: $actionId for key: $key")
        return performGlobalAction(actionId)
    }

    private fun isTextEntryAction(action: Action): Boolean {
        val uiType = action.uiActionType?.uppercase()
        return action.textInput != null || uiType == "SET_TEXT" || uiType == "TEXT_ENTRY" || uiType == "TYPE"
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
        val uiType = action.uiActionType?.uppercase()
        val target = action.target?.uppercase()
        return (uiType != null && (uiType == "SCROLL" || uiType == "SCROLL_FORWARD" || uiType == "SCROLL_BACKWARD")) ||
                action.scrollDirection != null ||
                (target != null && (target == "SCROLL_FORWARD" || target == "SCROLL_BACKWARD" || target == "SCROLL"))
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

    private fun handleClickNode(action: Action): Boolean {
        val targetNode = findTargetNode(action)
        if (targetNode == null) {
            Log.w(TAG, "Target node not found for click action: targetNodeId=${action.targetNodeId}, targetText=${action.targetText}")
            return false
        }

        // Find clickable node (either target node or closest clickable parent)
        var clickableNode: AccessibilityNodeInfo? = targetNode
        while (clickableNode != null && !clickableNode.isClickable) {
            clickableNode = clickableNode.parent
        }

        val nodeToClick = clickableNode ?: targetNode
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

    private fun findNodeByTraversal(node: AccessibilityNodeInfo, searchTerm: String): AccessibilityNodeInfo? {
        val termLower = searchTerm.lowercase()

        val viewIdMatch = node.viewIdResourceName?.lowercase()?.contains(termLower) == true
        val textMatch = node.text?.toString()?.lowercase()?.contains(termLower) == true
        val descMatch = node.contentDescription?.toString()?.lowercase()?.contains(termLower) == true

        if (viewIdMatch || textMatch || descMatch) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByTraversal(child, searchTerm)
            if (result != null) {
                return result
            }
        }

        return null
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable || node.isFocusable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
        }

        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
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
