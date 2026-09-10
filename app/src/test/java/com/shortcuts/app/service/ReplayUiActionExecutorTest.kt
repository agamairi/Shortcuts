package com.shortcuts.app.service

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayUiActionExecutorTest {
    @After
    fun tearDown() = clearAllMocks()

    @Test
    fun `LONG_PRESS dispatches ACTION_LONG_CLICK to the resolved accessibility target`() {
        val service = mockk<AutomationAccessibilityService>(relaxed = true)
        val target = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { service.findTargetNode(any()) } returns target
        every { target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) } returns true

        val result = ReplayUiActionExecutor(service).executeLongPress(
            Action(ActionType.UI_AUTOMATION, uiActionType = "LONG_PRESS", targetText = "Search")
        )

        assertEquals(ReplayUiActionResult.Success, result)
        verify { target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
    }

    @Test
    fun `PRESS_ENTER dispatches ACTION_IME_ENTER on Android R and later`() {
        val service = mockk<AutomationAccessibilityService>(relaxed = true)
        val field = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { service.findTargetNode(any()) } returns field
        val fakeImeEnterActionId = 0x01000000
        every {
            field.performAction(fakeImeEnterActionId)
        } returns true

        val result = ReplayUiActionExecutor(
            service = service,
            sdkInt = Build.VERSION_CODES.R,
            imeEnterActionId = { fakeImeEnterActionId }
        ).executePressEnter(
            Action(ActionType.UI_AUTOMATION, uiActionType = "PRESS_ENTER", targetNodeId = "com.example:id/search")
        )

        assertEquals(ReplayUiActionResult.Success, result)
        verify { field.performAction(fakeImeEnterActionId) }
    }

    @Test
    fun `PRESS_ENTER falls back to clicking the text field before Android R`() {
        val service = mockk<AutomationAccessibilityService>(relaxed = true)
        val field = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { service.findTargetNode(any()) } returns field
        every { field.performAction(AccessibilityNodeInfo.ACTION_CLICK) } returns true

        val result = ReplayUiActionExecutor(service, Build.VERSION_CODES.Q).executePressEnter(
            Action(ActionType.UI_AUTOMATION, uiActionType = "PRESS_ENTER", targetText = "Search")
        )

        assertEquals(ReplayUiActionResult.Success, result)
        verify { field.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }
}
