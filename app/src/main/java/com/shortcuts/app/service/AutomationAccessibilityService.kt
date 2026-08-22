package com.shortcuts.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class AutomationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutomationAccService"

        /**
         * How long a replayed step waits for its target to appear. A recorded shortcut replays far
         * faster than a person tapped it, so the view a step targets is routinely not on screen yet
         * when the step runs. Failing on the first miss is why recorded shortcuts were unusable.
         */
        private const val NODE_WAIT_TIMEOUT_MS = 5_000L
        private const val NODE_POLL_INTERVAL_MS = 150L
        private const val TAP_DURATION_MS = 60L
        private const val GESTURE_TIMEOUT_MS = 2_000L

        /**
         * Upper bound on the settle wait inside [awaitTargetNode]. Deliberately well under
         * [NODE_WAIT_TIMEOUT_MS] so a screen with continuous background events (a live ticker,
         * a typing indicator) — which never goes quiet long enough to "settle" — can't burn the
         * whole per-step budget here and starve the polling loop that follows it.
         */
        private const val SETTLE_WAIT_CAP_MS = 1_000L

        @Volatile
        var instance: AutomationAccessibilityService? = null
            internal set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Accessibility-service XML flags form a complete mask. Reapply the required flags when
        // the service connects so future configuration changes cannot drop the default service
        // flag or the node-discovery capabilities this app needs.
        val configuredInfo = serviceInfo
        configuredInfo.flags = AccessibilityServiceConfiguration.requiredFlags(configuredInfo.flags)
        serviceInfo = configuredInfo
        instance = this
        AutomationRecorder.onAccessibilityServiceConnected()
        Log.d(TAG, "AutomationAccessibilityService connected with flags=${configuredInfo.flags}")
    }

    override fun onDestroy() {
        val wasConnectedInstance = instance == this
        super.onDestroy()
        if (wasConnectedInstance) {
            instance = null
            AutomationRecorder.stopForAccessibilityDisconnect(this)
        }
        Log.d(TAG, "AutomationAccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) {
            AutomationRecorder.onAccessibilityEvent(event, this)
            lastEventTimeMs = System.currentTimeMillis()
        }
        // Handle UI Automation feedback here
    }

    @Volatile
    internal var lastEventTimeMs: Long = 0

    object AutomationTrace {
        data class CandidateInfo(
            val viewId: String?,
            val text: String?,
            val className: String?,
            val bounds: android.graphics.Rect,
            val score: Int
        )
        
        data class MatchTrace(
            val targetDesc: String,
            val candidates: List<CandidateInfo>,
            val pickedIndex: Int,
            val pickedScore: Int?
        )
        
        data class WaitTrace(
            val targetDesc: String,
            val waitTimeMs: Long,
            val success: Boolean
        )
        
        val matches = mutableListOf<MatchTrace>()
        val waits = mutableListOf<WaitTrace>()
        
        fun clear() {
            matches.clear()
            waits.clear()
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Interrupted")
    }

    open fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * Why the most recent [executeAction] returned false, phrased for the user rather than the log.
     * Null after a successful call. [ActionExecutorService] reads this so a failed tap can say what
     * it was looking for, instead of the old one-size-fits-all "This screen couldn't be automated".
     */
    @Volatile
    var lastFailureMessage: String? = null
        private set

    /** Records why a step failed and returns false, so handlers can `return fail("...")`. */
    private fun fail(message: String): Boolean {
        lastFailureMessage = message
        return false
    }

    /** The most human-recognisable name for what a step was aiming at. */
    private fun Action.describeTarget(): String {
        val label = targetText?.takeIf { it.isNotBlank() }
            ?: target?.takeIf { it.isNotBlank() }
            ?: targetNodeId?.takeIf { it.isNotBlank() }
        return if (label != null) "\"$label\"" else "the target"
    }

    /**
     * Executes a given UI automation action.
     * Returns true if the action was successfully performed, false otherwise.
     */
    fun executeAction(action: Action): Boolean {
        lastFailureMessage = null
        if (action.actionType != ActionType.UI_AUTOMATION) {
            Log.w(TAG, "Action type is not UI_AUTOMATION: ${action.actionType}")
            return fail("This step isn't a screen-automation step, so it can't be run on the current screen.")
        }
        if (getRootNode() == null) {
            return fail(
                "No app screen could be read. Open the app this step belongs to, then run the shortcut again."
            )
        }

        val performed = when {
            isGlobalNavigationAction(action) -> handleGlobalNavigation(action)
            isTextEntryAction(action) -> handleTextEntry(action)
            isScrollAction(action) -> handleScroll(action)
            isClickAction(action) -> handleClickNode(action)
            else -> {
                Log.e(TAG, "Unknown or unsupported uiActionType: '${action.uiActionType}'")
                fail("This step has no screen action set, so there was nothing to do.")
            }
        }
        // A handler can fail at the final performAction without having called fail().
        if (!performed && lastFailureMessage == null) {
            lastFailureMessage = "${action.describeTarget()} was found but wouldn't respond on this screen."
        }
        return performed
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
        val targetNode = awaitTargetNode(action)
        val editableNode = findEditableNode(targetNode) ?: targetNode

        if (editableNode == null) {
            Log.w(TAG, "No suitable target node found for text entry")
            return fail(
                "No text box matching ${action.describeTarget()} was on screen. " +
                    "Open the screen with that text box before this step runs."
            )
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

        var targetNode = awaitTargetNode(action)
        if (targetNode == null || !targetNode.isScrollable) {
            targetNode = findScrollableNode(getRootNode()) ?: targetNode
        }

        if (targetNode == null) {
            Log.w(TAG, "No scrollable node found")
            return fail("Nothing on this screen could be scrolled.")
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
        val targetNode = awaitTargetNode(action)
        if (targetNode == null) {
            Log.w(TAG, "Target node not found for click action: targetNodeId=${action.targetNodeId}, targetText=${action.targetText}")
            if (tapAtRecordedPoint(action)) {
                return true
            }
            return fail("${action.describeTarget()} wasn't on screen when this step ran.")
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
    /**
     * How long a replayed step waits for its target to appear. Settable so JVM unit tests can turn
     * the wait off: they assert the target-not-found path, and there is no real UI that will ever
     * arrive, so waiting only makes the suite slow.
     */
    @VisibleForTesting
    internal var nodeWaitTimeoutMillis: Long = NODE_WAIT_TIMEOUT_MS

    /**
     * Retries [findTargetNode] until the target appears or the wait budget runs out, re-reading the
     * window each attempt so a screen that is still loading gets a chance to settle.
     *
     * Bounded by a retry COUNT rather than a wall-clock deadline on purpose: `SystemClock` is not
     * available off-device and returns a constant 0 under plain JUnit, which turned a deadline
     * comparison into an infinite loop.
     *
     * Blocks the calling thread, so it must run off the main thread — every caller of
     * [ActionExecutorService.executeActions] dispatches to IO for exactly this reason.
     */
    fun waitForScreenToSettle(timeoutMs: Long) {
        val settleTimeMs = 300L
        val maxRetries = (timeoutMs / 50L).coerceAtLeast(1L).toInt()
        
        for (i in 0 until maxRetries) {
            val idleTime = System.currentTimeMillis() - lastEventTimeMs
            if (idleTime >= settleTimeMs) {
                break
            }
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }

    private fun awaitTargetNode(action: Action): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()

        // Fast path first: the target may already be on screen and stable (e.g. a static
        // screen, or one with harmless background chatter that never truly goes quiet) — do
        // not force every step to pay a settle wait before even attempting to find it.
        findTargetNode(action)?.let {
            AutomationTrace.waits.add(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, true))
            return it
        }

        // Only now give a genuinely transitioning screen (e.g. a navigation animation) a
        // moment to settle before polling. Capped well below the full timeout so a screen
        // with continuous background events (a live ticker, a typing indicator) can't burn
        // the whole budget here and starve the polling loop below.
        waitForScreenToSettle(minOf(nodeWaitTimeoutMillis, SETTLE_WAIT_CAP_MS))

        findTargetNode(action)?.let {
            AutomationTrace.waits.add(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, true))
            return it
        }

        val elapsed = System.currentTimeMillis() - start
        val remaining = nodeWaitTimeoutMillis - elapsed
        if (remaining <= 0) {
            AutomationTrace.waits.add(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, false))
            return null
        }

        val retries = ((remaining + NODE_POLL_INTERVAL_MS - 1) / NODE_POLL_INTERVAL_MS).toInt()
        repeat(retries) {
            try {
                Thread.sleep(NODE_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                AutomationTrace.waits.add(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, false))
                return null
            }
            findTargetNode(action)?.let { 
                AutomationTrace.waits.add(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, true))
                return it 
            }
        }
        AutomationTrace.waits.add(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, false))
        return null
    }

    /**
     * Taps the screen point the recorder captured. Last resort: it is the only selector that still
     * works when the target is an unlabelled container, or its view id changed between app versions.
     * Returns false when the step carries no recorded point (anything saved before recording
     * captured coordinates).
     */
    private fun tapAtRecordedPoint(action: Action): Boolean {
        val x = action.screenX ?: return false
        val y = action.screenY ?: return false
        if (x < 0 || y < 0) return false

        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()

        val finished = CountDownLatch(1)
        val landed = AtomicBoolean(false)
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    landed.set(true)
                    finished.countDown()
                }

                override fun onCancelled(description: GestureDescription?) {
                    finished.countDown()
                }
            },
            null
        )
        if (!dispatched) return false
        finished.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        Log.d(TAG, "Coordinate fallback tap at ($x, $y) landed=${landed.get()}")
        return landed.get()
    }

    fun findTargetNode(action: Action): AccessibilityNodeInfo? {
        val root = getRootNode() ?: return null

        val candidates = mutableSetOf<AccessibilityNodeInfo>()

        // 1. Search by View ID
        action.targetNodeId?.takeIf { it.isNotEmpty() }?.let { viewId ->
            val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
            if (!nodes.isNullOrEmpty()) {
                candidates.addAll(nodes)
            }
        }

        // 2. Search by Text
        val searchTerms = listOfNotNull(
            action.targetText,
            action.targetContentDescription,
            action.targetNodeId,
            action.target
        ).filter { it.isNotEmpty() }

        for (term in searchTerms) {
            val nodes = root.findAccessibilityNodeInfosByText(term)
            if (!nodes.isNullOrEmpty()) {
                candidates.addAll(nodes)
            }
        }

        // 3. Fallback: Robust Recursive Traversal
        for (term in searchTerms) {
            findAllNodesByTraversal(root, term, candidates)
        }

        if (candidates.isEmpty()) {
            AutomationTrace.matches.add(AutomationTrace.MatchTrace(action.describeTarget(), emptyList(), -1, null))
            return null
        }

        val candidateInfos = mutableListOf<AutomationTrace.CandidateInfo>()
        
        val scoredCandidates = candidates.map { node ->
            val score = scoreNode(node, action)
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            candidateInfos.add(AutomationTrace.CandidateInfo(
                viewId = node.viewIdResourceName,
                text = node.text?.toString() ?: node.contentDescription?.toString(),
                className = node.className?.toString(),
                bounds = rect,
                score = score
            ))
            node to score
        }.sortedByDescending { it.second }

        val bestMatch = scoredCandidates.first().first
        val bestScore = scoredCandidates.first().second
        
        // Find index of bestMatch in candidateInfos by matching identity or just using the sorted order
        // Actually, candidateInfos is in original order, but the trace should reflect the sorted or just which one was picked
        val pickedIndex = candidateInfos.indexOfFirst { it.score == bestScore } 
        
        AutomationTrace.matches.add(AutomationTrace.MatchTrace(action.describeTarget(), candidateInfos.toList(), pickedIndex, bestScore))
        return bestMatch
    }

    private fun scoreNode(node: AccessibilityNodeInfo, action: Action): Int {
        var score = 0
        
        // 1. Class name match is a strong signal
        if (!action.targetClassName.isNullOrEmpty() && node.className?.toString() == action.targetClassName) {
            score += 100
        }

        // 2. View ID exact match
        if (!action.targetNodeId.isNullOrEmpty() && node.viewIdResourceName == action.targetNodeId) {
            score += 50
        }

        // 3. Text exact match (vs substring)
        val termMatchesExact = listOfNotNull(action.targetText, action.targetContentDescription, action.target).any { term ->
            node.text?.toString() == term || node.contentDescription?.toString() == term
        }
        if (termMatchesExact) {
            score += 50
        }

        // 4. Proximity to screenX/screenY (tiebreaker)
        if (action.screenX != null && action.screenY != null) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val centerX = rect.centerX()
            val centerY = rect.centerY()
            val dx = centerX - action.screenX
            val dy = centerY - action.screenY
            val distanceSq = dx * dx + dy * dy
            val dist = Math.sqrt(distanceSq.toDouble()).toInt()
            val proximityScore = Math.max(0, 40 - (dist / 50))
            score += proximityScore
        }

        return score
    }

    internal fun findAllNodesByTraversal(
        node: AccessibilityNodeInfo,
        searchTerm: String,
        outNodes: MutableCollection<AccessibilityNodeInfo>,
        depth: Int = 0,
        maxDepth: Int = 20
    ) {
        if (depth > maxDepth) {
            return
        }

        val termLower = searchTerm.lowercase()

        val viewIdMatch = node.viewIdResourceName?.lowercase()?.contains(termLower) == true
        val textMatch = node.text?.toString()?.lowercase()?.contains(termLower) == true
        val descMatch = node.contentDescription?.toString()?.lowercase()?.contains(termLower) == true

        if (viewIdMatch || textMatch || descMatch) {
            outNodes.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllNodesByTraversal(child, searchTerm, outNodes, depth + 1, maxDepth)
        }
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
