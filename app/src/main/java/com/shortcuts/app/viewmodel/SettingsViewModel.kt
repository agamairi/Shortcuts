package com.shortcuts.app.viewmodel

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shortcuts.app.data.ThemePreferences
import com.shortcuts.app.service.DownloadState
import com.shortcuts.app.service.ModelDownloaderService
import com.shortcuts.app.ui.theme.ThemeMode
import com.shortcuts.app.util.AccessibilityStatusChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SettingsViewModel(
    val downloadState: StateFlow<DownloadState> = ModelDownloaderService.downloadState,
    private val startDownloadAction: ((Context) -> Unit)? = { ctx -> ModelDownloaderService.startDownload(ctx) },
    private val deleteModelAction: ((Context) -> Boolean)? = { ctx -> ModelDownloaderService.deleteModel(ctx) },
    initialThemePreferences: ThemePreferences? = null
) : ViewModel() {

    private val _isAccessibilityServiceEnabled = MutableStateFlow(false)
    val isAccessibilityServiceEnabled: StateFlow<Boolean> = _isAccessibilityServiceEnabled.asStateFlow()

    private var themePreferences: ThemePreferences? = null
    private var themeModeJob: Job? = null
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    init {
        initialThemePreferences?.let { preferences ->
            themePreferences = preferences
            observeThemeMode(preferences)
        }
    }

    fun getThemePreferences(context: Context): ThemePreferences {
        return themePreferences ?: ThemePreferences(context.applicationContext).also { preferences ->
            themePreferences = preferences
            observeThemeMode(preferences)
        }
    }

    fun updateThemeMode(context: Context, mode: ThemeMode) {
        viewModelScope.launch {
            getThemePreferences(context).updateThemeMode(mode)
        }
    }

    private fun observeThemeMode(preferences: ThemePreferences) {
        themeModeJob?.cancel()
        themeModeJob = viewModelScope.launch {
            preferences.themeModeFlow.collectLatest { mode -> _themeMode.value = mode }
        }
    }

    fun refreshAccessibilityStatus(context: Context? = null) {
        if (context != null) {
            _isAccessibilityServiceEnabled.value = AccessibilityStatusChecker.isAccessibilityEnabled(context)
        }
    }

    fun downloadModel(context: Context) {
        startDownloadAction?.invoke(context)
    }

    fun deleteModel(context: Context) {
        deleteModelAction?.invoke(context)
    }

    companion object {
        fun isAccessibilityServiceEnabledFromSettingString(
            enabledServices: String?,
            packageName: String = "com.shortcuts.app",
            serviceClassName: String = "com.shortcuts.app.service.AutomationAccessibilityService"
        ): Boolean {
            return AccessibilityStatusChecker.isAccessibilityServiceEnabledFromSettingString(
                enabledServices,
                packageName,
                serviceClassName
            )
        }
    }
}
