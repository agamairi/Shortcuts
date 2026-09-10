package com.shortcuts.app.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shortcuts.app.data.ThemePreferences
import com.shortcuts.app.ui.theme.ThemeMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.coEvery
import io.mockk.coVerify
import org.junit.Test

class SettingsViewModelTest {

    @Test
    fun `theme mode is exposed and updated through the view model`() = runTest(StandardTestDispatcher()) {
        // Deliberately no real DataStore here: spinning one up inside the ViewModel test leaked
        // uncaught cancellation into unrelated tests. Real persistence is covered by
        // ThemePreferencesTest; this test owns the ViewModel wiring only.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val modes = MutableStateFlow(ThemeMode.SYSTEM)
            val preferences = mockk<ThemePreferences>(relaxed = true)
            every { preferences.themeModeFlow } returns modes
            coEvery { preferences.updateThemeMode(any()) } coAnswers { modes.value = firstArg() }

            val viewModel = SettingsViewModel(initialThemePreferences = preferences)
            advanceUntilIdle()
            assertEquals(ThemeMode.SYSTEM, viewModel.themeMode.value)

            viewModel.updateThemeMode(mockk(relaxed = true), ThemeMode.DARK)
            advanceUntilIdle()
            assertEquals(ThemeMode.DARK, viewModel.themeMode.value)
            coVerify { preferences.updateThemeMode(ThemeMode.DARK) }

            viewModel.updateThemeMode(mockk(relaxed = true), ThemeMode.LIGHT)
            advanceUntilIdle()
            assertEquals(ThemeMode.LIGHT, viewModel.themeMode.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `downloadModel delegates to startDownloadAction`() {
        val startDownloadAction = mockk<(Context) -> Unit>(relaxed = true)
        val viewModel = SettingsViewModel(
            startDownloadAction = startDownloadAction
        )
        val context = mockk<Context>()

        viewModel.downloadModel(context)

        verify { startDownloadAction.invoke(context) }
    }

    @Test
    fun `deleteModel delegates to deleteModelAction`() {
        val deleteModelAction = mockk<(Context) -> Boolean>()
        val context = mockk<Context>()
        every { deleteModelAction.invoke(context) } returns true

        val viewModel = SettingsViewModel(
            deleteModelAction = deleteModelAction
        )

        viewModel.deleteModel(context)

        verify { deleteModelAction.invoke(context) }
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString with null or empty returns false`() {
        assertFalse(SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(null))
        assertFalse(SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(""))
        assertFalse(SettingsViewModel.isAccessibilityServiceEnabledFromSettingString("   "))
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString with target service returns true`() {
        assertTrue(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(
                "com.shortcuts.app/.service.AutomationAccessibilityService"
            )
        )
        assertTrue(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(
                "com.shortcuts.app/com.shortcuts.app.service.AutomationAccessibilityService"
            )
        )
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString with other service returns false`() {
        assertFalse(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(
                "com.other.app/.OtherAccessibilityService"
            )
        )
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString with multiple services containing target returns true`() {
        val settingString = "com.other.app/.OtherService:com.shortcuts.app/.service.AutomationAccessibilityService"
        assertTrue(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(settingString)
        )
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString with malformed string returns false`() {
        assertFalse(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString("malformed_service_string_without_slash")
        )
        assertFalse(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString("com.shortcuts.app.service.AutomationAccessibilityService")
        )
    }
}
