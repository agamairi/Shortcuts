package com.shortcuts.app.service.capture

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.shortcuts.app.service.AndroidRecorderNode
import com.shortcuts.app.service.AutomationAccessibilityService
import com.shortcuts.app.service.AutomationRecorder
import com.shortcuts.app.service.RecorderEvent
import com.shortcuts.app.service.RecorderEventType
import com.shortcuts.app.service.captureDisplaySnapshotForRecording
import com.shortcuts.app.service.deriveNodeLabel
import java.util.concurrent.atomic.AtomicBoolean

/** Always-on, transparent accessibility overlay which buffers a physical touch, records it, then relays it. */
object TouchCaptureOverlay {
    private const val TAG = "TouchCapture"
    private const val STEP_ASIDE_WATCHDOG_MS = 110L
    private const val STEP_ASIDE_AFTER_FRAME_MS = 80L
    private const val RELAY_TIMEOUT_MS = 1_500L
    private const val SETTLE_MS = 40L
    private const val MAX_RELAY_ATTEMPTS = 4
    private const val RELAY_RETRY_DELAY_MS = 50L
    private val lock = Any()
    private var state: State? = null

    private class State(
        val service: AutomationAccessibilityService,
        val windowManager: WindowManager,
        val view: CaptureView,
        var params: WindowManager.LayoutParams,
        val handler: Handler
    ) {
        var added = false
        var touchable = false
        var relayInFlight = false
        /** Set when a relayed gesture lands on this overlay because the step-aside hasn't applied yet. */
        var selfHitDuringRelay = false
    }

    fun show(service: AutomationAccessibilityService?, overlayMode: Boolean) {
        if (service == null || !AutomationRecorder.isRecording.value) return
        if (!overlayMode) return
        synchronized(lock) {
            val current = state
            if (current?.service === service && current.added) {
                recomputeLocked(current)
                return
            }
            hideLocked()
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val handler = Handler(Looper.getMainLooper())
            val view = CaptureView(service, handler)
            val params = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                baseFlags(), PixelFormat.TRANSLUCENT
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START }
            val fresh = State(service, wm, view, params, handler)
            view.owner = fresh
            state = fresh
            handler.post {
                synchronized(lock) {
                    if (state !== fresh || !AutomationRecorder.isRecording.value) return@synchronized
                    recomputeLocked(fresh)
                    runCatching {
                        fresh.windowManager.addView(fresh.view, fresh.params)
                        fresh.added = true
                        recomputeLocked(fresh)
                    }.onFailure { Log.e(TAG, "Unable to add capture overlay", it) }
                }
            }
        }
    }

    fun hide() = synchronized(lock) { hideLocked() }

    fun onWindowsChanged(service: AutomationAccessibilityService?) {
        synchronized(lock) {
            val current = state ?: return
            if (service != null && current.service !== service) return
            if (!current.relayInFlight) recomputeLocked(current)
        }
    }

    private fun hideLocked() {
        val current = state ?: return
        state = null
        current.relayInFlight = false
        current.handler.removeCallbacksAndMessages(null)
        if (current.added) runCatching { current.windowManager.removeViewImmediate(current.view) }
        current.added = false
    }

    private fun recomputeLocked(current: State) {
        val input = windowInput(current.service, current.windowManager) ?: return setTouchableLocked(current, null)
        val region = OverlayRegionPolicy.region(
            input.width, input.height, input.insets, input.imeBounds, input.shadeOpen,
            input.topAppPackage, current.service.packageName
        )
        setTouchableLocked(current, (region as? OverlayRegion.Touchable)?.rect)
    }

    private fun setTouchableLocked(current: State, rect: CaptureRect?) {
        val shouldTouch = rect != null && !current.relayInFlight
        if (rect == null) {
            current.params.width = 1
            current.params.height = 1
            current.params.x = 0
            current.params.y = 0
        } else {
            current.params.width = rect.width
            current.params.height = rect.height
            current.params.x = rect.left
            current.params.y = rect.top
        }
        current.params.flags = baseFlags() or if (shouldTouch) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        current.touchable = shouldTouch
        if (current.added) runCatching { current.windowManager.updateViewLayout(current.view, current.params) }
    }

    private fun beginRelay(current: State, gesture: ClassifiedGesture, hit: HitSnapshot?) {
        val plan = RelayPlanner.plan(gesture) ?: return
        current.relayInFlight = true
        setTouchableLocked(current, null)
        val asideAt = SystemClock.uptimeMillis()
        val hasRun = AtomicBoolean(false)
        fun dispatchOnce() {
            if (!hasRun.compareAndSet(false, true)) return
            relay(current, plan, asideAt, gesture, hit, attempt = 1)
        }
        Choreographer.getInstance().postFrameCallback { current.handler.postDelayed({ dispatchOnce() }, STEP_ASIDE_AFTER_FRAME_MS) }
        current.handler.postDelayed({ dispatchOnce() }, STEP_ASIDE_WATCHDOG_MS)
    }

    private fun relay(current: State, plan: RelayStrokePlan, asideAt: Long, gesture: ClassifiedGesture, hit: HitSnapshot?, attempt: Int) {
        if (state !== current || !AutomationRecorder.isRecording.value) return restore(current)
        val path = Path().apply {
            val first = plan.points.firstOrNull() ?: return@apply
            moveTo(first.x, first.y)
            plan.points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, plan.durationMs))
            .build()
        val finished = AtomicBoolean(false)
        var relayDispatched = false
        fun finish(completed: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            if (current.selfHitDuringRelay && attempt < MAX_RELAY_ATTEMPTS) {
                // The overlay caught its own relay: the window manager hasn't applied the
                // non-touchable flag yet. Wait a little longer and send the same gesture again.
                current.selfHitDuringRelay = false
                current.handler.postDelayed({ relay(current, plan, asideAt, gesture, hit, attempt + 1) }, RELAY_RETRY_DELAY_MS)
                return
            }
            AutomationRecorder.onCaptureRelayFinished(SystemClock.uptimeMillis())
            val total = SystemClock.uptimeMillis() - (gesture.down?.uptimeMs ?: SystemClock.uptimeMillis())
            Log.d(TAG, "kind=${gesture.kind} points=${plan.points.size} downToUpMs=${gesture.heldMillis} stepAsideMs=${SystemClock.uptimeMillis() - asideAt} attempts=$attempt selfHit=${current.selfHitDuringRelay} relayDispatched=$relayDispatched relay${if (completed) "Completed" else "Cancelled"}=true totalLatencyMs=$total window=${hit?.packageName} node=${hit?.className}")
            current.handler.postDelayed({ restore(current) }, SETTLE_MS)
        }
        if (attempt == 1) AutomationRecorder.onCaptureRelayStarted(SystemClock.uptimeMillis())
        current.selfHitDuringRelay = false
        val dispatched = runCatching {
            current.service.dispatchGesture(description, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) = finish(true)
                override fun onCancelled(description: GestureDescription?) = finish(false)
            }, current.handler)
        }.getOrDefault(false)
        relayDispatched = dispatched
        if (!dispatched) finish(false)
        current.handler.postDelayed({ finish(false) }, plan.durationMs + RELAY_TIMEOUT_MS)
    }

    private fun restore(current: State) = synchronized(lock) {
        if (state !== current) return@synchronized
        current.relayInFlight = false
        recomputeLocked(current)
    }

    private class CaptureView(context: Context, private val handler: Handler) : View(context) {
        lateinit var owner: State
        private val classifier = GestureClassifier(
            ViewConfiguration.get(context).scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong()
        )
        private var hit: HitSnapshot? = null
        private var v2Snapshot: com.shortcuts.app.automation.core.tree.WindowSetSnapshot? = null
        private var longPressTick: Runnable? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 90, 180, 255); strokeWidth = 8f; style = Paint.Style.STROKE }
        private val trail = Path()
        private var rippleX = 0f
        private var rippleY = 0f
        private var feedbackUntil = 0L

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (owner.relayInFlight) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) owner.selfHitDuringRelay = true
                return false
            }
            if (!AutomationRecorder.isRecording.value) return false
            val sample = TouchSample(
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> TouchAction.DOWN
                    MotionEvent.ACTION_MOVE -> TouchAction.MOVE
                    MotionEvent.ACTION_UP -> TouchAction.UP
                    MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
                    else -> TouchAction.CANCEL
                }, event.rawX, event.rawY, event.eventTime
            )
            if (sample.action == TouchAction.DOWN) {
                v2Snapshot = if (com.shortcuts.app.automation.android.EngineV2.enabled) {
                    com.shortcuts.app.automation.android.EngineV2.snapshotSource?.snapshot()
                } else null
                hit = hitTest(owner.service, sample.x.toInt(), sample.y.toInt())
                trail.reset(); trail.moveTo(event.x, event.y)
                scheduleLongPressTick()
            } else if (sample.action == TouchAction.MOVE) {
                trail.lineTo(event.x, event.y); feedbackUntil = SystemClock.uptimeMillis() + 250L; invalidate()
            }
            val classified = classifier.add(sample)
            if (sample.action == TouchAction.MOVE && classifier.classify().kind == CapturedGestureKind.SWIPE) cancelLongPressTick()
            if (classified != null) {
                cancelLongPressTick()
                if (classified.kind == CapturedGestureKind.CANCELLED) {
                    v2Snapshot = null
                    Toast.makeText(context, "Multi-touch is not recorded", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "kind=CANCELLED relayDispatched=false")
                } else {
                    val gestureKind = when (classified.kind) {
                        CapturedGestureKind.TAP -> com.shortcuts.app.automation.model.GestureKind.TAP
                        CapturedGestureKind.LONG_PRESS -> com.shortcuts.app.automation.model.GestureKind.LONG_PRESS
                        CapturedGestureKind.SWIPE -> com.shortcuts.app.automation.model.GestureKind.SWIPE
                        CapturedGestureKind.CANCELLED -> null
                    }
                    if (gestureKind != null) {
                        com.shortcuts.app.automation.android.capture.V2CaptureBridge.onGestureClassified(
                            kind = gestureKind,
                            samples = classified.samples,
                            beforeSnapshot = v2Snapshot,
                            ownPackage = owner.service.packageName
                        )
                    }
                    v2Snapshot = null
                    record(classified, hit)
                    rippleX = event.x; rippleY = event.y; feedbackUntil = SystemClock.uptimeMillis() + 300L; invalidate()
                    beginRelay(owner, classified, hit)
                }
            }
            return true
        }

        private fun scheduleLongPressTick() {
            cancelLongPressTick()
            longPressTick = Runnable { if (!owner.relayInFlight && classifier.classify().kind != CapturedGestureKind.SWIPE) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            handler.postDelayed(longPressTick!!, ViewConfiguration.getLongPressTimeout().toLong())
        }
        private fun cancelLongPressTick() { longPressTick?.let(handler::removeCallbacks); longPressTick = null }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (SystemClock.uptimeMillis() < feedbackUntil) {
                canvas.drawPath(trail, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(rippleX, rippleY, 22f, paint)
                paint.style = Paint.Style.STROKE
                postInvalidateOnAnimation()
            }
        }
    }

    private data class HitSnapshot(val packageName: String, val text: String?, val description: String?, val viewId: String?, val className: String?, val editable: Boolean, val windowId: Int?)
    private data class WindowInput(val width: Int, val height: Int, val insets: CaptureInsets, val imeBounds: CaptureRect?, val shadeOpen: Boolean, val topAppPackage: String?)

    private fun record(gesture: ClassifiedGesture, hit: HitSnapshot?) {
        val down = gesture.down ?: return
        val up = gesture.up ?: down
        val type = when (gesture.kind) { CapturedGestureKind.TAP -> RecorderEventType.CLICK; CapturedGestureKind.LONG_PRESS -> RecorderEventType.LONG_PRESS; CapturedGestureKind.SWIPE -> RecorderEventType.SCROLL; CapturedGestureKind.CANCELLED -> return }
        val event = RecorderEvent(type, hit?.packageName.orEmpty(), hit?.text, hit?.description, hit?.viewId, "", hit?.className, hit?.editable ?: false, down.x.toInt(), down.y.toInt(), captureDisplaySnapshotForRecording(AutomationAccessibilityService.instance ?: return), up.uptimeMs, scrollDeltaX = if (type == RecorderEventType.SCROLL) RecorderGestureMapper.scrollDelta(down.x, up.x) else null, scrollDeltaY = if (type == RecorderEventType.SCROLL) RecorderGestureMapper.scrollDelta(down.y, up.y) else null, windowId = hit?.windowId)
        AutomationRecorder.recordCapturedGesture(event)
    }

    private fun hitTest(service: AutomationAccessibilityService, x: Int, y: Int): HitSnapshot? {
        val window = service.windows.orEmpty().filter { candidate ->
            val bounds = Rect().also(candidate::getBoundsInScreen)
            candidate.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                windowPackage(candidate) != service.packageName && bounds.contains(x, y)
        }.maxByOrNull { it.layer } ?: return null
        val root = window.root ?: return null
        val leaf = findDeepestReverse(root, x, y) ?: run { root.recycle(); return null }
        if (leaf !== root) root.recycle()
        var current: AccessibilityNodeInfo? = leaf
        var selected: AccessibilityNodeInfo? = null
        while (current != null) {
            val bounds = Rect().also(current::getBoundsInScreen)
            val actionable = current.isClickable || current.isLongClickable || current.isEditable || current.isCheckable || current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (actionable && bounds.width().toLong() * bounds.height().toLong() * 5L < windowArea(window) * 4L) { selected = current; break }
            val parent = current.parent
            if (current !== leaf) current.recycle()
            current = parent
        }
        val target = selected ?: leaf
        val label = deriveNodeLabel(AndroidRecorderNode(target))
        val result = HitSnapshot(target.packageName?.toString().orEmpty(), label.text, label.contentDescription, label.viewIdResourceName, target.className?.toString(), target.isEditable, window.id)
        if (target !== leaf) target.recycle()
        leaf.recycle()
        return result
    }

    private fun windowArea(window: AccessibilityWindowInfo): Long { val r = Rect().also(window::getBoundsInScreen); return r.width().toLong() * r.height().toLong() }
    private fun findDeepestReverse(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect().also(node::getBoundsInScreen)
        if (!node.isVisibleToUser) return null
        val containsPoint = bounds.contains(x, y)
        if (!containsPoint && node.childCount == 0) return null
        for (index in node.childCount - 1 downTo 0) {
            val child = node.getChild(index) ?: continue
            val match = findDeepestReverse(child, x, y)
            if (match != null) { if (match !== child) child.recycle(); return match }
            child.recycle()
        }
        return node.takeIf { containsPoint }
    }

    private fun windowInput(service: AutomationAccessibilityService, wm: WindowManager): WindowInput? = runCatching {
        val (width, height, inset) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val b = metrics.bounds
            val i = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
            Triple(b.width(), b.height(), CaptureInsets(i.top, i.bottom))
        } else {
            val dm = service.resources.displayMetrics
            val status = resourceDimen(service, "status_bar_height")
            val nav = resourceDimen(service, "navigation_bar_height")
            Triple(dm.widthPixels, dm.heightPixels, CaptureInsets(status, nav))
        }
        val windows = service.windows.orEmpty()
        val ime = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w -> Rect().also(w::getBoundsInScreen).let { CaptureRect(it.left, it.top, it.right, it.bottom) } }
        val shade = windows.any { isNotificationShade(it, height) }
        val topAppPackage = OverlayRegionPolicy.topAppPackage(windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .map { CaptureApplicationWindow(it.layer, windowPackage(it)) }
            .toList())
        WindowInput(width, height, inset, ime, shade, topAppPackage)
    }.getOrNull()

    private fun resourceDimen(context: Context, name: String): Int { val id = context.resources.getIdentifier(name, "dimen", "android"); return if (id == 0) 0 else context.resources.getDimensionPixelSize(id) }
    private fun windowPackage(window: AccessibilityWindowInfo): String? {
        val root = window.root ?: return null
        return try { root.packageName?.toString() } finally { root.recycle() }
    }
    private fun isNotificationShade(window: AccessibilityWindowInfo, displayHeight: Int): Boolean {
        if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM || windowPackage(window) != "com.android.systemui") return false
        val bounds = Rect().also(window::getBoundsInScreen)
        return bounds.height() > displayHeight / 3
    }
    private fun baseFlags(): Int = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
}
