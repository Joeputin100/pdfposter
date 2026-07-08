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
        // RC54: persist the file URI of the imported source image so the
        // app can resurrect it after process death (Android killing the
        // backgrounded process for memory). updateImage() copies the
        // picked content URI's bytes to an app-private file, then writes
        // that local file:// URI here so loadSettings can restore it.
        val SELECTED_IMAGE_URI = stringPreferencesKey("selected_image_uri")
        // rc84: Play UGC policy — locally-persisted community block list.
        // Each entry is "uid\ndisplayName" (uids can't contain '\n'); see
        // MainViewModel.blockedUsers for the in-memory map + filtering.
        val COMMUNITY_BLOCKED_USERS = stringSetPreferencesKey("community_blocked_users")
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
