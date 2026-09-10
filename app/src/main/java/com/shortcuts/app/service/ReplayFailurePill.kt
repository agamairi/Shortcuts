package com.shortcuts.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * A small, non-interactive system overlay for an execution result the user needs to act on.
 *
 * It is intentionally owned by the connected accessibility service: a widget may start a
 * shortcut while RepeatKit has no activity, and TYPE_ACCESSIBILITY_OVERLAY needs that service's
 * window token. Presentation is always best effort and cannot affect replay completion.
 */
object ReplayFailurePill {
    private const val TAG = "ReplayFailurePill"
    private const val DISMISS_AFTER_MILLIS = 4_000L
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()
    private var activePill: ActivePill? = null

    fun show(service: AutomationAccessibilityService?, message: String) {
        if (service == null || message.isBlank()) {
            Log.w(TAG, "Unable to show replay failure pill: accessibility service is unavailable.")
            return
        }
        runCatching {
            mainHandler.post { addPillSafely(service, message) }
        }.onFailure { Log.w(TAG, "Unable to schedule replay failure pill.", it) }
    }

    /** Maps every executor failure reason to useful, user-facing wording. */
    fun messageFor(reason: FailureReason, detail: String): String = when (reason) {
        FailureReason.UNSUPPORTED_TARGET -> "This recorded control is no longer supported — $detail"
        FailureReason.INVALID_STATE -> "This step is incomplete — $detail"
        FailureReason.PLATFORM_RESTRICTION -> "Android needs your help for this step — $detail"
        FailureReason.ACTIVITY_UNAVAILABLE -> "Couldn't open the required app — $detail"
        FailureReason.DEVICE_UNAVAILABLE -> "This device can't complete the step — $detail"
        FailureReason.APP_NOT_FOUND -> "The required app is missing — $detail"
        FailureReason.NETWORK_ERROR -> "The online step failed — $detail"
        FailureReason.ACCESSIBILITY_UNAVAILABLE -> "Accessibility service is off — $detail"
        FailureReason.UI_AUTOMATION_FAILED -> detail
    }

    /** Maps every admission/start failure reason to useful, user-facing wording. */
    fun messageFor(reason: ShortcutRunFailureReason, detail: String): String = when (reason) {
        ShortcutRunFailureReason.SHORTCUT_LOOKUP_FAILED -> "Couldn't load this shortcut — $detail"
        ShortcutRunFailureReason.FOREGROUND_SERVICE_START_FAILED -> "Couldn't start replay — $detail"
        ShortcutRunFailureReason.EDITOR_RUN_START_FAILED -> "Couldn't start this draft replay — $detail"
    }

    fun messageFor(result: RunResult): String {
        val index = result.firstIncompleteIndex
        if (index < 0) return ""
        val stepNumber = index + 1
        val total = result.steps.size
        val stopped = result.steps.drop(stepNumber).any { it is StepResult.Skipped }
        val prefix = if (stopped) {
            "Step $stepNumber of $total couldn't be replayed, so the shortcut stopped"
        } else {
            "Step $stepNumber of $total couldn't be replayed"
        }
        val detail = when (val step = result.steps[index]) {
            is StepResult.Failed -> messageFor(step.reason, step.userMessage)
            is StepResult.NeedsPermission -> "${step.permission} permission is required."
            is StepResult.Skipped -> step.why
            StepResult.Success -> return ""
        }
        return "$prefix — $detail"
    }

    private fun addPillSafely(service: AutomationAccessibilityService, message: String) {
        runCatching {
            synchronized(lock) {
                activePill?.tearDown()
                val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val textView = TextView(service).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    maxLines = 3
                    setPadding(dp(service, 20), dp(service, 12), dp(service, 20), dp(service, 12))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(service, 28).toFloat()
                        setColor(0xEE282828.toInt())
                    }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = dp(service, 48)
                }
                val state = ActivePill(windowManager, textView)
                activePill = state
                try {
                    windowManager.addView(textView, params)
                    mainHandler.postDelayed({
                        synchronized(lock) {
                            if (activePill === state) {
                                state.tearDown()
                                activePill = null
                            }
                        }
                    }, DISMISS_AFTER_MILLIS)
                } catch (error: Exception) {
                    if (activePill === state) activePill = null
                    Log.w(TAG, "Unable to add replay failure pill.", error)
                }
            }
        }.onFailure { Log.w(TAG, "Unable to present replay failure pill.", it) }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class ActivePill(
        private val windowManager: WindowManager,
        private val view: TextView
    ) {
        fun tearDown() {
            runCatching { windowManager.removeViewImmediate(view) }
                .onFailure { Log.w(TAG, "Unable to remove replay failure pill.", it) }
        }
    }
}
