package io.github.fotlab.fotlab.feature.camera

/**
 * The camera module's user-tunable values, and the single source of their defaults.
 *
 * Both knobs live here rather than as literals at their use sites, so the drawer, the
 * discovery code and the keepalive loop cannot drift apart on "what is the default".
 *
 * The defaults are the values a fresh install runs with: a 10 s mDNS window and a 20 s
 * keepalive probe. Nothing is stored until the user changes a value, so an install that
 * has never touched the drawer behaves exactly like one whose stored value equals the
 * default.
 */
data class CameraSettings(
    /** How long the mDNS probe waits for network cameras to answer, in seconds. */
    val mdnsTimeoutSeconds: Int = DEFAULT_MDNS_TIMEOUT_SECONDS,
    /** How often a connected network camera is probed to keep the link alive, in seconds. */
    val keepaliveIntervalSeconds: Int = DEFAULT_KEEPALIVE_INTERVAL_SECONDS,
) {
    companion object {
        const val DEFAULT_MDNS_TIMEOUT_SECONDS: Int = 10
        const val DEFAULT_KEEPALIVE_INTERVAL_SECONDS: Int = 20

        const val MIN_MDNS_TIMEOUT_SECONDS: Int = 1
        const val MAX_MDNS_TIMEOUT_SECONDS: Int = 60

        const val MIN_KEEPALIVE_INTERVAL_SECONDS: Int = 5
        const val MAX_KEEPALIVE_INTERVAL_SECONDS: Int = 600

        /** Clamp a user-supplied mDNS timeout into the supported range. */
        fun clampMdnsTimeout(seconds: Int): Int =
            seconds.coerceIn(MIN_MDNS_TIMEOUT_SECONDS, MAX_MDNS_TIMEOUT_SECONDS)

        /** Clamp a user-supplied keepalive interval into the supported range. */
        fun clampKeepaliveInterval(seconds: Int): Int =
            seconds.coerceIn(MIN_KEEPALIVE_INTERVAL_SECONDS, MAX_KEEPALIVE_INTERVAL_SECONDS)
    }
}
