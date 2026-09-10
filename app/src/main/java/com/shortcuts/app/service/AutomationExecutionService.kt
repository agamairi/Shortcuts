package com.shortcuts.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shortcuts.app.R
import com.shortcuts.app.data.ActionConverter
import com.shortcuts.app.data.AppDatabase
import com.shortcuts.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Executes an already admitted saved-shortcut run and keeps it alive in the foreground. */
class AutomationExecutionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentRunId: String? = null
    @Volatile private var latestStartId: Int = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) {
            val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
            if (ShortcutRunCoordinator.cancel(runId)) {
                startForeground(FOREGROUND_NOTIFICATION_ID, stoppingNotification())
            } else {
                stopSelf(startId)
            }
            return START_NOT_STICKY
        }

        val automationId = intent?.getIntExtra(EXTRA_AUTOMATION_ID, NO_AUTOMATION_ID) ?: NO_AUTOMATION_ID
        val editorActionsJson = intent?.getStringExtra(EXTRA_EDITOR_ACTIONS_JSON)
        val editorShortcutName = intent?.getStringExtra(EXTRA_EDITOR_SHORTCUT_NAME)
        val runId = intent?.getStringExtra(EXTRA_RUN_ID).orEmpty()
        val lease = ShortcutRunCoordinator.leaseFor(runId)
        if ((automationId == NO_AUTOMATION_ID && editorActionsJson.isNullOrBlank()) || lease == null) {
            // Services are only started by ShortcutRunner after it owns a lease. Reject stale or
            // malformed intents rather than silently creating a second execution path.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        currentRunId = runId
        startForeground(FOREGROUND_NOTIFICATION_ID, foregroundNotification(runId))
        serviceScope.launch {
            try {
                if (!editorActionsJson.isNullOrBlank()) {
                    val result = ActionExecutorService(
                        context = this@AutomationExecutionService,
                        cancellation = lease.cancellation
                    ).executeActions(
                        ActionConverter().toActionList(editorActionsJson),
                        editorShortcutName?.trim().takeUnless { it.isNullOrBlank() } ?: "Shortcut"
                    )
                    publishResult(result)
                } else {
                    val automation = AppDatabase.getDatabase(this@AutomationExecutionService)
                        .automationDao()
                        .getAutomationById(automationId)
                    when {
                        automation == null -> publishFailure("Shortcut", "This shortcut no longer exists.")
                        !automation.isActive -> publishFailure(automation.name, "This shortcut is deactivated.")
                        else -> {
                            val actions = ActionConverter().toActionList(automation.actionsJson)
                            val result = ActionExecutorService(
                                context = this@AutomationExecutionService,
                                cancellation = lease.cancellation
                            ).executeActions(actions, automation.name)
                            publishResult(result)
                        }
                    }
                }
            } catch (_: Exception) {
                publishFailure("Shortcut", "This shortcut couldn't complete. Please try again.")
            } finally {
                // Hold the admission lease through foreground-service cleanup. A subsequent run
                // therefore cannot start while this service is still removing its notification.
                currentRunId = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(latestStartId)
                ShortcutRunCoordinator.release(runId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        currentRunId?.let(ShortcutRunCoordinator::cancel)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun publishResult(result: RunResult) {
        if (result.allSucceeded) {
            announce(result.userSummary())
            return
        }

        val incomplete = result.firstIncomplete
        val explanation = ReplayFailurePill.messageFor(result)
        ReplayFailurePill.show(AutomationAccessibilityService.instance, explanation)
        // Keep the actionable notification as a fallback when Accessibility Service has been
        // turned off and Android therefore cannot host a TYPE_ACCESSIBILITY_OVERLAY window.
        publishFailure(
            shortcutName = result.shortcutName,
            explanation = explanation,
            recoveryIntent = (incomplete as? StepResult.NeedsPermission)?.settingsIntent,
            presentPill = false
        )
    }

    private fun publishFailure(
        shortcutName: String,
        explanation: String,
        recoveryIntent: Intent? = null,
        presentPill: Boolean = true
    ) {
        if (presentPill) {
            ReplayFailurePill.show(
                AutomationAccessibilityService.instance,
                "$shortcutName couldn't be replayed — $explanation"
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            announce("$shortcutName: $explanation")
            return
        }
        val destination = recoveryIntent ?: Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            destination.filterHashCode(),
            destination.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, ATTENTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_bolt)
            .setContentTitle("$shortcutName couldn't finish")
            .setContentText(explanation)
            .setStyle(NotificationCompat.BigTextStyle().bigText(explanation))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun announce(message: String) {
        android.os.Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun foregroundNotification(runId: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_widget_bolt)
        .setContentTitle("Running shortcut")
        .setContentText("Your shortcut is running")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .addAction(
            R.drawable.ic_widget_bolt,
            "Stop",
            PendingIntent.getService(
                this,
                runId.hashCode(),
                Intent(this, AutomationExecutionService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_RUN_ID, runId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun stoppingNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_widget_bolt)
        .setContentTitle("Stopping shortcut")
        .setContentText("The shortcut will stop after its current step.")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        const val EXTRA_AUTOMATION_ID = "automation_id"
        const val EXTRA_RUN_ID = "shortcut_run_id"
        private const val EXTRA_EDITOR_ACTIONS_JSON = "editor_actions_json"
        private const val EXTRA_EDITOR_SHORTCUT_NAME = "editor_shortcut_name"
        private const val ACTION_STOP = "com.shortcuts.app.action.STOP_SHORTCUT_RUN"
        private const val NO_AUTOMATION_ID = -1
        private const val CHANNEL_ID = "shortcut_execution"
        private const val ATTENTION_CHANNEL_ID = "shortcut_attention"
        private const val FOREGROUND_NOTIFICATION_ID = 301
        private const val RESULT_NOTIFICATION_ID = 302

        /** Called exclusively by [ShortcutRunner] after its admission lease is acquired. */
        internal fun startAccepted(context: Context, automationId: Int, runId: String) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Shortcut execution", NotificationManager.IMPORTANCE_LOW)
                )
                notificationManager.createNotificationChannel(
                    NotificationChannel(ATTENTION_CHANNEL_ID, "Shortcuts needing attention", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationExecutionService::class.java)
                    .putExtra(EXTRA_AUTOMATION_ID, automationId)
                    .putExtra(EXTRA_RUN_ID, runId)
            )
        }

        /** Called exclusively by [ShortcutRunner] after an editor draft has been admitted. */
        internal fun startAcceptedEditorDraft(
            context: Context,
            shortcutName: String,
            actions: List<com.shortcuts.app.data.Action>,
            runId: String
        ) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Shortcut execution", NotificationManager.IMPORTANCE_LOW)
                )
                notificationManager.createNotificationChannel(
                    NotificationChannel(ATTENTION_CHANNEL_ID, "Shortcuts needing attention", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationExecutionService::class.java)
                    .putExtra(EXTRA_EDITOR_ACTIONS_JSON, ActionConverter().fromActionList(actions))
                    .putExtra(EXTRA_EDITOR_SHORTCUT_NAME, shortcutName)
                    .putExtra(EXTRA_RUN_ID, runId)
            )
        }
    }
}
