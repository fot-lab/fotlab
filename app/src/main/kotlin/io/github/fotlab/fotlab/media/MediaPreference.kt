package io.github.fotlab.fotlab.media

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Studio/media user preferences (FOTLAB-STUDIO-000001, R8 format sniffing).
 *
 * Kept separate from the library display prefs (`LibraryLayoutPreference`) because these are media-layer
 * settings. The settings UI is not built yet (see Open Question Q6), so for now the only value is the
 * sniff-timeout: `StudioEngine` reads it per open and falls back to [DEFAULT_SNIFF_TIMEOUT_MS] if the
 * store cannot be read. The [Flow] + setter exist so a settings screen can override it later without
 * touching the sniffer code.
 */
private val Context.dataStore by preferencesDataStore(name = "studio_prefs")

/** Default sniff-timeout, in milliseconds. Configurable via [MediaPreference.sniffTimeoutMs]. */
const val DEFAULT_SNIFF_TIMEOUT_MS = 5_000L

private val KEY_SNIFF_TIMEOUT_MS = longPreferencesKey("studio_sniff_timeout_ms")

/**
 * Per-SAF-call "last opened document" URIs. Android's Storage Access Framework remembers only ONE
 * global last directory for the whole app, so the LUT picker and the PNG export dialog fight over
 * the same starting location. We persist the last *document* URI each call landed on (under
 * `studio_prefs`) and hand it back to the system picker as [android.provider.DocumentsContract.EXTRA_INITIAL_URI],
 * which makes the picker open in that document's parent folder — giving LUT and export their own
 * independent "recent directory". Persisted alongside the document URI is a persistable URI
 * permission (see [io.github.fotlab.fotlab.feature.studio.persistUriPermission]) so the hint
 * survives process death.
 */
private val KEY_LUT_LAST_URI = stringPreferencesKey("studio_lut_last_uri")
private val KEY_EXPORT_LAST_URI = stringPreferencesKey("studio_export_last_uri")
private val KEY_IMPORT_LAST_URI = stringPreferencesKey("studio_import_last_uri")

class MediaPreference(context: Context) {

    private val store = context.applicationContext.dataStore

    /**
     * Bounded time for all sniffers to settle (R8). Defaults to [DEFAULT_SNIFF_TIMEOUT_MS] (5 s) until
     * the settings UI overrides it.
     */
    val sniffTimeoutMs: Flow<Long> = store.data.map { prefs ->
        prefs[KEY_SNIFF_TIMEOUT_MS] ?: DEFAULT_SNIFF_TIMEOUT_MS
    }

    /** Persist the sniff timeout (no settings UI calls this yet). */
    suspend fun setSniffTimeoutMs(value: Long) {
        store.edit { prefs -> prefs[KEY_SNIFF_TIMEOUT_MS] = value }
    }

    // -- Per-SAF-call last document URIs (each call remembers its own directory) ----------

    /** Last document URI picked for a LUT, or null if none yet. */
    val lastLutUri: Flow<String?> = store.data.map { it[KEY_LUT_LAST_URI] }

    /** Last document URI the PNG export dialog wrote to, or null if none yet. */
    val lastExportUri: Flow<String?> = store.data.map { it[KEY_EXPORT_LAST_URI] }

    /** Last document URI imported into the canvas, or null if none yet. */
    val lastImportUri: Flow<String?> = store.data.map { it[KEY_IMPORT_LAST_URI] }

    /** Remember the LUT document URI (or clear with null) for next time's initial folder. */
    suspend fun setLastLutUri(value: String?) {
        store.edit { prefs ->
            if (value != null) prefs[KEY_LUT_LAST_URI] = value else prefs.remove(KEY_LUT_LAST_URI)
        }
    }

    /** Remember the PNG export document URI (or clear with null) for next time's initial folder. */
    suspend fun setLastExportUri(value: String?) {
        store.edit { prefs ->
            if (value != null) prefs[KEY_EXPORT_LAST_URI] = value else prefs.remove(KEY_EXPORT_LAST_URI)
        }
    }

    /** Remember the imported document URI (or clear with null) for next time's initial folder. */
    suspend fun setLastImportUri(value: String?) {
        store.edit { prefs ->
            if (value != null) prefs[KEY_IMPORT_LAST_URI] = value else prefs.remove(KEY_IMPORT_LAST_URI)
        }
    }
}
