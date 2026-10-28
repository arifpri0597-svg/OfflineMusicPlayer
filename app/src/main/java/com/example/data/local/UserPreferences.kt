package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.PerformanceMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_music_prefs")

class UserPreferences(private val context: Context) {

    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val KEY_RESUME_LAST = booleanPreferencesKey("resume_last")
    private val KEY_PAUSE_ON_HEADSET_UNPLUG = booleanPreferencesKey("pause_on_headset_unplug")
    private val KEY_PERFORMANCE_MODE = stringPreferencesKey("performance_mode")

    private val syncBootPrefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        context.applicationContext.getSharedPreferences("pulse_boot_prefs", Context.MODE_PRIVATE)
    }

    private val safePreferencesFlow: Flow<Preferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    val themeMode: Flow<String> = safePreferencesFlow.map { preferences ->
        preferences[KEY_THEME] ?: "system"
    }

    val resumeLast: Flow<Boolean> = safePreferencesFlow.map { preferences ->
        preferences[KEY_RESUME_LAST] ?: true
    }

    val pauseOnHeadsetUnplug: Flow<Boolean> = safePreferencesFlow.map { preferences ->
        preferences[KEY_PAUSE_ON_HEADSET_UNPLUG] ?: true
    }

    val performanceMode: Flow<PerformanceMode> = safePreferencesFlow.map { preferences ->
        val raw = preferences[KEY_PERFORMANCE_MODE]
            ?: syncBootPrefs.getString("performance_mode", null)
        PerformanceMode.fromStorageKey(raw)
    }

    fun getInitialPerformanceModeSync(): PerformanceMode {
        val raw = try {
            syncBootPrefs.getString("performance_mode", null)
        } catch (_: Throwable) {
            null
        }
        return PerformanceMode.fromStorageKey(raw)
    }

    suspend fun setThemeMode(theme: String) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_THEME] = theme
            }
        } catch (_: IOException) {
        }
    }

    suspend fun setResumeLast(enabled: Boolean) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_RESUME_LAST] = enabled
            }
        } catch (_: IOException) {
        }
    }

    suspend fun setPauseOnHeadsetUnplug(enabled: Boolean) {
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_PAUSE_ON_HEADSET_UNPLUG] = enabled
            }
        } catch (_: IOException) {
        }
    }

    suspend fun setPerformanceMode(mode: PerformanceMode) {
        try {
            syncBootPrefs.edit().putString("performance_mode", mode.storageKey).apply()
        } catch (_: Throwable) {
        }
        try {
            context.dataStore.edit { preferences ->
                preferences[KEY_PERFORMANCE_MODE] = mode.storageKey
            }
        } catch (_: IOException) {
        }
    }
}

