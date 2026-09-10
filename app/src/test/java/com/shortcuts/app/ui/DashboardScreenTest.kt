package com.shortcuts.app.ui

import com.shortcuts.app.data.Automation
import com.shortcuts.app.ui.state.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardScreenTest {

    @Test
    fun `verify UiState Loading representation`() {
        val state: UiState<List<Automation>> = UiState.Loading
        assertTrue(state is UiState.Loading)
    }

    @Test
    fun `verify UiState Success with automations list`() {
        val automations = listOf(
            Automation(id = 101, name = "Morning Routine", actionsJson = "[]", isActive = true, triggerType = "MANUAL"),
            Automation(id = 102, name = "Night Mode", actionsJson = "[]", isActive = false, triggerType = "SCHEDULE")
        )
        val state: UiState<List<Automation>> = UiState.Success(automations)

        assertTrue(state is UiState.Success)
        val data = (state as UiState.Success).data
        assertEquals(2, data.size)
        assertEquals("Morning Routine", data[0].name)
        assertTrue(data[0].isActive)
    }

    @Test
    fun `verify UiState Empty list representation`() {
        val state: UiState<List<Automation>> = UiState.Success(emptyList())

        assertTrue(state is UiState.Success)
        assertTrue((state as UiState.Success).data.isEmpty())
    }

    @Test
    fun `verify UiState Error representation`() {
        val state: UiState<List<Automation>> = UiState.Error("Database disk I/O error")

        assertTrue(state is UiState.Error)
        assertEquals("Database disk I/O error", (state as UiState.Error).message)
    }

    @Test
    fun `verify toggle active automation logic`() {
        val original = Automation(id = 1, name = "Test", actionsJson = "[]", isActive = true)
        val toggled = original.copy(isActive = !original.isActive)

        assertEquals(false, toggled.isActive)
        assertEquals(original.id, toggled.id)
    }
}
