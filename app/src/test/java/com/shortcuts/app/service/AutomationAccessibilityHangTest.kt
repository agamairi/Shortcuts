package com.shortcuts.app.service

import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Test
import org.junit.Assert.*

class AutomationAccessibilityHangTest {
    @Test
    fun `awaitTargetNode respects timeout even if findTargetNode is slow`() {
        val service = spyk<AutomationAccessibilityService>()
        
        val dummyRoot = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { dummyRoot.isScrollable } returns false
        every { dummyRoot.childCount } returns 0
        every { dummyRoot.viewIdResourceName } returns "root"
        
        // Simulate a slow findTargetNode (e.g. 500ms) by delaying getRootNode
        every { service.getRootNode() } answers {
            Thread.sleep(500)
            dummyRoot
        }
        
        // Set wait timeout to 2000ms
        service.nodeWaitTimeoutMillis = 2000L
        
        val action = Action(actionType = ActionType.UI_AUTOMATION, uiActionType = "SCROLL")
        
        val start = System.currentTimeMillis()
        val result = service.executeAction(action)
        val end = System.currentTimeMillis()
        
        assertFalse(result)
        // If the loop doesn't check elapsed time, it will run for 13 iterations (2000/150)
        // Each taking 500ms -> 6500ms total!
        // With the fix, it should break after ~2000ms. Allow some overhead.
        assertTrue("Execution took too long: ${end - start}ms, expected ~2000ms", end - start < 4500)
    }
}
