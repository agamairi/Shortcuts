package com.shortcuts.app.automation.model

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyWorkflowMigratorTest {
    private val migrator = LegacyWorkflowMigrator()

    @Test
    fun `every legacy action type maps deterministically`() {
        val actions = listOf(
            Action(ActionType.APP_INTENT, packageName = "example.app"),
            Action(ActionType.UI_AUTOMATION, uiActionType = "TAP", targetText = "Save", targetPackageName = "example.app"),
            Action(ActionType.WAIT, delayMillis = 750),
            Action(ActionType.SYSTEM_TOGGLE, target = "wifi", state = "toggle"),
            Action(ActionType.HTTP_REQUEST, url = "https://example.test", method = "POST"),
            Action(ActionType.SEND_MESSAGE, target = "+15551234567", textInput = "hello"),
            Action(ActionType.DIAL_NUMBER, target = "+15557654321")
        )

        val first = migrator.migrate(actions).workflow
        val second = migrator.migrate(actions).workflow

        assertEquals(first, second)
        assertTrue(first.steps[0] is LaunchAppStep)
        assertTrue(first.steps[1] is UiStep)
        assertEquals(750L, (first.steps[2] as WaitStep).durationMs)
        actions.drop(3).forEachIndexed { offset, action ->
            assertEquals(action, (first.steps[offset + 3] as LegacyActionStep).action)
        }
        val launch = first.steps[0] as LaunchAppStep
        assertEquals(LaunchPolicy.RESUME_EXISTING_TASK, launch.policy)
        assertEquals(LaunchTarget.Launcher("example.app"), launch.target)
        assertTrue(LegacyWorkflowMigrator.WARNING_LEGACY_LAUNCH_RESUMES_TASK in launch.migrationWarnings)
    }

    @Test
    fun `legacy ui shapes map to operations and ranked exact locators`() {
        val actions = listOf(
            ui("TAP"),
            ui("LONG_PRESS"),
            ui("TYPE_TEXT", textInput = "Ada"),
            ui("PRESS_ENTER"),
            ui("SCROLL", scrollDirection = "UP")
        )

        val steps = migrator.migrate(actions).workflow.steps.map { it as UiStep }

        assertEquals(UiOperation.TAP, steps[0].operation)
        assertEquals(UiOperation.LONG_PRESS, steps[1].operation)
        assertEquals(UiOperation.SET_TEXT("Ada"), steps[2].operation)
        assertEquals(UiOperation.IME_ACTION(ImeActionKind.UNSPECIFIED), steps[3].operation)
        assertEquals(UiOperation.SWIPE, steps[4].operation)
        assertEquals(RevealDirection.UP, steps[4].reveal?.direction)
        assertEquals(
            listOf(LocatorKind.RESOURCE_ID_EXACT, LocatorKind.TEXT_EXACT, LocatorKind.CONTENT_DESCRIPTION_EXACT),
            steps[0].element?.strategies?.map(LocatorStrategy::kind)
        )
        assertEquals(listOf(0, 1, 2), steps[0].element?.strategies?.map(LocatorStrategy::rank))
        assertEquals("android.widget.Button", steps[0].element?.expectedClass)
    }

    @Test
    fun `global action becomes targetless ui operation`() {
        val action = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "GLOBAL_ACTION",
            globalAction = "GLOBAL_ACTION_BACK"
        )

        val step = migrator.migrate(listOf(action)).workflow.steps.single() as UiStep

        assertEquals(UiOperation.GLOBAL_ACTION("GLOBAL_ACTION_BACK"), step.operation)
        assertNull(step.element)
    }

    @Test
    fun `unresolved target warns and does not create text locator`() {
        val action = ui("TAP").copy(targetText = "UNRESOLVED")

        val step = migrator.migrate(listOf(action)).workflow.steps.single() as UiStep

        assertTrue(LegacyWorkflowMigrator.WARNING_UNRESOLVED_TARGET in step.migrationWarnings)
        assertFalse(step.element!!.strategies.any { it.kind == LocatorKind.TEXT_EXACT })
    }

    @Test
    fun `delay moves from an action to the following step`() {
        val actions = listOf(
            Action(ActionType.APP_INTENT, packageName = "example.app", delayMillis = 650),
            ui("TAP").copy(delayMillis = 900),
            Action(ActionType.HTTP_REQUEST, url = "https://example.test")
        )

        val steps = migrator.migrate(actions).workflow.steps

        assertNull(steps[0].capturedDelayFromPreviousMs)
        assertEquals(650L, steps[1].capturedDelayFromPreviousMs)
        assertEquals(900L, steps[2].capturedDelayFromPreviousMs)
    }

    @Test
    fun `legacy screen coordinates are retained and marked display legacy`() {
        val action = ui("TAP").copy(
            screenX = 540,
            screenY = 1_200,
            recordedDisplayWidth = 1_080,
            recordedDisplayHeight = 2_400,
            recordedDisplayRotation = 1,
            recordedDensityDpi = 420
        )

        val step = migrator.migrate(listOf(action)).workflow.steps.single() as UiStep
        val point = step.element?.rawPoint!!

        assertEquals(CoordinateSpace.DISPLAY_LEGACY, point.coordinateSpace)
        assertEquals(IntPoint(540, 1_200), point.displayPx)
        assertEquals(NormalizedPoint(0.5f, 0.5f), point.pointInWindow)
        assertTrue(LegacyWorkflowMigrator.WARNING_LEGACY_DISPLAY_COORDINATES in step.migrationWarnings)
    }

    private fun ui(
        type: String,
        textInput: String? = null,
        scrollDirection: String? = null
    ) = Action(
        actionType = ActionType.UI_AUTOMATION,
        targetNodeId = "example.app:id/save",
        targetText = "Save",
        targetContentDescription = "Save changes",
        targetClassName = "android.widget.Button",
        targetPackageName = "example.app",
        uiActionType = type,
        textInput = textInput,
        scrollDirection = scrollDirection
    )
}
