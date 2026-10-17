package com.shortcuts.app.automation.android

import com.shortcuts.app.automation.android.replay.buildGesturePlan
import com.shortcuts.app.automation.model.CapturedGesture
import com.shortcuts.app.automation.model.GestureKind
import com.shortcuts.app.automation.model.IntPoint
import com.shortcuts.app.automation.model.TimedPoint
import com.shortcuts.app.automation.model.UiOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturePlanBuilderTest {

    @Test
    fun `empty points return empty plan with 0 duration`() {
        val plan = buildGesturePlan(emptyList())
        assertEquals(0L, plan.durationMs)
        assertTrue(plan.points.isEmpty())
        assertFalse(plan.isHold)
    }

    @Test
    fun `single point defaults to 50ms tap`() {
        val points = listOf(IntPoint(100, 200))
        val plan = buildGesturePlan(points)
        assertEquals(50L, plan.durationMs)
        assertEquals(points, plan.points)
        assertFalse(plan.isHold)
    }

    @Test
    fun `single point with UiOperation LONG_PRESS uses 600ms hold`() {
        val points = listOf(IntPoint(150, 250))
        val plan = buildGesturePlan(points, operation = UiOperation.LONG_PRESS)
        assertEquals(600L, plan.durationMs)
        assertEquals(points, plan.points)
        assertTrue(plan.isHold)
    }

    @Test
    fun `single point with CapturedGesture LONG_PRESS uses 600ms hold`() {
        val points = listOf(IntPoint(150, 250))
        val gesture = CapturedGesture(
            kind = GestureKind.LONG_PRESS,
            durationMs = 800L,
            points = listOf(TimedPoint(IntPoint(150, 250), 0L))
        )
        val plan = buildGesturePlan(points, gesture = gesture)
        assertEquals(600L, plan.durationMs)
        assertTrue(plan.isHold)
    }

    @Test
    fun `multiple points default to 300ms duration`() {
        val points = listOf(IntPoint(100, 200), IntPoint(100, 600))
        val plan = buildGesturePlan(points)
        assertEquals(300L, plan.durationMs)
        assertEquals(points, plan.points)
        assertFalse(plan.isHold)
    }

    @Test
    fun `multiple points use CapturedGesture duration when provided`() {
        val points = listOf(IntPoint(100, 200), IntPoint(100, 400), IntPoint(100, 600))
        val gesture = CapturedGesture(
            kind = GestureKind.SWIPE,
            durationMs = 450L,
            points = points.map { TimedPoint(it, 0L) }
        )
        val plan = buildGesturePlan(points, gesture = gesture)
        assertEquals(450L, plan.durationMs)
        assertEquals(points, plan.points)
        assertFalse(plan.isHold)
    }

    @Test
    fun `multiple points duration is coerced to at least 1ms`() {
        val points = listOf(IntPoint(0, 0), IntPoint(10, 10))
        val gesture = CapturedGesture(
            kind = GestureKind.SWIPE,
            durationMs = 0L,
            points = emptyList()
        )
        val plan = buildGesturePlan(points, gesture = gesture)
        assertEquals(1L, plan.durationMs)
    }
}
