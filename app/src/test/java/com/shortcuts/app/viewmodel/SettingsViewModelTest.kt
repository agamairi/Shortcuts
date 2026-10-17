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
import com.shortcuts.app.diagnostics.DiagnosticsController
import com.shortcuts.app.diagnostics.DiagnosticsState
import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModelTest {

    private val packageName = "com.shortcuts.app"

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
        assertFalse(SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(null, packageName))
        assertFalse(SettingsViewModel.isAccessibilityServiceEnabledFromSettingString("", packageName))
        assertFalse(SettingsViewModel.isAccessibilityServiceEnabledFromSettingString("   ", packageName))
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString with target service returns true`() {
        assertTrue(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(
                "com.shortcuts.app/.service.AutomationAccessibilityService",
                packageName
            )
        )
        assertTrue(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(
                "com.shortcuts.app/com.shortcuts.app.service.AutomationAccessibilityService",
                packageName
            )
        )
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString with other service returns false`() {
        assertFalse(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(
                "com.other.app/.OtherAccessibilityService",
                packageName
            )
        )
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString with multiple services containing target returns true`() {
        val settingString = "com.other.app/.OtherService:com.shortcuts.app/.service.AutomationAccessibilityService"
        assertTrue(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(settingString, packageName)
        )
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString with malformed string returns false`() {
        assertFalse(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(
                "malformed_service_string_without_slash",
                packageName
            )
        )
        assertFalse(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(
                "com.shortcuts.app.service.AutomationAccessibilityService",
                packageName
            )
        )
    }

    @Test
    fun `isAccessibilityServiceEnabledFromSettingString distinguishes the dev build from the normal install`() {
        val devOnly = "com.shortcuts.app.dev/com.shortcuts.app.service.AutomationAccessibilityService"
        assertTrue(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(devOnly, "com.shortcuts.app.dev")
        )
        assertFalse(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(devOnly, "com.shortcuts.app")
        )
        assertFalse(
            SettingsViewModel.isAccessibilityServiceEnabledFromSettingString(
                "com.shortcuts.app/com.shortcuts.app.service.AutomationAccessibilityService",
                "com.shortcuts.app.dev"
            )
        )
    }

    @Test
    fun `diagnosticsState exposes controller state`() {
        val fakeController = FakeDiagnosticsController(
            DiagnosticsState(enabled = true, totalBytes = 2048L, oldestMillis = 1700000000000L)
        )
        val viewModel = SettingsViewModel(diagnosticsController = fakeController)

        val uiState = viewModel.diagnosticsState.value
        assertTrue(uiState.enabled)
        assertEquals("2.0 KB", uiState.sizeLabel)
        assertFalse(uiState.isEmpty)
    }

    @Test
    fun `setDiagnosticsEnabled delegates to controller and updates state`() {
        val fakeController = FakeDiagnosticsController(
            DiagnosticsState(enabled = false, totalBytes = 0L, oldestMillis = null)
        )
        val viewModel = SettingsViewModel(diagnosticsController = fakeController)
        val context = mockk<Context>(relaxed = true)

        viewModel.setDiagnosticsEnabled(context, true)

        assertEquals(true, fakeController.setEnabledCalledWith)
        assertTrue(viewModel.diagnosticsState.value.enabled)
    }

    @Test
    fun `clearDiagnostics delegates to controller and updates state`() {
        val fakeController = FakeDiagnosticsController(
            DiagnosticsState(enabled = true, totalBytes = 1024L * 1024L, oldestMillis = 1700000000000L)
        )
        val viewModel = SettingsViewModel(diagnosticsController = fakeController)

        viewModel.clearDiagnostics()

        assertTrue(fakeController.clearCalled)
        assertTrue(viewModel.diagnosticsState.value.isEmpty)
        assertEquals("0 B", viewModel.diagnosticsState.value.sizeLabel)
    }

    @Test
    fun `diagnosticsShareIntent delegates to controller`() {
        val expectedIntent = mockk<Intent>()
        val fakeController = FakeDiagnosticsController()
        fakeController.returnShareIntent = expectedIntent
        val viewModel = SettingsViewModel(diagnosticsController = fakeController)
        val context = mockk<Context>()

        val resultIntent = viewModel.diagnosticsShareIntent(context)

        assertEquals(expectedIntent, resultIntent)
    }

    @Test
    fun `refreshDiagnostics shows a log that grew after Settings was created`() = runTest(StandardTestDispatcher()) {
        // Seen on device: the section said "Empty" while the log held 31 KB, because the size was
        // only read once at app start, before anything had been written.
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val fakeController = FakeDiagnosticsController(DiagnosticsState(enabled = true, totalBytes = 0L, oldestMillis = null))
            val viewModel = SettingsViewModel(diagnosticsController = fakeController, diagnosticsDispatcher = dispatcher)
            assertTrue(viewModel.diagnosticsState.value.isEmpty)

            fakeController.stateOnRefresh = DiagnosticsState(enabled = true, totalBytes = 31_708L, oldestMillis = 1700000000000L)
            viewModel.refreshDiagnostics()
            advanceUntilIdle()

            assertTrue(!viewModel.diagnosticsState.value.isEmpty)
            assertEquals("31.0 KB", viewModel.diagnosticsState.value.sizeLabel)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class FakeDiagnosticsController(
        initialState: DiagnosticsState = DiagnosticsState(enabled = true, totalBytes = 1024L * 1024L, oldestMillis = 1700000000000L)
    ) : DiagnosticsController {
        private val _state = MutableStateFlow(initialState)
        override val state: StateFlow<DiagnosticsState> = _state

        var setEnabledCalledWith: Boolean? = null
        var clearCalled = false
        var buildExportFile: File = File("/fake/export.txt")
        var returnShareIntent: Intent? = mockk<Intent>()

        override fun setEnabled(context: Context, enabled: Boolean) {
            setEnabledCalledWith = enabled
            _state.value = _state.value.copy(enabled = enabled)
        }

        override fun isEnabled(): Boolean = _state.value.enabled

        override fun clear() {
            clearCalled = true
            _state.value = _state.value.copy(totalBytes = 0L, oldestMillis = null)
        }

        /** Simulates the log having grown since the view model was created. */
        var stateOnRefresh: DiagnosticsState? = null

        override fun refreshState() {
            stateOnRefresh?.let { _state.value = it }
        }

        override fun buildExport(context: Context): File = buildExportFile

        override fun shareIntent(context: Context, file: File): Intent = returnShareIntent ?: mockk()
    }
}
