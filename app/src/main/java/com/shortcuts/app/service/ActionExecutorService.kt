package com.shortcuts.app.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import java.net.HttpURLConnection
import java.net.URL

class ActionExecutorService(
    private val context: Context,
    private val accessibilityService: AutomationAccessibilityService? = null,
    var urlConnectionFactory: ((String) -> HttpURLConnection)? = null
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
        return try {
            val intent = if (action.target == "WIFI") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_WIFI)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
            } else {
                Intent(Settings.ACTION_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d("ActionExecutor", "Dispatched settings intent for target ${action.target}")
            true
        } catch (e: Exception) {
            Log.e("ActionExecutor", "Failed to start settings activity for target ${action.target}", e)
            false
        }
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
        val urlString = action.url ?: return false
        return try {
            val connection = urlConnectionFactory?.invoke(urlString)
                ?: (URL(urlString).openConnection() as HttpURLConnection)
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val method = action.method?.uppercase() ?: "GET"
            connection.requestMethod = method

            if ((method == "POST" || method == "PUT") && !action.textInput.isNullOrEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { os ->
                    os.write(action.textInput.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
            }

            val responseCode = connection.responseCode
            Log.d("ActionExecutor", "HTTP $method request to $urlString returned code $responseCode")
            connection.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e("ActionExecutor", "HTTP request to $urlString failed", e)
            false
        }
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

