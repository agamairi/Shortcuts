package com.shortcuts.app.service

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.shortcuts.app.data.Action
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes replay-only screen actions which are not part of the legacy accessibility-service
 * dispatcher. Keeping these here lets a recorded action be routed by [ActionExecutorService]
 * while preserving the service's selector scoring and coordinate-safety rules.
 */
internal class ReplayUiActionExecutor(
    private val service: AutomationAccessibilityService,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    // Resolved lazily so the framework constant is only touched on a real device.
    private val imeEnterActionId: () -> Int = {
        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
    }
) {
    fun executeLongPress(action: Action): ReplayUiActionResult {
        val node = awaitTargetNode(action)
        if (node == null) {
            return longPressAtRecordedPoint(action)
                ?: ReplayUiActionResult.Failure(
                    "Couldn't find ${action.replayTargetName()} — the app's screen changed."
                )
        }

        return try {
            if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                ReplayUiActionResult.Success
            } else if (longPressAtNodeCentre(node)) {
                ReplayUiActionResult.Success
            } else {
                ReplayUiActionResult.Failure("${action.replayTargetName()} wouldn't accept a long press on this screen.")
            }
        } catch (_: Exception) {
            ReplayUiActionResult.Failure("${action.replayTargetName()} couldn't be long-pressed on this screen.")
        } finally {
            node.recycle()
        }
    }

    fun executePressEnter(action: Action): ReplayUiActionResult {
        val target = awaitTargetNode(action) ?: focusedInputNode()
            ?: return ReplayUiActionResult.Failure(
                "Couldn't find the text field for ${action.replayTargetName()} — the app's screen changed."
            )
        return try {
            val committed = if (sdkInt >= Build.VERSION_CODES.R) {
                target.performAction(imeEnterActionId())
            } else {
                // Older Android versions do not expose ACTION_IME_ENTER. Clicking the focused
                // field is the only accessibility action available to ask its IME to commit.
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (committed) ReplayUiActionResult.Success
            else ReplayUiActionResult.Failure("The text field couldn't submit its entry. Try re-recording this step.")
        } catch (_: Exception) {
            ReplayUiActionResult.Failure("The text field couldn't submit its entry. Try re-recording this step.")
        } finally {
            target.recycle()
        }
    }

    private fun focusedInputNode(): AccessibilityNodeInfo? {
        val root = service.getRootNode() ?: return null
        return try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } finally {
            root.recycle()
        }
    }

    /** Uses the same bounded target wait as replay's tap path before treating a selector as gone. */
    private fun awaitTargetNode(action: Action): AccessibilityNodeInfo? {
        service.findTargetNode(action)?.let { return it }
        repeat(TARGET_LOOKUP_RETRIES) {
            try {
                Thread.sleep(TARGET_LOOKUP_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            service.findTargetNode(action)?.let { return it }
        }
        return null
    }

    /** Null means there was no coordinate fallback to attempt; failure retains the guard's reason. */
    private fun longPressAtRecordedPoint(action: Action): ReplayUiActionResult? {
        if (action.screenX == null || action.screenY == null) return null
        val replayContext = coordinateReplayContext()
            ?: return ReplayUiActionResult.Failure(
                "The current screen could not be checked, so this recorded long press was skipped. Re-record this step."
            )
        val decision = AutomationAccessibilityService.coordinateReplayPlan(action, replayContext)
        val plan = decision.plan ?: return ReplayUiActionResult.Failure(
            decision.failureMessage ?: "This recorded long press could not be safely replayed. Re-record this step."
        )
        return if (dispatchLongPress(plan.x, plan.y)) ReplayUiActionResult.Success
        else ReplayUiActionResult.Failure("The recorded long press could not be sent to this screen. Try again.")
    }

    private fun longPressAtNodeCentre(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (bounds.isEmpty()) return false
        return dispatchLongPress(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun dispatchLongPress(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS))
            .build()
        val finished = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val dispatched = service.dispatchGesture(
            gesture,
            object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    completed.set(true)
                    finished.countDown()
                }

                override fun onCancelled(description: GestureDescription?) {
                    finished.countDown()
                }
            },
            null
        )
        return dispatched && finished.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && completed.get()
    }

    /** Mirrors the existing tap fallback's guard before dispatching a long-press gesture. */
    private fun coordinateReplayContext(): AutomationAccessibilityService.Companion.CoordinateReplayContext? {
        val root = service.getRootNode() ?: return null
        return try {
            val display = captureDisplaySnapshotForRecording(service) ?: return null
            val windowBounds = root.window?.let { window -> Rect().also(window::getBoundsInScreen) }
            val rootBounds = Rect().also(root::getBoundsInScreen)
            AutomationAccessibilityService.Companion.CoordinateReplayContext(
                display = display,
                activePackageName = root.packageName?.toString(),
                activeWindowBounds = windowBounds,
                activeRootBounds = rootBounds,
                hasVisibleInputMethod = service.windows.orEmpty().any {
                    it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
                }
            )
        } finally {
            root.recycle()
        }
    }

    private fun Action.replayTargetName(): String {
        val label = targetText?.takeIf { it.isNotBlank() }
            ?: targetContentDescription?.takeIf { it.isNotBlank() }
            ?: targetNodeId?.takeIf { it.isNotBlank() }
            ?: target?.takeIf { it.isNotBlank() }
        return label?.let { "'$it'" } ?: "the recorded control"
    }

    private companion object {
        const val LONG_PRESS_DURATION_MS = 600L
        const val GESTURE_TIMEOUT_MS = 2_000L
        const val TARGET_LOOKUP_INTERVAL_MS = 150L
        const val TARGET_LOOKUP_RETRIES = 33
    }
}

internal sealed interface ReplayUiActionResult {
    data object Success : ReplayUiActionResult
    data class Failure(val message: String) : ReplayUiActionResult
}
