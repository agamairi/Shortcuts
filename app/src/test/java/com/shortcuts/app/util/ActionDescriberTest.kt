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
}
