package com.shortcuts.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Builds the launcher callback that binds a pinned widget to its selected shortcut. */
object ShortcutWidgetPinRequest {
    const val ACTION_WIDGET_PINNED = "com.shortcuts.app.action.SHORTCUT_WIDGET_PINNED"
    const val EXTRA_AUTOMATION_ID = "com.shortcuts.app.extra.AUTOMATION_ID"

    /** Kept pure so the API-specific mutability contract is unit-testable. */
    fun successCallbackFlags(sdkInt: Int): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (sdkInt >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

    fun createSuccessCallback(context: Context, automationId: Int): PendingIntent {
        val callbackIntent = Intent(context, ShortcutWidgetPinReceiver::class.java)
            .setAction(ACTION_WIDGET_PINNED)
            .putExtra(EXTRA_AUTOMATION_ID, automationId)
        return PendingIntent.getBroadcast(
            context,
            automationId,
            callbackIntent,
            successCallbackFlags(Build.VERSION.SDK_INT)
        )
    }
}
