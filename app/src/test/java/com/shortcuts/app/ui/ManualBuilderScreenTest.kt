package com.shortcuts.app.ui

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.ui.screens.InstalledAppInfo
import com.shortcuts.app.ui.screens.ManualBuilderUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun `installed app filtering and sorting`() {
        val appList = listOf(
            InstalledAppInfo(label = "Spotify", packageName = "com.spotify.music"),
            InstalledAppInfo(label = "Amazon Alexa", packageName = "com.amazon.dee.app"),
            InstalledAppInfo(label = "Philips Hue", packageName = "com.philips.lighting.hue")
        )

        val sorted = ManualBuilderUtils.filterAndSortApps(appList, "")
        assertEquals(3, sorted.size)
        assertEquals("Amazon Alexa", sorted[0].label)
        assertEquals("Philips Hue", sorted[1].label)
        assertEquals("Spotify", sorted[2].label)

        val filtered = ManualBuilderUtils.filterAndSortApps(appList, "alexa")
        assertEquals(1, filtered.size)
        assertEquals("Amazon Alexa", filtered[0].label)

        val filteredByPkg = ManualBuilderUtils.filterAndSortApps(appList, "philips")
        assertEquals(1, filteredByPkg.size)
        assertEquals("Philips Hue", filteredByPkg[0].label)
    }

    @Test
    fun `installed app label lookup`() {
        val appList = listOf(
            InstalledAppInfo(label = "Amazon Alexa", packageName = "com.amazon.dee.app")
        )

        assertEquals("Amazon Alexa", ManualBuilderUtils.getAppLabel(appList, "com.amazon.dee.app"))
        assertNull(ManualBuilderUtils.getAppLabel(appList, "com.unknown.app"))
        assertNull(ManualBuilderUtils.getAppLabel(appList, null))
    }

    @Test
    fun `human readable action summaries for all action types`() {
        val appList = listOf(
            InstalledAppInfo(label = "Amazon Alexa", packageName = "com.amazon.dee.app")
        )
        val lookup = { pkg: String -> ManualBuilderUtils.getAppLabel(appList, pkg) }

        val appActionKnown = Action(actionType = ActionType.APP_INTENT, packageName = "com.amazon.dee.app")
        assertEquals("Open Amazon Alexa", ManualBuilderUtils.getActionSummary(appActionKnown, lookup))

        val appActionUnknown = Action(actionType = ActionType.APP_INTENT, packageName = "com.other.app")
        assertEquals("Open com.other.app", ManualBuilderUtils.getActionSummary(appActionUnknown, lookup))

        val appActionEmpty = Action(actionType = ActionType.APP_INTENT)
        assertEquals("Open an App", ManualBuilderUtils.getActionSummary(appActionEmpty, lookup))

        val uiActionTargetNode = Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "Living Room")
        assertEquals("Tap 'Living Room'", ManualBuilderUtils.getActionSummary(uiActionTargetNode, lookup))

        val uiActionTargetText = Action(actionType = ActionType.UI_AUTOMATION, targetText = "Red")
        assertEquals("Tap 'Red'", ManualBuilderUtils.getActionSummary(uiActionTargetText, lookup))

        val uiActionEmpty = Action(actionType = ActionType.UI_AUTOMATION)
        assertEquals("Tap or Type on Screen", ManualBuilderUtils.getActionSummary(uiActionEmpty, lookup))

        val systemToggleAction = Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI", state = "ON")
        assertEquals("Toggle Wi-Fi ON", ManualBuilderUtils.getActionSummary(systemToggleAction, lookup))

        val systemToggleEmptyState = Action(actionType = ActionType.SYSTEM_TOGGLE, target = "BLUETOOTH")
        assertEquals("Toggle Bluetooth", ManualBuilderUtils.getActionSummary(systemToggleEmptyState, lookup))

        val httpAction = Action(actionType = ActionType.HTTP_REQUEST, url = "https://api.example.com/webhook", method = "POST")
        assertEquals("Web request to https://api.example.com/webhook", ManualBuilderUtils.getActionSummary(httpAction, lookup))

        val httpActionEmpty = Action(actionType = ActionType.HTTP_REQUEST)
        assertEquals("Web Request", ManualBuilderUtils.getActionSummary(httpActionEmpty, lookup))
    }

    @Test
    fun `create default action for each type`() {
        val appAction = ManualBuilderUtils.createDefaultAction(ActionType.APP_INTENT)
        assertEquals(ActionType.APP_INTENT, appAction.actionType)
        assertEquals("", appAction.packageName)

        val uiAction = ManualBuilderUtils.createDefaultAction(ActionType.UI_AUTOMATION)
        assertEquals(ActionType.UI_AUTOMATION, uiAction.actionType)
        assertEquals("", uiAction.targetNodeId)

        val sysAction = ManualBuilderUtils.createDefaultAction(ActionType.SYSTEM_TOGGLE)
        assertEquals(ActionType.SYSTEM_TOGGLE, sysAction.actionType)
        assertEquals("WIFI", sysAction.target)
        assertEquals("TOGGLE", sysAction.state)

        val httpAction = ManualBuilderUtils.createDefaultAction(ActionType.HTTP_REQUEST)
        assertEquals(ActionType.HTTP_REQUEST, httpAction.actionType)
        assertEquals("", httpAction.url)
        assertEquals("GET", httpAction.method)
    }

    @Test
    fun `action type metadata contains all action types with valid titles and icons`() {
        val metadataList = ManualBuilderUtils.ACTION_TYPE_METADATA
        assertEquals(4, metadataList.size)

        ActionType.values().forEach { type ->
            val meta = ManualBuilderUtils.getMetadataForType(type)
            assertNotNull(meta)
            assertTrue(meta.title.isNotBlank())
            assertTrue(meta.description.isNotBlank())
        }
    }
}
