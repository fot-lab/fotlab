package io.github.fotlab.fotlab.feature.camera.operation

import io.github.fotlab.fotlab.feature.camera.model.PTP_PARENT_ALL
import io.github.fotlab.fotlab.feature.camera.model.PTP_PARENT_ROOT
import io.github.fotlab.fotlab.feature.camera.model.PtpFormats
import io.github.fotlab.fotlab.feature.camera.model.PtpMediaFilter
import io.github.fotlab.fotlab.feature.camera.model.PtpObject
import io.github.fotlab.fotlab.feature.camera.model.PtpObjectInfo
import io.github.fotlab.fotlab.feature.camera.model.PtpResponseException
import io.github.fotlab.fotlab.feature.camera.model.PtpStorage
import io.github.fotlab.fotlab.feature.camera.profile.CameraFamily
import io.github.fotlab.fotlab.feature.camera.profile.CameraProfile
import io.github.fotlab.fotlab.feature.camera.protocol.PtpContainer
import io.github.fotlab.fotlab.feature.camera.protocol.PtpOpcode
import io.github.fotlab.fotlab.feature.camera.protocol.PtpSession
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay

/**
 * PTP-level file primitives. No Android storage or UI types live here.
 *
 * The device profile is passed in rather than read from the session: the session
 * deliberately knows nothing about brands, so the two evolve independently.
 */
class PtpFileOperations(
    private val session: PtpSession,
    private val profile: CameraProfile,
) {

    /** GetStorageIDs followed by GetStorageInfo for each volume. */
    suspend fun storages(): List<PtpStorage> {
        val result = session.execute(profile.storageIdsOpcode, expectDataIn = true)
        val ids = decodeHandles(result.data, limit = 256).ifEmpty { decodeSonyStorages(result.data) }
        val effective = ids.ifEmpty {
            profile.storageIdFallback?.let { listOf(it) } ?: emptyList()
        }
        delay(profile.preListDelayUsbMs)
        return effective.map { id -> storageInfo(id) }
    }

    suspend fun storageInfo(storageId: Long): PtpStorage {
        val result = session.execute(
            PtpOpcode.GET_STORAGE_INFO,
            intArrayOf(storageId.toInt()),
            expectDataIn = true,
        )
        val raw = result.data ?: return PtpStorage(id = storageId)
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val type = buffer.u16()
            val filesystem = buffer.u16()
            val access = buffer.u16()
            val capacity = buffer.long
            val free = buffer.long
            buffer.int // free space in images
            val description = readString(buffer)
            PtpStorage(storageId, type, filesystem, access, capacity, free, description)
        } catch (_: RuntimeException) {
            PtpStorage(id = storageId)
        }
    }

    /**
     * GetObjectHandles. A busy store gets one retry, matching observed camera timing.
     *
     * A format code of zero means "all formats" to a responder; 0xFFFFFFFF is not a
     * wildcard and makes some cameras return an empty handle list.
     */
    suspend fun objectHandles(
        storageId: Long,
        format: Long = 0L,
        parent: Long = PTP_PARENT_ALL,
    ): List<Long> {
        delay(profile.preListDelayUsbMs)
        val params = intArrayOf(storageId.toInt(), format.toInt(), parent.toInt())
        val result = try {
            session.execute(profile.objectHandlesOpcode, params, expectDataIn = true)
        } catch (error: PtpResponseException) {
            if (error.responseCode != PtpOpcode.Response.STORE_NOT_AVAILABLE) throw error
            delay(750)
            session.execute(profile.objectHandlesOpcode, params, expectDataIn = true)
        }
        return decodeHandles(result.data, limit = 200_000)
    }

    /**
     * The objects directly inside [parent], folders included.
     *
     * A parent-scoped listing is what a file manager needs: the root shows the
     * storage's top level, and entering a folder asks for that folder's children.
     * Devices that ignore the parent argument answer with their whole tree, so an
     * empty scoped listing falls back to the flat listing rather than showing an
     * empty screen.
     */
    suspend fun objects(
        storageId: Long,
        parent: Long = PTP_PARENT_ROOT,
        filter: PtpMediaFilter = PtpMediaFilter.ALL,
        batchSize: Int = 500,
    ): List<PtpObject> {
        var handles = objectHandles(storageId, parent = parent)
        if (handles.isEmpty() && parent != PTP_PARENT_ALL) {
            handles = objectHandles(storageId, parent = PTP_PARENT_ALL)
        }
        val result = ArrayList<PtpObject>(handles.size)
        handles.chunked(batchSize).forEach { batch ->
            batch.forEach { handle ->
                val info = objectInfo(handle)
                val folder = info.format == PtpFormats.ASSOCIATION
                if (folder || filter.accepts(info.format)) {
                    result += PtpObject(
                        handle = handle,
                        name = info.name.ifEmpty { handle.toString() },
                        format = info.format,
                        sizeBytes = info.sizeBytes,
                        storageId = info.storageId,
                        parent = info.parent,
                        hasThumbnail = info.thumbBytes > 0,
                    )
                }
            }
        }
        return result
    }

    suspend fun objectInfo(handle: Long): PtpObjectInfo {
        val result = session.execute(
            profile.objectInfoOpcode,
            intArrayOf(handle.toInt()),
            expectDataIn = true,
        )
        var info = ObjectInfoDecoder.decode(result.data, handle)
        val repair = profile.largeLengthOpcode
        if (info.sizeBytes == PtpOpcode.SIZE_32_SENTINEL && repair != null) {
            val extended = session.execute(repair, intArrayOf(handle.toInt()), expectDataIn = true)
            val raw = extended.data
            if (raw != null && raw.size >= 8) {
                info = info.copy(
                    sizeBytes = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).long,
                )
            }
        }
        return info
    }

    suspend fun thumbnail(handle: Long): ByteArray {
        val result = session.execute(
            profile.thumbOpcode,
            intArrayOf(handle.toInt()),
            expectDataIn = true,
        )
        return result.data ?: throw IOException("Device returned no thumbnail for $handle")
    }

    /** Range download in profile-sized chunks, reporting bytes after each chunk. */
    suspend fun download(
        handle: Long,
        totalBytes: Long,
        output: OutputStream,
        chunkHint: Int = profile.preferredChunkBytes,
        onProgress: (Long) -> Unit = {},
    ): Long {
        if (totalBytes > MAX_STANDARD_OFFSET && profile.partialObjectOpcode == PtpOpcode.GET_PARTIAL_OBJECT) {
            throw IOException(
                "Object $handle is larger than 4 GiB; this device needs a vendor range-read command",
            )
        }
        val chunk = profile.chunkSize(chunkHint)
        var offset = 0L
        while (offset < totalBytes) {
            val size = minOf(chunk.toLong(), totalBytes - offset).toInt()
            val params = partialParams(handle, offset, size)
            val result = session.execute(profile.partialObjectOpcode, params, expectDataIn = true)
            val bytes = result.data ?: throw IOException("Missing data phase for object $handle")
            output.write(bytes)
            output.flush()
            offset += bytes.size
            onProgress(offset)
            if (bytes.size < size) break
        }
        return offset
    }

    suspend fun delete(handle: Long) {
        val params = if (profile.family == CameraFamily.SONY) {
            intArrayOf((handle and 0x00FFFFFFL).toInt(), 0)
        } else {
            intArrayOf(handle.toInt())
        }
        session.execute(profile.deleteOpcode, params)
    }

    /**
     * SendObjectInfo + SendObject. PTP sends the whole file in one data phase, so
     * the bytes are buffered here; progress is reported while reading the source.
     */
    suspend fun upload(
        storageId: Long,
        parent: Long,
        name: String,
        sizeBytes: Long,
        source: InputStream,
        format: Long = formatForName(name),
        onProgress: (Long) -> Unit = {},
    ): Long {
        val dataset = ObjectInfoDecoder.encodeForUpload(
            storageId = storageId,
            parent = parent,
            format = format,
            size = sizeBytes,
            name = name,
        )
        val info = session.execute(
            PtpOpcode.SEND_OBJECT_INFO,
            intArrayOf(storageId.toInt(), parent.toInt(), 0),
            dataOut = dataset,
        )
        if (info.params.size < 3) throw IOException("SendObjectInfo did not return a handle")
        val newHandle = info.params[2]

        val payload = ByteArrayOutputStream()
        val buffer = ByteArray(profile.chunkSize(profile.preferredChunkBytes))
        var read = 0L
        while (true) {
            val count = source.read(buffer)
            if (count <= 0) break
            payload.write(buffer, 0, count)
            read += count
            onProgress(read)
        }
        val data = payload.toByteArray()
        if (sizeBytes > 0 && data.size.toLong() != sizeBytes) {
            throw IOException("Upload size mismatch: expected $sizeBytes, read ${data.size}")
        }
        session.execute(PtpOpcode.SEND_OBJECT, intArrayOf(), dataOut = data)
        return newHandle
    }

    /**
     * Create a folder (a PTP association) on the device.
     *
     * The standard path is SendObjectInfo with the association format followed by an empty
     * SendObject data phase: the first command reserves the object and names it, the second
     * closes it. Support is device-dependent — a camera that refuses answers with a response
     * code, which surfaces as a [PtpResponseException] rather than a silent no-op.
     *
     * @return the handle of the new folder.
     */
    suspend fun createFolder(storageId: Long, parent: Long, name: String): Long {
        val dataset = ObjectInfoDecoder.encodeForUpload(
            storageId = storageId,
            parent = parent,
            format = PtpFormats.ASSOCIATION,
            size = 0L,
            name = name,
        )
        val info = session.execute(
            PtpOpcode.SEND_OBJECT_INFO,
            intArrayOf(storageId.toInt(), parent.toInt(), 0),
            dataOut = dataset,
        )
        if (info.params.size < 3) throw IOException("SendObjectInfo did not return a handle")
        val handle = info.params[2]
        // The closing data phase carries no bytes. A device that *refuses* the empty phase has
        // already reserved the folder, so that refusal is not fatal for the result — but a dead
        // link still is, and it must not be swallowed here.
        try {
            session.execute(PtpOpcode.SEND_OBJECT, intArrayOf(), dataOut = ByteArray(0))
        } catch (refusal: PtpResponseException) {
            // The folder was reserved by SendObjectInfo; the device declined the empty body.
        }
        return handle
    }

    /** Standard GetPartialObject uses a 32-bit byte offset. Vendor ranges may differ. */
    private fun partialParams(handle: Long, offset: Long, size: Int): IntArray = when {
        profile.partialObjectOpcode == PtpOpcode.Sony.PARTIAL_OBJECT -> intArrayOf(
            handle.toInt(),
            (offset ushr 32).toInt(),
            (offset and 0xFFFFFFFFL).toInt(),
            size,
        )
        else -> intArrayOf(handle.toInt(), offset.toInt(), size)
    }

    private fun formatForName(name: String): Long = when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg", "jpe" -> PtpFormats.JPEG
        "png" -> PtpFormats.PNG
        "tif", "tiff" -> PtpFormats.TIFF
        "gif" -> PtpFormats.GIF
        "bmp" -> PtpFormats.BMP
        "jp2", "jpx" -> PtpFormats.JP2
        "avi" -> PtpFormats.AVI
        "mpg", "mpeg" -> PtpFormats.MPEG
        "mov" -> PtpFormats.QUICKTIME
        else -> PtpFormats.UNDEFINED
    }

    private fun decodeHandles(raw: ByteArray?, limit: Int): List<Long> {
        if (raw == null || raw.size < 4) return emptyList()
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.u32()
            if (count < 0 || count > limit || (count * 4) + 4 > raw.size) return emptyList()
            ArrayList<Long>(count.toInt()).apply {
                repeat(count.toInt()) { add(buffer.u32()) }
            }
        } catch (_: RuntimeException) {
            emptyList()
        }
    }

    /** Some devices return tagged storage records instead of a u32 handle array. */
    private fun decodeSonyStorages(raw: ByteArray?): List<Long> {
        if (raw == null) return emptyList()
        val result = ArrayList<Long>(2)
        if (contains(raw, byteArrayOf(0x30.toByte(), 0xCE.toByte(), 0x02.toByte(), 0x00.toByte()))) result += 0x01000000L
        if (contains(raw, byteArrayOf(0x56.toByte(), 0xCE.toByte(), 0x02.toByte(), 0x00.toByte()))) result += 0x02000000L
        return result
    }

    private fun contains(source: ByteArray, pattern: ByteArray): Boolean {
        if (pattern.size > source.size) return false
        outer@ for (start in 0..source.size - pattern.size) {
            for (index in pattern.indices) {
                if (source[start + index] != pattern[index]) continue@outer
            }
            return true
        }
        return false
    }

    private fun readString(buffer: ByteBuffer): String {
        val units = buffer.get().toInt() and 0xFF
        if (units <= 1 || buffer.remaining() < (units - 1) * 2) return ""
        val bytes = ByteArray((units - 1) * 2)
        buffer.get(bytes)
        return PtpContainer.decodeUtf16Le(bytes, units - 1)
    }

    private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF

    private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL

    private companion object {
        /** The standard range-read command carries a 32-bit byte offset. */
        const val MAX_STANDARD_OFFSET: Long = 0xFFFFFFFFL
    }
}
