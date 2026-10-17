package com.shortcuts.app.automation.core.tree

import com.shortcuts.app.automation.model.WindowType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HitTestTest {
    @Test
    fun `topmost eligible window wins`() {
        val lower = buttonWindow(pkg = "lower", id = 1, layer = 1, nodeId = 11)
        val upper = buttonWindow(pkg = "upper", id = 2, layer = 9, nodeId = 22)

        val result = windowSet(lower, upper).hitTest(50, 50)

        assertEquals(2, result?.window?.windowId)
        assertEquals(22, result?.target?.nodeId)
    }

    @Test
    fun `accessibility overlays and own package windows are skipped`() {
        val app = buttonWindow(pkg = "target.app", id = 1, layer = 1, nodeId = 11)
        val own = buttonWindow(pkg = "com.shortcuts.app", id = 2, layer = 8, nodeId = 22)
        val overlay = buttonWindow(
            pkg = "island.overlay",
            id = 3,
            layer = 10,
            nodeId = 33,
            type = WindowType.ACCESSIBILITY_OVERLAY
        )

        val result = windowSet(app, own, overlay).hitTest(
            50,
            50,
            excludePackages = setOf("com.shortcuts.app")
        )

        assertEquals("target.app", result?.window?.packageName)
        assertEquals(11, result?.target?.nodeId)
    }

    @Test
    fun `reverse sibling order picks visually top sibling`() {
        val target = window("target.app") {
            node("Root", id = 1) {
                node("Bottom", id = 2, bounds = r(0, 0, 100, 100), clickable = true)
                node("Top", id = 3, bounds = r(0, 0, 100, 100), clickable = true)
            }
        }

        assertEquals(3, windowSet(target).hitTest(50, 50)?.target?.nodeId)
    }

    @Test
    fun `invisible nodes are skipped`() {
        val target = window("target.app") {
            node("Root", id = 1) {
                node("Visible", id = 2, bounds = r(0, 0, 100, 100), clickable = true)
                node("HiddenTop", id = 3, bounds = r(0, 0, 100, 100), visible = false, clickable = true)
            }
        }

        assertEquals(2, windowSet(target).hitTest(50, 50)?.target?.nodeId)
    }

    @Test
    fun `nearest clickable ancestor is selected over decorative leaf`() {
        val target = window("target.app") {
            node("Root", id = 1) {
                node("ClickableRow", id = 2, bounds = r(0, 0, 300, 100), clickable = true) {
                    node("Label", id = 3, text = "Save", bounds = r(20, 20, 100, 60))
                }
            }
        }

        val result = windowSet(target).hitTest(50, 40)

        assertEquals(2, result?.target?.nodeId)
        assertEquals(3, result?.leaf?.nodeId)
    }

    @Test
    fun `full-window clickable scrim is rejected by area guard`() {
        val scrim = window("target.app", bounds = r(0, 0, 100, 100)) {
            node("Scrim", id = 1, bounds = r(0, 0, 100, 100), clickable = true)
        }

        assertNull(windowSet(scrim, width = 100, height = 100).hitTest(50, 50))
    }

    @Test
    fun `point outside every window returns null`() {
        val target = buttonWindow(pkg = "target.app", id = 1, layer = 1, nodeId = 11)

        assertNull(windowSet(target).hitTest(5_000, 5_000))
    }

    private fun buttonWindow(
        pkg: String,
        id: Int,
        layer: Int,
        nodeId: Int,
        type: WindowType = WindowType.APPLICATION
    ) = window(pkg, id = id, layer = layer, type = type) {
        node("android.widget.Button", id = nodeId, text = "Save", bounds = r(0, 0, 100, 100), clickable = true)
    }
}
