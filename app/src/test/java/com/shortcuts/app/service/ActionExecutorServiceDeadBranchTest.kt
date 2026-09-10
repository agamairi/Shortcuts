package com.shortcuts.app.service

import android.content.Context
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ActionExecutorServiceDeadBranchTest {
    @Test
    fun `waitForScreenToSettle is called via singleton when constructor arg is null`() {
        val mockContext = mockk<Context>(relaxed = true)
        val mockAccService = mockk<AutomationAccessibilityService>(relaxed = true)
        
        // Ensure steps succeed
        every { mockAccService.executeAction(any()) } returns true
        
        // Set singleton instead of constructor arg
        AutomationAccessibilityService.instance = mockAccService
        
        val service = ActionExecutorService(mockContext, null)
        
        val actions = listOf(
            Action(actionType = ActionType.UI_AUTOMATION, uiActionType = "TAP", delayMillis = 500L),
            Action(actionType = ActionType.UI_AUTOMATION, uiActionType = "TAP", delayMillis = 500L)
        )
        
        service.executeActions(actions, "Test")
        
        // delayMillis is called on the first step because index < lastIndex
        verify(exactly = 1) { mockAccService.waitForScreenToSettle(500L) }
    }
}
