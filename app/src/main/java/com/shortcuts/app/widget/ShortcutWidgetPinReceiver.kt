package com.shortcuts.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shortcuts.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Persists the selected shortcut after the launcher assigns an id to a pinned widget. */
class ShortcutWidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ShortcutWidgetPinRequest.ACTION_WIDGET_PINNED) return

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val automationId = intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_AUTOMATION_ID, 0)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || automationId <= 0) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getDatabase(context.applicationContext)
                database.widgetConfigDao().upsertConfig(
                    unifiedWidgetConfig(appWidgetId, listOf(automationId))
                )
                refreshShortcutWidget(context.applicationContext, appWidgetId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
