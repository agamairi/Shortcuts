package com.shortcuts.app.service

import android.view.accessibility.AccessibilityWindowInfo

/**
 * Which app a key press (Back, Enter) goes to while recording.
 *
 * Keys go to the window holding input focus. The active window is not a substitute: Android makes
 * the window last touched active, and while recording every touch lands on RepeatKit's own capture
 * overlay first, so the active window would say "RepeatKit" and the press would be thrown away as
 * one made inside RepeatKit. The overlay is never focusable, so the focused window is the real app.
 */
internal object KeyEventTarget {
    data class Window(val type: Int, val isFocused: Boolean, val packageName: String?)

    fun packageFor(windows: List<Window>, activeWindowPackage: () -> String?): String? {
        val focused = windows.filter {
            it.isFocused && it.packageName != null && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        }
        return (focused.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: focused.firstOrNull())
            ?.packageName
            ?: activeWindowPackage()
    }
}
