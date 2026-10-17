package com.shortcuts.app.service.capture

import com.shortcuts.app.service.RecorderEvent
import com.shortcuts.app.service.RecorderEventType
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderGestureMapperTest {
    @Test fun `finger moving up maps to positive forward scroll delta`() {
        val event = swipeEvent(900f, 300f)
        assertTrue(event.scrollDeltaY!! > 0)
    }
    @Test fun `finger moving down maps to negative backward scroll delta`() {
        assertTrue(swipeEvent(300f, 900f).scrollDeltaY!! < 0)
    }

    private fun swipeEvent(startY: Float, endY: Float) = RecorderEvent(
        eventType = RecorderEventType.SCROLL,
        packageName = "com.example",
        sourceText = null,
        sourceContentDescription = null,
        sourceViewId = null,
        enteredText = "",
        scrollDeltaY = RecorderGestureMapper.scrollDelta(startY, endY)
    )
}
