package com.shortcuts.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shortcuts.app.ui.screens.ManualBuilderScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualBuilderScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun manualBuilderScreen_rendersFormAndChips() {
        composeTestRule.setContent {
            ManualBuilderScreen(onNavigateBack = {})
        }

        composeTestRule.onNodeWithText("Manual Builder").assertIsDisplayed()
        composeTestRule.onNodeWithText("Shortcut Name").assertIsDisplayed()
        composeTestRule.onNodeWithText("MANUAL").assertIsDisplayed()
        composeTestRule.onNodeWithText("WIDGET").assertIsDisplayed()
        composeTestRule.onNodeWithText("SCHEDULE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add Action").assertIsDisplayed()
    }
}
