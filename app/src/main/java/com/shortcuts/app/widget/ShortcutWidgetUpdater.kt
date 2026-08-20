package com.shortcuts.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/** Re-renders every provider when Room reports that shortcuts changed. */
object ShortcutWidgetUpdater {
    suspend fun refreshAll(context: Context) {
        ShortcutWidget().updateAll(context)
        AutomationWidget().updateAll(context)
        CustomWidget().updateAll(context)
        GridWidget().updateAll(context)
        GreetingWidget().updateAll(context)
        ShortcutsListWidget().updateAll(context)
    }
}
