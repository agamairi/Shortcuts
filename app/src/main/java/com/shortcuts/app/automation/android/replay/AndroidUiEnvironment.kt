package com.shortcuts.app.automation.android.replay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.os.bundleOf
import com.shortcuts.app.automation.android.AccessibilitySnapshotAdapter
import com.shortcuts.app.automation.android.AccessibilitySnapshotAdapter.Companion.safeRecycle
import com.shortcuts.app.automation.core.tree.WindowSetSnapshot
import com.shortcuts.app.automation.model.CapturedGesture
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.NodeAction
import com.shortcuts.app.automation.model.UiOperation
import com.shortcuts.app.automation.replay.DispatchMethod
import com.shortcuts.app.automation.replay.DispatchResult
import com.shortcuts.app.automation.replay.UiActionRequest
import com.shortcuts.app.automation.replay.UiEnvironment
import com.shortcuts.app.automation.replay.UiEnvironmentEvent
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

    fun onAccessibilityEvent(event: AccessibilityEvent) {
        val now = SystemClock.uptimeMillis()
        val eventType = event.eventType

        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            val cls = event.className?.toString()
            if (!pkg.isNullOrBlank() && isLikelyActivityClass(cls)) {
                packageToActivityClass[pkg] = cls!!
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
            className.startsWith("androidx.compose.") ||
            className.endsWith("Dialog") ||
            className.endsWith("PopupWindow") ||
            className.endsWith("Menu")
        ) {
            return false
        }
        return true
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

class AndroidUiEnvironment(
    private val adapter: AccessibilitySnapshotAdapter,
    private val service: AccessibilityService
) : UiEnvironment {

    @Volatile
    private var lastSnapshot: WindowSetSnapshot? = null

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
        return snapshot
    }

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
        val expectedNode = lastSnapshot?.node(nodeId)
            ?: return DispatchResult.Rejected("Node $nodeId not found in last snapshot")
        val liveNode = adapter.findLiveNode(nodeId)
            ?: return DispatchResult.Rejected("Target node $nodeId is no longer present on screen")

        try {
            val liveClass = liveNode.className?.toString().orEmpty()
            if (liveClass != expectedNode.className) {
                return DispatchResult.Rejected(
                    "Class mismatch for node $nodeId: expected '${expectedNode.className}', found '$liveClass'"
                )
            }

            val liveBounds = Rect()
            liveNode.getBoundsInScreen(liveBounds)
            val expectedRect = Rect(
                expectedNode.boundsInScreen.left,
                expectedNode.boundsInScreen.top,
                expectedNode.boundsInScreen.right,
                expectedNode.boundsInScreen.bottom
            )
            if (!Rect.intersects(liveBounds, expectedRect)) {
                return DispatchResult.Rejected(
                    "Bounds mismatch for node $nodeId: live $liveBounds does not overlap expected $expectedRect"
                )
            }

            val actionToPerform = request.semanticAction
            val operation = request.operation

            val success = when {
                request.text != null && (actionToPerform == NodeAction.SET_TEXT || operation is UiOperation.SET_TEXT) -> {
                    val args = bundleOf(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE to request.text)
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                actionToPerform == NodeAction.IME_ENTER || operation is UiOperation.IME_ACTION -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    } else {
                        return DispatchResult.Rejected("ACTION_IME_ENTER requires API 30+")
                    }
                }
                actionToPerform == NodeAction.CLICK || operation == UiOperation.TAP -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                actionToPerform == NodeAction.LONG_CLICK || operation == UiOperation.LONG_PRESS -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                }
                actionToPerform == NodeAction.FOCUS -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                }
                actionToPerform == NodeAction.CLEAR_FOCUS -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
                }
                actionToPerform == NodeAction.SCROLL_FORWARD -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
                actionToPerform == NodeAction.SCROLL_BACKWARD -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                actionToPerform == NodeAction.SCROLL_UP -> {
                    liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id)
                }
                actionToPerform == NodeAction.SCROLL_DOWN -> {
                    liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
                }
                actionToPerform == NodeAction.SCROLL_LEFT -> {
                    liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)
                }
                actionToPerform == NodeAction.SCROLL_RIGHT -> {
                    liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
                }
                actionToPerform == NodeAction.EXPAND -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
                }
                actionToPerform == NodeAction.COLLAPSE -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE)
                }
                actionToPerform == NodeAction.SELECT -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_SELECT)
                }
                actionToPerform == NodeAction.SET_PROGRESS -> {
                    liveNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id)
                }
                else -> {
                    liveNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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

    private suspend fun performGesture(request: UiActionRequest): DispatchResult {
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
}
