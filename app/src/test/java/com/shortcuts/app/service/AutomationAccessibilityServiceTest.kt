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
}
