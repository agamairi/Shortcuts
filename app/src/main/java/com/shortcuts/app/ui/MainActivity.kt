package com.shortcuts.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.shortcuts.app.data.ThemePreferences
import android.util.Log
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import com.shortcuts.app.widget.ShortcutWidget
import kotlinx.coroutines.launch
import com.shortcuts.app.service.AutomationRecorder
import com.shortcuts.app.ui.theme.ShortcutsTheme as AppShortcutsTheme
import com.shortcuts.app.ui.theme.ThemeMode

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
        // A widget that was pinned while the refresh was broken renders "Tap to set up" forever:
        // its config row is fine, but the launcher caches the last RemoteViews and never asks for
        // another render on its own. Redrawing every instance on launch lets those self-heal.
        lifecycleScope.launch {
            runCatching { ShortcutWidget().updateAll(applicationContext) }
                .onFailure { Log.w("MainActivity", "Could not refresh placed widgets", it) }
        }
        val startDestination = intent?.getStringExtra(EXTRA_START_DESTINATION) ?: "dashboard"
        val themePreferences = ThemePreferences(applicationContext)
        setContent {
            val themeMode by themePreferences.themeModeFlow.collectAsState(initial = ThemeMode.SYSTEM)
            ShortcutsTheme(mode = themeMode) {
                RuntimePermissionPrompts()
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

private data class RuntimePermissionPrompt(
    val permission: String,
    val title: String,
    val explanation: String,
    val minimumApi: Int
)

@Composable
private fun RuntimePermissionPrompts() {
    val activity = androidx.compose.ui.platform.LocalContext.current as? ComponentActivity ?: return
    val prompts = remember {
        listOf(
            RuntimePermissionPrompt(
                Manifest.permission.POST_NOTIFICATIONS,
                "Show shortcut results",
                "Allow notifications so a widget can tell you which shortcut step needs attention.",
                Build.VERSION_CODES.TIRAMISU
            ),
            RuntimePermissionPrompt(
                Manifest.permission.BLUETOOTH_CONNECT,
                "Control Bluetooth shortcuts",
                "Allow Bluetooth access so Bluetooth shortcuts can open Android's confirmation panel.",
                Build.VERSION_CODES.S
            ),
            RuntimePermissionPrompt(
                Manifest.permission.CAMERA,
                "Use flashlight shortcuts",
                "Allow camera access only to turn this device's flashlight on or off.",
                Build.VERSION_CODES.M
            )
        )
    }
    var pendingPrompt by remember { mutableStateOf<RuntimePermissionPrompt?>(null) }
    fun nextMissingPermission(): RuntimePermissionPrompt? = prompts.firstOrNull { prompt ->
        Build.VERSION.SDK_INT >= prompt.minimumApi &&
            ContextCompat.checkSelfPermission(activity, prompt.permission) != PackageManager.PERMISSION_GRANTED
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingPrompt = nextMissingPermission()
    }

    LaunchedEffect(Unit) {
        pendingPrompt = nextMissingPermission()
    }
    pendingPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { pendingPrompt = null },
            title = { Text(prompt.title) },
            text = { Text(prompt.explanation) },
            confirmButton = {
                TextButton(onClick = { permissionLauncher.launch(prompt.permission) }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPrompt = null }) { Text("Not now") }
            }
        )
    }
}
