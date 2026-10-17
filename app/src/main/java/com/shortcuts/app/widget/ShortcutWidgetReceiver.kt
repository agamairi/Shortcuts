package com.shortcuts.app.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.WidgetConfigSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShortcutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShortcutWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    refreshAllShortcutWidgets(context.applicationContext)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context).widgetConfigDao()
                WidgetCleanupHelper.cleanupBindings(appWidgetIds) { id ->
                    dao.deleteConfig(id, WidgetConfigSource.UNIFIED.name)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
