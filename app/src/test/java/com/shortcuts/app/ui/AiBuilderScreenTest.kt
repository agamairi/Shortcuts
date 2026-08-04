package com.shortcuts.app.ui

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.ui.state.UiState
import com.shortcuts.app.viewmodel.AiBuilderData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiBuilderScreenTest {

    @Test
    fun `prompt input state mapping`() {
        val prompt = "Turn on flashlight when dark"
        val data = AiBuilderData(prompt = prompt)

        assertEquals("Turn on flashlight when dark", data.prompt)
        assertNull(data.downloadProgress)
        assertNull(data.generatedAutomation)
    }

    @Test
    fun `download progress state formatting`() {
        val progress = 75
        val data = AiBuilderData(downloadProgress = progress)

        assertNotNull(data.downloadProgress)
        assertEquals(75, data.downloadProgress)
        val floatProgress = (data.downloadProgress!! / 100f).coerceIn(0f, 1f)
        assertEquals(0.75f, floatProgress, 0.001f)
    }

    @Test
    fun `isGenerating state representation`() {
        val data = AiBuilderData(isGenerating = true)
        assertTrue(data.isGenerating)
    }

    @Test
    fun `generated preview card data rendering`() {
        val action = Action(actionType = ActionType.SYSTEM_TOGGLE, target = "FLASHLIGHT", state = "ON")
        val automation = Automation(
            name = "AI Shortcut: Flashlight On",
            actionsJson = ActionConverter().fromActionList(listOf(action)),
            triggerType = "AI_GENERATED"
        )
        val data = AiBuilderData(generatedAutomation = automation)

        val state: UiState<AiBuilderData> = UiState.Success(data)
        assertTrue(state is UiState.Success)

        val resultData = (state as UiState.Success).data
        assertNotNull(resultData.generatedAutomation)
        assertEquals("AI Shortcut: Flashlight On", resultData.generatedAutomation?.name)
        assertEquals("AI_GENERATED", resultData.generatedAutomation?.triggerType)
    }

    @Test
    fun `error banner error state handling`() {
        val state: UiState<AiBuilderData> = UiState.Error("Model download failed: Connection timeout")

        assertTrue(state is UiState.Error)
        assertEquals("Model download failed: Connection timeout", (state as UiState.Error).message)
    }
}
