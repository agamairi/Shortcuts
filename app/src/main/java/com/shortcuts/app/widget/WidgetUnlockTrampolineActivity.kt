package com.shortcuts.app.widget

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.shortcuts.app.service.ShortcutRunRequest
import com.shortcuts.app.service.ShortcutRunSource
import com.shortcuts.app.service.ShortcutRunner
import com.shortcuts.app.service.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transparent trampoline activity that requests keyguard dismissal on Android lock screen
 * before running an irreversible shortcut.
 */
class WidgetUnlockTrampolineActivity : Activity() {

    companion object {
        const val EXTRA_AUTOMATION_ID = "com.shortcuts.app.widget.extra.AUTOMATION_ID"
        private const val TAG = "WidgetUnlockTrampoline"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val automationId = intent.getIntExtra(EXTRA_AUTOMATION_ID, -1)
        if (automationId <= 0) {
            finish()
            return
        }

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager == null || !keyguardManager.isKeyguardLocked) {
            runShortcutAndFinish(automationId)
            return
        }

        keyguardManager.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                super.onDismissSucceeded()
                Log.i(TAG, "Keyguard dismiss succeeded for automation $automationId")
                runShortcutAndFinish(automationId)
            }

            override fun onDismissCancelled() {
                super.onDismissCancelled()
                Log.i(TAG, "Keyguard dismiss cancelled by user")
                finish()
            }

            override fun onDismissError() {
                super.onDismissError()
                Log.e(TAG, "Keyguard dismiss error")
                finish()
            }
        })
    }

    private fun runShortcutAndFinish(automationId: Int) {
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val outcome = ShortcutRunner(appContext).run(
                    ShortcutRunRequest(
                        shortcutId = automationId,
                        source = ShortcutRunSource.WIDGET
                    )
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, outcome.userMessage(), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error executing shortcut $automationId from trampoline", t)
            } finally {
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }
}
