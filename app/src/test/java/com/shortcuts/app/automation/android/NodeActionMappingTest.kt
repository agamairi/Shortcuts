package com.shortcuts.app.automation.android

import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.automation.android.AccessibilitySnapshotAdapter.Companion.mapActionIdToNodeAction
import com.shortcuts.app.automation.android.AccessibilitySnapshotAdapter.Companion.mapActionIdsToNodeActions
import com.shortcuts.app.automation.model.NodeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeActionMappingTest {

    @Test
    fun `maps standard click and long click actions`() {
        assertEquals(NodeAction.CLICK, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_CLICK))
        assertEquals(NodeAction.LONG_CLICK, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_LONG_CLICK))
    }

    @Test
    fun `maps text and focus actions`() {
        assertEquals(NodeAction.SET_TEXT, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_SET_TEXT))
        assertEquals(NodeAction.FOCUS, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_FOCUS))
        assertEquals(NodeAction.CLEAR_FOCUS, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS))
    }

    @Test
    fun `maps scroll actions`() {
        assertEquals(NodeAction.SCROLL_FORWARD, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
        assertEquals(NodeAction.SCROLL_BACKWARD, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))
        assertEquals(NodeAction.SCROLL_UP, mapActionIdToNodeAction(android.R.id.accessibilityActionScrollUp))
        assertEquals(NodeAction.SCROLL_DOWN, mapActionIdToNodeAction(android.R.id.accessibilityActionScrollDown))
        assertEquals(NodeAction.SCROLL_LEFT, mapActionIdToNodeAction(android.R.id.accessibilityActionScrollLeft))
        assertEquals(NodeAction.SCROLL_RIGHT, mapActionIdToNodeAction(android.R.id.accessibilityActionScrollRight))
    }

    @Test
    fun `maps expand, collapse, select, set progress and IME enter actions`() {
        assertEquals(NodeAction.EXPAND, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_EXPAND))
        assertEquals(NodeAction.COLLAPSE, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_COLLAPSE))
        assertEquals(NodeAction.SELECT, mapActionIdToNodeAction(AccessibilityNodeInfo.ACTION_SELECT))
        assertEquals(NodeAction.SET_PROGRESS, mapActionIdToNodeAction(android.R.id.accessibilityActionSetProgress))
        assertEquals(NodeAction.IME_ENTER, mapActionIdToNodeAction(android.R.id.accessibilityActionImeEnter))
    }

    @Test
    fun `unknown action id returns null`() {
        assertNull(mapActionIdToNodeAction(999999))
    }

    @Test
    fun `mapActionIdsToNodeActions returns expected set`() {
        val ids = listOf(
            AccessibilityNodeInfo.ACTION_CLICK,
            AccessibilityNodeInfo.ACTION_LONG_CLICK,
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            999999 // should be ignored
        )
        val actions = mapActionIdsToNodeActions(ids)
        assertEquals(3, actions.size)
        assertTrue(NodeAction.CLICK in actions)
        assertTrue(NodeAction.LONG_CLICK in actions)
        assertTrue(NodeAction.SET_TEXT in actions)
    }
}
