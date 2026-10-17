package com.shortcuts.app.automation.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Resources
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySnapshotAdapterTest {
    @Test
    fun `a broken window is skipped without losing healthy windows`() {
        val service = mockk<AccessibilityService>(relaxed = true)
        val resources = mockk<Resources>()
        val metrics = DisplayMetrics().apply {
            widthPixels = 1080
            heightPixels = 2400
            density = 2.625f
        }
        every { service.resources } returns resources
        every { resources.displayMetrics } returns metrics
        every { service.getSystemService(Context.WINDOW_SERVICE) } returns null

        val broken = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { broken.layer } returns 10
        every { broken.root } throws IllegalStateException("stale window")

        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns "com.android.settings"
        every { root.className } returns "android.widget.FrameLayout"
        every { root.childCount } returns 0
        every { root.actionList } returns emptyList()
        every { root.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(0, 0, 1080, 2400)
        }

        val healthy = mockk<AccessibilityWindowInfo>(relaxed = true)
        every { healthy.id } returns 42
        every { healthy.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { healthy.layer } returns 1
        every { healthy.isActive } returns true
        every { healthy.isFocused } returns true
        every { healthy.root } returns root
        every { healthy.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(0, 0, 1080, 2400)
        }
        var windowReads = 0
        every { service.windows } answers {
            windowReads += 1
            if (windowReads < 3) emptyList() else listOf(broken, healthy)
        }

        val result = AccessibilitySnapshotAdapter(service).snapshot()

        assertNotNull(result)
        assertEquals(1, result?.windows?.size)
        assertEquals(42, result?.windows?.single()?.windowId)
        assertEquals("com.android.settings", result?.windows?.single()?.packageName)
        assertTrue(windowReads >= 3)
    }
}
