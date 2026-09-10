package com.shortcuts.app.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.service.ShortcutRunRequest
import com.shortcuts.app.service.ShortcutRunSource
import com.shortcuts.app.service.ShortcutRunner
import com.shortcuts.app.service.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RunAutomationCallback : ActionCallback {
    companion object {
        val AutomationIdParamKey = ActionParameters.Key<Int>("automation_id")
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val explicitId = parameters[AutomationIdParamKey]
                val automationIdToRun = AutomationIdResolver.resolveAutomationId(explicitId) {
                    val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
                    val config = db.widgetConfigDao().getConfig(appWidgetId, com.shortcuts.app.data.WidgetConfigSource.UNIFIED.name)
                    if (config == null) {
                        Log.w("WidgetCallback", "No config found for widget $appWidgetId")
                    }
                    config?.automationIdsJson?.let { com.shortcuts.app.widget.WidgetConfigParser.automationIds(it).firstOrNull() }
                }

                if (automationIdToRun == null) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "This widget is no longer configured with a shortcut.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext
                }

                val outcome = ShortcutRunner(context.applicationContext).run(
                    ShortcutRunRequest(
                        shortcutId = automationIdToRun,
                        source = ShortcutRunSource.WIDGET
                    )
                )
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(context, outcome.userMessage(), android.widget.Toast.LENGTH_LONG).show()
                }
                Log.d("WidgetCallback", outcome.userMessage())
            } catch (e: Exception) {
                Log.e("WidgetCallback", "Failed to execute widget automation", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "RepeatKit couldn't start this shortcut right now. Please try again.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
