package com.shortcuts.app.service

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplayFailurePillTest {
    private lateinit var accessibilityService: AutomationAccessibilityService

    @Before
    fun setUp() {
        accessibilityService = mockk(relaxed = true)
    }

    @After
    fun tearDown() = clearAllMocks()

    @Test
    fun `unknown ui action produces a specific executor failure without dispatching it`() {
        val result = ActionExecutorService(mockk(relaxed = true), accessibilityService).executeAction(
            Action(ActionType.UI_AUTOMATION, uiActionType = "PINCH_TO_ZOOM")
        )

        assertTrue(result is StepResult.Failed)
        assertTrue((result as StepResult.Failed).userMessage.contains("PINCH_TO_ZOOM"))
        verify(exactly = 0) { accessibilityService.executeAction(any()) }
    }

    @Test
    fun `executor failure mapping is exhaustive and never generic`() {
        FailureReason.entries.forEach { reason ->
            val message = ReplayFailurePill.messageFor(reason, "specific detail")
            assertTrue("$reason needs usable wording", message.isNotBlank())
            assertFalse("$reason must retain the real failure detail", message == "Something went wrong")
            assertTrue("$reason must retain the real failure detail", message.contains("specific detail"))
        }
    }

    @Test
    fun `start failure mapping is exhaustive and never generic`() {
        ShortcutRunFailureReason.entries.forEach { reason ->
            val message = ReplayFailurePill.messageFor(reason, "specific detail")
            assertTrue("$reason needs usable wording", message.isNotBlank())
            assertTrue("$reason must retain the real failure detail", message.contains("specific detail"))
        }
    }

    @Test
    fun `partial run pill says which step stopped the replay`() {
        val message = ReplayFailurePill.messageFor(
            RunResult(
                shortcutName = "Find a café",
                steps = listOf(
                    StepResult.Success,
                    StepResult.Failed(FailureReason.UI_AUTOMATION_FAILED, "Couldn't find 'Search' — the app's screen changed."),
                    StepResult.Skipped("Skipped because step 2 did not complete")
                )
            )
        )

        assertTrue(message.contains("Step 2 of 3"))
        assertTrue(message.contains("shortcut stopped"))
        assertTrue(message.contains("Couldn't find 'Search'"))
    }
}
