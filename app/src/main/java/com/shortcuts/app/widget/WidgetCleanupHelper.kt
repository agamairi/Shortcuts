package com.shortcuts.app.widget

object WidgetCleanupHelper {
    suspend fun cleanupBindings(appWidgetIds: IntArray, deleteBinding: suspend (Int) -> Unit) {
        for (id in appWidgetIds) {
            deleteBinding(id)
        }
    }
}
