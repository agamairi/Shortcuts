package com.shortcuts.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shortcuts.app.diagnostics.DiagnosticsController
import com.shortcuts.app.diagnostics.DiagnosticsState
import com.shortcuts.app.ui.screens.SettingsScreen
import com.shortcuts.app.ui.theme.ShortcutsTheme
import com.shortcuts.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SettingsDiagnosticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeDiagnosticsController : DiagnosticsController {
        private val _state = MutableStateFlow(
            DiagnosticsState(enabled = true, totalBytes = 1024L * 1024L, oldestMillis = 1700000000000L)
        )
        override val state: StateFlow<DiagnosticsState> = _state
        override fun setEnabled(context: Context, enabled: Boolean) {}
        override fun isEnabled(): Boolean = true
        override fun clear() {}
        override fun refreshState() {}
        override fun buildExport(context: Context): File = File("/fake/export.txt")
        override fun shareIntent(context: Context, file: File): Intent = Intent()
    }

    @Test
    fun settingsDiagnostics_rendersSwitchAndShareButton() {
        val fakeController = FakeDiagnosticsController()
        val viewModel = SettingsViewModel(diagnosticsController = fakeController)

        composeTestRule.setContent {
            ShortcutsTheme {
                SettingsScreen(
                    onNavigateBack = {},
                    onNavigateToHelp = {},
                    viewModel = viewModel
                )
            }
        }

        composeTestRule.onNodeWithText("Keep a diagnostics log").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share log").assertIsDisplayed()
    }
}
