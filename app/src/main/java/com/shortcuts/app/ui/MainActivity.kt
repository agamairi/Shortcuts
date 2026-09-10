package com.shortcuts.app.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.shortcuts.app.data.ThemePreferences
import com.shortcuts.app.service.AutomationRecorder
import com.shortcuts.app.ui.theme.ShortcutsTheme as AppShortcutsTheme
import com.shortcuts.app.ui.theme.ThemeMode
import com.shortcuts.app.widget.ShortcutWidget
import kotlinx.coroutines.launch

/**
 * Compatibility entry point retained for the legacy widget configuration activities. All new
 * callers pass [mode], while old callers still correctly follow the system setting.
 */
@Composable
fun ShortcutsTheme(
    mode: ThemeMode = if (isSystemInDarkTheme()) ThemeMode.DARK else ThemeMode.LIGHT,
    content: @Composable () -> Unit
) {
    AppShortcutsTheme(mode = mode, content = content)
}

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_START_DESTINATION = "com.shortcuts.app.extra.START_DESTINATION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AutomationRecorder.restoreSession(applicationContext)
        // Fix #2: After process death and recreation, restoreSession() may set isRecording=true
        // but only startRecording() actually starts RecorderSessionService. Rather than silently
        // resuming recording with NO foreground notification and NO user-visible controls —
        // which is both a UX bug and an Android policy problem — we stop the stale recording
        // and preserve the captured steps as a reviewable draft. The user can then resume or
        // discard deliberately from the recorder screen's REVIEW state.
        if (AutomationRecorder.isRecording.value) {
            AutomationRecorder.stopRecording(applicationContext, com.shortcuts.app.service.RecorderSessionStopReason.DISCARDED)
        }
        // A widget that was pinned while the refresh was broken renders "Tap to set up" forever:
        // its config row is fine, but the launcher caches the last RemoteViews and never asks for
        // another render on its own. Redrawing every instance on launch lets those self-heal.
        lifecycleScope.launch {
            val manager = androidx.glance.appwidget.GlanceAppWidgetManager(applicationContext)
            val glanceIds = runCatching { manager.getGlanceIds(ShortcutWidget::class.java) }.getOrElse { emptyList() }
            for (id in glanceIds) {
                runCatching { ShortcutWidget().update(applicationContext, id) }
                    .onSuccess { Log.i("MainActivity", "Placed widget redraw succeeded for glanceId=$id") }
                    .onFailure { Log.w("MainActivity", "Placed widget redraw failed for glanceId=$id", it) }
            }
        }
        val startDestination = intent?.getStringExtra(EXTRA_START_DESTINATION) ?: "dashboard"
        val themePreferences = ThemePreferences(applicationContext)
        setContent {
            val themeMode by themePreferences.themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)
            ShortcutsTheme(mode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ShortcutsNavigation(startDestination = startDestination)
                }
            }
        }
    }
}
