package io.github.fotlab.fotlab.feature.gallery

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable user preference for the gallery display mode (`FOTLAB-UIXDES-000004` R9), stored in a
 * `DataStore` kept separate from the `fs_node` Room database. Only the column count is stored; the
 * mode is recovered through [GalleryLayoutMode.fromColumns], which defaults to a 3-column grid when
 * nothing has been stored yet.
 *
 * Owned by `GalleryCore`; the screen never touches this class directly.
 */
private val Context.dataStore by preferencesDataStore(name = "gallery_prefs")

private val KEY_LAYOUT_COLUMNS = intPreferencesKey("gallery_layout_columns")

class GalleryLayoutPreference(context: Context) {

    private val store = context.applicationContext.dataStore

    /** Current display mode, emitting the persisted value or [GalleryLayoutMode.DEFAULT]. */
    val mode: Flow<GalleryLayoutMode> = store.data.map { prefs ->
        GalleryLayoutMode.fromColumns(prefs[KEY_LAYOUT_COLUMNS] ?: GalleryLayoutMode.DEFAULT.columns)
    }

    /** Persist [mode]; called after every cycle (`FOTLAB-UIXDES-000004` R9). */
    suspend fun setMode(mode: GalleryLayoutMode) {
        store.edit { prefs -> prefs[KEY_LAYOUT_COLUMNS] = mode.columns }
    }
}
