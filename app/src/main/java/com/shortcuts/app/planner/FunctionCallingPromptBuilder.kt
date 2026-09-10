package com.shortcuts.app.planner

/** Pure, token-budget-aware construction of FunctionGemma's function-calling prompt. */
object FunctionCallingPromptBuilder {
    const val MODEL_CONTEXT_WINDOW_TOKENS = 1024
    const val RESERVED_RESPONSE_TOKENS = 256
    const val MAX_FUNCTION_PROMPT_TOKENS = MODEL_CONTEXT_WINDOW_TOKENS - RESERVED_RESPONSE_TOKENS

    /**
     * Adds grounded apps until the complete prompt reaches [maxTokens]. The supplied token
     * counter is MediaPipe's session.sizeInTokens in production, keeping the model's exact
     * tokenizer as the authority rather than approximating tokens in Kotlin.
     */
    fun build(
        userPrompt: String,
        availableApps: List<InstalledApp>,
        sizeInTokens: (String) -> Int,
        maxTokens: Int = MAX_FUNCTION_PROMPT_TOKENS
    ): String {
        val selectedApps = mutableListOf<InstalledApp>()
        for (app in availableApps) {
            val candidateApps = selectedApps + app
            if (sizeInTokens(render(userPrompt, candidateApps)) > maxTokens) break
            selectedApps += app
        }
        return render(userPrompt, selectedApps)
    }

    private fun render(userPrompt: String, apps: List<InstalledApp>): String {
        val appSection = apps.joinToString("\n") { "- ${it.userVisibleLabel} | ${it.packageName}" }
            .ifBlank { "- No launchable apps were available; do not call open_app." }
        val exampleApp = apps.firstOrNull()
        val exampleLabel = exampleApp?.userVisibleLabel ?: "an installed app"
        val examplePackage = exampleApp?.packageName ?: "selected.package.from.the.installed.list"
        // NOTE: Keep the toggle_system_setting description wording exactly as-is.
        // The stricter "CLOSED ENUM" phrasing (with "volume", "ring_mode", "auto_rotate",
        // and "Any setting value outside this list is invalid") was measured to suppress
        // open_app calls entirely — the model mapped every request (including app names like
        // "Chrome") into the enum. The softer "setting is one of:" framing with the original
        // six values fixes the regression. Do NOT "improve" this back to a closed enum.
        return """
            You are a model that can do function calling with the following functions:
            1. toggle_system_setting{setting, state} - Toggles a device setting. setting is one of: wifi, bluetooth, airplane_mode, location, do_not_disturb, flashlight. state is "on" or "off".
            2. open_app{package_name} - Opens an installed app. Select package_name only from the installed-app list below.
            3. send_http_request{url, method} - Sends an HTTP request. method is GET or POST.
            4. tap_screen_element{target_text} - Taps a UI element matching visible text on screen.
            5. type_text_on_screen{text} - Types text into the currently focused field on screen.

            Installed apps (label | package_name):
            $appSection

            Examples — opening an app is NOT a system setting. Never use toggle_system_setting for an app name such as Chrome:
            User: Open the installed app labelled $exampleLabel
            <start_function_call>call:open_app{package_name:<escape>$examplePackage<escape>}<end_function_call>

            User: Turn on the flashlight
            <start_function_call>call:toggle_system_setting{setting:<escape>flashlight<escape>,state:<escape>on<escape>}<end_function_call>

            User: Turn on bluetooth
            <start_function_call>call:toggle_system_setting{setting:<escape>bluetooth<escape>,state:<escape>on<escape>}<end_function_call>

            User: Turn on bluetooth then open the installed app labelled $exampleLabel
            <start_function_call>call:toggle_system_setting{setting:<escape>bluetooth<escape>,state:<escape>on<escape>}<end_function_call>
            <start_function_call>call:open_app{package_name:<escape>$examplePackage<escape>}<end_function_call>

            User: $userPrompt
        """.trimIndent()
    }
}
