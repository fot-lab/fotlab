package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.PtpResponseException
import io.github.fotlab.fotlab.feature.camera.model.PtpStorage
import io.github.fotlab.fotlab.feature.camera.model.toPtpInt
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpFuji
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.SonyHttp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.delay

/**
 * GetStorageIDs → GetStorageInfo for each volume (PTP paths), or getSchemeList
 * + getSourceList (Sony NEX HTTP).
 *
 * All opcode selection is delegated to [Opcodes.storages] — this routine
 * never branches on `family` or `vendor`; it branches on [TransportPath]
 * and pulls its opcodes from the shared table.
 */
object StoragesAction {

    suspend fun execute(ctx: CameraActionContext): List<PtpStorage> = when (ctx.path) {
        TransportPath.HTTP_IP_SONY_NEX -> executeHttp(ctx)
        TransportPath.PTP_USB_STD_ISO,
        TransportPath.PTP_IP_STD_ISO,
        TransportPath.PTP_USB_SONY_0X92XX,
        TransportPath.PTP_USB_CANON_0X91XX,
        TransportPath.PTP_IP_CANON_0X91XX,
        TransportPath.PTP_USB_NIKON_0X90XX,
        TransportPath.PTP_IP_NIKON_0X90XX,
        TransportPath.PTP_USB_FUJI_0XD2XX -> executePtp(ctx)
    }

    // ── PTP path ─────────────────────────────────────────────────────────

    private suspend fun executePtp(ctx: CameraActionContext): List<PtpStorage> {
        // Fuji has no GetStorageIDs — use fixed fallback (path-specific quirk)
        if (ctx.segment == Segment.FUJI_0XD2XX) {
            delay(ctx.profile.preListDelayUsbMs)
            return listOf(fetchStorageInfo(ctx, PtpFuji.STORAGE_ID_FALLBACK))
        }

        val pair = Opcodes.storages(ctx)
        val skipStandard = ctx.path.isVendorSegment && pair.standard == null
        val result = PtpExecutor.execute(
            ctx = ctx,
            standard = pair.standard,
            vendor = pair.vendor,
            params = intArrayOf(),
            expectDataIn = true,
            skipStandard = skipStandard,
        )
        val ids = decodeStorageIds(result.data).ifEmpty {
            // Sony 0x9209 returns tagged storage records instead of a bare u32 array
            if (ctx.segment == Segment.SONY_0X92XX) decodeSonyTaggedStorages(result.data)
            else emptyList()
        }
        val effective = ids.ifEmpty { ctx.profile.storageIdFallback?.let { listOf(it) } ?: emptyList() }
        delay(ctx.profile.preListDelayUsbMs)
        return effective.map { fetchStorageInfo(ctx, it) }
    }

    private suspend fun fetchStorageInfo(ctx: CameraActionContext, id: Long): PtpStorage = try {
        val pair = Opcodes.objectInfo(ctx) // storage info shares the ISO GetStorageInfo opcode
        val standard = pair.standard ?: io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpStd.GET_STORAGE_INFO
        val result = PtpExecutor.executeNoRetry(
            ctx = ctx, standard = standard, vendor = null,
            params = intArrayOf(id.toPtpInt()), expectDataIn = true,
        )
        decodeStorageInfo(result.data, id)
    } catch (_: PtpResponseException) {
        PtpStorage(id = id)
    }

    // ── HTTP path (Sony NEX ScalarWebAPI) ────────────────────────────────

    private suspend fun executeHttp(ctx: CameraActionContext): List<PtpStorage> {
        val schemes = SonyHttpExecutor.callAvContent(
            ctx = ctx, method = SonyHttp.AvContent.GET_SCHEME_LIST,
        ).optJSONArray("result")?.optJSONArray(0) ?: return emptyList()

        val storages = mutableListOf<PtpStorage>()
        for (i in 0 until schemes.length()) {
            val scheme = schemes.getJSONObject(i).optString("scheme")
            val sources = SonyHttpExecutor.callAvContent(
                ctx = ctx, method = SonyHttp.AvContent.GET_SOURCE_LIST,
                params = listOf(mapOf("scheme" to scheme)),
            ).optJSONArray("result")?.optJSONArray(0) ?: continue
            for (j in 0 until sources.length()) {
                val source = sources.getJSONObject(j).optString("source")
                storages += PtpStorage(
                    id = (i.toLong() shl 32) or j.toLong(),
                    description = "$scheme:$source",
                )
            }
        }
        return storages
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun allPtp() = TransportPath.values().filter { it.isPtp }

    // ── Decoders ──────────────────────────────────────────────────────────

    private fun decodeStorageIds(raw: ByteArray?): List<Long> {
        if (raw == null || raw.size < 4) return emptyList()
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.u32()
            if (count < 0 || count > 256 || (count * 4) + 4 > raw.size) return emptyList()
            ArrayList<Long>(count.toInt()).apply { repeat(count.toInt()) { add(buffer.u32()) } }
        } catch (_: RuntimeException) { emptyList() }
    }

    /** Sony 0x9209 returns tagged storage records instead of a bare u32 array. */
    private fun decodeSonyTaggedStorages(raw: ByteArray?): List<Long> {
        if (raw == null) return emptyList()
        val result = ArrayList<Long>(2)
        if (contains(raw, byteArrayOf(0x30.toByte(), 0xCE.toByte(), 0x02.toByte(), 0x00.toByte())))
            result += 0x01000000L
        if (contains(raw, byteArrayOf(0x56.toByte(), 0xCE.toByte(), 0x02.toByte(), 0x00.toByte())))
            result += 0x02000000L
        return result
    }

    private fun contains(source: ByteArray, pattern: ByteArray): Boolean {
        if (pattern.size > source.size) return false
        outer@ for (start in 0..source.size - pattern.size) {
            for (index in pattern.indices) { if (source[start + index] != pattern[index]) continue@outer }
            return true
        }
        return false
    }

    private fun decodeStorageInfo(raw: ByteArray?, id: Long): PtpStorage {
        if (raw == null) return PtpStorage(id = id)
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val type = buffer.u16(); val filesystem = buffer.u16(); val access = buffer.u16()
            val capacity = buffer.long; val free = buffer.long; buffer.int
            val description = readString(buffer)
            PtpStorage(id, type, filesystem, access, capacity, free, description)
        } catch (_: RuntimeException) { PtpStorage(id = id) }
    }

    private fun readString(buffer: ByteBuffer): String {
        val units = buffer.get().toInt() and 0xFF
        if (units <= 1 || buffer.remaining() < (units - 1) * 2) return ""
        val bytes = ByteArray((units - 1) * 2); buffer.get(bytes)
        return String(bytes, java.nio.charset.StandardCharsets.UTF_16LE).trimEnd('\u0000')
    }

    private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF
    private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL
}
