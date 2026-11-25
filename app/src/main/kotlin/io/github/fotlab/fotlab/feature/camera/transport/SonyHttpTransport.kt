package io.github.fotlab.fotlab.feature.camera.transport

import io.github.fotlab.fotlab.feature.camera.model.CameraLinkException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/**
 * JSON-RPC 2.0 HTTP transport for Sony cameras that expose the
 * `urn:schemas-sony-com:service:ScalarWebAPI:1` SSDP service.
 *
 * The camera publishes two sub-path endpoints off the [baseUrl]:
 * - `camera` — control, live view, event polling
 * - `avContent` — content browsing and transfer
 *
 * Every [call] posts a JSON-RPC request body and parses the response. Errors
 * are surfaced as [CameraLinkException] on transport-level failures and as
 * [SonyHttpException] on protocol-level errors.
 */
class SonyHttpTransport(
    private val baseUrl: String,
) {

    /** Human-readable label for logs and errors. */
    val label: String get() = "SonyHTTP $baseUrl"

    /** Hook fired exactly once when the link is torn down beyond recovery. */
    var onLinkBroken: (() -> Unit)? = null

    private val idCounter = AtomicInteger(0)
    @Volatile
    private var closed: Boolean = false

    private val cameraUrl: String get() = "$baseUrl/camera"
    private val avContentUrl: String get() = "$baseUrl/avContent"

    /** Short link: the camera service endpoint for control, events and live view. */
    suspend fun callCamera(method: String, params: List<Any?> = emptyList(), version: String = "1.0"): JSONObject =
        call(cameraUrl, method, params, version)

    /** Content service endpoint: browse, list, get URLs for media files. */
    suspend fun callAvContent(method: String, params: List<Any?> = emptyList(), version: String = "1.0"): JSONObject =
        call(avContentUrl, method, params, version)

    /** Close the transport and release any underlying handle. */
    suspend fun close() {
        closed = true
        onLinkBroken = null
    }

    private suspend fun call(
        endpoint: String,
        method: String,
        params: List<Any?>,
        version: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (closed) throw CameraLinkException("Sony HTTP transport is closed")
        val body = JSONObject()
            .put("method", method)
            .put("params", JSONArray(params))
            .put("id", idCounter.incrementAndGet())
            .put("version", version)
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code < 200 || code >= 300) {
                val errorBody = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrDefault("")
                throw CameraLinkException(
                    "Sony HTTP call $method failed: HTTP $code $errorBody",
                )
            }
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val response = JSONObject(responseText)
            parseError(response)?.let { throw it }
            response
        } catch (error: IOException) {
            markBroken()
            throw CameraLinkException("Sony HTTP call $method failed: ${error.message}", error)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseError(response: JSONObject): SonyHttpException? {
        val error = response.optJSONArray("error") ?: return null
        if (error.length() < 2) return null
        val code = error.optInt(0, 0)
        val message = error.optString(1)
        return SonyHttpException(code, message)
    }

    private fun markBroken() {
        if (closed) return
        closed = true
        val callback = onLinkBroken
        onLinkBroken = null
        callback?.invoke()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}

/** Protocol-level error from the Sony JSON-RPC API (camera returned an "error" array). */
class SonyHttpException(val code: Int, override val message: String) : RuntimeException("Sony API error $code: $message")
