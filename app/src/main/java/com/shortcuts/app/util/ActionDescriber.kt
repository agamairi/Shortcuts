package com.shortcuts.app.util

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import java.net.URI
import java.util.Locale

/** Converts stored shortcut actions into the sentence shown in the review editor. */
object ActionDescriber {
    fun describe(action: Action): String = when (action.actionType) {
        ActionType.SYSTEM_TOGGLE -> describeToggle(action)
        ActionType.APP_INTENT -> "Open ${appName(action.packageName)}"
        ActionType.HTTP_REQUEST -> describeRequest(action)
        ActionType.UI_AUTOMATION -> describeScreenAction(action)
        ActionType.WAIT -> "Wait ${formatWaitDuration(action.delayMillis)}"
        ActionType.SEND_MESSAGE -> describeMessage(action)
        ActionType.DIAL_NUMBER -> "Call ${action.target?.takeIf { it.isNotBlank() } ?: "a contact"}"
    }

    /**
     * Renders a pause the way a person would say it: "3 seconds", "1 second", "1.5 seconds".
     * Shared with the manual builder so the sentence and the review card never disagree.
     */
    fun formatWaitDuration(millis: Long?): String {
        val value = millis ?: return "a moment"
        if (value <= 0L) return "a moment"
        if (value < 1000L) return "$value milliseconds"
        val seconds = value / 1000.0
        val rendered = if (seconds % 1.0 == 0.0) {
            seconds.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", seconds)
        }
        return if (rendered == "1") "1 second" else "$rendered seconds"
    }

    private fun describeMessage(action: Action): String {
        val recipient = action.target?.takeIf { it.isNotBlank() } ?: "a contact"
        // The app never sends on the user's behalf — it opens their SMS app with the
        // message ready — so the description must not imply it was already sent.
        val body = action.textInput?.takeIf { it.isNotBlank() }
        return if (body != null) "Write \"$body\" to $recipient" else "Write a message to $recipient"
    }

    private fun describeToggle(action: Action): String {
        val target = action.target?.takeIf { it.isNotBlank() }?.let(::friendlyToggleName) ?: "a device setting"
        return when (action.state?.trim()?.lowercase()) {
            "on", "enable", "enabled" -> "Turn on $target"
            "off", "disable", "disabled" -> "Turn off $target"
            else -> "Toggle $target"
        }
    }

    private fun describeRequest(action: Action): String {
        val method = action.method?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "GET"
        val destination = action.url?.takeIf { it.isNotBlank() }?.let(::hostOrAddress) ?: "a web address"
        return "Send a $method web request to $destination"
    }

    private fun describeScreenAction(action: Action): String {
        val target = action.targetText?.takeIf { it.isNotBlank() }
            ?: action.targetNodeId?.takeIf { it.isNotBlank() }
            ?: action.target?.takeIf { it.isNotBlank() }
        val typesText = action.uiActionType?.trim()?.equals("TYPE_TEXT", ignoreCase = true) == true
        return when {
            typesText && !action.textInput.isNullOrBlank() && target != null ->
                "Type \"${action.textInput}\" into $target"
            typesText && !action.textInput.isNullOrBlank() -> "Type \"${action.textInput}\""
            target != null -> "Tap $target"
            else -> "Tap or type on the current screen"
        }
    }

    private fun friendlyToggleName(value: String): String = when (value.trim().lowercase().replace("_", "")) {
        "wifi" -> "Wi-Fi"
        "bluetooth" -> "Bluetooth"
        "flashlight", "torch" -> "the flashlight"
        "donotdisturb", "dnd" -> "Do Not Disturb"
        "autorotate", "rotation" -> "auto-rotate"
        "airplanemode" -> "Airplane mode"
        "ringmode", "ringer" -> "ring mode"
        else -> value.trim().replace('_', ' ')
    }

    private fun appName(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "an app"
        val parts = packageName.split('.').filter { it.isNotBlank() }
        val knownName = parts.firstOrNull { it.lowercase() in knownAppNames }
        val candidate = knownName ?: parts.lastOrNull().orEmpty()
        return candidate.replaceFirstChar { it.uppercase() }.replace('_', ' ')
    }

    private fun hostOrAddress(value: String): String = try {
        URI(value).host ?: value
    } catch (_: Exception) {
        value
    }

    private val knownAppNames = setOf("spotify", "youtube", "netflix", "slack", "discord", "whatsapp", "telegram")
}
