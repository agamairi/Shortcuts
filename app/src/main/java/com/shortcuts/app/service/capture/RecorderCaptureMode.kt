package com.shortcuts.app.service.capture

import android.content.Context

enum class RecorderCaptureMode { OVERLAY, PASSIVE }

/** Kept separate from the persisted session so changing capture strategy never changes a draft. */
object RecorderCaptureModePreferences {
    private const val FILE = "recorder_capture"
    private const val KEY = "mode"

    fun mode(context: Context): RecorderCaptureMode = when (
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
    ) {
        RecorderCaptureMode.PASSIVE.name -> RecorderCaptureMode.PASSIVE
        else -> RecorderCaptureMode.OVERLAY
    }

    fun setMode(context: Context, mode: RecorderCaptureMode) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }
}
