package com.shortcuts.app.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import com.shortcuts.app.data.Automation
import com.shortcuts.app.util.AccessibilityStatusChecker
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The one product-level request to run a saved shortcut.
 *
 * Keeping the launch surface in the request makes future trigger and editor callers use the
 * same admission policy while still leaving room for receipts to record where a run came from.
 */
data class ShortcutRunRequest(
    val shortcutId: Int,
    val source: ShortcutRunSource
)

enum class ShortcutRunSource {
    DASHBOARD,
    WIDGET,
    EDITOR,
    TRIGGER
}

/** A saved shortcut reduced to the fields required before execution can begin. */
data class RunnableShortcut(
    val id: Int,
    val name: String,
    val isActive: Boolean
)

/**
 * An explicit answer to a [ShortcutRunRequest]. Callers must never infer an outcome from a
 * boolean or from whether a service happened to be started.
 */
sealed interface ShortcutRunOutcome {
    val source: ShortcutRunSource

    data class Started(
        val request: ShortcutRunRequest,
        val shortcutName: String,
        val runId: String
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    data class RejectedAlreadyRunning(
        val request: ShortcutRunRequest
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    data class RejectedInactive(
        val request: ShortcutRunRequest,
        val shortcutName: String
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    data class RejectedMissingShortcut(
        val request: ShortcutRunRequest
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    data class Failed(
        val request: ShortcutRunRequest,
        val reason: ShortcutRunFailureReason,
        val userMessage: String
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    /**
     * Editors may run an unsaved draft, but still take the same admission lease and foreground
     * service path as every saved shortcut. The draft never bypasses [ShortcutRunner].
     */
    data class EditorStarted(
        val request: EditorShortcutRunRequest,
        val runId: String
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    data class EditorRejectedAlreadyRunning(
        val request: EditorShortcutRunRequest
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    data class EditorRejectedInvalidDraft(
        val request: EditorShortcutRunRequest,
        val userMessage: String
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    data class EditorConfirmationRequired(
        val request: EditorShortcutRunRequest,
        val effects: List<String>
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    data class EditorRejectedPreflight(
        val request: EditorShortcutRunRequest,
        val report: ShortcutPreflightReport
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }

    data class EditorFailed(
        val request: EditorShortcutRunRequest,
        val reason: ShortcutRunFailureReason,
        val userMessage: String
    ) : ShortcutRunOutcome {
        override val source: ShortcutRunSource = request.source
    }
}

enum class ShortcutRunFailureReason {
    SHORTCUT_LOOKUP_FAILED,
    FOREGROUND_SERVICE_START_FAILED,
    EDITOR_RUN_START_FAILED
}

/** One wording source for the immediate result shown by dashboard and widgets. */
fun ShortcutRunOutcome.userMessage(): String = when (this) {
    is ShortcutRunOutcome.Started -> "Running \"$shortcutName\""
    is ShortcutRunOutcome.RejectedAlreadyRunning -> "Another shortcut is already running."
    is ShortcutRunOutcome.RejectedInactive -> "Cannot run \"$shortcutName\" because it is deactivated."
    is ShortcutRunOutcome.RejectedMissingShortcut -> "This shortcut no longer exists."
    is ShortcutRunOutcome.Failed -> userMessage
    is ShortcutRunOutcome.EditorStarted -> "Running \"${request.shortcutName}\""
    is ShortcutRunOutcome.EditorRejectedAlreadyRunning -> "Another shortcut is already running."
    is ShortcutRunOutcome.EditorRejectedInvalidDraft -> userMessage
    is ShortcutRunOutcome.EditorConfirmationRequired -> "Review and confirm this shortcut's external effects before running it."
    is ShortcutRunOutcome.EditorRejectedPreflight -> "Check this shortcut and fix the listed problems before running it."
    is ShortcutRunOutcome.EditorFailed -> userMessage
}

interface ShortcutRunStore {
    suspend fun findShortcut(id: Int): RunnableShortcut?
}

fun interface AcceptedShortcutRunStarter {
    /** Starts the foreground service only after the runner has admitted this [runId]. */
    fun start(shortcutId: Int, runId: String)
}

/** Starts an admitted, unsaved editor draft in the same foreground service as saved shortcuts. */
fun interface AcceptedEditorRunStarter {
    fun start(shortcutName: String, actions: List<Action>, runId: String)
}

data class EditorShortcutRunRequest(
    val shortcutName: String,
    val actions: List<Action>,
    val source: ShortcutRunSource = ShortcutRunSource.EDITOR,
    /** Set only after the editor has shown one combined, effect-specific confirmation. */
    val externalEffectsConfirmed: Boolean = false
)

interface ShortcutRunAdmission {
    /** Returns a lease for the only active run, or null when another run already owns it. */
    fun tryAcquire(): ShortcutRunLease?

    /** Releases [runId] only when it still owns the active lease. */
    fun release(runId: String)
}

/**
 * Stable, user-actionable reasons a shortcut cannot safely start.
 *
 * This deliberately lives outside the executor's transient [FailureReason] values: preflight
 * reports a problem before an action is attempted, and the same category can later be stored in
 * a redacted run receipt without depending on a handler's implementation detail.
 */
enum class ShortcutIssueCategory {
    REQUIRED_FIELD_MISSING,
    UNSUPPORTED_ACTION,
    UNSUPPORTED_TARGET,
    APP_NOT_AVAILABLE,
    PERMISSION_REQUIRED,
    SYSTEM_SETTING_REQUIRED,
    URL_POLICY_VIOLATION,
    DEVICE_CAPABILITY_UNAVAILABLE,
    PLATFORM_RESTRICTION
}

/** A settings destination the editor can offer without validation ever launching an activity. */
enum class ShortcutPreflightResolution {
    APP_PERMISSIONS,
    DO_NOT_DISTURB_ACCESS,
    MODIFY_SYSTEM_SETTINGS,
    ACCESSIBILITY_SETTINGS
}

/** Creating this intent is safe; only the editor invokes [Context.startActivity] after a tap. */
fun ShortcutPreflightResolution.settingsIntent(context: Context): Intent = when (this) {
    ShortcutPreflightResolution.APP_PERMISSIONS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    ShortcutPreflightResolution.DO_NOT_DISTURB_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    ShortcutPreflightResolution.MODIFY_SYSTEM_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    ShortcutPreflightResolution.ACCESSIBILITY_SETTINGS -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}

data class ShortcutPreflightIssue(
    /** Zero-based, matching the action list; UI copy presents it as a one-based step number. */
    val actionIndex: Int,
    val category: ShortcutIssueCategory,
    val userMessage: String,
    val blocking: Boolean = true,
    val resolution: ShortcutPreflightResolution? = null
)

data class ShortcutPreflightReport(
    val issues: List<ShortcutPreflightIssue>
) {
    val canRun: Boolean
        get() = issues.none { it.blocking }

    val blockingIssues: List<ShortcutPreflightIssue>
        get() = issues.filter { it.blocking }
}

/**
 * Read-only device queries used by [ShortcutPreflight]. No executor, network client, activity,
 * accessibility gesture, or device-setting write is available through this interface.
 */
interface ShortcutPreflightEnvironment {
    val sdkInt: Int

    fun canLaunchPackage(packageName: String): Boolean
    fun hasPermission(permission: String): Boolean
    fun hasNotificationPolicyAccess(): Boolean
    fun canModifySystemSettings(): Boolean
    fun isAccessibilityServiceActive(): Boolean
    fun hasFlashlight(): Boolean
}

private class AndroidShortcutPreflightEnvironment(
    private val context: Context
) : ShortcutPreflightEnvironment {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun canLaunchPackage(packageName: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(packageName) != null
    }.getOrDefault(false)

    override fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    override fun hasNotificationPolicyAccess(): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.isNotificationPolicyAccessGranted == true

    override fun canModifySystemSettings(): Boolean = Settings.System.canWrite(context)

    override fun isAccessibilityServiceActive(): Boolean =
        AccessibilityStatusChecker.isAccessibilityServiceActive(context)

    override fun hasFlashlight(): Boolean {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        return runCatching {
            manager.cameraIdList.any { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrDefault(false)
    }
}

/**
 * A pure, no-side-effect check for predictable action failures.
 *
 * The preflight intentionally has no reference to [ActionExecutorService], OkHttp, or an Android
 * activity launcher. It only asks [ShortcutPreflightEnvironment] read-only questions, so calling
 * it cannot run a gesture, send a message, make a request, or change a system setting.
 */
class ShortcutPreflight(
    private val environment: ShortcutPreflightEnvironment
) {
    constructor(context: Context) : this(AndroidShortcutPreflightEnvironment(context.applicationContext))

    fun check(actions: List<Action>): ShortcutPreflightReport {
        val issues = actions.flatMapIndexed { index, action -> checkAction(index, action) }
        return ShortcutPreflightReport(issues)
    }

    private fun checkAction(index: Int, action: Action): List<ShortcutPreflightIssue> = when (action.actionType) {
        ActionType.SYSTEM_TOGGLE -> checkSystemToggle(index, action)
        ActionType.APP_INTENT -> checkAppIntent(index, action)
        ActionType.HTTP_REQUEST -> checkHttpRequest(index, action)
        ActionType.UI_AUTOMATION -> checkUiAutomation(index, action)
        ActionType.WAIT -> checkWait(index, action)
        ActionType.SEND_MESSAGE -> checkRecipient(index, action, "Enter a phone number before preparing a message.")
        ActionType.DIAL_NUMBER -> checkRecipient(index, action, "Enter a phone number before opening the dialer.")
    }

    private fun checkSystemToggle(index: Int, action: Action): List<ShortcutPreflightIssue> {
        val target = ActionExecutorService.normalizeToggleTarget(action.target)
            ?: return listOf(issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, "Choose a device control."))
        if (!validToggleStates.contains(action.state?.trim()?.lowercase())) {
            return listOf(issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, "Choose on, off, or toggle for ${action.target ?: "this control"}."))
        }
        return when (target) {
            "flashlight", "torch" -> checkFlashlight(index)
            "donotdisturb", "dnd" -> if (environment.hasNotificationPolicyAccess()) emptyList() else listOf(
                issue(
                    index,
                    ShortcutIssueCategory.SYSTEM_SETTING_REQUIRED,
                    "Allow Do Not Disturb access before this step can run.",
                    resolution = ShortcutPreflightResolution.DO_NOT_DISTURB_ACCESS
                )
            )
            "autorotate", "rotation" -> if (environment.canModifySystemSettings()) emptyList() else listOf(
                issue(
                    index,
                    ShortcutIssueCategory.SYSTEM_SETTING_REQUIRED,
                    "Allow RepeatKit to modify system settings before this step can run.",
                    resolution = ShortcutPreflightResolution.MODIFY_SYSTEM_SETTINGS
                )
            )
            "bluetooth" -> if (
                environment.sdkInt >= Build.VERSION_CODES.S &&
                !environment.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                listOf(
                    issue(
                        index,
                        ShortcutIssueCategory.PERMISSION_REQUIRED,
                        "Allow Nearby devices (Bluetooth) access before this step can run.",
                        resolution = ShortcutPreflightResolution.APP_PERMISSIONS
                    )
                )
            } else {
                emptyList()
            }
            "wifi", "airplanemode", "location" -> listOf(
                issue(
                    index,
                    ShortcutIssueCategory.PLATFORM_RESTRICTION,
                    "Android will open its settings for this control; you will need to finish the change there.",
                    blocking = false
                )
            )
            "volume", "ringmode", "ringer" -> emptyList()
            else -> listOf(
                issue(
                    index,
                    ShortcutIssueCategory.UNSUPPORTED_TARGET,
                    "\"${action.target}\" is not a device control this shortcut can run."
                )
            )
        }
    }

    private fun checkFlashlight(index: Int): List<ShortcutPreflightIssue> {
        if (environment.sdkInt >= Build.VERSION_CODES.M && !environment.hasPermission(Manifest.permission.CAMERA)) {
            return listOf(
                issue(
                    index,
                    ShortcutIssueCategory.PERMISSION_REQUIRED,
                    "Allow Camera access before this flashlight step can run.",
                    resolution = ShortcutPreflightResolution.APP_PERMISSIONS
                )
            )
        }
        return if (environment.hasFlashlight()) {
            emptyList()
        } else {
            listOf(issue(index, ShortcutIssueCategory.DEVICE_CAPABILITY_UNAVAILABLE, "This device does not provide a flashlight."))
        }
    }

    private fun checkAppIntent(index: Int, action: Action): List<ShortcutPreflightIssue> {
        val packageName = action.packageName?.trim().orEmpty()
        if (packageName.isEmpty()) {
            return listOf(issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, "Choose an app to open."))
        }
        return if (environment.canLaunchPackage(packageName)) {
            emptyList()
        } else {
            listOf(issue(index, ShortcutIssueCategory.APP_NOT_AVAILABLE, "$packageName is not installed on this device."))
        }
    }

    private fun checkHttpRequest(index: Int, action: Action): List<ShortcutPreflightIssue> {
        val spec = ActionExecutorService.httpRequestSpec(action)
            ?: return listOf(issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, "Enter a web address for this request."))
        if (!validHttpMethods.contains(spec.method)) {
            return listOf(issue(index, ShortcutIssueCategory.UNSUPPORTED_ACTION, "\"${spec.method}\" is not an HTTP method this shortcut can run."))
        }
        val uri = runCatching { URI(spec.url) }.getOrNull()
        if (uri?.host.isNullOrBlank()) {
            return listOf(issue(index, ShortcutIssueCategory.URL_POLICY_VIOLATION, "Enter a complete HTTP or HTTPS web address."))
        }
        return if (ActionExecutorService.isHttpAllowed(spec.url, spec.allowCleartext)) {
            emptyList()
        } else {
            listOf(
                issue(
                    index,
                    ShortcutIssueCategory.URL_POLICY_VIOLATION,
                    "Web requests must use HTTPS unless cleartext was explicitly enabled for a trusted server."
                )
            )
        }
    }

    private fun checkUiAutomation(index: Int, action: Action): List<ShortcutPreflightIssue> {
        if (!environment.isAccessibilityServiceActive()) {
            return listOf(
                issue(
                    index,
                    ShortcutIssueCategory.SYSTEM_SETTING_REQUIRED,
                    "Turn on RepeatKit Accessibility Service before this screen step can run.",
                    resolution = ShortcutPreflightResolution.ACCESSIBILITY_SETTINGS
                )
            )
        }

        val type = action.uiActionType?.trim()?.uppercase()
        val hasSelector = !action.targetText.isNullOrBlank() ||
            !action.targetNodeId.isNullOrBlank() ||
            !action.target.isNullOrBlank() ||
            !action.targetContentDescription.isNullOrBlank()
        val hasCoordinateFallback = action.screenX != null && action.screenY != null &&
            action.recordedDisplayWidth != null && action.recordedDisplayHeight != null &&
            action.recordedDisplayRotation != null && action.recordedDensityDpi != null
        val isGlobal = !action.globalAction.isNullOrBlank() || type in globalActionTypes
        val isText = action.textInput != null || type in textEntryTypes
        val isScroll = !action.scrollDirection.isNullOrBlank() || type in scrollTypes
        val isClick = type == null || type in clickTypes
        val isLongPress = type == "LONG_PRESS"
        val isPressEnter = type == "PRESS_ENTER"

        return when {
            isGlobal && !hasRecognizedGlobalAction(action) -> listOf(
                issue(index, ShortcutIssueCategory.UNSUPPORTED_ACTION, "Choose a supported global screen action.")
            )
            isText && action.textInput == null -> listOf(
                issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, "Enter text for this screen step.")
            )
            isLongPress && !hasSelector && !hasCoordinateFallback -> listOf(
                issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, "Choose something on screen to long-press.")
            )
            isPressEnter && !hasSelector -> listOf(
                issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, "Choose the text field that should submit its entry.")
            )
            isClick && !hasSelector && !hasCoordinateFallback -> listOf(
                issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, "Choose something on screen for this step to target.")
            )
            !isGlobal && !isText && !isScroll && !isClick && !isLongPress && !isPressEnter -> listOf(
                issue(index, ShortcutIssueCategory.UNSUPPORTED_ACTION, "\"${action.uiActionType}\" is not a screen action this shortcut can run.")
            )
            else -> emptyList()
        }
    }

    private fun checkWait(index: Int, action: Action): List<ShortcutPreflightIssue> = when (action.delayMillis) {
        null -> listOf(issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, "Choose how long this shortcut should wait."))
        !in 1..MAX_WAIT_MILLIS -> listOf(issue(index, ShortcutIssueCategory.UNSUPPORTED_ACTION, "Choose a wait between 1 millisecond and 10 minutes."))
        else -> emptyList()
    }

    private fun checkRecipient(index: Int, action: Action, message: String): List<ShortcutPreflightIssue> =
        if (action.target.isNullOrBlank()) listOf(issue(index, ShortcutIssueCategory.REQUIRED_FIELD_MISSING, message)) else emptyList()

    private fun issue(
        index: Int,
        category: ShortcutIssueCategory,
        message: String,
        blocking: Boolean = true,
        resolution: ShortcutPreflightResolution? = null
    ) = ShortcutPreflightIssue(index, category, message, blocking, resolution)

    private fun hasRecognizedGlobalAction(action: Action): Boolean {
        val key = (action.globalAction ?: action.uiActionType ?: action.target ?: action.intentAction)
            ?.trim()
            ?.uppercase()
            ?: return false
        return key in recognizedGlobalActions || key.toIntOrNull() in recognizedGlobalActionIds
    }

    private companion object {
        const val MAX_WAIT_MILLIS = 10 * 60 * 1000L
        val validToggleStates = setOf("on", "enable", "enabled", "off", "disable", "disabled", "toggle")
        val validHttpMethods = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        val globalActionTypes = setOf(
            "GLOBAL_ACTION", "GLOBAL_NAVIGATION", "GLOBAL", "SYSTEM_NAV",
            "GLOBAL_ACTION_BACK", "GLOBAL_ACTION_HOME", "GLOBAL_ACTION_RECENTS",
            "GLOBAL_ACTION_NOTIFICATIONS", "GLOBAL_ACTION_QUICK_SETTINGS",
            "GLOBAL_ACTION_POWER_DIALOG", "GLOBAL_ACTION_LOCK_SCREEN", "GLOBAL_ACTION_TAKE_SCREENSHOT"
        )
        val textEntryTypes = setOf("TYPE_TEXT", "SET_TEXT", "TEXT_ENTRY", "TYPE", "INPUT_TEXT")
        val scrollTypes = setOf("SCROLL", "SCROLL_FORWARD", "SCROLL_BACKWARD")
        val clickTypes = setOf("CLICK", "TAP", "PRESS")
        val recognizedGlobalActions = setOf(
            "BACK", "GLOBAL_ACTION_BACK", "HOME", "GLOBAL_ACTION_HOME", "RECENTS", "GLOBAL_ACTION_RECENTS",
            "NOTIFICATIONS", "GLOBAL_ACTION_NOTIFICATIONS", "QUICK_SETTINGS", "GLOBAL_ACTION_QUICK_SETTINGS",
            "POWER_DIALOG", "GLOBAL_ACTION_POWER_DIALOG", "LOCK_SCREEN", "GLOBAL_ACTION_LOCK_SCREEN",
            "TAKE_SCREENSHOT", "GLOBAL_ACTION_TAKE_SCREENSHOT"
        )
        val recognizedGlobalActionIds = setOf(1, 2, 3, 4, 5, 6, 8, 9)
    }
}

/**
 * One concise confirmation is enough for a run; asking once per risky step would train users to
 * dismiss the prompt. GET and HEAD are safe reads, while the remaining operations can act outward.
 */
object ShortcutRunSafety {
    fun confirmationEffects(actions: List<Action>): List<String> = buildList {
        if (actions.any { it.actionType == ActionType.SEND_MESSAGE }) add("prepare a message to another person")
        if (actions.any { it.actionType == ActionType.DIAL_NUMBER }) add("open the dialer for a call")
        actions.filter { it.actionType == ActionType.HTTP_REQUEST }
            .map { it.method?.trim()?.uppercase() ?: "GET" }
            .filterNot { it in safeHttpReadMethods }
            .distinct()
            .forEach { add("send an HTTP $it request") }
        if (actions.any { it.actionType == ActionType.UI_AUTOMATION }) add("interact with another app's screen")
    }

    private val safeHttpReadMethods = setOf("GET", "HEAD")
}

class ShortcutRunLease internal constructor(
    val runId: String,
    val cancellation: ExecutionCancellation
)

/**
 * Cooperative cancellation shared by the foreground service and executor. Cancellation interrupts
 * a cancellable delay immediately and is checked before each subsequent action.
 */
interface ExecutionCancellation {
    val isCancellationRequested: Boolean

    fun enterExecution()
    fun leaveExecution()
}

private class ActiveRunCancellation : ExecutionCancellation {
    private val cancelled = AtomicBoolean(false)
    private val executingThread = AtomicReference<Thread?>(null)

    override val isCancellationRequested: Boolean
        get() = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        executingThread.get()?.interrupt()
    }

    override fun enterExecution() {
        val thread = Thread.currentThread()
        executingThread.set(thread)
        if (cancelled.get()) thread.interrupt()
    }

    override fun leaveExecution() {
        executingThread.compareAndSet(Thread.currentThread(), null)
    }
}

internal object NoExecutionCancellation : ExecutionCancellation {
    override val isCancellationRequested: Boolean = false
    override fun enterExecution() = Unit
    override fun leaveExecution() = Unit
}

/** Process-wide, bounded admission for every accepted saved-shortcut run. */
object ShortcutRunCoordinator : ShortcutRunAdmission {
    private val activeLease = AtomicReference<ShortcutRunLease?>(null)

    override fun tryAcquire(): ShortcutRunLease? {
        val cancellation = ActiveRunCancellation()
        val lease = ShortcutRunLease(UUID.randomUUID().toString(), cancellation)
        return if (activeLease.compareAndSet(null, lease)) lease else null
    }

    override fun release(runId: String) {
        val active = activeLease.get()
        if (active?.runId == runId) activeLease.compareAndSet(active, null)
    }

    fun leaseFor(runId: String): ShortcutRunLease? = activeLease.get()
        ?.takeIf { it.runId == runId }

    fun cancel(runId: String): Boolean {
        val lease = leaseFor(runId) ?: return false
        (lease.cancellation as? ActiveRunCancellation)?.cancel()
        return true
    }
}

/**
 * Single execution gateway for all saved shortcuts. It owns lookup, active-state enforcement,
 * bounded admission, and foreground-service startup; the service owns the accepted run's lifetime.
 */
class ShortcutRunner(
    private val store: ShortcutRunStore,
    private val acceptedRunStarter: AcceptedShortcutRunStarter,
    private val admission: ShortcutRunAdmission = ShortcutRunCoordinator,
    private val acceptedEditorRunStarter: AcceptedEditorRunStarter? = null,
    private val preflightEnvironment: ShortcutPreflightEnvironment? = null
) {
    constructor(context: Context) : this(
        store = RoomShortcutRunStore(context.applicationContext),
        acceptedRunStarter = AcceptedShortcutRunStarter { shortcutId, runId ->
            AutomationExecutionService.startAccepted(context.applicationContext, shortcutId, runId)
        },
        acceptedEditorRunStarter = AcceptedEditorRunStarter { shortcutName, actions, runId ->
            AutomationExecutionService.startAcceptedEditorDraft(
                context = context.applicationContext,
                shortcutName = shortcutName,
                actions = actions,
                runId = runId
            )
        },
        preflightEnvironment = AndroidShortcutPreflightEnvironment(context.applicationContext)
    )

    /** Validation is deliberately available through the same product gateway without admission. */
    fun preflight(actions: List<Action>): ShortcutPreflightReport {
        val environment = requireNotNull(preflightEnvironment) {
            "ShortcutRunner needs a preflight environment before it can check a draft."
        }
        return ShortcutPreflight(environment).check(actions)
    }

    suspend fun run(request: ShortcutRunRequest): ShortcutRunOutcome {
        val shortcut = (try {
            store.findShortcut(request.shortcutId)
        } catch (_: Exception) {
            return presentStartFailure(ShortcutRunOutcome.Failed(
                request = request,
                reason = ShortcutRunFailureReason.SHORTCUT_LOOKUP_FAILED,
                userMessage = "RepeatKit couldn't look up this shortcut. Please try again."
            ))
        }) ?: return ShortcutRunOutcome.RejectedMissingShortcut(request)
        if (!shortcut.isActive) {
            return ShortcutRunOutcome.RejectedInactive(request, shortcut.name)
        }

        val lease = admission.tryAcquire()
            ?: return ShortcutRunOutcome.RejectedAlreadyRunning(request)

        return try {
            acceptedRunStarter.start(shortcut.id, lease.runId)
            ShortcutRunOutcome.Started(request, shortcut.name, lease.runId)
        } catch (_: Exception) {
            admission.release(lease.runId)
            presentStartFailure(ShortcutRunOutcome.Failed(
                request = request,
                reason = ShortcutRunFailureReason.FOREGROUND_SERVICE_START_FAILED,
                userMessage = "RepeatKit couldn't start this shortcut right now. Please try again."
            ))
        }
    }

    /**
     * Starts an unsaved editor draft only through the same admission coordinator and foreground
     * service path as a saved shortcut. Editors run a [ShortcutPreflight] before calling this.
     */
    fun runEditor(request: EditorShortcutRunRequest): ShortcutRunOutcome {
        if (request.source != ShortcutRunSource.EDITOR) {
            return ShortcutRunOutcome.EditorRejectedInvalidDraft(
                request,
                "Only the editor can run an unsaved shortcut draft."
            )
        }
        if (request.shortcutName.isBlank()) {
            return ShortcutRunOutcome.EditorRejectedInvalidDraft(
                request,
                "Give this shortcut a name before running it."
            )
        }
        if (request.actions.isEmpty()) {
            return ShortcutRunOutcome.EditorRejectedInvalidDraft(
                request,
                "Add at least one step before running this shortcut."
            )
        }
        preflightEnvironment?.let { environment ->
            val report = ShortcutPreflight(environment).check(request.actions)
            if (!report.canRun) return ShortcutRunOutcome.EditorRejectedPreflight(request, report)
        }
        val effects = ShortcutRunSafety.confirmationEffects(request.actions)
        if (effects.isNotEmpty() && !request.externalEffectsConfirmed) {
            return ShortcutRunOutcome.EditorConfirmationRequired(request, effects)
        }
        val starter = acceptedEditorRunStarter
            ?: return presentStartFailure(ShortcutRunOutcome.EditorFailed(
                request,
                ShortcutRunFailureReason.EDITOR_RUN_START_FAILED,
                "RepeatKit couldn't start this editor draft right now. Please try again."
            ))
        val lease = admission.tryAcquire()
            ?: return ShortcutRunOutcome.EditorRejectedAlreadyRunning(request)

        return try {
            starter.start(request.shortcutName.trim(), request.actions, lease.runId)
            ShortcutRunOutcome.EditorStarted(request, lease.runId)
        } catch (_: Exception) {
            admission.release(lease.runId)
            presentStartFailure(ShortcutRunOutcome.EditorFailed(
                request,
                ShortcutRunFailureReason.EDITOR_RUN_START_FAILED,
                "RepeatKit couldn't start this editor draft right now. Please try again."
            ))
        }
    }

    private fun presentStartFailure(outcome: ShortcutRunOutcome): ShortcutRunOutcome {
        val message = when (outcome) {
            is ShortcutRunOutcome.Failed -> ReplayFailurePill.messageFor(outcome.reason, outcome.userMessage)
            is ShortcutRunOutcome.EditorFailed -> ReplayFailurePill.messageFor(outcome.reason, outcome.userMessage)
            else -> return outcome
        }
        ReplayFailurePill.show(AutomationAccessibilityService.instance, message)
        return outcome
    }
}

private class RoomShortcutRunStore(context: Context) : ShortcutRunStore {
    // Constructing a runner for a preflight must not even open Room. Lookup is deferred until a
    // saved shortcut is actually admitted for execution.
    private val appContext = context.applicationContext
    private val automationDao by lazy { AppDatabase.getDatabase(appContext).automationDao() }

    override suspend fun findShortcut(id: Int): RunnableShortcut? = automationDao
        .getAutomationById(id)
        ?.toRunnableShortcut()
}

private fun Automation.toRunnableShortcut() = RunnableShortcut(
    id = id,
    name = name,
    isActive = isActive
)
