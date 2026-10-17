package com.shortcuts.app.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.shortcuts.app.BuildConfig
import com.shortcuts.app.automation.android.EngineV2
import com.shortcuts.app.util.AccessibilityStatusChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** State describing diagnostics storage size and age. */
data class DiagnosticsState(
    val enabled: Boolean = true,
    val totalBytes: Long = 0L,
    val oldestMillis: Long? = null
)

/** Controller interface for ViewModel interaction with Diagnostics. */
interface DiagnosticsController {
    val state: StateFlow<DiagnosticsState>
    fun setEnabled(context: Context, enabled: Boolean)
    fun isEnabled(): Boolean
    fun clear()
    fun refreshState()
    fun buildExport(context: Context): File
    fun shareIntent(context: Context, file: File): Intent
}

class DefaultDiagnosticsController : DiagnosticsController {
    override val state: StateFlow<DiagnosticsState> get() = Diagnostics.state
    override fun setEnabled(context: Context, enabled: Boolean) = Diagnostics.setEnabled(context, enabled)
    override fun isEnabled(): Boolean = Diagnostics.isEnabled()
    override fun clear() = Diagnostics.clear()
    override fun refreshState() = Diagnostics.refreshState()
    override fun buildExport(context: Context): File = Diagnostics.buildExport(context)
    override fun shareIntent(context: Context, file: File): Intent = Diagnostics.shareIntent(context, file)
}

/**
 * Android-facing facade singleton for logging, lifecycle management, and export.
 * Before [init] is called, [log] is a safe, silent no-op (crucial for JVM unit tests).
 */
object Diagnostics {

    private const val PREFS_NAME = "diagnostics"
    private const val PREF_KEY_ENABLED = "enabled"
    private const val EXPORT_DIR_NAME = "diagnostics-export"

    @Volatile
    private var logInstance: DiagnosticsLog? = null
    private var initialized = false

    private val _state = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (initialized && logInstance != null) return

        val appContext = context.applicationContext ?: context
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(PREF_KEY_ENABLED, true)

        val logDir = File(appContext.filesDir, "diagnostics")
        val log = DiagnosticsLog(
            dir = logDir,
            wallClock = System::currentTimeMillis,
            uptimeClock = SystemClock::uptimeMillis
        )
        log.enabled = enabled
        logInstance = log

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (previousHandler !is DiagnosticsCrashHandler) {
            Thread.setDefaultUncaughtExceptionHandler(
                DiagnosticsCrashHandler(previousHandler, log)
            )
        }

        initialized = true
        refreshState()

        log(
            "app",
            "process_start",
            mapOf(
                "versionName" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "buildType" to BuildConfig.BUILD_TYPE,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "sdkInt" to Build.VERSION.SDK_INT,
                "release" to Build.VERSION.RELEASE
            )
        )
    }

    /** Thread-safe, cheap event logging. Silent no-op before [init] or when disabled. */
    fun log(category: String, event: String, fields: Map<String, Any?> = emptyMap()) {
        logInstance?.append(category, event, fields)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext ?: context
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_KEY_ENABLED, enabled)
                .apply()
        } catch (_: Throwable) {
            // Swallowed
        }

        logInstance?.enabled = enabled
        refreshState()
    }

    fun isEnabled(): Boolean = logInstance?.enabled ?: false

    fun refreshState() {
        val log = logInstance
        _state.value = DiagnosticsState(
            enabled = log?.enabled ?: false,
            totalBytes = log?.totalBytes() ?: 0L,
            oldestMillis = log?.oldestTimestampMillis()
        )
    }

    fun clear() {
        logInstance?.clear()
        refreshState()
    }

    fun flush() {
        logInstance?.flush()
    }

    fun buildExport(context: Context): File {
        val appContext = context.applicationContext ?: context
        val exportDir = File(appContext.cacheDir, EXPORT_DIR_NAME)
        try {
            if (exportDir.exists()) {
                exportDir.listFiles()?.forEach { it.delete() }
            } else {
                exportDir.mkdirs()
            }
        } catch (_: Throwable) {
            // Swallowed
        }

        val timestamp = try {
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).format(LocalDateTime.now())
        } catch (_: Throwable) {
            System.currentTimeMillis().toString()
        }
        val exportFile = File(exportDir, "repeatkit-diagnostics-$timestamp.txt")

        val headerLines = listOf(
            "RepeatKit ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.BUILD_TYPE}",
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}",
            "Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            "Export time: ${OffsetDateTime.now(ZoneId.systemDefault())} (${ZoneId.systemDefault().id})",
            "EngineV2.enabled: ${EngineV2.enabled}",
            "Accessibility service enabled: ${AccessibilityStatusChecker.isAccessibilityServiceActive(appContext)}",
            "Default keyboard: ${resolveDefaultKeyboard(appContext)}",
            "Default home app: ${resolveDefaultHome(appContext)}",
            "Privacy: structural events only — no typed text or on-screen text."
        )

        val log = logInstance ?: DiagnosticsLog(File(appContext.filesDir, "diagnostics"))
        return log.export(exportFile, headerLines)
    }

    fun shareIntent(context: Context, file: File): Intent {
        val appContext = context.applicationContext ?: context
        val authority = "${appContext.packageName}.diagnostics"
        val uri = FileProvider.getUriForFile(appContext, authority, file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "RepeatKit diagnostics ${BuildConfig.VERSION_NAME}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, "Share diagnostics log")
    }

    private fun resolveDefaultKeyboard(context: Context): String {
        return try {
            val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!ime.isNullOrBlank()) {
                val component = ComponentName.unflattenFromString(ime)
                component?.packageName ?: ime.substringBefore('/')
            } else {
                "unknown"
            }
        } catch (_: Throwable) {
            "unknown"
        }
    }

    private fun resolveDefaultHome(context: Context): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            resolveInfo?.activityInfo?.packageName ?: "unknown"
        } catch (_: Throwable) {
            "unknown"
        }
    }

    internal fun setLogForTest(log: DiagnosticsLog?) {
        logInstance = log
        refreshState()
    }
}
