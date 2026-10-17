package com.shortcuts.app.automation.android

import android.content.Context
import com.shortcuts.app.automation.core.text.InstallTextTokenizer
import com.shortcuts.app.automation.model.TextTokenizer
import java.util.UUID

/**
 * The one per-install text tokenizer. Capture writes window-title tokens with it and replay
 * compares observed titles with it, so both sides must load the same salt from the same place.
 */
object InstallTokens {
    private const val PREFS_NAME = "v2_capture_bridge"
    private const val PREF_SALT = "tokenizer_salt"

    @Volatile
    private var cachedSalt: ByteArray? = null

    fun tokenizer(context: Context): TextTokenizer {
        val appContext = context.applicationContext ?: context
        return InstallTextTokenizer { salt(appContext) }
    }

    @Synchronized
    private fun salt(context: Context): ByteArray {
        cachedSalt?.let { return it }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(PREF_SALT, null)
        val value = if (stored.isNullOrBlank()) {
            UUID.randomUUID().toString().also { prefs.edit().putString(PREF_SALT, it).apply() }
        } else {
            stored
        }
        return value.toByteArray(Charsets.UTF_8).also { cachedSalt = it }
    }
}
