package com.shortcuts.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ModelDownloaderService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var downloader: ModelDownloader
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "model_downloader_channel"
        const val CHANNEL_NAME = "AI Model Downloader"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.shortcuts.app.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.shortcuts.app.action.CANCEL_DOWNLOAD"

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

        fun updateDownloadState(state: DownloadState) {
            _downloadState.value = state
        }

        fun startDownload(context: Context) {
            val intent = Intent(context, ModelDownloaderService::class.java).apply {
                action = ACTION_START_DOWNLOAD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, ModelDownloaderService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
            }
            context.startService(intent)
        }

        fun deleteModel(context: Context): Boolean {
            val file = File(context.filesDir, "functiongemma.litertlm")
            val deleted = if (file.exists()) file.delete() else false
            updateDownloadState(DownloadState.Idle)
            return deleted
        }
    }

    override fun onCreate() {
        super.onCreate()
        downloader = ModelDownloader(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_DOWNLOAD -> {
                cancelCurrentDownload()
            }
            ACTION_START_DOWNLOAD, null -> {
                startDownloadTask()
            }
        }
        return START_NOT_STICKY
    }

    fun startDownloadTask() {
        if (_downloadState.value is DownloadState.Downloading) {
            return
        }

        val initialNotification = buildNotification(0, "Starting download...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        _downloadState.value = DownloadState.Downloading(0)

        serviceScope.launch {
            val result = downloader.downloadModel { progress ->
                _downloadState.value = DownloadState.Downloading(progress)
                val notification = buildNotification(progress, "Downloading... $progress%")
                notificationManager.notify(NOTIFICATION_ID, notification)
            }

            result.fold(
                onSuccess = { file ->
                    _downloadState.value = DownloadState.Completed(file)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                },
                onFailure = { exception ->
                    val errorMsg = exception.localizedMessage ?: "Download failed"
                    _downloadState.value = DownloadState.Failed(errorMsg)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            )
        }
    }

    fun cancelCurrentDownload() {
        _downloadState.value = DownloadState.Failed("Cancelled")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for AI model download progress"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(progress: Int, statusText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FunctionGemma Model Download")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
