package io.github.fotlab.fotlab.media

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Studio/media user preferences (FOTLAB-STUDIO-000001, R8 format sniffing).
 *
 * Kept separate from the library display prefs (`LibraryLayoutPreference`) because these are media-layer
 * settings. The settings UI is not built yet (see Open Question Q6), so for now the only value is the
 * sniff-timeout default — and callers that do not yet read it simply use [DEFAULT_SNIFF_TIMEOUT_MS].
 * The [Flow] + setter exist so a future settings screen can read and override it without touching the
 * sniffer code.
 */
private val Context.dataStore by preferencesDataStore(name = "studio_prefs")

/** Default sniff-timeout, in milliseconds. Configurable via [MediaPreference.sniffTimeoutMs]. */
const val DEFAULT_SNIFF_TIMEOUT_MS = 5_000L

private val KEY_SNIFF_TIMEOUT_MS = longPreferencesKey("studio_sniff_timeout_ms")

class MediaPreference(context: Context) {

    private val store = context.applicationContext.dataStore

    /**
     * Bounded time for all sniffers to settle (R8). Defaults to [DEFAULT_SNIFF_TIMEOUT_MS] (5 s) until
     * the settings UI overrides it.
     */
    val sniffTimeoutMs: Flow<Long> = store.data.map { prefs ->
        prefs[KEY_SNIFF_TIMEOUT_MS] ?: DEFAULT_SNIFF_TIMEOUT_MS
    }

    /** Persist the sniff timeout (future settings UI; not yet wired). */
    suspend fun setSniffTimeoutMs(value: Long) {
        store.edit { prefs -> prefs[KEY_SNIFF_TIMEOUT_MS] = value }
    }
}
