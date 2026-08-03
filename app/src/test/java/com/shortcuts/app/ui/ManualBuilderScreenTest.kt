package com.shortcuts.app.ui

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualBuilderScreenTest {

    @Test
    fun `action list add and remove logic`() {
        val actionsList = mutableListOf<Action>()
        val action1 = Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI", state = "ON")
        val action2 = Action(actionType = ActionType.APP_INTENT, packageName = "com.spotify.music")

        actionsList.add(action1)
        actionsList.add(action2)
        assertEquals(2, actionsList.size)

        actionsList.removeAt(0)
        assertEquals(1, actionsList.size)
        assertEquals(ActionType.APP_INTENT, actionsList[0].actionType)
    }

    @Test
    fun `action list reorder logic`() {
        val actionsList = mutableListOf(
            Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI"),
            Action(actionType = ActionType.APP_INTENT, packageName = "com.test.app"),
            Action(actionType = ActionType.HTTP_REQUEST, url = "https://api.test.com")
        )

        // Move item at index 2 up to index 1
        val item = actionsList.removeAt(2)
        actionsList.add(1, item)

        assertEquals(ActionType.HTTP_REQUEST, actionsList[1].actionType)
        assertEquals(ActionType.APP_INTENT, actionsList[2].actionType)
    }

    @Test
    fun `manual builder form validation`() {
        val shortcutName = "   "
        val actions = listOf<Action>()

        val isNameValid = shortcutName.isNotBlank()
        val hasActions = actions.isNotEmpty()

        assertFalse(isNameValid)
        assertFalse(hasActions)
    }

    @Test
    fun `automation serialization and creation`() {
        val actions = listOf(
            Action(actionType = ActionType.SYSTEM_TOGGLE, target = "BLUETOOTH", state = "OFF")
        )
        val json = ActionConverter().fromActionList(actions)
        val automation = Automation(
            name = "Bluetooth Off",
            actionsJson = json,
            triggerType = "MANUAL"
        )

        assertEquals("Bluetooth Off", automation.name)
        assertEquals("MANUAL", automation.triggerType)

        val deserialized = ActionConverter().toActionList(automation.actionsJson)
        assertEquals(1, deserialized.size)
        assertEquals(ActionType.SYSTEM_TOGGLE, deserialized[0].actionType)
        assertEquals("BLUETOOTH", deserialized[0].target)
    }

    @Test
    fun `action property modification`() {
        val originalAction = Action(actionType = ActionType.UI_AUTOMATION, target = "Submit Button", textInput = "Old Text")
        val updatedAction = originalAction.copy(textInput = "New Text", target = "Save Button")

        assertEquals("New Text", updatedAction.textInput)
        assertEquals("Save Button", updatedAction.target)
        assertEquals(ActionType.UI_AUTOMATION, updatedAction.actionType)
    }
}


