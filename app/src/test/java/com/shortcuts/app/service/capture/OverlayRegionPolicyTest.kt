package com.shortcuts.app.service.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayRegionPolicyTest {
    private val insets = CaptureInsets(statusBar = 80, navigationBar = 120)
    private val devPackage = "com.shortcuts.app.dev"
    @Test fun `no IME excludes bars`() {
        val region = OverlayRegionPolicy.region(1080, 2400, insets, null, false, "com.example", devPackage) as OverlayRegion.Touchable
        assertEquals(CaptureRect(0, 80, 1080, 2280), region.rect)
    }
    @Test fun `IME shrinks to its top`() {
        val region = OverlayRegionPolicy.region(1080, 2400, insets, CaptureRect(0, 1500, 1080, 2400), false, "com.example", devPackage) as OverlayRegion.Touchable
        assertEquals(1500, region.rect.bottom)
    }
    @Test fun `shade is not touchable`() = assertTrue(OverlayRegionPolicy.region(1080, 2400, insets, null, true, "com.example", devPackage) is OverlayRegion.NotTouchable)
    @Test fun `dev RepeatKit foreground is not touchable`() = assertTrue(OverlayRegionPolicy.region(1080, 2400, insets, null, false, devPackage, devPackage) is OverlayRegion.NotTouchable)
    @Test fun `release package is not special in dev build`() = assertTrue(OverlayRegionPolicy.region(1080, 2400, insets, null, false, "com.shortcuts.app", devPackage) is OverlayRegion.Touchable)
    @Test fun `blocked app visible below top app does not block`() {
        val topAppPackage = OverlayRegionPolicy.topAppPackage(listOf(
            CaptureApplicationWindow(layer = 5, packageName = "com.google.android.permissioncontroller"),
            CaptureApplicationWindow(layer = 6, packageName = "com.example")
        ))
        assertEquals("com.example", topAppPackage)
        assertTrue(OverlayRegionPolicy.region(1080, 2400, insets, null, false, topAppPackage, devPackage) is OverlayRegion.Touchable)
    }
    @Test fun `permission controller is not touchable`() = assertTrue(OverlayRegionPolicy.region(1080, 2400, insets, null, false, "com.google.android.permissioncontroller", devPackage) is OverlayRegion.NotTouchable)
    @Test fun `three button nav is excluded`() { val r = OverlayRegionPolicy.region(1080, 2400, insets, null, false, "com.example", devPackage) as OverlayRegion.Touchable; assertEquals(2280, r.rect.bottom) }
}
