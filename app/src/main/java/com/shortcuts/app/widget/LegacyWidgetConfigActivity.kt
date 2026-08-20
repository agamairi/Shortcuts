package com.shortcuts.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge

/**
 * Compatibility entry point for a provider that was published before the adaptive widget.
 *
 * Android keeps a provider's configured Activity as part of its persisted widget metadata, so
 * these activities must remain available. New configuration is nevertheless performed by the
 * unified picker, which stores the same [AppWidgetManager.EXTRA_APPWIDGET_ID].
 */
abstract class LegacyWidgetConfigActivity : ComponentActivity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        startActivityForResult(
            Intent(this, ShortcutWidgetConfigActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            LEGACY_CONFIG_REQUEST_CODE
        )
    }

    @Deprecated("The legacy provider has to relay its launcher result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != LEGACY_CONFIG_REQUEST_CODE) return

        val completion = legacyWidgetConfigCompletion(appWidgetId, resultCode)
        if (completion == null) {
            setResult(resultCode)
        } else {
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, completion.appWidgetId)
            )
        }
        finish()
    }
}

/** Pure result contract shared by all legacy Activity relays and its JVM test. */
internal data class LegacyWidgetConfigCompletion(val appWidgetId: Int)

internal fun legacyWidgetConfigCompletion(
    appWidgetId: Int,
    resultCode: Int
): LegacyWidgetConfigCompletion? = when {
    appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID -> null
    resultCode != Activity.RESULT_OK -> null
    else -> LegacyWidgetConfigCompletion(appWidgetId)
}

private const val LEGACY_CONFIG_REQUEST_CODE = 481
