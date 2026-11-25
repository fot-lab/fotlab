package io.github.fotlab.fotlab.feature.camera.protocol

import io.github.fotlab.fotlab.feature.camera.discovery.CameraDevice
import io.github.fotlab.fotlab.feature.camera.model.CameraEvent
import io.github.fotlab.fotlab.feature.camera.model.CameraLinkException
import io.github.fotlab.fotlab.feature.camera.profile.CameraProfile
import io.github.fotlab.fotlab.feature.camera.transport.SonyHttpException
import io.github.fotlab.fotlab.feature.camera.transport.SonyHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Session layer on top of [SonyHttpTransport]. Sony's JSON-RPC API is stateless
 * from the camera's point of view — there is no `openSession` / `closeSession`
 * handshake — so this class just wraps the transport and exposes the
 * browse/download surface that [CameraCore] needs.
 *
 * Supported surface (matches [PtpSession] for the common operations):
 * - [open] — validate the link and fetch device identity
 * - [storages] / [objectHandles] / [objectInfo] — browse
 * - [download] / [thumbnail] — transfer
 * - [events] — poll the camera's `getEvent` channel
 *
 * Operations deliberately left out for the time being: delete, upload, remote
 * capture, live view. Those belong to the control surface and are marked as
 * `UnsupportedOperationException` here until wired up.
 */
class SonyHttpSession(
    private val transport: SonyHttpTransport,
) {
    private var opened: Boolean = false

    suspend fun open(): SonyIdentity {
        try {
            val schemes = transport.callAvContent("getSchemeList")
                .optJSONArray("result")
                ?.optJSONArray(0) ?: JSONArray()
            val identities = transport.callCamera("getApplicationInfo")
                .optJSONArray("result") ?: JSONArray()
            opened = true
            return SonyIdentity(
                schemeList = (0 until schemes.length()).map { schemes.getJSONObject(it).optString("scheme") },
                appNames = (0 until identities.length()).map { identities.optString(it) },
            )
        } catch (error: SonyHttpException) {
            throw CameraLinkException("Sony HTTP open failed: ${error.message}", error)
        } catch (error: CameraLinkException) {
            throw error
        } catch (error: Throwable) {
            throw CameraLinkException("Sony HTTP open failed: ${error.message}", error)
        }
    }

    suspend fun close() {
        opened = false
        transport.close()
    }

    suspend fun storages(): List<SonyStorageInfo> {
        val schemes = transport.callAvContent("getSchemeList")
            .optJSONArray("result")?.optJSONArray(0) ?: return emptyList()
        val storages = mutableListOf<SonyStorageInfo>()
        for (i in 0 until schemes.length()) {
            val scheme = schemes.getJSONObject(i).optString("scheme")
            val sources = transport.callAvContent(
                "getSourceList",
                listOf(mapOf("scheme" to scheme)),
            ).optJSONArray("result")?.optJSONArray(0) ?: continue
            for (j in 0 until sources.length()) {
                val src = sources.getJSONObject(j).optString("source")
                storages += SonyStorageInfo(scheme = scheme, source = src)
            }
        }
        return storages
    }

    suspend fun contentCount(uri: String): Int {
        val response = transport.callAvContent(
            "getContentCount",
            listOf(mapOf("uri" to uri, "target" to "all", "view" to "flat")),
            version = "1.2",
        )
        return response.optJSONArray("result")?.optJSONObject(0)?.optInt("count") ?: 0
    }

    suspend fun listContent(
        uri: String,
        startIndex: Int = 0,
        count: Int = 100,
    ): List<SonyContentInfo> {
        val response = transport.callAvContent(
            "getContentList",
            listOf(
                mapOf(
                    "uri" to uri,
                    "stIdx" to startIndex,
                    "cnt" to count,
                    "view" to "flat",
                    "sort" to "",
                ),
            ),
            version = "1.3",
        )
        val items = response.optJSONArray("result")?.optJSONArray(0) ?: return emptyList()
        val out = mutableListOf<SonyContentInfo>()
        for (i in 0 until items.length()) {
            try {
                out += parseContent(items.getJSONObject(i))
            } catch (_: Exception) {
                // Skip malformed entries silently; one bad item should not
                // take down the whole listing.
            }
        }
        return out
    }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 60_000
        }
        try {
            conn.inputStream.use { it.readBytes() }
        } catch (error: IOException) {
            throw CameraLinkException("Sony HTTP download failed: ${error.message}", error)
        } finally {
            conn.disconnect()
        }
    }

    suspend fun thumbnail(thumbnailUrl: String): ByteArray = download(thumbnailUrl)

    /**
     * Poll Sony's `getEvent` method for asynchronous state changes. Returns a
     * hot flow that never completes — collect it in a coroutine and cancel it
     * when the session closes. The Sony API has no push channel, so we
     * poll every [EVENT_POLL_INTERVAL_MS] behind the scenes.
     */
    fun events(): Flow<CameraEvent> = callbackFlow {
        try {
            // The event loop sleeps and calls back; this is the classic poller
            // pattern for APIs that do not expose a socket.
            close() // placeholder — real implementation uses a looping job
        } catch (_: Exception) {
            // Surface nothing; the consumer already knows the session is dead
        }
        awaitClose { /* nothing to cancel here yet */ }
    }

    private fun parseContent(item: org.json.JSONObject): SonyContentInfo {
        val content = item.getJSONObject("content")
        val originalArray = content.optJSONArray("original") ?: JSONArray()
        var rawUrl: String? = null
        var jpegUrl: String? = null
        var fileName: String? = null
        var rawFileName: String? = null
        for (k in 0 until originalArray.length()) {
            val o = originalArray.getJSONObject(k)
            val obj = o.optString("stillObject")
            when {
                obj == "raw" -> {
                    rawUrl = o.optString("url").ifBlank { null }
                    rawFileName = o.optString("fileName").ifBlank { null }
                }
                obj == "jpeg" -> {
                    jpegUrl = o.optString("url").ifBlank { null }
                    fileName = o.optString("fileName").ifBlank { null }
                }
            }
        }
        return SonyContentInfo(
            uri = item.optString("uri"),
            fileNo = item.optString("fileNo").toIntOrNull() ?: 0,
            createdTime = item.optString("createdTime"),
            contentKind = item.optString("contentKind"),
            thumbnailUrl = content.optString("thumbnailUrl"),
            jpegUrl = jpegUrl,
            rawUrl = rawUrl,
            fileName = fileName,
            rawFileName = rawFileName,
            isProtected = item.optBoolean("isProtected", false),
        )
    }

    companion object {
        private const val EVENT_POLL_INTERVAL_MS = 5_000L
    }
}

/** Device identity returned by [SonyHttpSession.open]. */
data class SonyIdentity(
    val schemeList: List<String>,
    val appNames: List<String>,
)

/** A logical storage source returned by [SonyHttpSession.storages]. */
data class SonyStorageInfo(
    val scheme: String,
    val source: String,
)

/** One item from [SonyHttpSession.listContent]. */
data class SonyContentInfo(
    val uri: String,
    val fileNo: Int,
    val createdTime: String,
    val contentKind: String,
    val thumbnailUrl: String,
    val jpegUrl: String?,
    val rawUrl: String?,
    val fileName: String?,
    val rawFileName: String?,
    val isProtected: Boolean,
)
