package com.shortcuts.app.widget

import com.google.gson.Gson
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLockedTapRouterTest {

    private val gson = Gson()

    @Test
    fun `decideRoute routes to UNLOCK_TRAMPOLINE when locked and shortcut has irreversible effects`() {
        val route = WidgetTapRouter.decideRoute(isLocked = true, isIrreversible = true)
        assertEquals(WidgetTapRoute.UNLOCK_TRAMPOLINE, route)
    }

    @Test
    fun `decideRoute routes directly when locked but shortcut is harmless`() {
        val route = WidgetTapRouter.decideRoute(isLocked = true, isIrreversible = false)
        assertEquals(WidgetTapRoute.RUN_DIRECTLY, route)
    }

    @Test
    fun `decideRoute routes directly when unlocked even if shortcut has irreversible effects`() {
        val route = WidgetTapRouter.decideRoute(isLocked = false, isIrreversible = true)
        assertEquals(WidgetTapRoute.RUN_DIRECTLY, route)
    }

    @Test
    fun `decideRoute routes directly when unlocked and shortcut is harmless`() {
        val route = WidgetTapRouter.decideRoute(isLocked = false, isIrreversible = false)
        assertEquals(WidgetTapRoute.RUN_DIRECTLY, route)
    }

    @Test
    fun `isIrreversible is false for empty actions list`() {
        assertFalse(WidgetTapRouter.isIrreversible("[]"))
    }

    @Test
    fun `isIrreversible is false for harmless read-only action`() {
        val safeActions = listOf(
            Action(
                actionType = ActionType.HTTP_REQUEST,
                url = "https://example.com/status",
                method = "GET"
            )
        )
        val json = gson.toJson(safeActions)
        assertFalse(WidgetTapRouter.isIrreversible(json))
    }

    @Test
    fun `isIrreversible is true for outbound action like sending message`() {
        val outboundActions = listOf(
            Action(
                actionType = ActionType.SEND_MESSAGE,
                target = "123456789"
            )
        )
        val json = gson.toJson(outboundActions)
        assertTrue(WidgetTapRouter.isIrreversible(json))
    }

    @Test
    fun `isIrreversible is true for modifying http request like POST`() {
        val postAction = listOf(
            Action(
                actionType = ActionType.HTTP_REQUEST,
                url = "https://example.com/api",
                method = "POST"
            )
        )
        val json = gson.toJson(postAction)
        assertTrue(WidgetTapRouter.isIrreversible(json))
    }
}
