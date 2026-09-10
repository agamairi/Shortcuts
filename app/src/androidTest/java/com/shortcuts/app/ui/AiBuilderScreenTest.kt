package com.shortcuts.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shortcuts.app.ui.screens.AiBuilderScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiBuilderScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun aiBuilderScreen_rendersPromptInputAndButton() {
        composeTestRule.setContent {
            AiBuilderScreen(onNavigateBack = {})
        }

        composeTestRule.onNodeWithText("AI Shortcuts Builder").assertIsDisplayed()
        composeTestRule.onNodeWithText("Describe your automated workflow:").assertIsDisplayed()
        composeTestRule.onNodeWithText("Download Model & Generate").assertIsDisplayed()
    }
}
