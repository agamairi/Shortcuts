package com.shortcuts.app.service.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureClassifierTest {
    private fun classify(vararg samples: TouchSample) = GestureClassifier(10f, 500L).also { c -> samples.forEach(c::add) }.classify()

    @Test fun tap() = assertEquals(CapturedGestureKind.TAP, classify(TouchSample(TouchAction.DOWN, 10f, 10f, 0), TouchSample(TouchAction.UP, 10f, 10f, 80)).kind)
    @Test fun `slow tap under long press is a tap`() = assertEquals(CapturedGestureKind.TAP, classify(TouchSample(TouchAction.DOWN, 0f, 0f, 0), TouchSample(TouchAction.UP, 0f, 0f, 499)).kind)
    @Test fun `long press`() = assertEquals(CapturedGestureKind.LONG_PRESS, classify(TouchSample(TouchAction.DOWN, 0f, 0f, 0), TouchSample(TouchAction.UP, 0f, 0f, 500)).kind)
    @Test fun swipe() = assertEquals(CapturedGestureKind.SWIPE, classify(TouchSample(TouchAction.DOWN, 0f, 0f, 0), TouchSample(TouchAction.MOVE, 11f, 0f, 10), TouchSample(TouchAction.UP, 11f, 0f, 20)).kind)
    @Test fun `tiny jitter stays tap`() = assertEquals(CapturedGestureKind.TAP, classify(TouchSample(TouchAction.DOWN, 0f, 0f, 0), TouchSample(TouchAction.MOVE, 6f, 6f, 30), TouchSample(TouchAction.UP, 6f, 6f, 60)).kind)
    @Test fun `second pointer cancels`() = assertEquals(CapturedGestureKind.CANCELLED, classify(TouchSample(TouchAction.DOWN, 0f, 0f, 0), TouchSample(TouchAction.POINTER_DOWN, 0f, 0f, 10)).kind)
}
