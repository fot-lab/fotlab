package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.PtpObject
import io.github.fotlab.fotlab.feature.camera.model.PtpFormats
import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps Sony ScalarWebAPI JSON objects into transport-agnostic [PtpObject].
 *
 * Sony's HTTP API addresses files by URI and advertises three variants:
 * - jpeg  → lower resolution preview (if available)
 * - raw   → full resolution RAW
 * - mp4   → video
 *
 * We pick the highest-fidelity variant advertised and derive format/size/URLs
 * from it. PtpObject does not carry transport-specific URLs — callers that
 * need direct byte access go through [DownloadAction] which knows to pull
 * from the camera's HTTP endpoint.
 */
object SonyHttpContentAdapter {

    /** Convert one getContentList JSON item into a [PtpObject]. */
    fun toPtpObject(item: JSONObject, storageId: Long, fileNoOffset: Int): PtpObject {
        val content = item.getJSONObject("content")
        val originalArray = content.optJSONArray("original") ?: JSONArray()
        var rawUrl: String? = null
        var jpegUrl: String? = null
        var fileName: String? = null
        var rawFileName: String? = null
        var sizeBytes = 0L
        var format: Long = PtpFormats.JPEG
        for (k in 0 until originalArray.length()) {
            val o = originalArray.getJSONObject(k)
            val obj = o.optString("stillObject")
            when {
                obj == "raw" -> {
                    rawUrl = o.optString("url").ifBlank { null }
                    rawFileName = o.optString("fileName").ifBlank { null }
                    sizeBytes = o.optLong("size", sizeBytes)
                    format = rawFormatFromName(rawFileName ?: "")
                }
                obj == "jpeg" -> {
                    jpegUrl = o.optString("url").ifBlank { null }
                    fileName = o.optString("fileName").ifBlank { null }
                    if (sizeBytes == 0L) sizeBytes = o.optLong("size", 0)
                    if (format == PtpFormats.JPEG) format = PtpFormats.JPEG
                }
                obj == "mp4" -> {
                    jpegUrl = o.optString("url").ifBlank { jpegUrl }
                    fileName = o.optString("fileName").ifBlank { fileName }
                    sizeBytes = o.optLong("size", sizeBytes)
                    format = PtpFormats.QUICKTIME
                }
            }
        }

        val displayName = rawFileName ?: fileName ?: "sony-${item.optString("fileNo")}"
        return PtpObject(
            handle = makeSyntheticHandle(storageId, fileNoOffset),
            name = displayName,
            format = format,
            sizeBytes = sizeBytes,
            storageId = storageId,
            parent = 0L,
            hasThumbnail = content.optString("thumbnailUrl").isNotBlank(),
        )
    }

    /** Store transport-specific URLs alongside the PtpObject for download use. */
    data class SonyObjectUrls(
        val rawUrl: String?,
        val jpegUrl: String?,
        val thumbnailUrl: String?,
    ) {
        /** The URL to pull for the highest-quality content. */
        val bestUrl: String? get() = rawUrl ?: jpegUrl
    }

    fun extractUrls(item: JSONObject): SonyObjectUrls {
        val content = item.getJSONObject("content")
        val originalArray = content.optJSONArray("original") ?: JSONArray()
        var rawUrl: String? = null
        var jpegUrl: String? = null
        for (k in 0 until originalArray.length()) {
            val o = originalArray.getJSONObject(k)
            val obj = o.optString("stillObject")
            when {
                obj == "raw" -> rawUrl = o.optString("url").ifBlank { null }
                obj == "jpeg" -> jpegUrl = o.optString("url").ifBlank { null }
            }
        }
        return SonyObjectUrls(
            rawUrl = rawUrl,
            jpegUrl = jpegUrl,
            thumbnailUrl = content.optString("thumbnailUrl").ifBlank { null },
        )
    }

    /** Build a synthetic handle from storage + offset — opaque key that maps back to URLs. */
    fun makeSyntheticHandle(storageId: Long, fileNoOffset: Int): Long =
        ((storageId and 0x00000000FFFFFFFFL) shl 32) or (fileNoOffset.toLong() and 0xFFFFFFFFL)

    private fun rawFormatFromName(name: String): Long = when {
        name.endsWith(".ARW", ignoreCase = true) -> 0xB100L // Sony ARW
        name.endsWith(".SR2", ignoreCase = true) -> 0xB101L
        name.endsWith(".NEF", ignoreCase = true) -> 0xB201L // Nikon NEF
        name.endsWith(".CR2", ignoreCase = true) || name.endsWith(".CR3", ignoreCase = true) -> 0xB103L // Canon
        name.endsWith(".RAF", ignoreCase = true) -> 0xB104L // Fuji
        name.endsWith(".DNG", ignoreCase = true) -> 0xB105L // Adobe DNG
        else -> PtpFormats.UNDEFINED
    }
}
