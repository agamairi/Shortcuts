package com.shortcuts.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.shortcuts.app.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_preferences")

class ThemePreferences private constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.themeDataStore)

    companion object {
        private val MODE_KEY = stringPreferencesKey("theme_mode")

        /** Visible for JVM tests, which supply a real in-memory/on-disk preferences store. */
        fun forDataStore(dataStore: DataStore<Preferences>): ThemePreferences = ThemePreferences(dataStore)
    }

    val themeModeFlow: Flow<ThemeMode> = dataStore.data
        .map { preferences -> ThemeMode.fromKey(preferences[MODE_KEY]) }

    suspend fun updateThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[MODE_KEY] = mode.key
        }
    }
}
