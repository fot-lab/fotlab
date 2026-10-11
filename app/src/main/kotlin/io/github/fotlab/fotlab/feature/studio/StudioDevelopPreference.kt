package io.github.fotlab.fotlab.feature.studio

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable user preference for the Studio **develop pipeline** — currently which demosaic
 * algorithm the pipeline uses (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`).
 *
 * Its own `DataStore` file: it is an editing-pipeline setting, not a media-layer one
 * ([io.github.fotlab.fotlab.media.MediaPreference] already owns `studio_prefs` — two
 * `preferencesDataStore` delegates must never name the same file, the second one throws), and it
 * is not a library display preference either.
 *
 * Owned by [StudioEngine]; the screen reads and writes it only through that engine.
 */
private val Context.studioDevelopDataStore by preferencesDataStore(name = "studio_develop_prefs")

/**
 * The last demosaic the user picked, as the catalogue's own id (`rawler:default`,
 * `rawler:superpixel`, `rawtrp:vng4`, …) — never the enum name and never a translated label, so a
 * rename or a new locale cannot orphan the stored value.
 *
 * Storing the id rather than a boolean is what lets the pick *be* the algorithm: superpixel used
 * to be a separate `downsample` switch that overrode the pick, so the two knobs could contradict
 * each other and "what will this render as" was unanswerable from the choice alone.
 *
 * Absent (not defaulted) when nothing has been stored — [StudioEngine] resolves it, so the default
 * lives in one place instead of being duplicated as a literal here.
 */
private val KEY_DEMOSAIC_ID = stringPreferencesKey("studio_develop_demosaic_id")

/**
 * The second-layer (demosaiced camera-space) cache switch. Absent (not defaulted) when the user
 * has never touched it — [StudioEngine] owns the ON default, so it lives in one place.
 */
private val KEY_DEMOSAICED_CACHE = booleanPreferencesKey("studio_demosaiced_cache_enabled")

class StudioDevelopPreference(context: Context) {

    private val store = context.applicationContext.studioDevelopDataStore

    /** The persisted candidate id, or `null` when the user has never picked one. */
    val demosaicId: Flow<String?> = store.data.map { prefs -> prefs[KEY_DEMOSAIC_ID] }

    /** Persist [id] so the next launch starts from the same choice. */
    suspend fun setDemosaicId(id: String) {
        store.edit { prefs -> prefs[KEY_DEMOSAIC_ID] = id }
    }

    /**
     * The persisted demosaiced-cache switch, or `null` when the user has never toggled it. ON is
     * the product default; OFF trades grade-only edit speed for a substantially lower resident
     * memory ceiling (the camera-space buffer of a 50 MP RAW is ~0.5 GB).
     */
    val demosaicedCacheEnabled: Flow<Boolean?> = store.data.map { prefs -> prefs[KEY_DEMOSAICED_CACHE] }

    /** Persist the demosaiced-cache switch so the next launch starts from the same choice. */
    suspend fun setDemosaicedCacheEnabled(enabled: Boolean) {
        store.edit { prefs -> prefs[KEY_DEMOSAICED_CACHE] = enabled }
    }
}
