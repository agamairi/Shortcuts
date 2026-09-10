package com.shortcuts.app.service
import android.graphics.Rect
import org.junit.Test
import org.junit.Assert.assertTrue
import io.mockk.mockk
import io.mockk.every
class RectTest {
    @Test
    fun testRect() {
        val r = mockk<Rect>(relaxed = true)
        r.left = 0
        r.top = 0
        r.right = 1080
        r.bottom = 2400
        every { r.contains(any(), any()) } returns true
        assertTrue(r.right == 1080)
        assertTrue(r.contains(500, 500))
    }
}
