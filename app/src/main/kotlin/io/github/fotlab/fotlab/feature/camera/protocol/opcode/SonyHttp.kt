package io.github.fotlab.fotlab.feature.camera.protocol.opcode

/**
 * Sony JSON-RPC HTTP method constants — the ScalarWebAPI endpoint.
 *
 * Sony exposes two sub-path endpoints under the base URL:
 * - `camera`     → control, live view, events
 * - `avContent`  → content browse, list, get URLs
 *
 * Every method is called as JSON-RPC 2.0:
 * ```json
 * { "method": "...", "params": [ ... ], "id": N, "version": "1.0" }
 * ```
 *
 * The API is stateless — there is no open/close handshake on the HTTP side.
 *
 * Methods we know are NOT supported: delete, upload, remote capture, live view.
 * They will throw UnsupportedOperationException if wired in.
 */
object SonyHttp {
    // ── camera endpoint ──────────────────────────────────────────────────
    object Camera {
        const val GET_APPLICATION_INFO: String = "getApplicationInfo"
        const val GET_EVENT: String = "getEvent"
        const val GET_AVAILABLE_METHODS: String = "getAvailableMethod"
        const val START_REC_MODE: String = "startRecMode"
        const val STOP_REC_MODE: String = "stopRecMode"
    }

    // ── avContent endpoint ────────────────────────────────────────────────
    object AvContent {
        const val GET_SCHEME_LIST: String = "getSchemeList"
        const val GET_SOURCE_LIST: String = "getSourceList"
        const val GET_CONTENT_COUNT: String = "getContentCount"
        const val GET_CONTENT_LIST: String = "getContentList"
        const val GET_THUMBNAIL: String = "getThumbnail"
        const val GET_IMAGE: String = "getImage"
    }

    // ── Endpoint paths (appended to base URL) ────────────────────────────────
    const val ENDPOINT_CAMERA: String = "camera"
    const val ENDPOINT_AV_CONTENT: String = "avContent"

    // ── Known version strings ─────────────────────────────────────────────
    const val VERSION_DEFAULT: String = "1.0"
    const val VERSION_CONTENT_COUNT: String = "1.2"
    const val VERSION_CONTENT_LIST: String = "1.3"

    // ── Error codes known from Sony API docs ─────────────────────────────
    object ErrorCode {
        const val UNSUPPORTED: Int = 40003
        const val INVALID_PARAM: Int = 40004
        const val BUSY: Int = 40005
        const val NOT_FOUND: Int = 40006
        const val TIMEOUT: Int = 40007
    }
}
