package com.shortcuts.app.planner

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType

/** Pure parser for FunctionGemma's native function-call wire format. */
class FunctionCallParser(
    private val appResolver: (String) -> AppMatch? = { null }
) {
    private val functionCallRegex = Regex(
        "<start_function_call>call:([a-zA-Z_][a-zA-Z0-9_]*)\\{(.*?)\\}<end_function_call>",
        RegexOption.DOT_MATCHES_ALL
    )
    private val functionArgRegex = Regex(
        "([a-zA-Z_][a-zA-Z0-9_]*):(?:<escape>(.*?)<escape>|([^,}]+))",
        RegexOption.DOT_MATCHES_ALL
    )

    /** Parses every valid, supported call in order; malformed calls are ignored. */
    fun parseActions(response: String): List<Action> = functionCallRegex.findAll(response)
        .mapNotNull { match ->
            val args = functionArgRegex.findAll(match.groupValues[2]).associate { argument ->
                val key = argument.groupValues[1]
                val value = (argument.groupValues[2].ifEmpty { null } ?: argument.groupValues[3]).trim()
                key to value
            }
            actionFor(match.groupValues[1], args)
        }
        .toList()

    /**
     * Device controls that ActionExecutorService.handleSystemToggle actually recognizes.
     * Mirrors that when-branch and its normalization (lowercase, non-alphanumerics stripped).
     * A toggle target outside this set is not a device control at all.
     */
    private val KNOWN_TOGGLE_TARGETS = setOf(
        "flashlight", "torch", "donotdisturb", "dnd", "volume", "ringmode", "ringer",
        "autorotate", "rotation", "wifi", "bluetooth", "airplanemode", "location"
    )

    private fun normalizeToggleTarget(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    private fun actionFor(functionName: String, args: Map<String, String>): Action? {
        return when (functionName) {
                "toggle_system_setting" -> {
                val setting = args["setting"]?.takeIf { it.isNotBlank() } ?: return null
                val state = args["state"]?.takeIf { it.isNotBlank() } ?: return null
                // Observed on-device: "open Chrome" came back as
                // toggle_system_setting{setting:chrome} — the model picked the wrong
                // function. If the target is not a real device control but DOES name an
                // installed app, repair it to an app launch. Emitting a device toggle for
                // "chrome" can only ever fail.
                if (normalizeToggleTarget(setting) !in KNOWN_TOGGLE_TARGETS) {
                    appResolver(setting)?.let { repaired ->
                        return Action(
                            actionType = ActionType.APP_INTENT,
                            packageName = repaired.app.packageName
                        )
                    }
                }
                Action(actionType = ActionType.SYSTEM_TOGGLE, target = setting, state = state)
            }
            "open_app" -> {
                val query = args["package_name"]?.takeIf { it.isNotBlank() } ?: return null
                val match = appResolver(query) ?: return null
                Action(actionType = ActionType.APP_INTENT, packageName = match.app.packageName)
            }
            "send_http_request" -> {
                val url = args["url"]?.takeIf { it.isNotBlank() } ?: return null
                Action(actionType = ActionType.HTTP_REQUEST, url = url, method = args["method"] ?: "GET")
            }
            "tap_screen_element" -> {
                val targetText = args["target_text"]?.takeIf { it.isNotBlank() } ?: return null
                Action(actionType = ActionType.UI_AUTOMATION, uiActionType = "TAP", targetText = targetText)
            }
            "type_text_on_screen" -> {
                val text = args["text"]?.takeIf { it.isNotBlank() } ?: return null
                Action(actionType = ActionType.UI_AUTOMATION, uiActionType = "TYPE", textInput = text)
            }
            else -> null
        }
    }
}
