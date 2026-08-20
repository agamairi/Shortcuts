package com.shortcuts.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
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

/** Keeps widget-triggered shortcut chains alive long enough to finish and reports their outcome. */
class AutomationExecutionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val automationId = intent?.getIntExtra(EXTRA_AUTOMATION_ID, NO_AUTOMATION_ID) ?: NO_AUTOMATION_ID
        if (automationId == NO_AUTOMATION_ID) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(FOREGROUND_NOTIFICATION_ID, foregroundNotification())
        serviceScope.launch {
            val automation = AppDatabase.getDatabase(this@AutomationExecutionService)
                .automationDao()
                .getAutomationById(automationId)
            if (automation == null) {
                publishFailure("Shortcut", "This shortcut no longer exists.")
            } else {
                val actions = ActionConverter().toActionList(automation.actionsJson)
                val result = ActionExecutorService(this@AutomationExecutionService)
                    .executeActions(actions, automation.name)
                publishResult(result)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun publishResult(result: RunResult) {
        if (result.allSucceeded) {
            Toast.makeText(this, "Ran '${result.shortcutName}'", Toast.LENGTH_SHORT).show()
            return
        }
        val stepIndex = result.steps.indexOfFirst { it !is StepResult.Success } + 1
        val explanation = when (val incomplete = result.firstIncomplete) {
            is StepResult.Failed -> incomplete.userMessage
            is StepResult.NeedsPermission -> "Permission required: ${incomplete.permission}. Tap to open settings."
            is StepResult.Skipped -> incomplete.why
            else -> "This shortcut couldn't be completed."
        }
        publishFailure(result.shortcutName, "Step $stepIndex: $explanation")
    }

    private fun publishFailure(shortcutName: String, explanation: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "$shortcutName: $explanation", Toast.LENGTH_LONG).show()
            return
        }
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_bolt)
            .setContentTitle("$shortcutName couldn't finish")
            .setContentText(explanation)
            .setStyle(NotificationCompat.BigTextStyle().bigText(explanation))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun foregroundNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_widget_bolt)
        .setContentTitle("Running shortcut")
        .setContentText("Your shortcut is running")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        const val EXTRA_AUTOMATION_ID = "automation_id"
        private const val NO_AUTOMATION_ID = -1
        private const val CHANNEL_ID = "shortcut_execution"
        private const val FOREGROUND_NOTIFICATION_ID = 301
        private const val RESULT_NOTIFICATION_ID = 302

        fun start(context: android.content.Context, automationId: Int) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Shortcut execution", NotificationManager.IMPORTANCE_LOW)
                )
            }
            ContextCompat.startForegroundService(
                context,
                Intent(context, AutomationExecutionService::class.java)
                    .putExtra(EXTRA_AUTOMATION_ID, automationId)
            )
        }
    }
}
