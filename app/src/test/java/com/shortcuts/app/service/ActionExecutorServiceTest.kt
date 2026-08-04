package com.shortcuts.app.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.net.HttpURLConnection
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActionExecutorServiceTest {

    private lateinit var mockContext: Context
    private lateinit var mockPackageManager: PackageManager
    private lateinit var mockAccessibilityService: AutomationAccessibilityService
    private lateinit var actionExecutorService: ActionExecutorService

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockPackageManager = mockk(relaxed = true)
        mockAccessibilityService = mockk(relaxed = true)

        every { mockContext.packageManager } returns mockPackageManager

        actionExecutorService = ActionExecutorService(mockContext, mockAccessibilityService)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `executeAction handles SYSTEM_TOGGLE action`() {
        val action = Action(
            actionType = ActionType.SYSTEM_TOGGLE,
            target = "WIFI",
            state = "ON"
        )

        val result = actionExecutorService.executeAction(action)
        assertTrue(result)
        verify { mockContext.startActivity(any()) }
    }

    @Test
    fun `executeAction handles SYSTEM_TOGGLE failure when activity fails`() {
        every { mockContext.startActivity(any()) } throws ActivityNotFoundException("Activity not found")
        val action = Action(
            actionType = ActionType.SYSTEM_TOGGLE,
            target = "WIFI",
            state = "ON"
        )

        val result = actionExecutorService.executeAction(action)
        assertFalse(result)
    }

    @Test
    fun `executeAction handles APP_INTENT action when launch intent exists`() {
        val launchIntent = mockk<Intent>(relaxed = true)
        every { mockPackageManager.getLaunchIntentForPackage("com.example.app") } returns launchIntent

        val action = Action(
            actionType = ActionType.APP_INTENT,
            packageName = "com.example.app"
        )

        val result = actionExecutorService.executeAction(action)
        assertTrue(result)
        verify { mockContext.startActivity(launchIntent) }
    }

    @Test
    fun `executeAction handles APP_INTENT action when package not found`() {
        every { mockPackageManager.getLaunchIntentForPackage(any()) } returns null

        val action = Action(
            actionType = ActionType.APP_INTENT,
            packageName = "com.nonexistent.app"
        )

        val result = actionExecutorService.executeAction(action)
        assertFalse(result)
    }

    @Test
    fun `executeAction handleHttpRequest returns true when response code is 200`() {
        val mockConnection = mockk<HttpURLConnection>(relaxed = true)
        every { mockConnection.responseCode } returns 200

        val service = ActionExecutorService(mockContext, mockAccessibilityService, urlConnectionFactory = { mockConnection })
        val action = Action(
            actionType = ActionType.HTTP_REQUEST,
            url = "https://api.example.com/status",
            method = "GET"
        )

        val result = service.executeAction(action)
        assertTrue(result)
    }

    @Test
    fun `executeAction handleHttpRequest returns false when response code is 500`() {
        val mockConnection = mockk<HttpURLConnection>(relaxed = true)
        every { mockConnection.responseCode } returns 500

        val service = ActionExecutorService(mockContext, mockAccessibilityService, urlConnectionFactory = { mockConnection })
        val action = Action(
            actionType = ActionType.HTTP_REQUEST,
            url = "https://api.example.com/status",
            method = "POST",
            textInput = "{\"data\": 123}"
        )

        val result = service.executeAction(action)
        assertFalse(result)
    }

    @Test
    fun `executeAction dispatches UI_AUTOMATION action to AutomationAccessibilityService`() {
        val uiAction = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetNodeId = "com.example:id/button"
        )

        every { mockAccessibilityService.executeAction(uiAction) } returns true

        val result = actionExecutorService.executeAction(uiAction)
        assertTrue(result)
        verify { mockAccessibilityService.executeAction(uiAction) }
    }

    @Test
    fun `executeAction UI_AUTOMATION returns false when AccessibilityService is unavailable`() {
        val executorWithoutService = ActionExecutorService(mockContext, accessibilityService = null)
        AutomationAccessibilityService.instance = null

        val uiAction = Action(
            actionType = ActionType.UI_AUTOMATION,
            uiActionType = "CLICK",
            targetNodeId = "com.example:id/button"
        )

        val result = executorWithoutService.executeAction(uiAction)
        assertFalse(result)
    }

    @Test
    fun `executeActions executes list of actions sequentially`() {
        val mockConnection = mockk<HttpURLConnection>(relaxed = true)
        every { mockConnection.responseCode } returns 200

        val executor = ActionExecutorService(mockContext, mockAccessibilityService, urlConnectionFactory = { mockConnection })

        val action1 = Action(actionType = ActionType.SYSTEM_TOGGLE, target = "WIFI", state = "ON")
        val action2 = Action(actionType = ActionType.HTTP_REQUEST, url = "http://example.com", method = "GET")
        val action3 = Action(actionType = ActionType.UI_AUTOMATION, targetNodeId = "id/btn")

        every { mockAccessibilityService.executeAction(action3) } returns true

        val result = executor.executeActions(listOf(action1, action2, action3))
        assertTrue(result)
        verify { mockAccessibilityService.executeAction(action3) }
    }
}
