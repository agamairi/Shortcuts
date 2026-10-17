package com.shortcuts.app.viewmodel

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import com.shortcuts.app.data.ThemePreferences
import com.shortcuts.app.diagnostics.DefaultDiagnosticsController
import com.shortcuts.app.diagnostics.DiagnosticsController
import com.shortcuts.app.diagnostics.DiagnosticsState
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DiagnosticsUiState(
    val enabled: Boolean = true,
    val sizeLabel: String = "0 B",
    val sinceLabel: String = "",
    val isEmpty: Boolean = true
)

class SettingsViewModel(
    val downloadState: StateFlow<DownloadState> = ModelDownloaderService.downloadState,
    private val startDownloadAction: ((Context) -> Unit)? = { ctx -> ModelDownloaderService.startDownload(ctx) },
    private val deleteModelAction: ((Context) -> Boolean)? = { ctx -> ModelDownloaderService.deleteModel(ctx) },
    initialThemePreferences: ThemePreferences? = null,
    private val diagnosticsController: DiagnosticsController = DefaultDiagnosticsController(),
    private val diagnosticsDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
) : ViewModel() {

    private val _isAccessibilityServiceEnabled = MutableStateFlow(false)
    val isAccessibilityServiceEnabled: StateFlow<Boolean> = _isAccessibilityServiceEnabled.asStateFlow()

    private var themePreferences: ThemePreferences? = null
    private var themeModeJob: Job? = null
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _diagnosticsState = MutableStateFlow(
        toUiState(diagnosticsController.state.value)
    )
    val diagnosticsState: StateFlow<DiagnosticsUiState> = _diagnosticsState.asStateFlow()

    init {
        initialThemePreferences?.let { preferences ->
            themePreferences = preferences
            observeThemeMode(preferences)
        }
    }

    private fun toUiState(state: DiagnosticsState): DiagnosticsUiState {
        val isEmpty = state.totalBytes == 0L
        return DiagnosticsUiState(
            enabled = state.enabled,
            sizeLabel = formatSizeLabel(state.totalBytes),
            sinceLabel = formatSinceLabel(state.oldestMillis, isEmpty),
            isEmpty = isEmpty
        )
    }

    private fun formatSizeLabel(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        else -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    }

    private fun formatSinceLabel(oldestMillis: Long?, isEmpty: Boolean): String {
        if (oldestMillis == null || isEmpty) return ""
        return try {
            val dt = Instant.ofEpochMilli(oldestMillis).atZone(ZoneId.systemDefault())
            val dateStr = DateTimeFormatter.ofPattern("d MMM", Locale.US).format(dt)
            "since $dateStr"
        } catch (_: Throwable) {
            ""
        }
    }

    private fun updateDiagnosticsUiState() {
        _diagnosticsState.value = toUiState(diagnosticsController.state.value)
    }

    fun setDiagnosticsEnabled(context: Context, enabled: Boolean) {
        diagnosticsController.setEnabled(context, enabled)
        updateDiagnosticsUiState()
    }

    fun clearDiagnostics() {
        diagnosticsController.clear()
        updateDiagnosticsUiState()
    }

    fun diagnosticsShareIntent(context: Context): Intent? {
        return runCatching {
            val file = diagnosticsController.buildExport(context)
            diagnosticsController.shareIntent(context, file)
        }.getOrNull()
    }

    /** Reads the log's size and age off the main thread (a few small file reads). */
    fun refreshDiagnostics() {
        viewModelScope.launch(diagnosticsDispatcher) {
            diagnosticsController.refreshState()
            updateDiagnosticsUiState()
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
            packageName: String
        ): Boolean {
            return AccessibilityStatusChecker.isAccessibilityServiceEnabledFromSettingString(
                enabledServices,
                packageName
            )
        }
    }
}
