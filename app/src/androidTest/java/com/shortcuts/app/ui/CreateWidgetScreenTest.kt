package com.shortcuts.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shortcuts.app.ui.screens.CreateWidgetScreen
import com.shortcuts.app.viewmodel.CustomWidgetViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreateWidgetScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun createWidgetScreen_rendersTitleAndSections() {
        val viewModel = CustomWidgetViewModel()
        composeTestRule.setContent {
            CreateWidgetScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToAiBuilder = {}
            )
        }

        composeTestRule.onNodeWithText("Create Custom Widget").assertIsDisplayed()
        composeTestRule.onNodeWithText("Describe it with AI").assertIsDisplayed()
        composeTestRule.onNodeWithText("Or customize manually").assertIsDisplayed()
        composeTestRule.onNodeWithText("Live Widget Preview").assertIsDisplayed()
    }
}
