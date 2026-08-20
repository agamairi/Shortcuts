package com.shortcuts.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager

/** Renders a newly configured provider instance instead of leaving Glance's setup state stale. */
internal suspend fun refreshShortcutWidget(context: Context, appWidgetId: Int) {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    ShortcutWidget().update(context, glanceId)
}
