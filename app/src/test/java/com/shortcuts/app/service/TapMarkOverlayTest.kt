package com.shortcuts.app.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test


import org.junit.After
import org.junit.Before

class TapMarkOverlayTest {

    @Test
    fun `point lookup recycles its independently acquired root when it returns a descendant`() {
        val r = Rect()
        r.left = 0; r.top = 0; r.right = 100; r.bottom = 100
        val service = spyk(AutomationAccessibilityService())
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val descendant = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { service.getRootNode() } returns root
        every { root.childCount } returns 1
        every { root.getChild(0) } returns descendant
        every { root.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().apply { left = 0; top = 0; right = 100; bottom = 100 }
        }
        every { descendant.childCount } returns 0
        every { descendant.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().apply { left = 10; top = 10; right = 90; bottom = 90 }
        }

        val found = TapMarkOverlay.findNodeAtPoint(service, x = 50, y = 50)

        assertEquals(descendant, found)
        verify(exactly = 1) { root.recycle() }
        verify(exactly = 0) { descendant.recycle() }
        found?.recycle()
    }
}
