package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_music_prefs")

class UserPreferences(private val context: Context) {

    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val KEY_RESUME_LAST = booleanPreferencesKey("resume_last")
    private val KEY_PAUSE_ON_HEADSET_UNPLUG = booleanPreferencesKey("pause_on_headset_unplug")
    private val KEY_DUCK_AUDIO = booleanPreferencesKey("duck_audio")

    val themeMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_THEME] ?: "system"
    }

    val resumeLast: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_RESUME_LAST] ?: true
    }

    val pauseOnHeadsetUnplug: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_PAUSE_ON_HEADSET_UNPLUG] ?: true
    }

    suspend fun setThemeMode(theme: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_THEME] = theme
        }
    }

    suspend fun setResumeLast(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_RESUME_LAST] = enabled
        }
    }
}
