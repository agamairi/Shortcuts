package com.shortcuts.app.service

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyEventTargetTest {

    private val overlay = KeyEventTarget.Window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, isFocused = false, packageName = null)

    @Test
    fun `key goes to the focused app even when RepeatKit's overlay was touched last`() {
        val windows = listOf(
            overlay,
            KeyEventTarget.Window(AccessibilityWindowInfo.TYPE_APPLICATION, isFocused = true, packageName = "com.repeatkit.fixtures")
        )

        val target = KeyEventTarget.packageFor(windows) { "com.shortcuts.app.dev" }

        assertEquals("com.repeatkit.fixtures", target)
    }

    @Test
    fun `a press inside RepeatKit itself is still reported as RepeatKit`() {
        val windows = listOf(
            KeyEventTarget.Window(AccessibilityWindowInfo.TYPE_APPLICATION, isFocused = true, packageName = "com.shortcuts.app.dev")
        )

        assertEquals("com.shortcuts.app.dev", KeyEventTarget.packageFor(windows) { null })
    }

    @Test
    fun `an overlay is never the key target even if reported focused`() {
        val windows = listOf(
            KeyEventTarget.Window(AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, isFocused = true, packageName = "com.shortcuts.app.dev"),
            KeyEventTarget.Window(AccessibilityWindowInfo.TYPE_APPLICATION, isFocused = true, packageName = "com.android.settings")
        )

        assertEquals("com.android.settings", KeyEventTarget.packageFor(windows) { null })
    }

    @Test
    fun `falls back to the active window when no window reports focus`() {
        val windows = listOf(
            KeyEventTarget.Window(AccessibilityWindowInfo.TYPE_APPLICATION, isFocused = false, packageName = null)
        )

        assertEquals("com.android.chrome", KeyEventTarget.packageFor(windows) { "com.android.chrome" })
    }
}
