package io.github.fotlab.fotlab.feature.library

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
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

/**
 * Whether the full-screen viewer's detail panel is shown by default. `false` means the panel is
 * hidden when the dialog opens and the user opts in by tapping the info icon (`FOTLAB-UIXDES`,
 * viewer layout). Persisted so the last choice is remembered across launches.
 */
private val KEY_VIEWER_SHOW_INFO = booleanPreferencesKey("library_viewer_show_info")

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

    /** Whether the viewer detail panel is shown by default; `false` (hidden) when unset. */
    val showViewerInfo: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_VIEWER_SHOW_INFO] ?: false
    }

    /** Persist the viewer detail-panel visibility so the next open remembers it. */
    suspend fun setShowViewerInfo(show: Boolean) {
        store.edit { prefs -> prefs[KEY_VIEWER_SHOW_INFO] = show }
    }
}
