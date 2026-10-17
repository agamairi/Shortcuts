package com.shortcuts.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.CustomWidgetTemplate
import com.shortcuts.app.data.WidgetConfig
import com.shortcuts.app.data.WidgetConfigSource
import com.shortcuts.app.data.WidgetLayoutKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class PinCallbackData(
    val appWidgetId: Int,
    val automationId: Int,
    val templateId: Int? = null
)

fun parsePinCallbackIntent(intent: Intent): PinCallbackData? {
    if (intent.action != ShortcutWidgetPinRequest.ACTION_WIDGET_PINNED) return null
    val appWidgetId = intent.getIntExtra(
        AppWidgetManager.EXTRA_APPWIDGET_ID,
        AppWidgetManager.INVALID_APPWIDGET_ID
    )
    val automationId = intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_AUTOMATION_ID, 0)
    if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || automationId <= 0) return null
    val templateId = intent.getIntExtra(ShortcutWidgetPinRequest.EXTRA_TEMPLATE_ID, 0).takeIf { it > 0 }
    return PinCallbackData(appWidgetId, automationId, templateId)
}

fun createPinnedWidgetConfig(
    appWidgetId: Int,
    automationId: Int,
    templateId: Int? = null,
    template: CustomWidgetTemplate? = null
): WidgetConfig {
    return WidgetConfig(
        widgetId = appWidgetId,
        sourceType = WidgetConfigSource.UNIFIED.name,
        automationIdsJson = Gson().toJson(listOf(automationId)),
        templateId = templateId,
        label = template?.label,
        colorKey = template?.colorKey,
        iconKey = template?.iconKey,
        layoutKey = WidgetLayoutKey.SINGLE.name
    )
}

/** Persists the selected shortcut after the launcher assigns an id to a pinned widget. */
class ShortcutWidgetPinReceiver : BroadcastReceiver() {
    private companion object {
        const val TAG = "ShortcutWidgetPin"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pinData = parsePinCallbackIntent(intent) ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val database = AppDatabase.getDatabase(context.applicationContext)
                val template = pinData.templateId?.let { database.customWidgetTemplateDao().getById(it) }
                val config = createPinnedWidgetConfig(
                    appWidgetId = pinData.appWidgetId,
                    automationId = pinData.automationId,
                    templateId = pinData.templateId,
                    template = template
                )
                database.widgetConfigDao().upsertConfig(config)
                refreshShortcutWidget(context.applicationContext, pinData.appWidgetId)
            } catch (t: Throwable) {
                // Without this the failure vanished into the receiver's scope, and the widget sat
                // on "Tap to set up" with no clue why. A pinned widget failing to bind is exactly
                // the thing worth a log line.
                Log.e(TAG, "Failed to bind pinned widget ${pinData.appWidgetId} to automation ${pinData.automationId}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
