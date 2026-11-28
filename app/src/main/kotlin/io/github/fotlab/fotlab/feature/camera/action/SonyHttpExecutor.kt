package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.CameraLinkException
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.SonyHttp
import io.github.fotlab.fotlab.feature.camera.transport.SonyHttpTransport
import org.json.JSONObject

/**
 * Sony HTTP executor — wraps [SonyHttpTransport] with endpoint routing,
 * version defaults, and error normalisation.
 *
 * HTTP actions call through this executor instead of constructing raw JSON-RPC
 * payloads themselves. Endpoint selection (camera vs avContent) and the
 * version string are all routed here — one place to tune when Sony bumps an API.
 */
object SonyHttpExecutor {

    suspend fun callCamera(
        ctx: CameraActionContext,
        method: String,
        params: List<Any?> = emptyList(),
        version: String = SonyHttp.VERSION_DEFAULT,
    ): JSONObject {
        val transport = ctx.httpTransport
            ?: throw CameraLinkException("No Sony HTTP transport available")
        return transport.callCamera(method, params, version)
    }

    suspend fun callAvContent(
        ctx: CameraActionContext,
        method: String,
        params: List<Any?> = emptyList(),
        version: String = SonyHttp.VERSION_DEFAULT,
    ): JSONObject {
        val transport = ctx.httpTransport
            ?: throw CameraLinkException("No Sony HTTP transport available")
        return transport.callAvContent(method, params, version)
    }

    suspend fun download(ctx: CameraActionContext, url: String): ByteArray {
        val transport = ctx.httpTransport
            ?: throw CameraLinkException("No Sony HTTP transport available")
        // Direct URL download — SonyHttpTransport owns the HTTP POST path;
        // for direct GET we go through the transport's URL download helper
        return transport.downloadUrl(url)
    }
}

/**
 * Extension for [SonyHttpTransport] to expose direct URL download.
 * SonyHttpTransport only had JSON-RPC call methods before; actions need
 * the same transport instance to also pull raw bytes from a URL the
 * camera advertised (e.g. jpegUrl, thumbnailUrl).
 */
suspend fun SonyHttpTransport.downloadUrl(url: String): ByteArray {
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 60_000
        }
        try {
            conn.inputStream.use { it.readBytes() }
        } catch (error: java.io.IOException) {
            throw CameraLinkException("Sony HTTP download failed: ${error.message}", error)
        } finally {
            conn.disconnect()
        }
    }
}
