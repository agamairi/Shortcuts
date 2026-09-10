package com.shortcuts.app.service

import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import io.mockk.slot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationAccessibilityServiceReplayTest {

    @Test
    fun `preferCoordinateReplay = true skips node lookup and goes straight to tapAtRecordedPoint`() {
        val service = spyk<AutomationAccessibilityService>()
        val rootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { service.getRootNode() } returns rootNode
        val mockWindowBounds = mockk<Rect>(relaxed = true)
        mockWindowBounds.left = 0
        mockWindowBounds.top = 0
        mockWindowBounds.right = 1080
        mockWindowBounds.bottom = 2400

        val mockRootBounds = mockk<Rect>(relaxed = true)
        mockRootBounds.left = 0
        mockRootBounds.top = 0
        mockRootBounds.right = 1080
        mockRootBounds.bottom = 2400
        every { mockRootBounds.contains(any(), any()) } returns true

        // Mock captureCoordinateReplayContext to pass validation
        every { service.captureCoordinateReplayContext() } returns AutomationAccessibilityService.Companion.CoordinateReplayContext(
            display = DisplaySnapshot(width = 1080, height = 2400, rotation = 0, densityDpi = 420),
            activePackageName = "com.test.app",
            activeWindowBounds = mockWindowBounds,
            activeRootBounds = mockRootBounds,
            hasVisibleInputMethod = false
        )
        // Mock dispatchGesture to succeed
        val callbackSlot = slot<android.accessibilityservice.AccessibilityService.GestureResultCallback>()
        every { service.dispatchGesture(any(), capture(callbackSlot), any()) } answers {
            callbackSlot.captured.onCompleted(null)
            true
        }

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetText = "Ghost Target",
            screenX = 500,
            screenY = 500,
            recordedDisplayWidth = 1080,
            recordedDisplayHeight = 2400,
            recordedDisplayRotation = 0,
            targetPackageName = "com.test.app",
            preferCoordinateReplay = true
        )

        AutomationAccessibilityService.AutomationTrace.clear()
        
        val result = service.executeAction(action)
        
        assertTrue(result)
        // Since node lookup was skipped, findTargetNode shouldn't be called, so no MatchTrace is recorded
        assertTrue(AutomationAccessibilityService.AutomationTrace.matches.isEmpty())
        verify(exactly = 1) { service.dispatchGesture(any(), any(), any()) }
    }

    @Test
    fun `preferCoordinateReplay = false preserves today's node-first behavior exactly`() {
        val service = spyk<AutomationAccessibilityService>()
        val rootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        val targetNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        
        every { targetNode.isClickable } returns true
        every { targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        
        every { service.getRootNode() } returns rootNode
        // Mock findTargetNode to return the target node, simulating successful semantic match
        every { service.findTargetNode(any()) } returns targetNode

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetText = "Ghost Target",
            screenX = 500,
            screenY = 500,
            recordedDisplayWidth = 1080,
            recordedDisplayHeight = 2400,
            recordedDisplayRotation = 0,
            targetPackageName = "com.test.app",
            preferCoordinateReplay = false
        )

        AutomationAccessibilityService.AutomationTrace.clear()
        
        val result = service.executeAction(action)
        
        assertTrue(result)
        // Verify findTargetNode was called
        verify(atLeast = 1) { service.findTargetNode(any()) }
        // Verify dispatchGesture was NOT called
        verify(exactly = 0) { service.dispatchGesture(any(), any(), any()) }
        verify(exactly = 1) { targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
}
