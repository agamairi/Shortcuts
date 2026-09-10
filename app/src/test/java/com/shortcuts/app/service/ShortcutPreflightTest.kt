package com.shortcuts.app.service

import android.Manifest
import android.os.Build
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutPreflightTest {

    @Test
    fun `preflight catches missing required fields without attempting an action`() {
        val report = ShortcutPreflight(FakeEnvironment()).check(
            listOf(
                Action(ActionType.SYSTEM_TOGGLE, target = null, state = "on"),
                Action(ActionType.APP_INTENT),
                Action(ActionType.HTTP_REQUEST),
                Action(ActionType.WAIT, delayMillis = null),
                Action(ActionType.SEND_MESSAGE, target = " "),
                Action(ActionType.DIAL_NUMBER, target = " "),
                Action(ActionType.UI_AUTOMATION, uiActionType = "TAP")
            )
        )

        assertFalse(report.canRun)
        assertEquals(7, report.issues.size)
        assertTrue(report.issues.all { it.category == ShortcutIssueCategory.REQUIRED_FIELD_MISSING })
    }

    @Test
    fun `preflight catches a missing app and unavailable device capability`() {
        val report = ShortcutPreflight(
            FakeEnvironment(launchablePackages = emptySet(), flashlightAvailable = false)
        ).check(
            listOf(
                Action(ActionType.APP_INTENT, packageName = "com.example.not.installed"),
                Action(ActionType.SYSTEM_TOGGLE, target = "flashlight", state = "on")
            )
        )

        assertEquals(
            listOf(ShortcutIssueCategory.APP_NOT_AVAILABLE, ShortcutIssueCategory.DEVICE_CAPABILITY_UNAVAILABLE),
            report.issues.map { it.category }
        )
    }

    @Test
    fun `preflight catches required permissions and system settings`() {
        val environment = FakeEnvironment(
            grantedPermissions = emptySet(),
            notificationPolicyAccess = false,
            canModifySystemSettings = false,
            accessibilityActive = false
        )

        val report = ShortcutPreflight(environment).check(
            listOf(
                Action(ActionType.SYSTEM_TOGGLE, target = "flashlight", state = "on"),
                Action(ActionType.SYSTEM_TOGGLE, target = "bluetooth", state = "on"),
                Action(ActionType.SYSTEM_TOGGLE, target = "donotdisturb", state = "on"),
                Action(ActionType.SYSTEM_TOGGLE, target = "autorotate", state = "on"),
                Action(ActionType.UI_AUTOMATION, uiActionType = "GLOBAL_ACTION_BACK")
            )
        )

        assertEquals(
            listOf(
                ShortcutIssueCategory.PERMISSION_REQUIRED,
                ShortcutIssueCategory.PERMISSION_REQUIRED,
                ShortcutIssueCategory.SYSTEM_SETTING_REQUIRED,
                ShortcutIssueCategory.SYSTEM_SETTING_REQUIRED,
                ShortcutIssueCategory.SYSTEM_SETTING_REQUIRED
            ),
            report.issues.map { it.category }
        )
        assertEquals(
            listOf(
                ShortcutPreflightResolution.APP_PERMISSIONS,
                ShortcutPreflightResolution.APP_PERMISSIONS,
                ShortcutPreflightResolution.DO_NOT_DISTURB_ACCESS,
                ShortcutPreflightResolution.MODIFY_SYSTEM_SETTINGS,
                ShortcutPreflightResolution.ACCESSIBILITY_SETTINGS
            ),
            report.issues.map { it.resolution }
        )
    }

    @Test
    fun `preflight catches URL policy violations and unsupported executor constraints`() {
        val report = ShortcutPreflight(FakeEnvironment()).check(
            listOf(
                Action(ActionType.HTTP_REQUEST, url = "http://device.local/hook", method = "POST"),
                Action(ActionType.HTTP_REQUEST, url = "https://api.example.com", method = "CONNECT"),
                Action(ActionType.UI_AUTOMATION, uiActionType = "SWIPE_MAGIC")
            )
        )

        assertEquals(
            listOf(
                ShortcutIssueCategory.URL_POLICY_VIOLATION,
                ShortcutIssueCategory.UNSUPPORTED_ACTION,
                ShortcutIssueCategory.UNSUPPORTED_ACTION
            ),
            report.issues.map { it.category }
        )
    }

    @Test
    fun `preflight reports platform restrictions as warnings rather than pretending the action will run`() {
        val report = ShortcutPreflight(FakeEnvironment()).check(
            listOf(Action(ActionType.SYSTEM_TOGGLE, target = "wifi", state = "on"))
        )

        assertTrue(report.canRun)
        assertEquals(ShortcutIssueCategory.PLATFORM_RESTRICTION, report.issues.single().category)
        assertFalse(report.issues.single().blocking)
    }

    @Test
    fun `preflight never acquires admission or starts the service executor path`() {
        val starter = RecordingStarter()
        val editorStarter = RecordingEditorStarter()
        val admission = RecordingAdmission()
        val runner = ShortcutRunner(
            store = FakeStore(),
            acceptedRunStarter = starter,
            admission = admission,
            acceptedEditorRunStarter = editorStarter,
            preflightEnvironment = FakeEnvironment()
        )

        val report = runner.preflight(
            listOf(Action(ActionType.HTTP_REQUEST, url = "https://api.example.com/webhook", method = "POST"))
        )

        // The recording starters are the only paths to AutomationExecutionService, which owns
        // ActionExecutorService and OkHttp. A check must reach neither path.
        assertTrue(report.canRun)
        assertFalse(admission.acquireAttempted)
        assertTrue(starter.startedShortcutIds.isEmpty())
        assertTrue(editorStarter.startedDrafts.isEmpty())
    }

    @Test
    fun `confirmation is requested once for outward and uncertain effects but not safe reads`() {
        val effects = ShortcutRunSafety.confirmationEffects(
            listOf(
                Action(ActionType.HTTP_REQUEST, url = "https://api.example.com", method = "GET"),
                Action(ActionType.HTTP_REQUEST, url = "https://api.example.com", method = "POST"),
                Action(ActionType.SEND_MESSAGE, target = "+14165551212"),
                Action(ActionType.UI_AUTOMATION, uiActionType = "TAP", targetText = "Submit")
            )
        )

        assertEquals(
            listOf(
                "prepare a message to another person",
                "send an HTTP POST request",
                "interact with another app's screen"
            ),
            effects
        )
    }

    private class FakeEnvironment(
        private val launchablePackages: Set<String> = setOf("com.example.installed"),
        private val grantedPermissions: Set<String> = setOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_CONNECT
        ),
        private val notificationPolicyAccess: Boolean = true,
        private val canModifySystemSettings: Boolean = true,
        private val accessibilityActive: Boolean = true,
        private val flashlightAvailable: Boolean = true,
        override val sdkInt: Int = Build.VERSION_CODES.S
    ) : ShortcutPreflightEnvironment {
        override fun canLaunchPackage(packageName: String): Boolean = packageName in launchablePackages
        override fun hasPermission(permission: String): Boolean = permission in grantedPermissions
        override fun hasNotificationPolicyAccess(): Boolean = notificationPolicyAccess
        override fun canModifySystemSettings(): Boolean = canModifySystemSettings
        override fun isAccessibilityServiceActive(): Boolean = accessibilityActive
        override fun hasFlashlight(): Boolean = flashlightAvailable
    }

    private class FakeStore : ShortcutRunStore {
        override suspend fun findShortcut(id: Int): RunnableShortcut? = null
    }

    private class RecordingStarter : AcceptedShortcutRunStarter {
        val startedShortcutIds = mutableListOf<Int>()
        override fun start(shortcutId: Int, runId: String) {
            startedShortcutIds += shortcutId
        }
    }

    private class RecordingEditorStarter : AcceptedEditorRunStarter {
        val startedDrafts = mutableListOf<String>()
        override fun start(shortcutName: String, actions: List<Action>, runId: String) {
            startedDrafts += shortcutName
        }
    }

    private class RecordingAdmission : ShortcutRunAdmission {
        var acquireAttempted = false
        override fun tryAcquire(): ShortcutRunLease? {
            acquireAttempted = true
            return ShortcutRunLease("test", NoExecutionCancellation)
        }

        override fun release(runId: String) = Unit
    }
}
