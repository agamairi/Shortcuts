package com.shortcuts.app.viewmodel

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsViewModelTest {

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
