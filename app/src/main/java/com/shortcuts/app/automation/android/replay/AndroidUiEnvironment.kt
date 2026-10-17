package com.shortcuts.app.automation.android.replay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.os.bundleOf
import com.shortcuts.app.automation.android.AccessibilitySnapshotAdapter
import com.shortcuts.app.automation.android.AccessibilitySnapshotAdapter.Companion.safeRecycle
import com.shortcuts.app.automation.core.matching.WindowMatcher
import com.shortcuts.app.automation.core.text.TextNormalizer
import com.shortcuts.app.automation.core.tree.DerivedLabel
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.core.tree.labelOf
import com.shortcuts.app.automation.model.CapturedGesture
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.LocatorKind
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.replay.DispatchMethod
import com.shortcuts.app.automation.replay.DispatchResult
import com.shortcuts.app.automation.replay.UiActionRequest
import com.shortcuts.app.automation.replay.UiEnvironment
import com.shortcuts.app.automation.replay.UiEnvironmentEvent
import com.shortcuts.app.automation.replay.isScrollRequest
import com.shortcuts.app.service.AutomationAccessibilityService
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

object AndroidUiEventHub {
    private val _events = MutableSharedFlow<UiEnvironmentEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<UiEnvironmentEvent> = _events.asSharedFlow()

    private val packageToActivityClass = ConcurrentHashMap<String, String>()
    private val activityClassCache = ConcurrentHashMap<Pair<String, String>, Boolean>()

    @Volatile
    var packageManager: PackageManager? = null

    fun resetForTesting() {
        packageToActivityClass.clear()
        activityClassCache.clear()
        packageManager = null
    }

    private fun getEffectivePackageManager(): PackageManager? =
        packageManager ?: runCatching { AutomationAccessibilityService.instance?.packageManager }.getOrNull()

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        val eventType = event.eventType

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val cls = event.className?.toString()
            if (!pkg.isNullOrBlank() && !cls.isNullOrBlank() && isLikelyActivity(pkg, cls)) {
                packageToActivityClass[pkg] = cls
            }
        }

        val envEvent: UiEnvironmentEvent? = when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> UiEnvironmentEvent.WindowsChanged(now)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> UiEnvironmentEvent.ContentChanged(
                now,
                event.windowId.takeIf { it != -1 }
            )
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> UiEnvironmentEvent.FocusChanged(
                now,
                event.windowId.takeIf { it != -1 },
                null
            )
            else -> null
        }
        if (envEvent != null) {
            _events.tryEmit(envEvent)
        }
    }

    fun onDisconnected() {
        _events.tryEmit(UiEnvironmentEvent.AccessibilityDisconnected(SystemClock.uptimeMillis()))
    }

    fun currentActivityClass(packageName: String): String? = packageToActivityClass[packageName]

    fun isLikelyActivityClass(className: String?): Boolean {
        if (className.isNullOrBlank()) return false
        if (!className.contains('.')) return false
        if (className.startsWith("android.widget.") ||
            className.startsWith("android.view.") ||
            className.startsWith("androidx.compose.")
        ) {
            return false
        }
        val simpleName = className.substringAfterLast('.')
        if (WindowMatcher.NON_ACTIVITY_TOKENS.any { simpleName.contains(it) }) {
            return false
        }
        return true
    }

    fun isLikelyActivity(packageName: String, className: String): Boolean {
        if (!isLikelyActivityClass(className)) return false
        val pm = getEffectivePackageManager() ?: return true
        val key = Pair(packageName, className)
        return activityClassCache.computeIfAbsent(key) {
            runCatching {
                pm.getActivityInfo(ComponentName(packageName, className), 0)
                true
            }.getOrDefault(false)
        }
    }
}

data class GesturePlan(
    val durationMs: Long,
    val points: List<IntPoint>,
    val isHold: Boolean = false
)

fun buildGesturePlan(
    points: List<IntPoint>,
    operation: UiOperation? = null,
    gesture: CapturedGesture? = null
): GesturePlan {
    if (points.isEmpty()) return GesturePlan(0L, emptyList())
    if (points.size == 1) {
        val isLongPress = operation == UiOperation.LONG_PRESS || gesture?.kind == GestureKind.LONG_PRESS
        val duration = if (isLongPress) 600L else 50L
        return GesturePlan(durationMs = duration, points = points, isHold = isLongPress)
    }
    val duration = (gesture?.durationMs ?: 300L).coerceAtLeast(1L)
    return GesturePlan(durationMs = duration, points = points, isHold = false)
}

/** Pure guard kept separate so stale-dispatch handling is JVM-testable without an Android service. */
internal fun staleSnapshotGenerationRejection(
    requestGeneration: Long,
    latestGeneration: Long
): DispatchResult.Rejected? = if (requestGeneration == latestGeneration) {
    null
} else {
    DispatchResult.Rejected("Stale snapshot generation: request=$requestGeneration, latest=$latestGeneration")
}

class AndroidUiEnvironment(
    private val adapter: AccessibilitySnapshotAdapter,
    private val service: AccessibilityService
) : UiEnvironment {

    @Volatile
    private var lastSnapshot: WindowSetSnapshot? = null
    @Volatile
    private var lastSnapshotGeneration: Long = 0L

    override fun snapshotWindows(): WindowSetSnapshot {
        val snapshot = adapter.snapshot() ?: WindowSetSnapshot(
            capturedAtUptimeMs = SystemClock.uptimeMillis(),
            displayWidth = 0,
            displayHeight = 0,
            rotation = 0,
            density = 1f,
            windows = emptyList()
        )
        lastSnapshot = snapshot
        lastSnapshotGeneration = adapter.latestGeneration
        return snapshot
    }

    override fun snapshotGeneration(): Long = lastSnapshotGeneration

    override fun events(): Flow<UiEnvironmentEvent> = AndroidUiEventHub.events

    override fun isAccessibilityAvailable(): Boolean =
        AutomationAccessibilityService.instance != null

    override fun currentActivityClass(windowId: Int): String? {
        val window = lastSnapshot?.window(windowId) ?: return null
        return AndroidUiEventHub.currentActivityClass(window.packageName)
    }

    override suspend fun perform(request: UiActionRequest): DispatchResult = when (request.dispatchMethod) {
        DispatchMethod.SEMANTIC_ACTION -> performSemanticAction(request)
        DispatchMethod.GESTURE -> performGesture(request)
        DispatchMethod.GLOBAL_ACTION -> performGlobalAction(request)
    }

    private fun performSemanticAction(request: UiActionRequest): DispatchResult {
        val nodeId = request.nodeId
            ?: return DispatchResult.Rejected("Missing nodeId in SEMANTIC_ACTION request")
        val expectedNode = expectedNodeFor(request)
            ?: return DispatchResult.Rejected("Stale or missing snapshot target for node $nodeId")
        val liveNode = adapter.findLiveNode(request.snapshotGeneration, nodeId)
            ?: return DispatchResult.Rejected("Target node $nodeId is no longer present on screen")

        try {
            val snapshot = adapter.snapshotForGeneration(request.snapshotGeneration)
            val window = snapshot?.windows?.firstOrNull { it.node(expectedNode.nodeId) != null }
            val derivedLabel = window?.labelOf(expectedNode)
            val isScroll = isScrollRequest(request)

            validateLiveIdentity(
                liveNode = liveNode,
                expectedNode = expectedNode,
                selector = request.targetSelector,
                isScroll = isScroll,
                derivedLabel = derivedLabel
            )?.let { return it }

            val actionToPerform = request.semanticAction
            val operation = request.operation

            // Dispatch on the action the engine asked for. The step's operation is only a fallback:
            // a reveal scroll requested during a TAP step must scroll the container, not click it,
            // and a FOCUS request during a SET_TEXT step must focus, not set the text early.
            val success = if (actionToPerform != null) {
                performNodeAction(liveNode, actionToPerform, request.text)
            } else {
                when (operation) {
                    is UiOperation.SET_TEXT -> performNodeAction(liveNode, NodeAction.SET_TEXT, request.text ?: operation.text)
                    is UiOperation.IME_ACTION -> performNodeAction(liveNode, NodeAction.IME_ENTER, null)
                    UiOperation.LONG_PRESS -> performNodeAction(liveNode, NodeAction.LONG_CLICK, null)
                    else -> performNodeAction(liveNode, NodeAction.CLICK, null)
                }
            }

            return if (success) {
                DispatchResult.Accepted(DispatchMethod.SEMANTIC_ACTION)
            } else {
                DispatchResult.Rejected("Action ${actionToPerform ?: operation} returned false")
            }
        } finally {
            liveNode.safeRecycle()
        }
    }

    private fun performNodeAction(liveNode: AccessibilityNodeInfo, action: NodeAction, text: String?): Boolean =
        when (action) {
            NodeAction.SET_TEXT -> {
                if (text == null) false else liveNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    bundleOf(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to text)
                )
            }
            // ACTION_FOCUS returns false for a field that already has input focus; that is success.
            NodeAction.FOCUS -> liveNode.isFocused || liveNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            NodeAction.CLEAR_FOCUS -> liveNode.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            NodeAction.IME_ENTER -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            NodeAction.CLICK -> liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            NodeAction.LONG_CLICK -> liveNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            NodeAction.SCROLL_FORWARD -> liveNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            NodeAction.SCROLL_BACKWARD -> liveNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            NodeAction.SCROLL_UP -> liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id)
            NodeAction.SCROLL_DOWN -> liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
            NodeAction.SCROLL_LEFT -> liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)
            NodeAction.SCROLL_RIGHT -> liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
            NodeAction.EXPAND -> liveNode.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
            NodeAction.COLLAPSE -> liveNode.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE)
            NodeAction.SELECT -> liveNode.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            NodeAction.SET_PROGRESS -> false
        }

    private suspend fun performGesture(request: UiActionRequest): DispatchResult {
        validateGestureTarget(request)?.let { return it }
        val plan = buildGesturePlan(request.gesturePoints, request.operation, request.gesture)
        if (plan.points.isEmpty()) {
            return DispatchResult.Rejected("No gesture points provided")
        }

        val path = Path()
        path.moveTo(plan.points[0].x.toFloat(), plan.points[0].y.toFloat())
        for (i in 1 until plan.points.size) {
            path.lineTo(plan.points[i].x.toFloat(), plan.points[i].y.toFloat())
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, plan.durationMs)
        val gestureDesc = GestureDescription.Builder().addStroke(stroke).build()

        val timeoutMs = plan.durationMs + 2000L
        val outcome = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val started = service.dispatchGesture(gestureDesc, callback, null)
                if (!started && cont.isActive) {
                    cont.resume(false)
                }
            }
        }

        return when (outcome) {
            true -> DispatchResult.Accepted(DispatchMethod.GESTURE)
            false -> DispatchResult.Rejected("Gesture cancelled or rejected by system")
            null -> DispatchResult.Rejected("Gesture timed out after $timeoutMs ms")
        }
    }

    private fun performGlobalAction(request: UiActionRequest): DispatchResult {
        val actionName = request.globalAction ?: (request.operation as? UiOperation.GLOBAL_ACTION)?.kind
        val globalActionId = when (actionName?.trim()?.uppercase()) {
            "BACK", "1" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME", "2" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS", "3" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS", "4" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS", "5" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "POWER_DIALOG", "6" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "LOCK_SCREEN", "8" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "TAKE_SCREENSHOT", "9" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> null
        } ?: return DispatchResult.Rejected("Unknown global action '$actionName'")

        val success = service.performGlobalAction(globalActionId)
        return if (success) {
            DispatchResult.Accepted(DispatchMethod.GLOBAL_ACTION)
        } else {
            DispatchResult.Rejected("Global action '$actionName' returned false")
        }
    }

    private fun expectedNodeFor(request: UiActionRequest) = request.nodeId?.let { nodeId ->
        if (staleSnapshotGenerationRejection(request.snapshotGeneration, adapter.latestGeneration) != null) null
        else adapter.snapshotForGeneration(request.snapshotGeneration)?.node(nodeId)
    }

    /** Validate a gesture's target too: a coordinate must not bypass snapshot-scoped identity. */
    private fun validateGestureTarget(request: UiActionRequest): DispatchResult.Rejected? {
        val nodeId = request.nodeId ?: return null
        val expectedNode = expectedNodeFor(request)
            ?: return DispatchResult.Rejected("Stale or missing snapshot target for node $nodeId")
        val liveNode = adapter.findLiveNode(request.snapshotGeneration, nodeId)
            ?: return DispatchResult.Rejected("Target node $nodeId is no longer present on screen")
        return try {
            val snapshot = adapter.snapshotForGeneration(request.snapshotGeneration)
            val window = snapshot?.windows?.firstOrNull { it.node(expectedNode.nodeId) != null }
            val derivedLabel = window?.labelOf(expectedNode)
            val isScroll = isScrollRequest(request)

            validateLiveIdentity(
                liveNode = liveNode,
                expectedNode = expectedNode,
                selector = request.targetSelector,
                isScroll = isScroll,
                derivedLabel = derivedLabel
            )
        } finally {
            liveNode.safeRecycle()
        }
    }

    internal fun validateLiveIdentity(
        liveNode: AccessibilityNodeInfo,
        expectedNode: com.shortcuts.app.automation.core.tree.NodeSnapshot,
        selector: com.shortcuts.app.automation.model.ElementSelector? = null,
        isScroll: Boolean = false,
        derivedLabel: DerivedLabel? = null
    ): DispatchResult.Rejected? {
        if (liveNode.className?.toString().orEmpty() != expectedNode.className) {
            return DispatchResult.Rejected("Class mismatch for snapshot target")
        }
        if (liveNode.viewIdResourceName != expectedNode.resourceId) {
            return DispatchResult.Rejected("Resource-id mismatch for snapshot target")
        }
        if (liveNode.packageName?.toString().orEmpty() != expectedNode.packageName) {
            return DispatchResult.Rejected("Package mismatch for snapshot target")
        }
        val liveBounds = Rect().also(liveNode::getBoundsInScreen)
        val expectedBounds = expectedNode.boundsInScreen
        val boundsOverlap = maxOf(liveBounds.left, expectedBounds.left) < minOf(liveBounds.right, expectedBounds.right) &&
            maxOf(liveBounds.top, expectedBounds.top) < minOf(liveBounds.bottom, expectedBounds.bottom)
        if (!boundsOverlap) {
            return DispatchResult.Rejected("Bounds mismatch for snapshot target")
        }
        if (isScroll) {
            return null
        }

        val targetText = derivedLabel?.text
            ?: selector?.strategies?.firstOrNull { it.kind == LocatorKind.TEXT_EXACT }?.value
            ?: TextNormalizer.normalize(expectedNode.text)

        val targetDesc = derivedLabel?.contentDescription
            ?: selector?.strategies?.firstOrNull { it.kind == LocatorKind.CONTENT_DESCRIPTION_EXACT }?.value
            ?: TextNormalizer.normalize(expectedNode.contentDescription)

        if (targetText != null) {
            val matches = liveNodeMatches(liveNode, maxDepth = 3) { node ->
                TextNormalizer.sameText(node.text?.toString(), targetText) ||
                    TextNormalizer.sameText(node.contentDescription?.toString(), targetText)
            }
            if (!matches) {
                return DispatchResult.Rejected("Selector identity changed for snapshot target")
            }
        }

        if (targetDesc != null) {
            val matches = liveNodeMatches(liveNode, maxDepth = 3) { node ->
                TextNormalizer.sameText(node.contentDescription?.toString(), targetDesc) ||
                    TextNormalizer.sameText(node.text?.toString(), targetDesc)
            }
            if (!matches) {
                return DispatchResult.Rejected("Selector identity changed for snapshot target")
            }
        }

        return null
    }

    internal fun liveNodeMatches(
        root: AccessibilityNodeInfo,
        maxDepth: Int = 3,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        if (predicate(root)) return true
        if (maxDepth <= 0) return false
        return searchDescendants(root, currentDepth = 1, maxDepth = maxDepth, predicate = predicate)
    }

    private fun searchDescendants(
        node: AccessibilityNodeInfo,
        currentDepth: Int,
        maxDepth: Int,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                if (predicate(child)) {
                    return true
                }
                if (currentDepth < maxDepth) {
                    if (searchDescendants(child, currentDepth + 1, maxDepth, predicate)) {
                        return true
                    }
                }
            } finally {
                child.safeRecycle()
            }
        }
        return false
    }
}
