package com.shortcuts.app.service.capture

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.service.AutomationAccessibilityService
import com.shortcuts.app.service.AutomationRecorder
import com.shortcuts.app.service.RecorderEventType
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TouchCaptureOverlayTest {

    @Before
    fun setUp() {
        AutomationRecorder.clearRecording()
        AutomationRecorder.setRecordingForTest(true)
    }

    @org.junit.After
    fun tearDown() {
        AutomationAccessibilityService.instance = null
        AutomationRecorder.clearRecording()
    }

    @Test
    fun testRecordWithEmptyHitPackageFallsBackToTopAppPackageSoNeverDropped() {
        val mockService = mockk<AutomationAccessibilityService>(relaxed = true)
        every { mockService.packageName } returns "com.shortcuts.app"
        val mockWindow = mockk<android.view.accessibility.AccessibilityWindowInfo>(relaxed = true)
        every { mockWindow.type } returns android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindow.layer } returns 10
        val mockNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mockNode.packageName } returns "com.example.topapp"
        every { mockWindow.root } returns mockNode
        every { mockService.windows } returns listOf(mockWindow)
        AutomationAccessibilityService.instance = mockService

        val gesture = ClassifiedGesture(
            kind = CapturedGestureKind.TAP,
            samples = listOf(
                TouchSample(TouchAction.DOWN, 100f, 200f, 1_000L),
                TouchSample(TouchAction.UP, 100f, 200f, 1_050L)
            )
        )

        // When hit is null (e.g. pre-relay snapshot was late or missed budget),
        // fallback to topAppPackage prevents the legacy session from dropping the tap!
        TouchCaptureOverlay.record(gesture, null)

        val recorded = AutomationRecorder.recordedActions.value
        assertEquals("Tap must not be dropped when hit is null", 1, recorded.size)
        assertEquals("com.example.topapp", recorded[0].targetPackageName)

        // Also test when hit has empty packageName:
        val emptyHit = TouchCaptureOverlay.HitSnapshot(
            packageName = "",
            text = "Button",
            description = null,
            viewId = "id/btn",
            className = "android.widget.Button",
            editable = false,
            windowId = 1
        )
        TouchCaptureOverlay.record(gesture, emptyHit)
        val recorded2 = AutomationRecorder.recordedActions.value
        assertEquals("Tap must not be dropped when hit packageName is empty", 2, recorded2.size)
        assertEquals("com.example.topapp", recorded2[1].targetPackageName)
    }

    @Test
    fun testFindDeepestReverseBoundsWalkToAtMost300Nodes() {
        val visitedCount = AtomicInteger(0)
        val dummyNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { dummyNode.isVisibleToUser } returns true
        every { dummyNode.childCount } returns 2
        every { dummyNode.getChild(any()) } answers { dummyNode }
        every { dummyNode.getBoundsInScreen(any()) } answers {
            val r = firstArg<Rect>()
            r.set(0, 0, 1000, 1000)
        }

        TouchCaptureOverlay.findDeepestReverse(dummyNode, 500, 500, visitedCount)
        // Traversal must be capped at 300 nodes
        assertTrue("Traversal must visit at most 301 nodes (capped at 300)", visitedCount.get() <= 301)
    }
}
