package com.shortcuts.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.shortcuts.app.util.NotificationThrottler
import com.shortcuts.app.util.RecorderNotificationPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground lifetime owner for an active recording session. */
class RecorderSessionService : Service() {
    private var serviceScope: CoroutineScope? = null
    private var throttler: NotificationThrottler? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AutomationRecorder.restoreSession(this)
        if (!AutomationRecorder.isRecording.value) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        
        val initialCount = AutomationRecorder.recordedActions.value.size
        startForeground(
            RecorderSessionNotifier.RECORDING_NOTIFICATION_ID, 
            RecorderSessionNotifier.recordingNotification(this, initialCount)
        )
        
        serviceScope?.cancel()
        val scope = CoroutineScope(Dispatchers.Main + Job())
        serviceScope = scope

        val updateThrottler = NotificationThrottler(
            windowMillis = 500L,
            scheduleTask = { delay, task ->
                val runnable = Runnable { task() }
                handler.postDelayed(runnable, delay)
                runnable
            },
            cancelTask = { task ->
                handler.removeCallbacks(task as Runnable)
            },
            onUpdate = { count ->
                val notification = RecorderSessionNotifier.recordingNotification(this, count)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(RecorderSessionNotifier.RECORDING_NOTIFICATION_ID, notification)
            }
        )
        throttler = updateThrottler

        scope.launch {
            AutomationRecorder.recordedActions
                .map { it.size }
                .distinctUntilChanged()
                .collect { count ->
                    updateThrottler.onCountChanged(count)
                }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope?.cancel()
        throttler?.forceUpdate(AutomationRecorder.recordedActions.value.size)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecorderSessionService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RecorderSessionService::class.java))
        }
    }
}

object RecorderSessionNotifier {
    private const val CHANNEL_ID = "recorder_channel"
    const val RECORDING_NOTIFICATION_ID = 1337
    private const val DISCONNECT_NOTIFICATION_ID = 1338

    fun recordingNotification(context: Context, count: Int = 0): android.app.Notification {
        val manager = notificationManager(context)
        val builder = android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Recording Shortcut")
            .setContentText(RecorderNotificationPresenter.formatContentText(count))
            .setOngoing(true)
            .addAction(
                android.app.Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(context, android.R.drawable.ic_media_pause),
                    "Stop",
                    PendingIntent.getBroadcast(
                        context,
                        0,
                        Intent(context, RecorderStopReceiver::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                ).build()
            )

        val apiLevel = android.os.Build.VERSION.SDK_INT
        val canPromote = if (apiLevel >= 36) manager.canPostPromotedNotifications() else false
        val presentation = RecorderNotificationPresenter.determinePresentation(apiLevel, canPromote)

        if (presentation == RecorderNotificationPresenter.Presentation.PROMOTED_CHIP) {
            // On Android 16+, use a clean small icon and pass the chip text.
            builder.setSmallIcon(android.R.drawable.ic_media_play)
            builder.setShortCriticalText(RecorderNotificationPresenter.formatChipText(count))
            
            val extras = android.os.Bundle()
            extras.putBoolean("android.requestPromotedOngoing", true)
            builder.addExtras(extras)
        } else {
            // Fallback: Use the generated bitmap badge.
            builder.setSmallIcon(RecorderNotificationPresenter.createSmallIcon(context, count).toIcon(context))
        }

        val notification = builder.build()
        // DEBUG: Caller can verify on device if this actually qualified.
        if (apiLevel >= 36) {
            android.util.Log.d("PromotedChip", "hasPromotableCharacteristics: " + notification.hasPromotableCharacteristics())
        }

        return notification
    }

    fun showAccessibilityDisconnected(context: Context) {
        notificationManager(context).notify(
            DISCONNECT_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Recording stopped")
                .setContentText("The accessibility service disconnected. Your captured steps are kept for review.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun notificationManager(context: Context): NotificationManager {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Shortcut Recorder", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return manager
    }
}
