package io.github.fotlab.fotlab.feature.gallery

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.fotlab.fotlab.feature.library.LibraryLayoutMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable user preference for the gallery display mode.
 * Independent from the library's DataStore — the gallery has its own file so the two features
 * remember their last-used layout separately.
 */
private val Context.galleryDataStore by preferencesDataStore(name = "gallery_prefs")

private val KEY_GALLERY_LAYOUT_COLUMNS = intPreferencesKey("gallery_layout_columns")
private val KEY_GALLERY_VIEWER_SHOW_INFO = booleanPreferencesKey("gallery_viewer_show_info")

class GalleryLayoutPreference(context: Context) {

    private val store = context.applicationContext.galleryDataStore

    val mode: Flow<LibraryLayoutMode> = store.data.map { prefs ->
        LibraryLayoutMode.fromColumns(prefs[KEY_GALLERY_LAYOUT_COLUMNS] ?: LibraryLayoutMode.DEFAULT.columns)
    }

    suspend fun setMode(mode: LibraryLayoutMode) {
        store.edit { prefs -> prefs[KEY_GALLERY_LAYOUT_COLUMNS] = mode.columns }
    }

    val showViewerInfo: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_GALLERY_VIEWER_SHOW_INFO] ?: false
    }

    suspend fun setShowViewerInfo(show: Boolean) {
        store.edit { prefs -> prefs[KEY_GALLERY_VIEWER_SHOW_INFO] = show }
    }
}
