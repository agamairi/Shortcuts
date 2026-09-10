package com.shortcuts.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives the "Mark a tap" action from the recording notification.
 *
 * When the user taps the notification action during an active recording, this receiver
 * arms the [TapMarkOverlay] so the next on-screen tap is captured as a recorded step.
 * The overlay is added via the live [AutomationAccessibilityService] instance —
 * if the service is not connected the arm call is a safe no-op.
 */
class MarkTapReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TapMarkOverlay.arm(AutomationAccessibilityService.instance)
    }
}
