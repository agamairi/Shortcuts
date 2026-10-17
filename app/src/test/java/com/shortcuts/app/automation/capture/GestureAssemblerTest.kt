package com.shortcuts.app.automation.capture

import com.shortcuts.app.automation.core.tree.r
import com.shortcuts.app.automation.core.tree.window
import com.shortcuts.app.automation.core.tree.windowSet
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.IntPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureAssemblerTest {
    @Test
    fun `swipe preserves path timing kind and duration while normalizing to its window`() {
        val targetWindow = window(APP, bounds = r(100, 200, 900, 1_800)) {
            node("android.widget.FrameLayout", id = 1) {
                node("android.widget.TextView", id = 2, text = "Row", bounds = r(200, 300, 800, 600))
            }
        }
        val before = windowSet(targetWindow)
        val captured = interaction(
            before = before,
            kind = GestureKind.SWIPE,
            down = 2_000,
            up = 2_480,
            start = IntPoint(500, 1_400),
            end = IntPoint(500, 500)
        )

        val gesture = GestureAssembler().assemble(captured, targetWindow)

        assertEquals(GestureKind.SWIPE, gesture.kind)
        assertEquals(480, gesture.durationMs)
        assertEquals(captured.points.map { it.displayPx }, gesture.points.map { it.displayPx })
        assertEquals(listOf(0L, 240L, 480L), gesture.points.map { it.elapsedMs })
        assertEquals(0.5f, gesture.points.first().pointInWindow!!.x)
        assertEquals(0.75f, gesture.points.first().pointInWindow!!.y)
        assertEquals(0.1875f, gesture.points.last().pointInWindow!!.y)
    }
}
