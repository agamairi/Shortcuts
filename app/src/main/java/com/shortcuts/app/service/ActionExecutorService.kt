package com.shortcuts.app.service

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType

class ActionExecutorService(
    private val context: Context,
    private val accessibilityService: AutomationAccessibilityService? = null
) {

    fun executeActions(actions: List<Action>): Boolean {
        var allSuccessful = true
        for (action in actions) {
            val result = executeAction(action)
            if (!result) {
                allSuccessful = false
            }
        }
        return allSuccessful
    }

    fun executeAction(action: Action): Boolean {
        return when (action.actionType) {
            ActionType.SYSTEM_TOGGLE -> handleSystemToggle(action)
            ActionType.APP_INTENT -> handleAppIntent(action)
            ActionType.HTTP_REQUEST -> handleHttpRequest(action)
            ActionType.UI_AUTOMATION -> handleUiAutomation(action)
        }
    }

    private fun handleSystemToggle(action: Action): Boolean {
        if (action.target == "WIFI") {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            Log.d("ActionExecutor", "Toggling WIFI to ${action.state}, wifiManager: $wifiManager")
            return true
        }
        return true
    }

    private fun handleAppIntent(action: Action): Boolean {
        action.packageName?.let { pkg ->
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d("ActionExecutor", "Launched app: $pkg")
                return true
            }
        }
        return false
    }

    private fun handleHttpRequest(action: Action): Boolean {
        // Use Retrofit or OkHttp to make the request
        Log.d("ActionExecutor", "Making HTTP ${action.method} request to ${action.url}")
        return true
    }

    private fun handleUiAutomation(action: Action): Boolean {
        Log.d("ActionExecutor", "Dispatching UI Automation for targetNodeId='${action.targetNodeId}'")
        val service = accessibilityService ?: AutomationAccessibilityService.instance
        return if (service != null) {
            service.executeAction(action)
        } else {
            Log.w("ActionExecutor", "AutomationAccessibilityService is not running or available")
            false
        }
    }
}
