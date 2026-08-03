package com.shortcuts.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shortcuts.app.data.Automation
import com.shortcuts.app.ui.screens.DashboardScreenContent
import com.shortcuts.app.ui.state.UiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dashboardScreen_displaysLoadingSpinner() {
        composeTestRule.setContent {
            DashboardScreenContent(
                uiState = UiState.Loading,
                onNavigateToManualBuilder = {},
                onNavigateToAiBuilder = {}
            )
        }

        composeTestRule.onNodeWithText("Loading shortcuts...").assertIsDisplayed()
    }

    @Test
    fun dashboardScreen_displaysEmptyStateWhenListIsEmpty() {
        composeTestRule.setContent {
            DashboardScreenContent(
                uiState = UiState.Success(emptyList()),
                onNavigateToManualBuilder = {},
                onNavigateToAiBuilder = {}
            )
        }

        composeTestRule.onNodeWithText("No shortcuts created yet").assertIsDisplayed()
    }

    @Test
    fun dashboardScreen_displaysAutomationsList() {
        val automations = listOf(
            Automation(id = 1, name = "WiFi Auto", actionsJson = "[]", isActive = true, triggerType = "MANUAL")
        )

        composeTestRule.setContent {
            DashboardScreenContent(
                uiState = UiState.Success(automations),
                onNavigateToManualBuilder = {},
                onNavigateToAiBuilder = {}
            )
        }

        composeTestRule.onNodeWithText("WiFi Auto").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trigger: MANUAL").assertIsDisplayed()
    }
}
