package com.shortcuts.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType

class ActionExecutorService(private val context: Context) {

    fun executeActions(actions: List<Action>) {
        for (action in actions) {
            when (action.actionType) {
                ActionType.SYSTEM_TOGGLE -> handleSystemToggle(action)
                ActionType.APP_INTENT -> handleAppIntent(action)
                ActionType.HTTP_REQUEST -> handleHttpRequest(action)
                ActionType.UI_AUTOMATION -> handleUiAutomation(action)
            }
        }
    }

    private fun handleSystemToggle(action: Action) {
        if (action.target == "WIFI") {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // Note: WifiManager.setWifiEnabled is deprecated in recent Android versions,
            // requires settings panel intent on Android 10+. This is a simplified example.
            Log.d("ActionExecutor", "Toggling WIFI to ${action.state}")
        }
    }

    private fun handleAppIntent(action: Action) {
        action.packageName?.let { pkg ->
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d("ActionExecutor", "Launched app: $pkg")
            }
        }
    }

    private fun handleHttpRequest(action: Action) {
        // Use Retrofit or OkHttp to make the request
        Log.d("ActionExecutor", "Making HTTP ${action.method} request to ${action.url}")
    }

    private fun handleUiAutomation(action: Action) {
        // Send a broadcast or direct call to AutomationAccessibilityService
        Log.d("ActionExecutor", "Dispatching UI Automation for ${action.targetNodeId}")
    }
}
