package com.shortcuts.app.planner

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionCallingPromptBuilderTest {
    @Test
    fun `prompt describes setting values with open one-of wording, not a closed enum`() {
        val prompt = FunctionCallingPromptBuilder.build("turn on wifi", emptyList(), { 0 })

        assertTrue(prompt.contains(
            "setting is one of: wifi, bluetooth, airplane_mode, location, do_not_disturb, flashlight"
        ))
        assertFalse(prompt.contains("CLOSED ENUM"))
        assertFalse(prompt.contains("Any setting value outside this list is invalid"))
    }

    @Test
    fun `prompt puts app opening beside a toggle example to disambiguate functions`() {
        val prompt = FunctionCallingPromptBuilder.build(
            userPrompt = "open Chrome",
            availableApps = listOf(InstalledApp("Chrome", "com.android.chrome")),
            sizeInTokens = { 0 }
        )

        val openChrome = prompt.indexOf("User: Open the installed app labelled Chrome")
        val flashlight = prompt.indexOf("User: Turn on the flashlight")
        assertTrue(openChrome >= 0)
        assertTrue(prompt.substring(openChrome, flashlight).contains("call:open_app"))
        assertTrue(flashlight > openChrome)
        assertTrue(prompt.substring(flashlight).contains("call:toggle_system_setting"))
    }

    @Test
    fun `grounded app list stops when the tokenizer budget would be exceeded`() {
        val apps = listOf(
            InstalledApp("One", "com.example.one"),
            InstalledApp("Two", "com.example.two"),
            InstalledApp("Three", "com.example.three")
        )
        val prompt = FunctionCallingPromptBuilder.build(
            userPrompt = "open an app",
            availableApps = apps,
            // Count only installed-app rows. The third candidate exceeds the two-row budget.
            sizeInTokens = { value -> value.lines().count { " | com.example." in it } },
            maxTokens = 2
        )

        assertTrue(prompt.contains("- One | com.example.one"))
        assertTrue(prompt.contains("- Two | com.example.two"))
        assertFalse(prompt.contains("- Three | com.example.three"))
    }
}
