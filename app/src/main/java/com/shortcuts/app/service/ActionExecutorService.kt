package com.shortcuts.app.service

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.shortcuts.app.data.Action
import com.shortcuts.app.data.ActionType
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

class ActionExecutorService(
    private val context: Context,
    private val accessibilityService: AutomationAccessibilityService? = null,
    private val callFactory: Call.Factory = OkHttpClient()
) {
    private var lastSuccessDetail: String? = null

    fun executeActions(actions: List<Action>, shortcutName: String = "Shortcut"): RunResult {
        val results = mutableListOf<StepResult>()
        val details = mutableListOf<String?>()
        actions.forEachIndexed { index, action ->
            val result = executeAction(action)
            results += result
            details += lastSuccessDetail

            if (result !is StepResult.Success && !action.continueOnError) {
                actions.drop(index + 1).forEach {
                    results += StepResult.Skipped("Skipped because step ${index + 1} did not complete")
                    details += null
                }
                return RunResult(shortcutName, results, details)
            }

            action.delayMillis
                ?.takeIf { it > 0 && index < actions.lastIndex }
                ?.let { Thread.sleep(it) }
        }
        return RunResult(shortcutName, results, details)
    }

    fun executeAction(action: Action): StepResult {
        lastSuccessDetail = null
        return when (action.actionType) {
        ActionType.SYSTEM_TOGGLE -> handleSystemToggle(action)
        ActionType.APP_INTENT -> handleAppIntent(action)
        ActionType.HTTP_REQUEST -> handleHttpRequest(action)
        ActionType.UI_AUTOMATION -> handleUiAutomation(action)
        ActionType.WAIT -> handleWait(action)
        ActionType.SEND_MESSAGE -> handleSendMessage(action)
        ActionType.DIAL_NUMBER -> handleDialNumber(action)
        }
    }

    private fun handleWait(action: Action): StepResult {
        val duration = action.delayMillis ?: return failed(FailureReason.INVALID_STATE, "Choose how long this shortcut should wait.")
        if (duration !in 1..MAX_WAIT_MILLIS) {
            return failed(FailureReason.INVALID_STATE, "Choose a wait between 1 millisecond and 10 minutes.")
        }
        return try {
            Thread.sleep(duration)
            StepResult.Success
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            failed(FailureReason.DEVICE_UNAVAILABLE, "The wait was interrupted before it finished.")
        }
    }

    private fun handleSystemToggle(action: Action): StepResult {
        val target = action.target.normalizedToggleTarget()
            ?: return failed(FailureReason.UNSUPPORTED_TARGET, "Choose a device control for this shortcut.")
        val state = action.state.normalizedToggleState()
            ?: return failed(FailureReason.INVALID_STATE, "Choose on, off, or toggle for ${action.target ?: "this control"}.")

        return when (target) {
            "flashlight", "torch" -> setFlashlight(state)
            "donotdisturb", "dnd" -> setDoNotDisturb(state)
            "volume" -> setVolume(state)
            "ringmode", "ringer" -> setRingMode(state)
            "autorotate", "rotation" -> setAutoRotate(state)
            "wifi" -> openRestrictedPanel(
                Intent(wifiSettingsAction()),
                "Android doesn't allow apps to turn WiFi on directly, so the WiFi panel was opened."
            )
            "bluetooth" -> requestBluetoothEnable(state)
            "airplanemode" -> openRestrictedPanel(
                Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
                "Android doesn't allow apps to change Airplane mode, so Airplane mode settings were opened."
            )
            "location" -> openRestrictedPanel(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                "Android doesn't allow apps to change Location directly, so Location settings were opened."
            )
            else -> failed(
                FailureReason.UNSUPPORTED_TARGET,
                "\"${action.target}\" isn't a device control this shortcut can run."
            )
        }
    }

    private fun setFlashlight(state: ToggleState): StepResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            return needsPermission(android.Manifest.permission.CAMERA)
        }
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return failed(FailureReason.DEVICE_UNAVAILABLE, "This device doesn't provide a flashlight.")
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        } ?: return failed(FailureReason.DEVICE_UNAVAILABLE, "This device doesn't provide a flashlight.")

        val enabled = when (state) {
            ToggleState.ON -> true
            ToggleState.OFF -> false
            ToggleState.TOGGLE -> !(torchStates[cameraId] ?: false)
        }
        return try {
            manager.setTorchMode(cameraId, enabled)
            torchStates[cameraId] = enabled
            StepResult.Success
        } catch (_: Exception) {
            failed(FailureReason.DEVICE_UNAVAILABLE, "The flashlight isn't available right now.")
        }
    }

    private fun setDoNotDisturb(state: ToggleState): StepResult {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return failed(FailureReason.DEVICE_UNAVAILABLE, "Do Not Disturb isn't available on this device.")
        if (!manager.isNotificationPolicyAccessGranted) {
            return StepResult.NeedsPermission(
                permission = "Do Not Disturb access",
                settingsIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            )
        }
        val filter = when (state) {
            ToggleState.ON -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            ToggleState.OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
            ToggleState.TOGGLE -> if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            } else {
                NotificationManager.INTERRUPTION_FILTER_ALL
            }
        }
        return try {
            manager.setInterruptionFilter(filter)
            StepResult.Success
        } catch (_: SecurityException) {
            StepResult.NeedsPermission(
                permission = "Do Not Disturb access",
                settingsIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            )
        }
    }

    private fun setVolume(state: ToggleState): StepResult {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return failed(FailureReason.DEVICE_UNAVAILABLE, "Volume controls aren't available on this device.")
        return try {
            val direction = when (state) {
                ToggleState.ON -> AudioManager.ADJUST_UNMUTE
                ToggleState.OFF -> AudioManager.ADJUST_MUTE
                ToggleState.TOGGLE -> if (manager.isStreamMute(AudioManager.STREAM_MUSIC)) {
                    AudioManager.ADJUST_UNMUTE
                } else {
                    AudioManager.ADJUST_MUTE
                }
            }
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            StepResult.Success
        } catch (_: Exception) {
            failed(FailureReason.DEVICE_UNAVAILABLE, "The device volume couldn't be changed.")
        }
    }

    private fun setRingMode(state: ToggleState): StepResult {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return failed(FailureReason.DEVICE_UNAVAILABLE, "Ring mode isn't available on this device.")
        return try {
            manager.ringerMode = when (state) {
                ToggleState.ON -> AudioManager.RINGER_MODE_NORMAL
                ToggleState.OFF -> AudioManager.RINGER_MODE_SILENT
                ToggleState.TOGGLE -> if (manager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
                    AudioManager.RINGER_MODE_NORMAL
                } else {
                    AudioManager.RINGER_MODE_SILENT
                }
            }
            StepResult.Success
        } catch (_: SecurityException) {
            failed(FailureReason.DEVICE_UNAVAILABLE, "Ring mode couldn't be changed on this device.")
        }
    }

    private fun setAutoRotate(state: ToggleState): StepResult {
        if (!Settings.System.canWrite(context)) {
            return StepResult.NeedsPermission(
                permission = "Modify system settings",
                settingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }
        return try {
            val value = when (state) {
                ToggleState.ON -> 1
                ToggleState.OFF -> 0
                ToggleState.TOGGLE -> if (Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0
                ) == 0) 1 else 0
            }
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, value)
            StepResult.Success
        } catch (_: SecurityException) {
            StepResult.NeedsPermission(
                permission = "Modify system settings",
                settingsIntent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        }
    }

    private fun requestBluetoothEnable(state: ToggleState): StepResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            return needsPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Android exposes no Settings.Panel for Bluetooth (only WIFI, INTERNET_CONNECTIVITY,
        // NFC and VOLUME exist). ACTION_REQUEST_ENABLE shows the system confirm dialog for
        // turning Bluetooth on; turning it off has no such intent and must go via settings.
        val turningOff = state == ToggleState.OFF
        val intent = if (turningOff) {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        } else {
            Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        }
        val what = if (turningOff) "Bluetooth settings were opened" else "the Bluetooth prompt was opened"
        return openRestrictedPanel(
            intent,
            "Android requires you to confirm Bluetooth changes, so $what."
        )
    }

    private fun openRestrictedPanel(intent: Intent, userMessage: String): StepResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        failed(FailureReason.PLATFORM_RESTRICTION, userMessage)
    } catch (_: Exception) {
        failed(FailureReason.ACTIVITY_UNAVAILABLE, "Android settings couldn't be opened for this control.")
    }

    private fun handleAppIntent(action: Action): StepResult {
        val packageName = action.packageName
            ?: return failed(FailureReason.APP_NOT_FOUND, "Choose an app to open.")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return failed(FailureReason.APP_NOT_FOUND, "${action.packageName} isn't installed on this device.")
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            StepResult.Success
        } catch (_: Exception) {
            failed(FailureReason.ACTIVITY_UNAVAILABLE, "${action.packageName} couldn't be opened.")
        }
    }

    private fun handleHttpRequest(action: Action): StepResult {
        val spec = httpRequestSpec(action)
            ?: return failed(FailureReason.NETWORK_ERROR, "Enter a valid web address for this request.")
        if (!isHttpAllowed(spec.url, spec.allowCleartext)) {
            return failed(
                FailureReason.NETWORK_ERROR,
                "For safety, web requests must use HTTPS. Turn on this action's cleartext option only for a server you trust."
            )
        }
        return try {
            val requestBuilder = Request.Builder().url(spec.url)
            spec.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            spec.secretReference?.let { reference ->
                val token = HttpSecretStore.get(context, reference)
                if (token.isNullOrBlank()) {
                    return failed(FailureReason.NETWORK_ERROR, "The saved authentication token for this web request is unavailable.")
                }
                requestBuilder.header("Authorization", "Bearer $token")
            }
            val body = spec.body?.toRequestBody()
            requestBuilder.method(spec.method, body.takeIf { spec.method.permitsRequestBody() })
            callFactory.newCall(requestBuilder.build()).execute().use { response ->
                val preview = response.body?.string().orEmpty().replace(Regex("\\s+"), " ").take(400)
                val detail = buildString {
                    append("HTTP ${response.code}")
                    if (preview.isNotBlank()) append(": ").append(preview)
                }
                if (response.isSuccessful) {
                    lastSuccessDetail = detail
                    StepResult.Success
                } else failed(FailureReason.NETWORK_ERROR, "$detail — the server rejected the request.")
            }
        } catch (_: Exception) {
            failed(FailureReason.NETWORK_ERROR, "The web request couldn't be completed. Check your connection and address.")
        }
    }

    private fun handleSendMessage(action: Action): StepResult {
        val recipient = action.target?.trim().orEmpty()
        val uri = smsSendToUri(recipient)
        if (uri == null) {
            return failed(FailureReason.ACTIVITY_UNAVAILABLE, "Enter a phone number before preparing a message.")
        }
        return try {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(uri)).apply {
                putExtra("sms_body", action.textInput.orEmpty())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            StepResult.Success
        } catch (_: Exception) {
            failed(FailureReason.ACTIVITY_UNAVAILABLE, "Your SMS app couldn't be opened. Check that a messaging app is installed.")
        }
    }

    private fun handleDialNumber(action: Action): StepResult {
        val recipient = action.target?.trim().orEmpty()
        val uri = dialUri(recipient)
        if (uri == null) {
            return failed(FailureReason.ACTIVITY_UNAVAILABLE, "Enter a phone number before opening the dialer.")
        }
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            StepResult.Success
        } catch (_: Exception) {
            failed(FailureReason.ACTIVITY_UNAVAILABLE, "The dialer couldn't be opened on this device.")
        }
    }

    private fun handleUiAutomation(action: Action): StepResult {
        val service = accessibilityService ?: AutomationAccessibilityService.instance
            ?: return failed(
                FailureReason.ACCESSIBILITY_UNAVAILABLE,
                "Turn on Shortcuts Accessibility Service to run this step."
            )
        return if (service.executeAction(action)) {
            StepResult.Success
        } else {
            failed(
                FailureReason.UI_AUTOMATION_FAILED,
                service.lastFailureMessage ?: "The target was not found or could not be used on the current screen."
            )
        }
    }

    private fun needsPermission(permission: String) = StepResult.NeedsPermission(
        permission = permission,
        settingsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    )

    private fun failed(reason: FailureReason, message: String) = StepResult.Failed(reason, message)

    private enum class ToggleState { ON, OFF, TOGGLE }

    private fun String?.normalizedToggleTarget(): String? = normalizeToggleTarget(this)

    private fun String?.normalizedToggleState(): ToggleState? = when (this?.trim()?.lowercase()) {
        "on", "enable", "enabled" -> ToggleState.ON
        "off", "disable", "disabled" -> ToggleState.OFF
        "toggle" -> ToggleState.TOGGLE
        else -> null
    }

    companion object {
        private const val MAX_WAIT_MILLIS = 10 * 60 * 1000L
        private val torchStates = ConcurrentHashMap<String, Boolean>()

        /** Normalizes user and model spellings such as "Do Not Disturb" into a stable target id. */
        fun normalizeToggleTarget(value: String?): String? = value
            ?.lowercase()
            ?.filter { it.isLetterOrDigit() }
            ?.takeIf { it.isNotEmpty() }

        /**
         * Pure mapping from SDK level to the WiFi screen Android actually permits us to open.
         * Extracted so regression tests can assert the exact action string: in plain JVM unit
         * tests android.jar is a stub, so Intent.getAction() always returns null and asserting
         * on a constructed Intent proves nothing. These Settings constants are compile-time
         * String constants, so they inline correctly under test.
         */
        fun wifiSettingsAction(sdkInt: Int = Build.VERSION.SDK_INT): String =
            if (sdkInt >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI
            else Settings.ACTION_WIFI_SETTINGS

        /** Pure value used by the SMS intent; kept testable without the stubbed android.jar. */
        fun smsSendToUri(recipient: String): String? = recipient.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "smsto:$it" }

        /** Pure value used by the dial intent; kept testable without the stubbed android.jar. */
        fun dialUri(recipient: String): String? = recipient.trim()
            .takeIf { it.isNotEmpty() }
            ?.let { "tel:$it" }

        /**
         * Maps the existing, serialized Action fields for HTTP without changing Room's JSON shape:
         * target=headers (one `Name: value` per line), textInput=body, targetNodeId=encrypted
         * secret reference, state=ALLOW_CLEARTEXT. globalAction is used only transiently by the
         * editor before the token is encrypted and removed before serialization.
         */
        fun httpRequestSpec(action: Action): HttpRequestSpec? {
            val url = action.url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val method = action.method?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "GET"
            return HttpRequestSpec(
                url = url,
                method = method,
                headers = parseCustomHeaders(action.target),
                body = action.textInput,
                secretReference = action.targetNodeId?.trim()?.takeIf { it.isNotEmpty() },
                allowCleartext = action.state.equals("ALLOW_CLEARTEXT", ignoreCase = true)
            )
        }

        /** HTTPS is mandatory unless this individual action explicitly opts in to HTTP. */
        fun isHttpAllowed(url: String, allowCleartext: Boolean): Boolean {
            val scheme = runCatching { java.net.URI(url).scheme?.lowercase() }.getOrNull()
            return scheme == "https" || (scheme == "http" && allowCleartext)
        }

        fun prepareActionForPersistence(context: Context, action: Action): Action? {
            if (action.actionType != ActionType.HTTP_REQUEST || action.globalAction.isNullOrBlank()) return action
            val reference = HttpSecretStore.put(context, action.globalAction) ?: return null
            return action.copy(targetNodeId = reference, globalAction = null)
        }

        private fun parseCustomHeaders(rawHeaders: String?): Map<String, String> = rawHeaders
            .orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            .filter { (name, value) -> name.isNotEmpty() && value.isNotEmpty() && !name.isSensitiveHeaderName() }
            .toMap()

        private fun String.isSensitiveHeaderName(): Boolean =
            equals("authorization", ignoreCase = true) ||
                equals("x-api-key", ignoreCase = true) ||
                equals("api-key", ignoreCase = true)
    }
}

data class HttpRequestSpec(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val secretReference: String?,
    val allowCleartext: Boolean
)

private fun String.permitsRequestBody(): Boolean = this !in setOf("GET", "HEAD")

private object HttpSecretStore {
    private const val FILE_NAME = "http_secrets"

    fun put(context: Context, token: String): String? = runCatching {
        val reference = "http_secret_${UUID.randomUUID()}"
        preferences(context).edit().putString(reference, token).apply()
        reference
    }.getOrNull()

    fun get(context: Context, reference: String): String? = runCatching {
        preferences(context).getString(reference, null)
    }.getOrNull()

    // security-crypto 1.1.x replaces the deprecated MasterKeys helper with MasterKey.Builder,
    // and EncryptedSharedPreferences.create takes (context, fileName, masterKey, ...) in that order.
    private fun preferences(context: Context): android.content.SharedPreferences {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
