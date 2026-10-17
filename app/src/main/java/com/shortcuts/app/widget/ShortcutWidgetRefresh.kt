package com.shortcuts.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "ShortcutWidgetRefresh"

/** How long to wait for Glance to learn about a widget the launcher only just pinned. */
private const val GLANCE_ID_ATTEMPTS = 5
private const val GLANCE_ID_RETRY_DELAY_MS = 200L

val PREF_KEY_LAST_UPDATE = longPreferencesKey("widget_last_update_timestamp")

private val refreshMutex = Mutex()

/**
 * Renders a newly configured provider instance instead of leaving Glance's setup state stale.
 *
 * The launcher can deliver the pin-success callback before Glance has mapped the new
 * `appWidgetId` to a `GlanceId`, and `getGlanceIdBy` THROWS rather than returning null in that
 * window. The throw used to escape into the receiver's coroutine and vanish, leaving a freshly
 * pinned widget stuck on "Tap to set up" forever even though its config row had been written.
 * So: retry briefly, then fall back to updating every instance of the provider.
 */
internal suspend fun refreshShortcutWidget(context: Context, appWidgetId: Int) {
    refreshMutex.withLock {
        val appContext = context.applicationContext
        val manager = GlanceAppWidgetManager(appContext)
        val awm = AppWidgetManager.getInstance(appContext)

        repeat(GLANCE_ID_ATTEMPTS) { attempt ->
            val glanceId = runCatching {
                manager.getGlanceIdBy(appWidgetId)
            }.getOrNull()

            if (glanceId != null) {
                runCatching {
                    updateAppWidgetState(appContext, glanceId) { prefs ->
                        prefs[PREF_KEY_LAST_UPDATE] = System.currentTimeMillis()
                    }
                    ShortcutWidget().update(appContext, glanceId)
                }.onSuccess {
                    Log.i(TAG, "Redrawing widget $appWidgetId (glanceId=$glanceId) succeeded")
                    // Mitigation: Some launchers cache the old RemoteViews and don't visually refresh
                    // until forced. Re-applying the options can act as a no-op nudge to re-inflate.
                    runCatching {
                        awm.updateAppWidgetOptions(appWidgetId, awm.getAppWidgetOptions(appWidgetId))
                    }.onFailure { Log.w(TAG, "Launcher nudge failed for widget $appWidgetId", it) }
                }.onFailure {
                    Log.e(TAG, "Redrawing widget $appWidgetId (glanceId=$glanceId) failed", it)
                    com.shortcuts.app.diagnostics.Diagnostics.log(
                        "widget",
                        "error",
                        mapOf("errorClass" to it.javaClass.name, "where" to "ShortcutWidgetRefresh.refreshShortcutWidget")
                    )
                }
                return
            }
            if (attempt < GLANCE_ID_ATTEMPTS - 1) {
                delay(GLANCE_ID_RETRY_DELAY_MS)
            }
        }

        Log.w(TAG, "No GlanceId for appWidgetId=$appWidgetId after $GLANCE_ID_ATTEMPTS attempts; updating all instances")
        val glanceIds = runCatching { manager.getGlanceIds(ShortcutWidget::class.java) }.getOrElse { emptyList() }
        for (id in glanceIds) {
            runCatching {
                updateAppWidgetState(appContext, id) { prefs ->
                    prefs[PREF_KEY_LAST_UPDATE] = System.currentTimeMillis()
                }
                ShortcutWidget().update(appContext, id)
                val resolvedAppWidgetId = runCatching { manager.getAppWidgetId(id) }.getOrNull()
                if (resolvedAppWidgetId != null && resolvedAppWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    runCatching {
                        awm.updateAppWidgetOptions(resolvedAppWidgetId, awm.getAppWidgetOptions(resolvedAppWidgetId))
                    }
                }
            }.onSuccess { Log.i(TAG, "Fallback redraw succeeded for glanceId=$id") }
             .onFailure {
                 Log.e(TAG, "Fallback redraw failed for glanceId=$id", it)
                 com.shortcuts.app.diagnostics.Diagnostics.log(
                     "widget",
                     "error",
                     mapOf("errorClass" to it.javaClass.name, "where" to "ShortcutWidgetRefresh.fallbackRedraw")
                 )
             }
        }
    }
}

internal suspend fun refreshAllShortcutWidgets(context: Context) {
    refreshMutex.withLock {
        val appContext = context.applicationContext
        val manager = GlanceAppWidgetManager(appContext)
        val awm = AppWidgetManager.getInstance(appContext)
        val glanceIds = runCatching { manager.getGlanceIds(ShortcutWidget::class.java) }.getOrElse { emptyList() }
        for (id in glanceIds) {
            runCatching {
                updateAppWidgetState(appContext, id) { prefs ->
                    prefs[PREF_KEY_LAST_UPDATE] = System.currentTimeMillis()
                }
                ShortcutWidget().update(appContext, id)
                val appWidgetId = runCatching { manager.getAppWidgetId(id) }.getOrNull()
                if (appWidgetId != null && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    runCatching {
                        awm.updateAppWidgetOptions(appWidgetId, awm.getAppWidgetOptions(appWidgetId))
                    }
                }
            }.onSuccess { Log.i(TAG, "Redraw all succeeded for glanceId=$id") }
             .onFailure {
                 Log.e(TAG, "Redraw all failed for glanceId=$id", it)
                 com.shortcuts.app.diagnostics.Diagnostics.log(
                     "widget",
                     "error",
                     mapOf("errorClass" to it.javaClass.name, "where" to "ShortcutWidgetRefresh.refreshAllShortcutWidgets")
                 )
             }
        }
    }
}
