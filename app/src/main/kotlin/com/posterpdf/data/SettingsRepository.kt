package com.posterpdf.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    companion object {
        val POSTER_WIDTH = stringPreferencesKey("poster_width")
        val POSTER_HEIGHT = stringPreferencesKey("poster_height")
        val PAPER_SIZE = stringPreferencesKey("paper_size")
        val MARGIN = stringPreferencesKey("margin")
        val OVERLAP = stringPreferencesKey("overlap")
        val SHOW_OUTLINES = booleanPreferencesKey("show_outlines")
        val OUTLINE_STYLE = stringPreferencesKey("outline_style")
        val OUTLINE_THICKNESS = stringPreferencesKey("outline_thickness")
        val OUTLINE_SELECTION = stringPreferencesKey("outline_selection")
        val LAST_COUNTED_HASH = stringPreferencesKey("last_counted_hash")
        val LABEL_PANES = booleanPreferencesKey("label_panes")
        val INCLUDE_INSTRUCTIONS = booleanPreferencesKey("include_instructions")
        val UNITS = stringPreferencesKey("units")
        val IS_FIRST_RUN = booleanPreferencesKey("is_first_run")
        val DEBUG_LOGGING_ENABLED = booleanPreferencesKey("debug_logging_enabled")
        val POSTERS_MADE_COUNT = intPreferencesKey("posters_made_count")
    }

    val settingsFlow: Flow<Map<Preferences.Key<*>, Any>> = context.dataStore.data
        .map { preferences ->
            preferences.asMap()
        }

    suspend fun <T> saveSetting(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { preferences ->
            preferences[key] = value
        }
    }

    suspend fun resetSettings() {
        context.dataStore.edit { it.clear() }
    }
}
