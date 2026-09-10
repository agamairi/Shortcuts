package com.shortcuts.app.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Small, non-interactive recording indicator shown above the foreground app.
 *
 * This window never receives input: semantic events remain the recorder's source of truth, and
 * the user's taps, scrolling, and keyboard input continue directly to the app underneath.
 */
object RecordingHudOverlay {
    private val lock = Any()

    @Volatile
    private var activeState: OverlayState? = null

    private class OverlayState(
        val windowManager: WindowManager,
        val view: TextView,
        val handler: Handler
    ) {
        @Volatile var added = false
    }

    fun show(service: AutomationAccessibilityService?, stepCount: Int) {
        if (service == null) return
        val handler = Handler(Looper.getMainLooper())
        val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = service.resources.displayMetrics.density
        val view = TextView(service).apply {
            text = label(stepCount)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(
                (14 * density).toInt(),
                (8 * density).toInt(),
                (14 * density).toInt(),
                (8 * density).toInt()
            )
            background = GradientDrawable().apply {
                setColor(0xDD202124.toInt())
                cornerRadius = 20 * density
                setStroke((1 * density).coerceAtLeast(1f).toInt(), 0x55FFFFFF)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val state = OverlayState(windowManager, view, handler)

        synchronized(lock) {
            removeLocked(activeState)
            activeState = state
        }

        handler.post {
            synchronized(lock) {
                if (activeState !== state || !AutomationRecorder.isRecording.value) return@synchronized
                runCatching {
                    windowManager.addView(view, layoutParams((12 * density).toInt()))
                    state.added = true
                }
            }
        }
    }

    fun update(stepCount: Int) {
        val state = activeState ?: return
        state.handler.post {
            if (activeState === state) state.view.text = label(stepCount)
        }
    }

    fun hide() {
        synchronized(lock) {
            removeLocked(activeState)
            activeState = null
        }
    }

    internal fun windowFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    private fun layoutParams(margin: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        windowFlags(),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = margin
        y = margin
    }

    private fun removeLocked(state: OverlayState?) {
        if (state == null || !state.added) return
        state.added = false
        state.handler.post { runCatching { state.windowManager.removeView(state.view) } }
    }

    private fun label(stepCount: Int): String {
        val noun = if (stepCount == 1) "step" else "steps"
        return "● Recording · $stepCount $noun"
    }
}
