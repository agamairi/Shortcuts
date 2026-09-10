package com.shortcuts.app.service

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.shortcuts.app.data.Action

/** Synchronous preferences writes keep every captured event recoverable after process death. */
class RecorderSessionStorePreferences(context: Context) : RecorderSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    override fun read(): PersistedRecorderSession? {
        if (!preferences.contains(IS_RECORDING_KEY)) return null
        val actionsJson = preferences.getString(ACTIONS_KEY, "[]") ?: "[]"
        val actionsType = object : TypeToken<List<Action>>() {}.type
        val actions = runCatching { gson.fromJson<List<Action>>(actionsJson, actionsType) }.getOrNull() ?: emptyList()
        return PersistedRecorderSession(
            isRecording = preferences.getBoolean(IS_RECORDING_KEY, false),
            recordedActions = actions
        )
    }

    override fun write(session: PersistedRecorderSession) {
        preferences.edit()
            .putBoolean(IS_RECORDING_KEY, session.isRecording)
            .putString(ACTIONS_KEY, gson.toJson(session.recordedActions))
            .commit()
    }

    override fun clear() {
        preferences.edit().clear().commit()
    }

    private companion object {
        const val FILE_NAME = "recorder_session"
        const val IS_RECORDING_KEY = "is_recording"
        const val ACTIONS_KEY = "actions"
    }
}
