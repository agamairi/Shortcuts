package com.shortcuts.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RecorderStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutomationRecorder.stopRecording(context)
    }
}
