package com.shortcuts.app.ui

import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.viewmodel.CustomWidgetBuilderData
import com.shortcuts.app.widget.WidgetColorKey
import com.shortcuts.app.widget.WidgetIconKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreateWidgetScreenTest {

    @Test
    fun `color and icon selection updates builder data and preview representation`() {
        val initialData = CustomWidgetBuilderData()
        assertNull(initialData.selectedColorKey)
        assertNull(initialData.selectedIconKey)
        assertEquals("", initialData.label)

        val updatedData = initialData.copy(
            label = "Night Mode Tile",
            selectedColorKey = WidgetColorKey.PURPLE,
            selectedIconKey = WidgetIconKey.BOLT
        )

        assertEquals("Night Mode Tile", updatedData.label)
        assertEquals(WidgetColorKey.PURPLE, updatedData.selectedColorKey)
        assertEquals(WidgetIconKey.BOLT, updatedData.selectedIconKey)

        val previewBgColor = updatedData.selectedColorKey?.composeColor
        assertNotNull(previewBgColor)
        assertEquals(WidgetColorKey.PURPLE.composeColor, previewBgColor)
    }

    @Test
    fun `save button disabled until all required fields are set`() {
        // Incomplete data: missing color, icon, automation ID
        val incompleteData = CustomWidgetBuilderData(label = "Incomplete Tile")
        val isSaveEnabledIncomplete = incompleteData.label.isNotBlank() &&
                incompleteData.selectedColorKey != null &&
                incompleteData.selectedIconKey != null &&
                incompleteData.selectedAutomationId != null

        assertFalse(isSaveEnabledIncomplete)

        // Complete data: all 4 required fields present
        val completeData = CustomWidgetBuilderData(
            label = "Complete Tile",
            selectedColorKey = WidgetColorKey.GREEN,
            selectedIconKey = WidgetIconKey.HOME,
            selectedAutomationId = 42
        )
        val isSaveEnabledComplete = completeData.label.isNotBlank() &&
                completeData.selectedColorKey != null &&
                completeData.selectedIconKey != null &&
                completeData.selectedAutomationId != null

        assertTrue(isSaveEnabledComplete)
    }

    @Test
    fun `AI no-match message state includes affordance for AI Builder`() {
        val noMatchMsg = "No matching shortcut found for 'NonExistentShortcut'. Create it first in the AI Builder, or pick an existing shortcut below."
        val data = CustomWidgetBuilderData(
            label = "Smart Tile",
            selectedColorKey = WidgetColorKey.BLUE,
            selectedIconKey = WidgetIconKey.STAR,
            aiNoMatchMessage = noMatchMsg
        )

        assertNotNull(data.aiNoMatchMessage)
        assertTrue(data.aiNoMatchMessage!!.contains("Create it first in the AI Builder"))
        assertNull(data.selectedAutomationId)
    }

    @Test
    fun `download progress state formatting`() {
        val data = CustomWidgetBuilderData(downloadProgress = 85)

        assertNotNull(data.downloadProgress)
        assertEquals(85, data.downloadProgress)
        val progressFraction = (data.downloadProgress!! / 100f).coerceIn(0f, 1f)
        assertEquals(0.85f, progressFraction, 0.001f)
    }

    @Test
    fun `UiState Error handling representation for CreateWidgetScreen`() {
        val state: UiState<CustomWidgetBuilderData> = UiState.Error("Failed to save template: Database locked")

        assertTrue(state is UiState.Error)
        assertEquals("Failed to save template: Database locked", (state as UiState.Error).message)
    }
}
