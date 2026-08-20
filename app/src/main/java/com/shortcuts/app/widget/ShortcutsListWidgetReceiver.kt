package com.shortcuts.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.WidgetConfigSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ShortcutsListWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = ShortcutsListWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        CoroutineScope(Dispatchers.IO).launch {
            val dao = AppDatabase.getDatabase(context).widgetListBindingDao()
            WidgetCleanupHelper.cleanupBindings(appWidgetIds) { id ->
                dao.deleteBinding(id)
                AppDatabase.getDatabase(context).widgetConfigDao()
                    .deleteConfig(id, WidgetConfigSource.LIST.name)
            }
        }
    }
}
