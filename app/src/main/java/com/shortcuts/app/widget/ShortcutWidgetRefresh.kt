package com.shortcuts.app.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.delay

private const val TAG = "ShortcutWidgetRefresh"

/** How long to wait for Glance to learn about a widget the launcher only just pinned. */
private const val GLANCE_ID_ATTEMPTS = 5
private const val GLANCE_ID_RETRY_DELAY_MS = 200L

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
    repeat(GLANCE_ID_ATTEMPTS) { attempt ->
        val glanceId = runCatching {
            GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
        }.getOrNull()

        if (glanceId != null) {
            runCatching { ShortcutWidget().update(context, glanceId) }
                .onFailure { Log.e(TAG, "Redrawing widget $appWidgetId failed", it) }
            return
        }
        if (attempt < GLANCE_ID_ATTEMPTS - 1) {
            delay(GLANCE_ID_RETRY_DELAY_MS)
        }
    }

    Log.w(TAG, "No GlanceId for appWidgetId=$appWidgetId after $GLANCE_ID_ATTEMPTS attempts; updating all instances")
    runCatching { ShortcutWidget().updateAll(context) }
        .onFailure { Log.e(TAG, "Falling back to updateAll failed", it) }
}
