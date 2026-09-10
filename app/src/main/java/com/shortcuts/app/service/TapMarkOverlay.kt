package com.shortcuts.app.service

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType

/**
 * "Mark a tap" helper overlay for the recording flow.
 *
 * ## Why this exists
 *
 * Some apps (notably those rendered entirely with Jetpack Compose, like Google Messages)
 * never fire [android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED] events
 * that [AutomationRecorder] can observe. For those apps the normal passive-capture
 * recording path records nothing at all.
 *
 * Every shipped Android automation app (Tasker/AutoInput, MacroDroid, Automate) solves
 * this the same way: a semi-transparent overlay is shown, the user deliberately taps
 * **on the overlay** (aiming at the real UI visible underneath), and the overlay captures
 * that (x, y) for its own use. The touch is never relayed to the underlying app — that
 * pattern (catch → relay via `dispatchGesture`) is architecturally broken and has been
 * confirmed to fail across six different implementation attempts.
 *
 * ## How it works
 *
 * 1. [arm] is called (from the recording notification's "Mark a tap" action).
 * 2. A full-screen, semi-transparent [TYPE_ACCESSIBILITY_OVERLAY] is added via the
 *    [AutomationAccessibilityService]'s own [WindowManager] (required to avoid
 *    `BadTokenException`).
 * 3. When the user taps ([MotionEvent.ACTION_UP]), the overlay captures `rawX`/`rawY`,
 *    best-effort queries the accessibility tree at that point for node metadata, builds
 *    an [Action] in the same shape the normal recorder produces, and appends it to the
 *    active recording via [AutomationRecorder.appendManualTap].
 * 4. The overlay is removed immediately — no relay/pass-through is attempted.
 * 5. If the user doesn't tap within [AUTO_DISMISS_TIMEOUT_MS], the overlay auto-dismisses.
 */
object TapMarkOverlay {
    private const val TAG = "TapMarkOverlay"

    /**
     * Auto-dismiss timeout. If the user backs out or changes their mind, the overlay
     * tears itself down after this window so it can never get stuck armed indefinitely.
     */
    private const val AUTO_DISMISS_TIMEOUT_MS = 20_000L

    /** Maximum depth when traversing the accessibility tree to find a node at a point. */
    private const val MAX_TREE_DEPTH = 25

    // ── Fix #1: Concurrent-arm guard ──
    // We track the currently-armed overlay and its associated state so that:
    //   • A second arm() call safely tears down the previous overlay before adding a new one
    //     (replacing rather than stacking — chosen over no-op because the user's intent is
    //      clearly "I want to mark a tap now", so honouring the latest request is safest).
    //   • disarm() can tear down the overlay from outside (used by Fix #2).
    @Volatile
    private var activeOverlayState: ActiveOverlayState? = null
    private val overlayLock = Any()

    /**
     * Bundles the mutable pieces of an armed overlay so [disarm] and concurrent [arm]
     * can tear it down atomically.
     */
    private class ActiveOverlayState(
        val windowManager: WindowManager,
        val overlayView: FrameLayout,
        val autoDismissRunnable: Runnable,
        val mainHandler: Handler
    ) {
        /** Local armed flag; once false the touch listener is inert. */
        @Volatile
        var armed: Boolean = true

        fun tearDown() {
            if (!armed) return
            armed = false
            mainHandler.removeCallbacks(autoDismissRunnable)
            safeRemoveView(windowManager, overlayView)
        }
    }

    /**
     * Arms the tap-mark overlay during an active recording session.
     *
     * The overlay must be added via the [AutomationAccessibilityService]'s own context
     * and [WindowManager] because [TYPE_ACCESSIBILITY_OVERLAY] windows require a token
     * that only an accessibility service holds. Using an Activity context causes
     * `BadTokenException`.
     *
     * @param service The live accessibility service instance. If null, arming is a no-op
     *   and an error is logged (the service must be connected for recording to be active).
     */
    fun arm(service: AutomationAccessibilityService?) {
        if (service == null) {
            Log.e(TAG, "Cannot arm: accessibility service is not connected.")
            return
        }
        if (!AutomationRecorder.isRecording.value) {
            Log.w(TAG, "Cannot arm: no recording session is active.")
            return
        }

        // Fix #1: If an overlay is already showing, tear it down before adding the new one.
        synchronized(overlayLock) {
            activeOverlayState?.let { previous ->
                Log.w(TAG, "arm() called while an overlay is already active — replacing previous overlay.")
                previous.tearDown()
                activeOverlayState = null
            }
        }

        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlayView = FrameLayout(service).apply {
            setBackgroundColor(0x55000000) // Semi-transparent dark scrim
        }

        // Instruction label so the user knows what to do.
        val instructionLabel = TextView(service).apply {
            text = "Tap where you want to record"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            setPadding(32, 48, 32, 48)
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0x88000000.toInt())
        }
        val labelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        overlayView.addView(instructionLabel, labelParams)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val mainHandler = Handler(Looper.getMainLooper())

        // Create the state object early so the auto-dismiss and touch listener can reference it.
        // The autoDismissRunnable is set below after constructing the state.
        lateinit var state: ActiveOverlayState

        // Safety net: auto-dismiss after timeout so the overlay can't stay up forever.
        val autoDismissRunnable = Runnable {
            synchronized(overlayLock) {
                if (!state.armed || activeOverlayState !== state) return@synchronized
                state.armed = false
                Log.w(TAG, "Auto-dismiss: no tap within ${AUTO_DISMISS_TIMEOUT_MS}ms, removing overlay.")
                safeRemoveView(windowManager, overlayView)
                activeOverlayState = null
            }
        }

        state = ActiveOverlayState(windowManager, overlayView, autoDismissRunnable, mainHandler)

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> synchronized(overlayLock) {
                    state.armed && activeOverlayState === state
                } // Consume down so we get the matching UP.

                MotionEvent.ACTION_UP -> {
                    synchronized(overlayLock) {
                        if (!state.armed || activeOverlayState !== state) return@setOnTouchListener false
                        state.armed = false
                        mainHandler.removeCallbacks(autoDismissRunnable)
                        activeOverlayState = null
                    }

                    val x = event.rawX
                    val y = event.rawY
                    Log.i(TAG, "Tap marked at ($x, $y)")

                    // Best-effort: query the accessibility tree for a node at this point.
                    val nodeInfo = findNodeAtPoint(service, x.toInt(), y.toInt())
                    val resolvedText: String?
                    val resolvedContentDescription: String?
                    val resolvedNodeId: String?
                    val resolvedClassName: String?
                    val resolvedPackageName: String?

                    if (nodeInfo != null) {
                        val derived = deriveNodeLabel(AndroidRecorderNode(nodeInfo))
                        resolvedText = derived.text
                        resolvedContentDescription = derived.contentDescription
                        resolvedNodeId = derived.viewIdResourceName
                        resolvedClassName = nodeInfo.className?.toString()
                        resolvedPackageName = nodeInfo.packageName?.toString()
                        nodeInfo.recycle()
                    } else {
                        resolvedText = null
                        resolvedContentDescription = null
                        resolvedNodeId = null
                        resolvedClassName = null
                        resolvedPackageName = null
                    }

                    // Fix #3: screenX/screenY always reflect the user's actual tap point
                    // (event.rawX/rawY), NOT the resolved node's bounds-center. The node
                    // metadata (text, contentDescription, viewId, className) is still attached
                    // for the normal ID/text-based lookup to use first during replay;
                    // screenX/screenY remain purely the coordinate fallback, consistent with
                    // how RecorderSession.createClickAction already separates "resolved
                    // semantic identity" from "raw tap point" for auto-captured clicks.
                    val resolvedScreenX = x.toInt()
                    val resolvedScreenY = y.toInt()
                    val displaySnapshot = captureDisplaySnapshotForRecording(service)

                    // Fix #5: targetText falls back to contentDescription when text is null,
                    // consistent with the normal recorder's TAP-creation logic in
                    // RecorderSession.createClickAction (line ~256):
                    //   val targetText = event.sourceText ?: event.sourceContentDescription
                    val targetText = resolvedText ?: resolvedContentDescription

                    val action = Action(
                        actionType = ActionType.UI_AUTOMATION,
                        uiActionType = "TAP",
                        targetText = targetText,
                        targetNodeId = resolvedNodeId,
                        targetContentDescription = resolvedContentDescription,
                        targetClassName = resolvedClassName,
                        targetPackageName = resolvedPackageName,
                        screenX = resolvedScreenX,
                        screenY = resolvedScreenY,
                        recordedDisplayWidth = displaySnapshot?.width,
                        recordedDisplayHeight = displaySnapshot?.height,
                        recordedDisplayRotation = displaySnapshot?.rotation,
                        recordedDensityDpi = displaySnapshot?.densityDpi,
                        delayMillis = 500L
                    )

                    AutomationRecorder.appendManualTap(action)

                    // Remove the overlay immediately — no relay/pass-through.
                    safeRemoveView(windowManager, overlayView)
                    true
                }

                else -> false
            }
        }

        // Store the state *before* posting to the main thread so disarm() can reach it
        // even if the overlay hasn't been added to the window yet.
        synchronized(overlayLock) {
            activeOverlayState = state
        }

        // Add the overlay on the main thread (required for WindowManager).
        mainHandler.post {
            synchronized(overlayLock) {
                // A stop or a newer arm can happen before this queued work reaches the main
                // thread. Never add a superseded full-screen window in that case.
                if (activeOverlayState !== state || !state.armed || !AutomationRecorder.isRecording.value) {
                    Log.i(TAG, "Skipping stale overlay add; the recording or overlay state changed first.")
                    return@synchronized
                }
                try {
                    windowManager.addView(overlayView, params)
                    mainHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_TIMEOUT_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add overlay.", e)
                    state.armed = false
                    if (activeOverlayState === state) activeOverlayState = null
                }
            }
        }
    }

    /**
     * Fix #2: Dismisses any currently-armed overlay immediately.
     *
     * Called from [AutomationRecorder.stopRecording] so that stopping a recording
     * does not leave the overlay up blocking input (the overlay would otherwise stay
     * visible until the user taps it or the 20-second auto-dismiss fires — producing
     * a step that gets silently dropped since recording is no longer active).
     */
    fun disarm() {
        synchronized(overlayLock) {
            val state = activeOverlayState ?: return
            Log.i(TAG, "disarm(): tearing down active overlay because recording stopped.")
            state.tearDown()
            activeOverlayState = null
        }
    }

    /**
     * Best-effort search for the smallest accessibility node whose screen bounds contain
     * the given point. Walks the full tree depth-first, preferring the deepest (most
     * specific) node that contains the point — the same heuristic Android's own touch
     * exploration uses.
     *
     * The root is an independently acquired wrapper. When a descendant (or no node) is returned,
     * it is recycled here; only the returned wrapper remains caller-owned.
     */
    internal fun findNodeAtPoint(
        service: AutomationAccessibilityService,
        x: Int,
        y: Int
    ): AccessibilityNodeInfo? {
        val root = service.getRootNode() ?: return null
        val match = findDeepestNodeContaining(root, x, y, depth = 0)
        if (match !== root) root.recycle()
        return match
    }

    /**
     * Recursively finds the deepest (most specific) node whose screen bounds contain
     * ([x], [y]). Returns null when no child of [node] contains the point.
     *
     * Fix #4: Every [AccessibilityNodeInfo] obtained via [AccessibilityNodeInfo.getChild]
     * that is NOT the one ultimately returned is recycled within this method. The single
     * returned node is left for the caller to recycle after use. Previously, intermediate
     * children and superseded ancestor matches were leaked, which could exhaust the finite
     * AccessibilityNodeInfo pool over repeated use.
     */
    private fun findDeepestNodeContaining(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH) return null

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        // Use explicit field comparison matching Android's Rect.contains() exactly,
        // because Rect.contains() returns default false in plain JVM unit tests.
        val contains = bounds.left < bounds.right && bounds.top < bounds.bottom &&
                x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
        if (!contains) return null

        // Try children first — a deeper match is always more specific.
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val deeperMatch = findDeepestNodeContaining(child, x, y, depth + 1)
            if (deeperMatch != null) {
                // Fix #4: `child` itself may or may not be the deeperMatch (it is when
                // the child was the leaf hit; it isn't when a grandchild matched). If
                // child != deeperMatch, child is an intermediate ancestor we no longer
                // need — recycle it. The remaining siblings are also recycled below.
                if (child !== deeperMatch) {
                    child.recycle()
                }
                // Recycle all remaining un-visited siblings before returning.
                for (j in (i + 1) until node.childCount) {
                    node.getChild(j)?.recycle()
                }
                return deeperMatch
            }
            // This child didn't contain the point (or had no deeper match) — recycle it.
            child.recycle()
        }

        // No child contained the point (or node is a leaf); this node is the best match.
        return node
    }

    private fun safeRemoveView(windowManager: WindowManager, view: android.view.View) {
        try {
            if (view.isAttachedToWindow) {
                windowManager.removeView(view)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove overlay view (may already be removed).", e)
        }
    }
}
