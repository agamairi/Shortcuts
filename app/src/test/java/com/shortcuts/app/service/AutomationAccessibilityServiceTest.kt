package com.shortcuts.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutomationAccessibilityServiceTest {

    private lateinit var service: AutomationAccessibilityService
    private lateinit var mockRootNode: AccessibilityNodeInfo

    @Before
    fun setup() {
        service = spyk(AutomationAccessibilityService())
        // No real UI will ever appear in a JVM test, so skip the replay wait entirely.
        service.nodeWaitTimeoutMillis = 0L
        mockRootNode = mockk(relaxed = true)
        every { service.getRootNode() } returns mockRootNode
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `executeAction returns false for non UI_AUTOMATION action type`() {
        val action = Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI")
        val result = service.executeAction(action)
        assertFalse(result)
    }

    @Test
    fun `executeAction maps global action BACK correctly`() {
        every { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) } returns true

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "GLOBAL_ACTION",
            globalAction = "BACK"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }
    }

    @Test
    fun `executeAction maps global action HOME correctly`() {
        every { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) } returns true

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            target = "GLOBAL_ACTION_HOME"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }
    }

    @Test
    fun `executeAction maps global action RECENTS correctly`() {
        every { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) } returns true

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            globalAction = "RECENTS"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }
    }

    @Test
    fun `findTargetNode locates node by view ID`() {
        val targetNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mockRootNode.findAccessibilityNodeInfosByViewId("com.app:id/submit_btn") } returns listOf(targetNode)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            targetNodeId = "com.app:id/submit_btn"
        )

        val found = service.findTargetNode(action)
        assertNotNull(found)
        assertEquals(targetNode, found)
    }

    @Test
    fun `findTargetNode falls back to text search when view ID yields no result`() {
        every { mockRootNode.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        val targetNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mockRootNode.findAccessibilityNodeInfosByText("Submit") } returns listOf(targetNode)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            targetNodeId = "com.app:id/submit_btn",
            targetText = "Submit"
        )

        val found = service.findTargetNode(action)
        assertNotNull(found)
        assertEquals(targetNode, found)
    }

    @Test
    fun `findTargetNode falls back to recursive tree traversal checking text and content description`() {
        every { mockRootNode.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { mockRootNode.findAccessibilityNodeInfosByText(any()) } returns emptyList()

        val childNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { mockRootNode.childCount } returns 1
        every { mockRootNode.getChild(0) } returns childNode
        every { childNode.contentDescription } returns "Play Music Button"

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            targetText = "Play Music"
        )

        val found = service.findTargetNode(action)
        assertNotNull(found)
        assertEquals(childNode, found)
    }

    @Test
    fun `executeAction performs SET_TEXT action with constructed argument Bundle`() {
        val editableNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { editableNode.isEditable } returns true
        every { mockRootNode.findAccessibilityNodeInfosByViewId("com.app:id/edit_text") } returns listOf(editableNode)

        val bundleSlot = slot<Bundle>()
        every { editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, capture(bundleSlot)) } returns true

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "SET_TEXT",
            targetNodeId = "com.app:id/edit_text",
            textInput = "Sample Input"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, any()) }
        assertTrue(bundleSlot.isCaptured)
        assertNotNull(bundleSlot.captured)
    }

    @Test
    fun `executeAction performs ACTION_CLICK on clickable node`() {
        val clickableNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { clickableNode.isClickable } returns true
        every { clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        every { mockRootNode.findAccessibilityNodeInfosByViewId("com.app:id/button") } returns listOf(clickableNode)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetNodeId = "com.app:id/button"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `executeAction traverses to parent when target node is not clickable`() {
        val childNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        val parentNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { childNode.isClickable } returns false
        every { childNode.parent } returns parentNode
        every { parentNode.isClickable } returns true
        every { parentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        every { mockRootNode.findAccessibilityNodeInfosByViewId("com.app:id/label") } returns listOf(childNode)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetNodeId = "com.app:id/label"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { parentNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `executeAction performs forward scrolling on scrollable node`() {
        val scrollableNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { scrollableNode.isScrollable } returns true
        every { scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) } returns true
        every { mockRootNode.findAccessibilityNodeInfosByViewId("com.app:id/list") } returns listOf(scrollableNode)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "SCROLL",
            scrollDirection = "FORWARD",
            targetNodeId = "com.app:id/list"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
    }

    @Test
    fun `executeAction performs backward scrolling on scrollable node`() {
        val scrollableNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { scrollableNode.isScrollable } returns true
        every { scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) } returns true
        every { mockRootNode.findAccessibilityNodeInfosByViewId("com.app:id/list") } returns listOf(scrollableNode)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "SCROLL_BACKWARD",
            targetNodeId = "com.app:id/list"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
    }

    @Test
    fun `executeAction returns false when root node is null`() {
        every { service.getRootNode() } returns null

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetNodeId = "com.app:id/button"
        )

        val result = service.executeAction(action)
        assertFalse(result)
    }

    @Test
    fun `executeAction returns false when target node is not found`() {
        every { mockRootNode.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        every { mockRootNode.findAccessibilityNodeInfosByText(any()) } returns emptyList()
        every { mockRootNode.childCount } returns 0

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetNodeId = "nonexistent_id"
        )

        val result = service.executeAction(action)
        assertFalse(result)
    }

    @Test
    fun `executeAction rejects invalid globalAction string without defaulting to BACK`() {
        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            globalAction = "INVALID_GLOBAL_KEY"
        )
        val result = service.executeAction(action)
        assertFalse(result)
        verify(exactly = 0) { service.performGlobalAction(any()) }
    }

    @Test
    fun `executeAction maps extra global actions correctly`() {
        every { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) } returns true
        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            globalAction = "NOTIFICATIONS"
        )
        val result = service.executeAction(action)
        assertTrue(result)
        verify { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
    }

    @Test
    fun `executeAction treats UI click action with target BACK as click action`() {
        val targetNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { targetNode.isClickable } returns true
        every { targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true
        every { mockRootNode.findAccessibilityNodeInfosByText("BACK") } returns listOf(targetNode)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetText = "BACK"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        verify(exactly = 0) { service.performGlobalAction(any()) }
    }

    @Test
    fun `executeAction returns false for unknown or unsupported uiActionType`() {
        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "UNKNOWN_UNSUPPORTED_ACTION"
        )

        val result = service.executeAction(action)
        assertFalse(result)
    }

    @Test
    fun `executeAction handles parent cycle without infinite loop`() {
        val nodeA = mockk<AccessibilityNodeInfo>(relaxed = true)
        val nodeB = mockk<AccessibilityNodeInfo>(relaxed = true)

        every { nodeA.isClickable } returns false
        every { nodeB.isClickable } returns false

        every { nodeA.parent } returns nodeB
        every { nodeB.parent } returns nodeA
        every { nodeA.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        every { mockRootNode.findAccessibilityNodeInfosByViewId("com.app:id/cyclic_btn") } returns listOf(nodeA)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetNodeId = "com.app:id/cyclic_btn"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { nodeA.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `executeAction handles deep parent hierarchy exceeding maxParentDepth`() {
        val nodes = List(30) { mockk<AccessibilityNodeInfo>(relaxed = true) }
        for (i in 0 until 29) {
            every { nodes[i].isClickable } returns false
            every { nodes[i].parent } returns nodes[i + 1]
        }
        every { nodes[29].isClickable } returns false
        every { nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        every { mockRootNode.findAccessibilityNodeInfosByViewId("com.app:id/deep_btn") } returns listOf(nodes[0])

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetNodeId = "com.app:id/deep_btn"
        )

        val result = service.executeAction(action)
        assertTrue(result)
        verify { nodes[0].performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    @Test
    fun `findAllNodesByTraversal stops searching when depth exceeds maxDepth`() {
        val deepChild = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { deepChild.text } returns "Deep Target"

        val found = mutableListOf<AccessibilityNodeInfo>()
        service.findAllNodesByTraversal(deepChild, "Deep Target", found, depth = 21, maxDepth = 20)
        assertTrue(found.isEmpty())
    }

    @Test
    fun `findEditableNode stops searching when depth exceeds maxDepth`() {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isEditable } returns true

        val found = service.findEditableNode(node, depth = 21, maxDepth = 20)
        org.junit.Assert.assertNull(found)
    }

    @Test
    fun `findScrollableNode stops searching when depth exceeds maxDepth`() {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isScrollable } returns true

        val found = service.findScrollableNode(node, depth = 21, maxDepth = 20)
        org.junit.Assert.assertNull(found)
    }
    @Test
    fun `matcher picks the best candidate based on class and position`() {
        val rootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { service.getRootNode() } returns rootNode

        val badNode1 = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { badNode1.text } returns "Submit"
        every { badNode1.className } returns "android.widget.TextView"
        every { badNode1.getBoundsInScreen(any()) } answers {
            firstArg<android.graphics.Rect>().set(0, 0, 100, 100)
        }

        val goodNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { goodNode.text } returns "Submit"
        every { goodNode.className } returns "android.widget.Button"
        every { goodNode.getBoundsInScreen(any()) } answers {
            firstArg<android.graphics.Rect>().set(500, 500, 600, 600)
        }

        val badNode2 = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { badNode2.text } returns "Submit"
        every { badNode2.className } returns "android.widget.Button"
        every { badNode2.getBoundsInScreen(any()) } answers {
            firstArg<android.graphics.Rect>().set(1000, 1000, 1100, 1100)
        }

        every { rootNode.findAccessibilityNodeInfosByText("Submit") } returns listOf(badNode1, goodNode, badNode2)

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetText = "Submit",
            targetClassName = "android.widget.Button",
            screenX = 550,
            screenY = 550
        )

        AutomationAccessibilityService.AutomationTrace.clear()
        val resultNode = service.findTargetNode(action)

        assertEquals(goodNode, resultNode)
        
        val trace = AutomationAccessibilityService.AutomationTrace.matches.last()
        assertEquals(3, trace.candidates.size)
        assertEquals(1, trace.pickedIndex)
    }

    @Test
    fun `readiness wait succeeds when target appears after a delay`() {
        service.nodeWaitTimeoutMillis = 1000L
        val rootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { service.getRootNode() } returns rootNode
        
        val targetNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { targetNode.text } returns "Delayed Target"
        every { targetNode.isClickable } returns true
        every { targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        var callCount = 0
        every { rootNode.findAccessibilityNodeInfosByText("Delayed Target") } answers {
            if (callCount++ < 3) emptyList() else listOf(targetNode)
        }

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetText = "Delayed Target"
        )

        AutomationAccessibilityService.AutomationTrace.clear()
        val result = service.executeAction(action)
        
        assertTrue(result)
        verify { targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        
        val trace = AutomationAccessibilityService.AutomationTrace.waits.last()
        assertTrue(trace.success)
        assertTrue("Wait time should be positive", trace.waitTimeMs > 0)
    }

    @Test
    fun `readiness wait fails cleanly when target never appears within timeout`() {
        service.nodeWaitTimeoutMillis = 500L
        val rootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { service.getRootNode() } returns rootNode
        every { rootNode.findAccessibilityNodeInfosByText("Ghost Target") } returns emptyList()

        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetText = "Ghost Target"
        )

        AutomationAccessibilityService.AutomationTrace.clear()
        
        val startTime = System.currentTimeMillis()
        val result = service.executeAction(action)
        val elapsed = System.currentTimeMillis() - startTime
        
        assertFalse(result)
        assertTrue("Should wait at least 500ms", elapsed >= 500)
        assertTrue("Should not hang forever", elapsed < 2000)
        
        val trace = AutomationAccessibilityService.AutomationTrace.waits.last()
        assertFalse(trace.success)
    }
}
