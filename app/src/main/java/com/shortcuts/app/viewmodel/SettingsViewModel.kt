package com.shortcuts.app.viewmodel

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import com.shortcuts.app.service.DownloadState
import com.shortcuts.app.service.ModelDownloaderService
import com.shortcuts.app.util.AccessibilityStatusChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsViewModel(
    val downloadState: StateFlow<DownloadState> = ModelDownloaderService.downloadState,
    private val startDownloadAction: ((Context) -> Unit)? = { ctx -> ModelDownloaderService.startDownload(ctx) },
    private val deleteModelAction: ((Context) -> Boolean)? = { ctx -> ModelDownloaderService.deleteModel(ctx) }
) : ViewModel() {

    private val _isAccessibilityServiceEnabled = MutableStateFlow(false)
    val isAccessibilityServiceEnabled: StateFlow<Boolean> = _isAccessibilityServiceEnabled.asStateFlow()

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
