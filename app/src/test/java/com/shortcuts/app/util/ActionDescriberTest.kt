package com.shortcuts.app.util

import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionDescriberTest {
    @Test
    fun `system toggle is described in plain language`() {
        assertEquals(
            "Turn on Wi-Fi",
            ActionDescriber.describe(Action(ActionType.SYSTEM_TOGGLE, target = "wifi", state = "on"))
        )
    }

    @Test
    fun `app launch is described in plain language`() {
        assertEquals(
            "Open Spotify",
            ActionDescriber.describe(Action(ActionType.APP_INTENT, packageName = "com.spotify.music"))
        )
    }

    @Test
    fun `web request is described in plain language`() {
        assertEquals(
            "Send a POST web request to example.com",
            ActionDescriber.describe(Action(ActionType.HTTP_REQUEST, url = "https://example.com/hook", method = "post"))
        )
    }

    @Test
    fun `screen action is described in plain language`() {
        assertEquals(
            "Type \"Hello\" into Search",
            ActionDescriber.describe(
                Action(
                    ActionType.UI_AUTOMATION,
                    targetText = "Search",
                    uiActionType = "TYPE_TEXT",
                    textInput = "Hello"
                )
            )
        )
    }

    @Test
    fun `scroll action with target and direction is described in plain language`() {
        assertEquals(
            "Scroll down on Feed",
            ActionDescriber.describe(
                Action(
                    ActionType.UI_AUTOMATION,
                    targetText = "Feed",
                    uiActionType = "SCROLL",
                    scrollDirection = "down"
                )
            )
        )
    }

    @Test
    fun `scroll action without target or direction is described in plain language`() {
        assertEquals(
            "Scroll on the current screen",
            ActionDescriber.describe(
                Action(
                    ActionType.UI_AUTOMATION,
                    uiActionType = "SCROLL"
                )
            )
        )
    }

    @Test
    fun `text entry aliases are described correctly`() {
        val aliases = listOf("TYPE", "TYPE_TEXT", "TEXT_ENTRY", "INPUT_TEXT", "SET_TEXT")
        for (alias in aliases) {
            assertEquals(
                "Type \"Hello\" into Search",
                ActionDescriber.describe(
                    Action(
                        ActionType.UI_AUTOMATION,
                        targetText = "Search",
                        uiActionType = alias,
                        textInput = "Hello"
                    )
                )
            )
            assertEquals(
                "Type \"Hello\"",
                ActionDescriber.describe(
                    Action(
                        ActionType.UI_AUTOMATION,
                        uiActionType = alias,
                        textInput = "Hello"
                    )
                )
            )
        }
    }

    @Test
    fun `new recorder actions are described explicitly`() {
        assertEquals(
            "Long-press Search",
            ActionDescriber.describe(
                Action(ActionType.UI_AUTOMATION, targetText = "Search", uiActionType = "LONG_PRESS")
            )
        )
        assertEquals(
            "Press Enter in Search",
            ActionDescriber.describe(
                Action(ActionType.UI_AUTOMATION, targetText = "Search", uiActionType = "PRESS_ENTER")
            )
        )
    }
}
