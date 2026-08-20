package com.shortcuts.app.ui.screens

import android.content.Context
import com.shortcuts.app.util.AccessibilityStatusChecker

/** Keeps recorder start decisions testable without relying on the persisted disclosure opt-in. */
class RecorderSessionController(
    private val isAccessibilityServiceActive: (Context) -> Boolean =
        AccessibilityStatusChecker::isAccessibilityServiceActive
) {
    /** Returns false without invoking [onStart] when the real service is unavailable. */
    fun startIfServiceActive(context: Context, onStart: () -> Unit): Boolean {
        if (!isAccessibilityServiceActive(context)) return false
        onStart()
        return true
    }
}
