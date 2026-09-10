package com.shortcuts.app.planner

import com.shortcuts.app.data.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionCallParserTest {
    private val parser = FunctionCallParser()

    @Test fun `parses zero calls`() = assertTrue(parser.parseActions("Just normal model text").isEmpty())

    @Test fun `parses one call`() {
        val actions = parser.parseActions(toggle("wifi", "on"))
        assertEquals(1, actions.size)
        assertEquals(ActionType.SYSTEM_TOGGLE, actions.single().actionType)
    }

    @Test fun `parses two calls in order`() {
        val actions = parser.parseActions(toggle("wifi", "on") + toggle("flashlight", "off"))
        assertEquals(listOf("wifi", "flashlight"), actions.map { it.target })
    }

    @Test fun `parses three calls in order`() {
        val actions = parser.parseActions(toggle("wifi", "on") + toggle("bluetooth", "off") + toggle("do_not_disturb", "on"))
        assertEquals(listOf("wifi", "bluetooth", "do_not_disturb"), actions.map { it.target })
    }

    @Test fun `ignores malformed call while retaining valid call`() {
        val malformed = "<start_function_call>call:toggle_system_setting{setting:<escape>wifi<escape>}<end_function_call>"
        val actions = parser.parseActions(malformed + toggle("flashlight", "on"))
        assertEquals(listOf("flashlight"), actions.map { it.target })
    }

    private fun toggle(setting: String, state: String) =
        "<start_function_call>call:toggle_system_setting{setting:<escape>$setting<escape>,state:<escape>$state<escape>}<end_function_call>"

    @Test
    fun `mis-selected toggle naming an installed app is repaired to an app launch`() {
        // Observed on a real device: "open Chrome" came back as
        // toggle_system_setting{setting:chrome}. A device toggle for "chrome" can only fail.
        val parser = FunctionCallParser { query ->
            if (query.equals("chrome", ignoreCase = true)) {
                AppMatch(InstalledApp("Chrome", "com.android.chrome"), 1.0f)
            } else null
        }
        val response = "<start_function_call>call:toggle_system_setting{setting:<escape>chrome<escape>,state:<escape>on<escape>}<end_function_call>"

        val actions = parser.parseActions(response)

        assertEquals(1, actions.size)
        assertEquals(ActionType.APP_INTENT, actions[0].actionType)
        assertEquals("com.android.chrome", actions[0].packageName)
    }

    @Test
    fun `a genuine device toggle is never rewritten into an app launch`() {
        // Guards the repair from over-firing: "wifi" is a real control and must stay one
        // even if some installed app happens to fuzzy-match the word.
        val parser = FunctionCallParser { AppMatch(InstalledApp("WiFi Analyzer", "com.example.wifi"), 1.0f) }
        val response = "<start_function_call>call:toggle_system_setting{setting:<escape>wifi<escape>,state:<escape>on<escape>}<end_function_call>"

        val actions = parser.parseActions(response)

        assertEquals(1, actions.size)
        assertEquals(ActionType.SYSTEM_TOGGLE, actions[0].actionType)
        assertEquals("wifi", actions[0].target)
    }

    @Test
    fun `unknown toggle target that matches no app stays a toggle and fails honestly`() {
        val parser = FunctionCallParser { null }
        val response = "<start_function_call>call:toggle_system_setting{setting:<escape>quantum drive<escape>,state:<escape>on<escape>}<end_function_call>"

        val actions = parser.parseActions(response)

        assertEquals(1, actions.size)
        assertEquals(ActionType.SYSTEM_TOGGLE, actions[0].actionType)
    }
}
