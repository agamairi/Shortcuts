package com.shortcuts.app.widget

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.WidgetConfigSource
import com.shortcuts.app.service.ShortcutRunRequest
import com.shortcuts.app.service.ShortcutRunSafety
import com.shortcuts.app.service.ShortcutRunSource
import com.shortcuts.app.service.ShortcutRunner
import com.shortcuts.app.service.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class WidgetTapRoute {
    RUN_DIRECTLY,
    UNLOCK_TRAMPOLINE
}

object WidgetTapRouter {
    fun decideRoute(isLocked: Boolean, isIrreversible: Boolean): WidgetTapRoute {
        return if (isLocked && isIrreversible) {
            WidgetTapRoute.UNLOCK_TRAMPOLINE
        } else {
            WidgetTapRoute.RUN_DIRECTLY
        }
    }

    fun isIrreversible(actionsJson: String): Boolean {
        return ShortcutRunSafety.confirmationEffects(actionsJson).isNotEmpty()
    }

    /**
     * Steps that open an app or act on another app's screen cannot work behind the lock screen
     * (the app would open under the keyguard and accessibility cannot reach it), so they need
     * the phone unlocked first. Toggles, web requests and waits can run while locked.
     */
    fun requiresUnlockedScreen(actionsJson: String): Boolean {
        val document = com.shortcuts.app.data.ShortcutSteps.decode(actionsJson)
        if (document is com.shortcuts.app.data.ShortcutDocument.Unreadable) return true
        return document.actions.any { action ->
            action.actionType == com.shortcuts.app.data.ActionType.UI_AUTOMATION ||
                action.actionType == com.shortcuts.app.data.ActionType.APP_INTENT ||
                action.actionType == com.shortcuts.app.data.ActionType.SEND_MESSAGE ||
                action.actionType == com.shortcuts.app.data.ActionType.DIAL_NUMBER
        }
    }
}

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
                    val config = db.widgetConfigDao().getConfig(appWidgetId, WidgetConfigSource.UNIFIED.name)
                    if (config == null) {
                        Log.w("WidgetCallback", "No config found for widget $appWidgetId")
                    }
                    config?.automationIdsJson?.let { WidgetConfigParser.automationIds(it).firstOrNull() }
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

                val automation = db.automationDao().getAutomationById(automationIdToRun)
                if (automation == null) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            context,
                            "This shortcut was deleted.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    return@withContext
                }

                val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                val isLocked = keyguardManager?.isKeyguardLocked == true
                val needsUnlock = WidgetTapRouter.isIrreversible(automation.actionsJson) ||
                    WidgetTapRouter.requiresUnlockedScreen(automation.actionsJson)
                val route = WidgetTapRouter.decideRoute(isLocked = isLocked, isIrreversible = needsUnlock)

                if (route == WidgetTapRoute.UNLOCK_TRAMPOLINE) {
                    val trampolineIntent = Intent(context, WidgetUnlockTrampolineActivity::class.java).apply {
                        putExtra(WidgetUnlockTrampolineActivity.EXTRA_AUTOMATION_ID, automationIdToRun)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    context.startActivity(trampolineIntent)
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
