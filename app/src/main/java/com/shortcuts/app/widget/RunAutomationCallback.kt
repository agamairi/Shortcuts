package com.shortcuts.app.widget

import android.content.Context
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.service.AutomationExecutionService
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
                    val binding = db.widgetBindingDao().getBinding(appWidgetId)
                    if (binding == null) {
                        Log.w("WidgetCallback", "No binding found for widget $appWidgetId")
                    }
                    binding?.automationId
                }

                if (automationIdToRun == null) {
                    return@withContext
                }

                val automation = db.automationDao().getAutomationById(automationIdToRun)
                if (automation == null) {
                    Log.w("WidgetCallback", "Automation $automationIdToRun not found")
                    return@withContext
                }

                AutomationExecutionService.start(context, automation.id)
                Log.d("WidgetCallback", "Started foreground execution for '${automation.name}'")
            } catch (e: Exception) {
                Log.e("WidgetCallback", "Failed to execute widget automation", e)
            }
        }
    }
}
