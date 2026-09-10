package io.github.fotlab.fotlab.feature.library

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable user preference for the library display mode (`FOTLAB-UIXDES-000004` R9), stored in a
 * `DataStore` kept separate from the `fs_node` Room database. Only the column count is stored; the
 * mode is recovered through [LibraryLayoutMode.fromColumns], which defaults to a 3-column grid when
 * nothing has been stored yet.
 *
 * Owned by `LibraryCore`; the screen never touches this class directly.
 */
private val Context.dataStore by preferencesDataStore(name = "library_prefs")

private val KEY_LAYOUT_COLUMNS = intPreferencesKey("library_layout_columns")

class LibraryLayoutPreference(context: Context) {

    private val store = context.applicationContext.dataStore

    /** Current display mode, emitting the persisted value or [LibraryLayoutMode.DEFAULT]. */
    val mode: Flow<LibraryLayoutMode> = store.data.map { prefs ->
        LibraryLayoutMode.fromColumns(prefs[KEY_LAYOUT_COLUMNS] ?: LibraryLayoutMode.DEFAULT.columns)
    }

    /** Persist [mode]; called after every cycle (`FOTLAB-UIXDES-000004` R9). */
    suspend fun setMode(mode: LibraryLayoutMode) {
        store.edit { prefs -> prefs[KEY_LAYOUT_COLUMNS] = mode.columns }
    }
}
