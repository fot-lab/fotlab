package io.github.fotlab.fotlab.feature.studio

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable user preference for the Studio **develop pipeline** — currently the quarter-resolution
 * downsampling switch in the Studio drawer (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`).
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
 * Whether the develop pipeline should run its demosaic stage at quarter resolution (rawler's
 * superpixel debayer) instead of full resolution.
 *
 * A *preference*, not a per-render parameter: `StudioEngine` keeps the live value and passes it
 * into every develop call, so flipping the switch re-renders nothing by itself — the next develop
 * (a parameter change, a grade change or opening another file) picks it up.
 */
private val KEY_DOWNSAMPLE = booleanPreferencesKey("studio_develop_downsample")

class StudioDevelopPreference(context: Context) {

    private val store = context.applicationContext.studioDevelopDataStore

    /** The persisted switch; `true` (quarter resolution) when nothing has been stored yet. */
    val downsample: Flow<Boolean> = store.data.map { prefs -> prefs[KEY_DOWNSAMPLE] ?: true }

    /** Persist the switch so the next launch starts from the same choice. */
    suspend fun setDownsample(enabled: Boolean) {
        store.edit { prefs -> prefs[KEY_DOWNSAMPLE] = enabled }
    }
}
