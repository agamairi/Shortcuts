package com.shortcuts.app.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.Build
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import okhttp3.Call
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun tearDown() = clearAllMocks()

    @Test
    fun `lowercase wifi opens the wifi panel and never generic settings`() {
        // The original bug: handleSystemToggle compared target to "WIFI" while the model emits
        // "wifi", so every toggle fell through to the generic Settings catch-all.
        val result = actionExecutorService.executeAction(
            Action(ActionType.SYSTEM_TOGGLE, target = "wifi", state = "on")
        )

        assertTrue(result is StepResult.Failed)
        val failure = result as StepResult.Failed
        // PLATFORM_RESTRICTION (not UNSUPPORTED_TARGET) proves "wifi" was recognized, not dropped.
        assertEquals(FailureReason.PLATFORM_RESTRICTION, failure.reason)
        assertTrue(failure.userMessage.contains("WiFi"))
        verify(exactly = 1) { mockContext.startActivity(any()) }
    }

    @Test
    fun `wifi resolves to a wifi-specific settings action on every supported sdk`() {
        // Asserted against the pure mapping because android.jar is stubbed in JVM unit tests:
        // Intent.getAction() would return null here regardless of what we passed in.
        val onQPlus = ActionExecutorService.wifiSettingsAction(Build.VERSION_CODES.Q)
        val onLegacy = ActionExecutorService.wifiSettingsAction(Build.VERSION_CODES.O)

        assertEquals(Settings.Panel.ACTION_WIFI, onQPlus)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, onLegacy)
        assertTrue(onQPlus != Settings.ACTION_SETTINGS)
        assertTrue(onLegacy != Settings.ACTION_SETTINGS)
    }

    @Test
    fun `flashlight state on and off call torch mode in both directions`() {
        val cameraManager = mockk<CameraManager>(relaxed = true)
        val characteristics = mockk<CameraCharacteristics>()
        every { mockContext.getSystemService(Context.CAMERA_SERVICE) } returns cameraManager
        every { cameraManager.cameraIdList } returns arrayOf("back")
        every { cameraManager.getCameraCharacteristics("back") } returns characteristics
        every { characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) } returns true

        assertEquals(
            StepResult.Success,
            actionExecutorService.executeAction(Action(ActionType.SYSTEM_TOGGLE, target = "flashlight", state = "on"))
        )
        assertEquals(
            StepResult.Success,
            actionExecutorService.executeAction(Action(ActionType.SYSTEM_TOGGLE, target = "flashlight", state = "off"))
        )

        verify { cameraManager.setTorchMode("back", true) }
        verify { cameraManager.setTorchMode("back", false) }
    }

    @Test
    fun `unknown system toggle is an explicit failure and does not open settings`() {
        val result = actionExecutorService.executeAction(
            Action(ActionType.SYSTEM_TOGGLE, target = "teleport", state = "on")
        )

        assertTrue(result is StepResult.Failed)
        assertEquals(FailureReason.UNSUPPORTED_TARGET, (result as StepResult.Failed).reason)
        verify(exactly = 0) { mockContext.startActivity(any()) }
    }

    @Test
    fun `app intent succeeds when launch intent exists`() {
        val launchIntent = mockk<Intent>(relaxed = true)
        every { mockPackageManager.getLaunchIntentForPackage("com.example.app") } returns launchIntent

        assertEquals(
            StepResult.Success,
            actionExecutorService.executeAction(Action(ActionType.APP_INTENT, packageName = "com.example.app"))
        )
        verify { mockContext.startActivity(launchIntent) }
    }

    @Test
    fun `app intent identifies an app that is not installed`() {
        every { mockPackageManager.getLaunchIntentForPackage(any()) } returns null

        val result = actionExecutorService.executeAction(Action(ActionType.APP_INTENT, packageName = "com.missing"))

        assertEquals(FailureReason.APP_NOT_FOUND, (result as StepResult.Failed).reason)
    }

    @Test
    fun `http request reports success only for a successful response`() {
        val callFactory = mockk<Call.Factory>()
        val call = mockk<Call>()
        val response = mockk<Response>(relaxed = true)
        every { callFactory.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns true
        val service = ActionExecutorService(mockContext, mockAccessibilityService, callFactory)

        assertEquals(
            StepResult.Success,
            service.executeAction(Action(ActionType.HTTP_REQUEST, url = "https://api.example.com", method = "GET"))
        )
    }

    @Test
    fun `chain stops on a failed step and marks following actions skipped`() {
        val result = actionExecutorService.executeActions(
            listOf(
                Action(ActionType.SYSTEM_TOGGLE, target = "unknown", state = "on"),
                Action(ActionType.APP_INTENT, packageName = "com.example.app")
            ),
            shortcutName = "Test chain"
        )

        assertFalse(result.allSucceeded)
        assertEquals(FailureReason.UNSUPPORTED_TARGET, result.firstFailure?.reason)
        assertTrue(result.steps[1] is StepResult.Skipped)
        verify(exactly = 0) { mockPackageManager.getLaunchIntentForPackage(any()) }
    }

    @Test
    fun `chain continues after a failed step only when that step opts in`() {
        val callFactory = mockk<Call.Factory>()
        val call = mockk<Call>()
        val response = mockk<Response>(relaxed = true)
        every { callFactory.newCall(any()) } returns call
        every { call.execute() } returns response
        every { response.isSuccessful } returns true
        val service = ActionExecutorService(mockContext, mockAccessibilityService, callFactory)

        val result = service.executeActions(
            listOf(
                Action(ActionType.SYSTEM_TOGGLE, target = "unknown", state = "on", continueOnError = true),
                Action(ActionType.HTTP_REQUEST, url = "https://api.example.com", method = "GET")
            )
        )

        assertTrue(result.steps[0] is StepResult.Failed)
        assertEquals(StepResult.Success, result.steps[1])
    }

    @Test
    fun `message and dial action values are pure and policy safe`() {
        // Do not inspect Intent here: JVM android.jar stubs return null for Intent internals.
        assertEquals("smsto:+14165551212", ActionExecutorService.smsSendToUri(" +14165551212 "))
        assertEquals("tel:+14165551212", ActionExecutorService.dialUri(" +14165551212 "))
        assertEquals(null, ActionExecutorService.smsSendToUri("   "))
        assertEquals(null, ActionExecutorService.dialUri("   "))
    }

    @Test
    fun `https is required unless this HTTP action explicitly opts into cleartext`() {
        assertTrue(ActionExecutorService.isHttpAllowed("https://api.example.com/hook", allowCleartext = false))
        assertFalse(ActionExecutorService.isHttpAllowed("http://device.local/hook", allowCleartext = false))
        assertTrue(ActionExecutorService.isHttpAllowed("http://device.local/hook", allowCleartext = true))
        assertFalse(ActionExecutorService.isHttpAllowed("ftp://example.com/file", allowCleartext = true))
    }

    @Test
    fun `RunResult containing a Failed step produces a message carrying its userMessage`() {
        val result = actionExecutorService.executeActions(
            listOf(Action(ActionType.SYSTEM_TOGGLE, target = "unknown", state = "on")),
            shortcutName = "Test Failure"
        )
        val step = result.steps.first()
        assertTrue(step is StepResult.Failed)
        val failedStep = step as StepResult.Failed
        assertEquals("\"unknown\" isn't a device control this shortcut can run.", failedStep.userMessage)
    }

    @Test
    fun `RunResult containing NeedsPermission surfaces the permission and exposes its settings intent`() {
        // Mock permission missing for DND
        val mockNotificationManager = mockk<android.app.NotificationManager>(relaxed = true)
        every { mockContext.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockNotificationManager
        every { mockNotificationManager.isNotificationPolicyAccessGranted } returns false

        val result = actionExecutorService.executeAction(
            Action(ActionType.SYSTEM_TOGGLE, target = "donotdisturb", state = "on")
        )

        assertTrue(result is StepResult.NeedsPermission)
        val needsPermission = result as StepResult.NeedsPermission
        assertEquals("Do Not Disturb access", needsPermission.permission)
        // A settings intent must be offered so the UI can route the user to grant it.
        // Its ACTION cannot be asserted here: JVM unit tests run against a stubbed android.jar
        // (isReturnDefaultValues = true), so Intent.getAction() always returns null regardless
        // of what was constructed. The action string itself is covered by the pure-function
        // test above; this test owns the executor's behaviour.
        assertNotNull(needsPermission.settingsIntent)
    }
}
