package com.shortcuts.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

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

        /**
         * The current app context used to decide whether a raw coordinate can be replayed.
         *
         * Recorded actions predating this check contain full-display metrics but not recorded
         * window bounds. Consequently, coordinate fallback is allowed only for a current,
         * full-display app window with no visible IME; split-screen, freeform, inset-shifted
         * content, and an unknown foreground app are refused. A uniform same-rotation scale is
         * the only transform considered well-defined for those legacy full-display coordinates.
         */
        internal data class CoordinateReplayContext(
            val display: DisplaySnapshot,
            val activePackageName: String?,
            val activeWindowBounds: Rect?,
            val activeRootBounds: Rect,
            val hasVisibleInputMethod: Boolean
        )

        internal data class CoordinateReplayPlan(
            val x: Float,
            val y: Float
        )

        internal data class CoordinateReplayDecision(
            val plan: CoordinateReplayPlan? = null,
            val failureMessage: String? = null
        )

        internal fun coordinateReplayPlan(
            action: Action,
            current: CoordinateReplayContext?
        ): CoordinateReplayDecision {
            val recordedWidth = action.recordedDisplayWidth
            val recordedHeight = action.recordedDisplayHeight
            val recordedRotation = action.recordedDisplayRotation
            if (recordedWidth == null || recordedHeight == null || recordedRotation == null) {
                return unsafeCoordinateReplay("This recorded coordinate tap has no display information. Re-record this step before running it.")
            }
            if (current == null) {
                return unsafeCoordinateReplay("The current app window could not be checked, so this coordinate tap was skipped to avoid tapping the wrong control.")
            }
            val recordedPackage = action.targetPackageName?.takeIf { it.isNotBlank() }
                ?: return unsafeCoordinateReplay("This coordinate fallback has no recorded app identity. Re-record this step before running it.")
            if (current.activePackageName != recordedPackage) {
                return unsafeCoordinateReplay("This step was recorded in a different app than the one currently open. Open the recorded app and try again.")
            }
            if (current.hasVisibleInputMethod) {
                return unsafeCoordinateReplay("A keyboard is changing the current screen layout, so this coordinate tap was skipped. Dismiss the keyboard or re-record this step.")
            }
            val activeWindowBounds = current.activeWindowBounds
                ?: return unsafeCoordinateReplay("The active app window bounds could not be checked, so this coordinate tap was skipped. Re-record this step.")
            if (!isFullDisplayWindow(activeWindowBounds, current.display)) {
                return unsafeCoordinateReplay("The recorded app is currently in a resized, split-screen, or freeform window. Re-record this step in the current layout.")
            }
            if (recordedRotation != current.display.rotation) {
                return unsafeCoordinateReplay("This coordinate tap was recorded at a different screen rotation. Re-record this step to avoid tapping the wrong control.")
            }
            if (recordedWidth <= 0 || recordedHeight <= 0 || current.display.width <= 0 || current.display.height <= 0) {
                return unsafeCoordinateReplay("The recorded or current display dimensions are invalid, so this coordinate tap was skipped. Re-record this step.")
            }

            val scaleX = current.display.width.toFloat() / recordedWidth
            val scaleY = current.display.height.toFloat() / recordedHeight
            if (abs(scaleX - scaleY) > MAX_UNIFORM_SCALE_DELTA) {
                return unsafeCoordinateReplay("The current display has a different aspect ratio, so this coordinate tap cannot be safely transformed. Re-record this step.")
            }

            val x = (action.screenX ?: return unsafeCoordinateReplay(
                "This coordinate fallback has no recorded point. Re-record this step before running it."
            )) * scaleX
            val y = (action.screenY ?: return unsafeCoordinateReplay(
                "This coordinate fallback has no recorded point. Re-record this step before running it."
            )) * scaleY
            if (!current.activeRootBounds.contains(x.roundToInt(), y.roundToInt())) {
                return unsafeCoordinateReplay("The recorded point is outside the current app content bounds, likely because system bars or the window layout changed. Re-record this step.")
            }
            return CoordinateReplayDecision(plan = CoordinateReplayPlan(x, y))
        }

        private fun unsafeCoordinateReplay(message: String): CoordinateReplayDecision =
            CoordinateReplayDecision(failureMessage = message)

        private const val MAX_UNIFORM_SCALE_DELTA = 0.01f

        private fun isFullDisplayWindow(bounds: Rect, display: DisplaySnapshot): Boolean {
            return bounds.left == 0 &&
                bounds.top == 0 &&
                bounds.right == display.width &&
                bounds.bottom == display.height
        }
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
            RecordingHudOverlay.hide()
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

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val root = getRootNode()
        val activePackageName = root?.packageName?.toString()
        root?.recycle()
        return AutomationRecorder.onKeyEvent(event, this, activePackageName)
    }

    @Volatile
    internal var lastEventTimeMs: Long = 0

    object AutomationTrace {
        /** Keep diagnostics useful without retaining every screen's node text for the service lifetime. */
        internal const val MAX_ENTRIES = 100
        internal const val MAX_CANDIDATES_PER_MATCH = 10
        private val lock = Any()

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
        
        private val recordedMatches = ArrayDeque<MatchTrace>(MAX_ENTRIES)
        private val recordedWaits = ArrayDeque<WaitTrace>(MAX_ENTRIES)

        val matches: List<MatchTrace>
            get() = synchronized(lock) { recordedMatches.toList() }
        val waits: List<WaitTrace>
            get() = synchronized(lock) { recordedWaits.toList() }

        fun recordMatch(trace: MatchTrace) = synchronized(lock) {
            // Trace data is only consumed by tests today. Keep structural diagnostics, but do
            // not retain source-node or recorded target text beyond this execution path.
            recordedMatches.addLast(
                trace.copy(
                    targetDesc = redactedTextShape(trace.targetDesc),
                    candidates = trace.candidates
                        .take(MAX_CANDIDATES_PER_MATCH)
                        .map { candidate -> candidate.copy(text = candidate.text?.let(::redactedTextShape)) }
                )
            )
            if (recordedMatches.size > MAX_ENTRIES) recordedMatches.removeFirst()
        }

        fun recordWait(trace: WaitTrace) = synchronized(lock) {
            recordedWaits.addLast(trace.copy(targetDesc = redactedTextShape(trace.targetDesc)))
            if (recordedWaits.size > MAX_ENTRIES) recordedWaits.removeFirst()
        }
        
        fun clear() = synchronized(lock) {
            recordedMatches.clear()
            recordedWaits.clear()
        }

        private fun redactedTextShape(value: String): String = "chars=${value.length}"
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
        val readableRoot = getRootNode()
        if (readableRoot == null) {
            return fail(
                "No app screen could be read. Open the app this step belongs to, then run the shortcut again."
            )
        }
        readableRoot.recycle()

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
            Log.w(TAG, "Unrecognized or invalid global navigation action")
            return false
        }

        Log.d(TAG, "Executing performGlobalAction with actionId: $actionId")
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
            targetNode?.recycle()
            Log.w(TAG, "No suitable target node found for text entry")
            return fail(
                "No text box matching ${action.describeTarget()} was on screen. " +
                    "Open the screen with that text box before this step runs."
            )
        }

        val arguments = bundleOf(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to textToSet
        )

        Log.d(TAG, "Executing ACTION_SET_TEXT with text length: ${textToSet.length}")
        return try {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } finally {
            if (editableNode !== targetNode) targetNode?.recycle()
            editableNode.recycle()
        }
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
            val root = getRootNode()
            fallbackPackageFailure(action, root)?.let { message ->
                root?.recycle()
                targetNode?.recycle()
                return fail(message)
            }
            val scrollableFallback = findScrollableNode(root)
            if (root !== scrollableFallback) root?.recycle()
            if (scrollableFallback != null) {
                if (targetNode !== scrollableFallback) targetNode?.recycle()
                targetNode = scrollableFallback
            }
        }

        if (targetNode == null) {
            Log.w(TAG, "No scrollable node found")
            return fail("Nothing on this screen could be scrolled.")
        }

        Log.d(TAG, "Executing scroll actionId $scrollActionId")
        return try {
            targetNode.performAction(scrollActionId)
        } finally {
            targetNode.recycle()
        }
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
            Log.w(TAG, "Target node not found for click action; semantic selectors were unavailable")
            if (tapAtRecordedPoint(action)) {
                return true
            }
            return if (lastFailureMessage != null) false else fail("${action.describeTarget()} wasn't on screen when this step ran.")
        }

        // Find clickable node (either target node or closest clickable parent up to maxParentDepth)
        var clickableNode: AccessibilityNodeInfo = targetNode
        var nodeToClick: AccessibilityNodeInfo = targetNode
        var depth = 0
        val maxParentDepth = 25
        val visited = mutableSetOf<AccessibilityNodeInfo>()
        val acquiredParents = mutableListOf<AccessibilityNodeInfo>()

        while (!clickableNode.isClickable && depth < maxParentDepth) {
            if (!visited.add(clickableNode)) {
                Log.w(TAG, "Cycle detected in parent hierarchy at depth $depth")
                break
            }
            val parent = clickableNode.parent ?: break
            if (parent === clickableNode) {
                Log.w(TAG, "Self-referential parent hierarchy at depth $depth")
                break
            }
            clickableNode = parent
            if (parent !== targetNode) acquiredParents += parent
            depth++
        }

        if (clickableNode.isClickable) nodeToClick = clickableNode
        Log.d(TAG, "Executing ACTION_CLICK")
        return try {
            nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } finally {
            recycleNodesExcept(acquiredParents, nodeToClick)
            targetNode.recycle()
            if (nodeToClick !== targetNode) nodeToClick.recycle()
        }
    }

    /**
     * Raw fallbacks have no selector left to prove intent, so unlike semantic lookup they require
     * an app identity and re-check the foreground root immediately before acting. Old steps that
     * predate [Action.targetPackageName] must be re-recorded before a blind fallback can run.
     */
    private fun fallbackPackageFailure(action: Action, root: AccessibilityNodeInfo?): String? {
        val recordedPackage = action.targetPackageName?.takeIf { it.isNotBlank() }
            ?: return "This fallback has no recorded app identity. Re-record this step before running it."
        val activePackage = root?.packageName?.toString()
        return if (activePackage == recordedPackage) {
            null
        } else {
            "This step was recorded in a different app than the one currently open. Open the recorded app and try again."
        }
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
            AutomationTrace.recordWait(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, true))
            return it
        }

        // Only now give a genuinely transitioning screen (e.g. a navigation animation) a
        // moment to settle before polling. Capped well below the full timeout so a screen
        // with continuous background events (a live ticker, a typing indicator) can't burn
        // the whole budget here and starve the polling loop below.
        waitForScreenToSettle(minOf(nodeWaitTimeoutMillis, SETTLE_WAIT_CAP_MS))

        findTargetNode(action)?.let {
            AutomationTrace.recordWait(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, true))
            return it
        }

        val elapsed = System.currentTimeMillis() - start
        val remaining = nodeWaitTimeoutMillis - elapsed
        if (remaining <= 0) {
            AutomationTrace.recordWait(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, false))
            return null
        }

        val retries = ((remaining + NODE_POLL_INTERVAL_MS - 1) / NODE_POLL_INTERVAL_MS).toInt()
        repeat(retries) {
            try {
                Thread.sleep(NODE_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                AutomationTrace.recordWait(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, false))
                return null
            }
            findTargetNode(action)?.let { 
                AutomationTrace.recordWait(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, true))
                return it 
            }
            if (System.currentTimeMillis() - start >= nodeWaitTimeoutMillis) {
                AutomationTrace.recordWait(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, false))
                return null
            }
        }
        AutomationTrace.recordWait(AutomationTrace.WaitTrace(action.describeTarget(), System.currentTimeMillis() - start, false))
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

        val validation = coordinateReplayPlan(action, captureCoordinateReplayContext())
        val safePlan = validation.plan ?: run {
            val message = validation.failureMessage
                ?: "The coordinate tap could not be safely checked, so it was skipped. Re-record this step."
            Log.w(TAG, message)
            return fail(message)
        }

        val path = Path().apply { moveTo(safePlan.x, safePlan.y) }
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
        Log.d(TAG, "Coordinate fallback gesture completed=${landed.get()}")
        return landed.get()
    }

    fun findTargetNode(action: Action): AccessibilityNodeInfo? {
        val root = getRootNode() ?: return null

        // AccessibilityNodeInfo.equals() is logical-node equality, not wrapper identity. Keep
        // every acquired wrapper here; the identity-aware recycler below owns deduplication.
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        var bestMatch: AccessibilityNodeInfo? = null

        try {

            // 1. Search by View ID
            action.targetNodeId?.takeIf { it.isNotEmpty() }?.let { viewId ->
                addCandidates(candidates, root.findAccessibilityNodeInfosByViewId(viewId))
            }

            // 2. Search by Text
            val searchTerms = listOfNotNull(
                action.targetText,
                action.targetContentDescription,
                action.targetNodeId,
                action.target
            ).filter { it.isNotEmpty() }

            for (term in searchTerms) {
                addCandidates(candidates, root.findAccessibilityNodeInfosByText(term))
            }

            // 3. Fallback: Robust Recursive Traversal
            for (term in searchTerms) {
                findAllNodesByTraversal(root, term, candidates)
            }

            // Cross-app safety check: if the recorded step captured which app its target
            // belonged to, reject candidates from a different app rather than risk clicking
            // a same-worded element the automation wandered into unexpectedly. Rejected
            // candidates stay in `candidates` so the `finally` block below still recycles
            // every node this search obtained.
            val recordedPackage = action.targetPackageName?.takeIf { it.isNotBlank() }
            val eligibleCandidates = if (recordedPackage != null) {
                candidates.filter { it.packageName?.toString() == recordedPackage }
            } else {
                candidates.toList()
            }

            if (eligibleCandidates.isEmpty()) {
                AutomationTrace.recordMatch(AutomationTrace.MatchTrace(action.describeTarget(), emptyList(), -1, null))
                return null
            }

            val scoredCandidates = eligibleCandidates.map { node ->
                val score = scoreNode(node, action)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                ScoredCandidate(
                    node = node,
                    info = AutomationTrace.CandidateInfo(
                        viewId = node.viewIdResourceName,
                        text = node.text?.toString() ?: node.contentDescription?.toString(),
                        className = node.className?.toString(),
                        bounds = rect,
                        score = score
                    )
                )
            }

            val bestCandidate = scoredCandidates.maxBy { it.info.score }
            bestMatch = bestCandidate.node
            val bestScore = bestCandidate.info.score
            val candidateInfos = scoredCandidates.map { it.info }
            val pickedIndex = scoredCandidates.indexOfFirst { it.node === bestMatch }

            AutomationTrace.recordMatch(AutomationTrace.MatchTrace(action.describeTarget(), candidateInfos, pickedIndex, bestScore))
            return bestMatch
        } finally {
            recycleNodesExcept(candidates + listOf(root), bestMatch)
        }
    }

    private data class ScoredCandidate(
        val node: AccessibilityNodeInfo,
        val info: AutomationTrace.CandidateInfo
    )

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
    ): Boolean {
        if (depth > maxDepth) {
            return false
        }

        val termLower = searchTerm.lowercase()

        val viewIdMatch = node.viewIdResourceName?.lowercase()?.contains(termLower) == true
        val textMatch = node.text?.toString()?.lowercase()?.contains(termLower) == true
        val descMatch = node.contentDescription?.toString()?.lowercase()?.contains(termLower) == true

        val retainedForCaller = (viewIdMatch || textMatch || descMatch) && outNodes.add(node)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val retainedChild = findAllNodesByTraversal(child, searchTerm, outNodes, depth + 1, maxDepth)
            if (!retainedChild && child !== node) child.recycle()
        }
        return retainedForCaller
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
                if (child !== result) child.recycle()
                return result
            }
            if (child !== node) child.recycle()
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
                if (child !== result) child.recycle()
                return result
            }
            if (child !== node) child.recycle()
        }

        return null
    }

    fun performClickOnNode(viewIdResourceName: String) {
        val rootNode = getRootNode() ?: return
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewIdResourceName).orEmpty()
        try {
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked a requested view-id target")
                    return
                }
            }
        } finally {
            recycleNodesExcept(nodes + rootNode, retained = null)
        }
    }

    private fun addCandidates(
        candidates: MutableCollection<AccessibilityNodeInfo>,
        nodes: List<AccessibilityNodeInfo>?
    ) {
        nodes.orEmpty().forEach(candidates::add)
    }

    private fun recycleNodesExcept(
        nodes: Iterable<AccessibilityNodeInfo>,
        retained: AccessibilityNodeInfo?
    ) {
        val recycled = Collections.newSetFromMap(IdentityHashMap<AccessibilityNodeInfo, Boolean>())
        nodes.forEach { node ->
            if (node !== retained && recycled.add(node)) node.recycle()
        }
    }

    private fun captureDisplaySnapshot(context: android.content.Context): DisplaySnapshot? =
        captureDisplaySnapshotForRecording(context)

    @androidx.annotation.VisibleForTesting internal fun captureCoordinateReplayContext(): CoordinateReplayContext? {
        val root = getRootNode() ?: return null
        return try {
            val display = captureDisplaySnapshot(this) ?: return null
            val activeWindowBounds = root.window?.let { window ->
                Rect().also(window::getBoundsInScreen)
            }
            val activeRootBounds = Rect().also(root::getBoundsInScreen)
            val hasVisibleInputMethod = windows.orEmpty().any { window ->
                window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
            CoordinateReplayContext(
                display = display,
                activePackageName = root.packageName?.toString(),
                activeWindowBounds = activeWindowBounds,
                activeRootBounds = activeRootBounds,
                hasVisibleInputMethod = hasVisibleInputMethod
            )
        } finally {
            root.recycle()
        }
    }

}
