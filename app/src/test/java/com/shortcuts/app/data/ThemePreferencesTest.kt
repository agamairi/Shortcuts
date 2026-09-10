package com.shortcuts.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shortcuts.app.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemePreferencesTest {

    @Test
    fun `theme mode defaults to system and persists each selected mode`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val preferences = ThemePreferences.forDataStore(testDataStore(scope))

            assertEquals(ThemeMode.SYSTEM, preferences.themeModeFlow.first())
            ThemeMode.entries.forEach { mode ->
                preferences.updateThemeMode(mode)
                assertEquals(mode, preferences.themeModeFlow.first())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `legacy accent key is ignored while theme mode remains readable`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = testDataStore(scope)
            store.edit { it[stringPreferencesKey("theme_accent")] = "PURPLE" }
            val preferences = ThemePreferences.forDataStore(store)

            assertEquals(ThemeMode.SYSTEM, preferences.themeModeFlow.first())
            preferences.updateThemeMode(ThemeMode.DARK)
            assertEquals(ThemeMode.DARK, preferences.themeModeFlow.first())
        } finally {
            scope.cancel()
        }
    }

    private fun testDataStore(scope: CoroutineScope) = PreferenceDataStoreFactory.create(scope = scope) {
        val directory = File(System.getProperty("java.io.tmpdir"), "shortcuts-theme-test-${System.nanoTime()}")
        directory.mkdirs()
        File(directory, "preferences.preferences_pb")
    }
}
