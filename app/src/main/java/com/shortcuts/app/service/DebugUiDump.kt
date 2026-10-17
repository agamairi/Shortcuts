package com.shortcuts.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.PrintWriter

/**
 * Debug builds only: prints the on-screen tree in uiautomator's XML shape for the E2E harness, via
 * `adb shell dumpsys activity service <this service> rk-ui`.
 *
 * The harness cannot use `uiautomator dump` while RepeatKit runs: Android unbinds other accessibility
 * services for as long as a UiAutomation is connected, so every dump restarted RepeatKit's service in
 * the middle of recordings and runs. Reading the tree through the service itself disturbs nothing.
 */
internal object DebugUiDump {
    private const val MAX_DEPTH = 80

    fun write(windows: List<AccessibilityWindowInfo>, out: PrintWriter) {
        out.println("<hierarchy rotation=\"0\">")
        windows
            .filter {
                it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD
            }
            // App windows first, so a locator matches the app before the status or navigation bar.
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    .thenByDescending { it.layer }
            )
            .forEach { window ->
                val root = runCatching { window.root }.getOrNull() ?: return@forEach
                writeNode(root, out, depth = 0)
                root.recycle()
            }
        out.println("</hierarchy>")
    }

    private fun writeNode(node: AccessibilityNodeInfo, out: PrintWriter, depth: Int) {
        val bounds = Rect().also(node::getBoundsInScreen)
        out.print("<node")
        attribute(out, "text", node.text)
        attribute(out, "resource-id", node.viewIdResourceName)
        attribute(out, "class", node.className)
        attribute(out, "package", node.packageName)
        attribute(out, "content-desc", node.contentDescription)
        attribute(out, "clickable", node.isClickable.toString())
        attribute(out, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        out.print(">")
        if (depth < MAX_DEPTH) {
            for (index in 0 until node.childCount) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                writeNode(child, out, depth + 1)
                child.recycle()
            }
        }
        out.println("</node>")
    }

    private fun attribute(out: PrintWriter, name: String, value: CharSequence?) {
        out.print(" $name=\"")
        out.print(escape(value?.toString().orEmpty()))
        out.print("\"")
    }

    internal fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when {
                ch == '&' -> append("&amp;")
                ch == '<' -> append("&lt;")
                ch == '>' -> append("&gt;")
                ch == '"' -> append("&quot;")
                ch == '\n' -> append("&#10;")
                ch < ' ' -> Unit // other control characters are not valid XML
                else -> append(ch)
            }
        }
    }
}
