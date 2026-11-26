package io.github.fotlab.fotlab.feature.camera

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Durable user preferences for the camera module.
 *
 * Its own `DataStore` file (`camera_prefs`): the camera feature has no other store, and
 * two `preferencesDataStore` delegates must never name the same file — the second one
 * throws at first access.
 *
 * A value that was never stored is reported as `null`, so the default lives in exactly
 * one place ([CameraSettings]) and an unset preference cannot drift into a second copy
 * of the literal.
 */
private val Context.cameraPrefs by preferencesDataStore(name = "camera_prefs")

private val KEY_MDNS_TIMEOUT_SECONDS = intPreferencesKey("camera_mdns_timeout_seconds")
private val KEY_KEEPALIVE_INTERVAL_SECONDS = intPreferencesKey("camera_keepalive_interval_seconds")

class CameraPreference(context: Context) {

    private val store = context.applicationContext.cameraPrefs

    /** The stored mDNS timeout in seconds, or `null` when the user never set one. */
    val mdnsTimeoutSeconds: Flow<Int?> = store.data.map { prefs ->
        prefs[KEY_MDNS_TIMEOUT_SECONDS]
    }

    /** The stored keepalive interval in seconds, or `null` when the user never set one. */
    val keepaliveIntervalSeconds: Flow<Int?> = store.data.map { prefs ->
        prefs[KEY_KEEPALIVE_INTERVAL_SECONDS]
    }

    /** Persist the mDNS timeout; the next discovery uses it. */
    suspend fun setMdnsTimeoutSeconds(seconds: Int) {
        store.edit { prefs ->
            prefs[KEY_MDNS_TIMEOUT_SECONDS] = CameraSettings.clampMdnsTimeout(seconds)
        }
    }

    /** Persist the keepalive interval; a live link picks it up right away. */
    suspend fun setKeepaliveIntervalSeconds(seconds: Int) {
        store.edit { prefs ->
            prefs[KEY_KEEPALIVE_INTERVAL_SECONDS] = CameraSettings.clampKeepaliveInterval(seconds)
        }
    }
}
