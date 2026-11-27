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
import io.github.fotlab.fotlab.feature.camera.protocol.PtpTransactionResult
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Object records carried by the family's paged content-list command.
     *
     * The listing dataset is the only reliable per-object information source on
     * these devices, so the listing fills this cache and every later
     * object-info lookup is served from it.
     */
    private val listedInfo = ConcurrentHashMap<Long, PtpObjectInfo>()

    /** The packed (class byte + format) value the transfer commands take as a parameter. */
    private val listedPackedFormats = ConcurrentHashMap<Long, Long>()

    /** GetStorageIDs followed by GetStorageInfo for each volume. */
    suspend fun storages(): List<PtpStorage> {
        val skipSony = profile.objectHandlesOpcode == PtpOpcode.Sony.CONTENT_LIST
        val result = executeWithStandardFallback(
            PtpOpcode.GET_STORAGE_IDS,
            profile.storageIdsOpcode,
            intArrayOf(),
            expectDataIn = true,
            skipStandard = skipSony,
        )
        val ids = decodeHandles(result.data, limit = 256).ifEmpty { decodeSonyStorages(result.data) }
        val effective = ids.ifEmpty {
            profile.storageIdFallback?.let { listOf(it) } ?: emptyList()
        }
        delay(profile.preListDelayUsbMs)
        return effective.map { id ->
            // StorageInfo is descriptive only: a responder that refuses it still
            // has objects to enumerate under this storage ID.
            try {
                storageInfo(id)
            } catch (_: PtpResponseException) {
                PtpStorage(id = id)
            }
        }
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
     * GetObjectHandles. Standard-first with bounded busy retries on both the
     * standard ISO/PIMA opcode and the profile's vendor extension.
     *
     * A format code of zero means "no format filter" to a responder; 0xFFFFFFFF is
     * the images-only filter, not a wildcard, and can hide non-image objects.
     */
    suspend fun objectHandles(
        storageId: Long,
        format: Long = 0L,
        parent: Long = PTP_PARENT_ALL,
    ): List<Long> {
        delay(profile.preListDelayUsbMs)
        val skipSony = profile.objectHandlesOpcode == PtpOpcode.Sony.CONTENT_LIST
        val params = intArrayOf(storageId.toInt(), format.toInt(), parent.toInt())
        val result = executeWithBusyRetry(
            PtpOpcode.GET_OBJECT_HANDLES,
            profile.objectHandlesOpcode,
            params,
            skipStandard = skipSony,
        )
        return decodeHandles(result.data, limit = 200_000)
    }

    /**
     * PIMA 15740 section 9.3.4: a responder performing an asynchronous operation
     * answers Device_Busy and the initiator must retry later. Store_Not_Available
     * is retried the same way because a freshly inserted card is often busy on
     * the first list. Retries are bounded and back off, so a permanently busy
     * device fails with the last response instead of blocking forever.
     *
     * The retry loop also wraps a **standard-first, vendor-extension fallback**:
     * every conforming PTP device answers the standard ISO/PIMA 15740 opcodes;
     * vendor extensions are optional refinements. We try the standard opcode
     * first, and if the responder explicitly says it does not support it
     * (0x2005 OPERATION_NOT_SUPPORTED or 0x2006 PARAMETER_NOT_SUPPORTED) we
     * retry with the vendor one instead. Other response codes propagate unchanged.
     *
     * [skipStandard] bypasses the standard attempt entirely. This is needed for
     * Sony's 0x92xx family, where the standard GetObjectHandles returns empty or
     * truncated data rather than a refusal.
     */
    private suspend fun executeWithBusyRetry(
        standardOpcode: Int,
        vendorOpcode: Int,
        params: IntArray,
        skipStandard: Boolean = false,
    ): PtpTransactionResult {
        val candidates = when {
            skipStandard -> listOf(vendorOpcode)
            standardOpcode == vendorOpcode -> listOf(standardOpcode)
            else -> listOf(standardOpcode, vendorOpcode)
        }
        var lastError: PtpResponseException? = null
        for (code in candidates) {
            val attempt = retryOnceOrError(code, params)
            when (attempt) {
                is RetryResult.Ok -> return attempt.result
                is RetryResult.TransientBusy -> {
                    // Standard opcode hit a busy — this device might still answer
                    // the vendor variant, which is less commonly throttled.
                    val notFirst = (code != candidates.first())
                    if (notFirst) {
                        // Already past the first candidate and still busy, give up.
                        lastError = attempt.lastError
                        break
                    }
                    lastError = attempt.lastError
                }
                is RetryResult.NotSupported -> {
                    // Standard opcode not supported — fall through to vendor.
                    if (code == candidates.last()) lastError = attempt.lastError
                }
            }
        }
        throw lastError ?: PtpResponseException(vendorOpcode, PtpOpcode.Response.GENERAL_ERROR)
    }

    /** Retry one opcode up to MAX_BUSY_RETRIES times. */
    private suspend fun retryOnceOrError(code: Int, params: IntArray): RetryResult {
        var attempt = 0
        var busyCount = 0
        var lastError: PtpResponseException? = null
        while (true) {
            try {
                return RetryResult.Ok(session.execute(code, params, expectDataIn = true))
            } catch (error: PtpResponseException) {
                lastError = error
                val transient = error.responseCode == PtpOpcode.Response.DEVICE_BUSY ||
                    error.responseCode == PtpOpcode.Response.STORE_NOT_AVAILABLE
                val unsupported = error.responseCode == PtpOpcode.Response.OPERATION_NOT_SUPPORTED ||
                    error.responseCode == PtpOpcode.Response.PARAMETER_NOT_SUPPORTED
                when {
                    unsupported -> return RetryResult.NotSupported(error)
                    transient -> {
                        busyCount++
                        if (busyCount >= MAX_BUSY_RETRIES) {
                            return RetryResult.TransientBusy(error)
                        }
                        delay(BUSY_RETRY_BASE_MS * (busyCount))
                    }
                    else -> throw error
                }
            }
        }
    }

    private sealed class RetryResult {
        data class Ok(val result: PtpTransactionResult) : RetryResult()
        data class TransientBusy(val lastError: PtpResponseException) : RetryResult()
        data class NotSupported(val lastError: PtpResponseException) : RetryResult()
    }

    /**
     * Execute a single PTP operation with standard-first fallback, no busy retry.
     *
     * Used for per-object queries (ObjectInfo, Thumbnail) where the device
     * should answer immediately and a busy response would indicate a deeper
     * problem than a transient list refresh backpressure. Same fallback rule
     * as [executeWithBusyRetry].
     */
    private suspend fun executeWithStandardFallback(
        standardOpcode: Int,
        vendorOpcode: Int,
        params: IntArray,
        expectDataIn: Boolean = true,
        dataOut: ByteArray? = null,
        skipStandard: Boolean = false,
    ): PtpTransactionResult {
        if (skipStandard || standardOpcode == vendorOpcode) {
            return session.execute(vendorOpcode, params, dataOut, expectDataIn)
        }
        return try {
            session.execute(standardOpcode, params, dataOut, expectDataIn)
        } catch (error: PtpResponseException) {
            val unsupported = error.responseCode == PtpOpcode.Response.OPERATION_NOT_SUPPORTED ||
                error.responseCode == PtpOpcode.Response.PARAMETER_NOT_SUPPORTED
            if (unsupported) {
                session.execute(vendorOpcode, params, dataOut, expectDataIn)
            } else {
                throw error
            }
        }
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
        if (profile.objectHandlesOpcode == PtpOpcode.Sony.CONTENT_LIST) {
            val listed = listedObjects(storageId, filter)
            if (listed != null) return listed
        }
        var handles = objectHandles(storageId, parent = parent)
        if (handles.isEmpty() && parent != PTP_PARENT_ALL) {
            handles = objectHandles(storageId, parent = PTP_PARENT_ALL)
        }
        // Empty stays empty — do not fall back to the aggregate storage
        // sentinel (0xFFFFFFFF). PIMA 15740 section 10.4.7 defines it as
        // addressing every storage at once, so a device that returns handles
        // for it would mix cards and yield false-positives. NikonCameraControl
        // walks one real storageId per call for the same reason.
        val result = ArrayList<PtpObject>(handles.size)
        handles.chunked(batchSize).forEach { batch ->
            batch.forEach { handle ->
                // GetObjectHandles already returned this handle, so the object exists
                // even when GetObjectInfo is refused or its dataset decodes to no
                // format. Keep it visible instead of dropping it silently.
                val info = try {
                    objectInfo(handle)
                } catch (_: PtpResponseException) {
                    PtpObjectInfo(handle = handle)
                }
                val folder = info.format == PtpFormats.ASSOCIATION
                val accepted = folder || filter == PtpMediaFilter.ALL || filter.accepts(info.format)
                if (accepted) {
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

    /**
     * Lists objects through the paged content-list command, which answers with
     * full object records instead of bare handles.
     *
     * The command pages through the device's content by a record timestamp and
     * is class-scoped, so the aggregate storage sentinel fans out to the known
     * volumes. Returns `null` when the device refuses the command, letting the
     * caller fall back to the generic handle-based path.
     */
    private suspend fun listedObjects(
        storageId: Long,
        filter: PtpMediaFilter,
    ): List<PtpObject>? {
        val targets = if (storageId == ALL_STORAGES_ID) {
            // The aggregate sentinel carries no class byte; ask each known volume.
            storages().map { it.id }.filter { it != ALL_STORAGES_ID }
        } else {
            listOf(storageId)
        }
        if (targets.isEmpty()) return null
        listedInfo.clear()
        listedPackedFormats.clear()
        val result = ArrayList<PtpObject>(1024)
        for (target in targets) {
            val classByte = (target ushr 24).toInt()
            var cursor = 0L
            var pages = 0
            while (pages < MAX_LIST_PAGES) {
                pages++
                val page = executeWithBusyRetry(
                    profile.objectHandlesOpcode, // standard = Sony CONTENT_LIST (no standard counterpart)
                    profile.objectHandlesOpcode,
                    intArrayOf(
                        cursor.toInt(),
                        (cursor ushr 32).toInt(),
                        SonyContentListDecoder.PAGE_SIZE,
                        classByte,
                        0,
                    ),
                    skipStandard = true,
                )
                val decoded = SonyContentListDecoder.decodePage(page.data)
                if (decoded.recordCount <= 0 || decoded.entries.isEmpty()) break
                for (entry in decoded.entries) {
                    // A re-fetched page repeats its boundary record; keep one row per handle.
                    if (listedInfo.putIfAbsent(entry.handle, entry.toInfo()) != null) continue
                    listedPackedFormats[entry.handle] = entry.packedFormat
                    val accepted = filter == PtpMediaFilter.ALL || filter.accepts(entry.format)
                    if (accepted) {
                        result += PtpObject(
                            handle = entry.handle,
                            name = entry.name.ifEmpty { entry.handle.toString() },
                            format = entry.format,
                            sizeBytes = entry.sizeBytes,
                            storageId = target,
                            parent = 0L,
                            hasThumbnail = entry.hasImageBlock,
                        )
                    }
                }
                if (decoded.recordCount < SonyContentListDecoder.PAGE_SIZE) break
                // The next page continues after the newest record this page carried.
                cursor = if (decoded.maxCursor == cursor) cursor + 1 else decoded.maxCursor
            }
        }
        return result
    }

    suspend fun objectInfo(handle: Long): PtpObjectInfo {
        if (profile.objectHandlesOpcode == PtpOpcode.Sony.CONTENT_LIST) {
            listedInfo[handle]?.let { return it }
        }
        val skipSony = profile.objectHandlesOpcode == PtpOpcode.Sony.CONTENT_LIST
        val result = executeWithStandardFallback(
            PtpOpcode.GET_OBJECT_INFO,
            profile.objectInfoOpcode,
            intArrayOf(handle.toInt()),
            expectDataIn = true,
            skipStandard = skipSony,
        )
        var info = ObjectInfoDecoder.decode(result.data, handle)
        val repair = profile.largeLengthOpcode
        if (info.sizeBytes == PtpOpcode.SIZE_32_SENTINEL && repair != null) {
            val extended = executeWithStandardFallback(
                repair,
                repair,
                intArrayOf(handle.toInt()),
                expectDataIn = true,
            )
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
        val skipSony = profile.objectHandlesOpcode == PtpOpcode.Sony.CONTENT_LIST
        val result = executeWithStandardFallback(
            PtpOpcode.GET_THUMB,
            profile.thumbOpcode,
            intArrayOf(handle.toInt()),
            expectDataIn = true,
            skipStandard = skipSony,
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
        val packedFormat = listedPackedFormats[handle] ?: STILL_IMAGE_PACKED_FORMAT
        val chunk = profile.chunkSize(chunkHint)
        var offset = 0L
        while (offset < totalBytes) {
            val size = minOf(chunk.toLong(), totalBytes - offset).toInt()
            val params = partialParams(handle, offset, size, packedFormat)
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
            // The class byte of the object's packed format; the reference value
            // when the listing has not seen the handle is the still-image class.
            val classByte = listedPackedFormats[handle]?.let { (it ushr 24).toInt() } ?: 1
            intArrayOf((handle and 0x00FFFFFFL).toInt(), classByte)
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

    /** Standard range-read uses a 32-bit offset; the family variant is 64-bit and class-scoped. */
    private fun partialParams(handle: Long, offset: Long, size: Int, packedFormat: Long): IntArray = when {
        profile.partialObjectOpcode == PtpOpcode.Sony.PARTIAL_OBJECT -> intArrayOf(
            (handle and 0x00FFFFFFL).toInt(),
            packedFormat.toInt(),
            offset.toInt(),
            (offset ushr 32).toInt(),
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

        /** Bounded retries for Device_Busy / Store_Not_Available (PIMA 9.3.4). */
        const val MAX_BUSY_RETRIES = 2
        const val BUSY_RETRY_BASE_MS = 400L

        /** GetObjectHandles storage ID that aggregates every storage (PIMA 10.4.7). */
        const val ALL_STORAGES_ID: Long = 0xFFFFFFFFL

        /** Upper bound for content-list pages, so a misbehaving responder cannot loop us. */
        const val MAX_LIST_PAGES = 10_000

        /** Packed (class byte + format) assumed for a handle the listing has not seen. */
        const val STILL_IMAGE_PACKED_FORMAT: Long = 0x01000001L
    }
}
