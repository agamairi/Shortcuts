package com.shortcuts.app.service.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayPlannerTest {
    @Test fun `durations are clamped`() {
        val tap = ClassifiedGesture(CapturedGestureKind.TAP, listOf(TouchSample(TouchAction.DOWN, 1f, 1f, 0), TouchSample(TouchAction.UP, 1f, 1f, 2)))
        val hold = ClassifiedGesture(CapturedGestureKind.LONG_PRESS, listOf(TouchSample(TouchAction.DOWN, 1f, 1f, 0), TouchSample(TouchAction.UP, 1f, 1f, 3000)))
        assertEquals(50L, RelayPlanner.plan(tap)!!.durationMs)
        assertEquals(1500L, RelayPlanner.plan(hold)!!.durationMs)
    }

    @Test fun `swipe downsampling preserves endpoints and ordered timing`() {
        val samples = (0..30).map { TouchSample(if (it == 0) TouchAction.DOWN else if (it == 30) TouchAction.UP else TouchAction.MOVE, it.toFloat(), 0f, it * 10L) }
        val plan = RelayPlanner.plan(ClassifiedGesture(CapturedGestureKind.SWIPE, samples))!!
        assertTrue(plan.points.size <= 12)
        assertEquals(0f, plan.points.first().x)
        assertEquals(30f, plan.points.last().x)
        assertTrue(plan.points.zipWithNext().all { it.first.relativeTimeMs <= it.second.relativeTimeMs })
    }
}
