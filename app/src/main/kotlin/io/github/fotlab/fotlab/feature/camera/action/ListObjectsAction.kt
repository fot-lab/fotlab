package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.PTP_PARENT_ALL
import io.github.fotlab.fotlab.feature.camera.model.PTP_PARENT_ROOT
import io.github.fotlab.fotlab.feature.camera.model.PtpFormats
import io.github.fotlab.fotlab.feature.camera.model.PtpMediaFilter
import io.github.fotlab.fotlab.feature.camera.model.PtpObject
import io.github.fotlab.fotlab.feature.camera.model.PtpObjectInfo
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpSony
import io.github.fotlab.fotlab.feature.camera.protocol.opcode.SonyHttp
import kotlinx.coroutines.delay

/**
 * List objects by storage + parent folder + media filter.
 *
 * Routing is 100% [TransportPath]-driven: the routine never reads vendor
 * or transport fields directly — it reads [ctx.path](CameraActionContext.path)
 * and looks its opcodes up in [Opcodes.objectHandles].
 *
 * ### TransportPath → listing strategy
 * | TransportPath                 | Strategy                                                      |
 * |-------------------------------|---------------------------------------------------------------|
 * | PTP_USB_STD_ISO               | Standard GetObjectHandles (0x1007) + per-handle GetObjectInfo |
 * | PTP_IP_STD_ISO                | Standard GetObjectHandles (0x1007) + per-handle GetObjectInfo |
 * | PTP_USB_SONY_0X92XX           | Sony CONTENT_LIST (0x923C) — pages with timestamp cursor       |
 * | HTTP_IP_SONY_NEX              | Sony ScalarWebAPI getContentList, paginated stIdx/cnt=100      |
 * | PTP_USB_CANON_0X91XX          | Standard → Canon OBJECT_HANDLES (0x9116) fallback              |
 * | PTP_IP_CANON_0X91XX           | Standard → Canon OBJECT_HANDLES (0x9116) fallback              |
 * | PTP_USB_NIKON_0X90XX          | Standard → Nikon OBJECT_HANDLES (0x90C7) fallback             |
 * | PTP_IP_NIKON_0X90XX           | Standard → Nikon OBJECT_HANDLES (0x90C7) fallback              |
 * | PTP_USB_FUJI_0XD2XX           | Standard GetObjectHandles (Fuji USB skips GetStorageIDs earlier)|
 */
object ListObjectsAction {

    private const val MAX_LIST_PAGES = 10_000
    private const val ALL_STORAGES_SENTINEL: Long = 0xFFFFFFFFL

    suspend fun execute(
        ctx: CameraActionContext,
        storageId: Long,
        parent: Long = PTP_PARENT_ROOT,
        filter: PtpMediaFilter = PtpMediaFilter.ALL,
        batchSize: Int = 500,
    ): List<PtpObject> = when (ctx.path) {
        TransportPath.HTTP_IP_SONY_NEX -> executeHttp(ctx, storageId, filter)
        TransportPath.PTP_USB_SONY_0X92XX -> listObjectsSonyContentList(ctx, storageId, filter)
        in TransportPath.values().filter { it.isPtp } -> executePtpStandard(ctx, storageId, parent, filter, batchSize)
    }

    // ── Standard PTP path (with vendor fallback from Opcodes table) ─────

    private suspend fun executePtpStandard(
        ctx: CameraActionContext,
        storageId: Long,
        parent: Long,
        filter: PtpMediaFilter,
        batchSize: Int,
    ): List<PtpObject> {
        delay(ctx.profile.preListDelayUsbMs)
        val handles = listHandles(ctx, storageId, parent)
        val result = ArrayList<PtpObject>(handles.size)
        handles.chunked(batchSize).forEach { batch ->
            batch.forEach { handle ->
                val info = try {
                    ObjectInfoAction.execute(ctx, handle)
                } catch (_: io.github.fotlab.fotlab.feature.camera.model.PtpResponseException) {
                    PtpObjectInfo(handle = handle)
                }
                val folder = info.format == PtpFormats.ASSOCIATION
                val accepted = folder || filter == PtpMediaFilter.ALL || filter.accepts(info.format)
                if (accepted) {
                    result += PtpObject(
                        handle = handle, name = info.name.ifEmpty { handle.toString() },
                        format = info.format, sizeBytes = info.sizeBytes,
                        storageId = info.storageId, parent = info.parent,
                        hasThumbnail = info.thumbBytes > 0,
                    )
                }
            }
        }
        return result
    }

    private suspend fun listHandles(
        ctx: CameraActionContext,
        storageId: Long,
        parent: Long,
    ): List<Long> {
        val pair = Opcodes.objectHandles(ctx)
        val skipStandard = ctx.path.isVendorSegment && pair.standard == null
        val params = intArrayOf(storageId.toInt(), 0, parent.toInt())
        val result = PtpExecutor.execute(
            ctx = ctx, standard = pair.standard, vendor = pair.vendor,
            params = params, expectDataIn = true, skipStandard = skipStandard,
        )
        var handles = decodeHandles(result.data)
        if (handles.isEmpty() && parent == PTP_PARENT_ROOT) {
            val fallback = PtpExecutor.execute(
                ctx = ctx, standard = pair.standard, vendor = pair.vendor,
                params = intArrayOf(ALL_STORAGES_SENTINEL.toInt(), 0, PTP_PARENT_ALL.toInt()),
                expectDataIn = true, skipStandard = skipStandard,
            )
            handles = decodeHandles(fallback.data)
        }
        return handles
    }

    private fun decodeHandles(raw: ByteArray?): List<Long> {
        if (raw == null || raw.size < 4) return emptyList()
        return try {
            val buffer = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int.toLong() and 0xFFFFFFFFL
            if (count < 0 || count > 200_000 || (count * 4) + 4 > raw.size) return emptyList()
            ArrayList<Long>(count.toInt()).apply { repeat(count.toInt()) { add(buffer.int.toLong() and 0xFFFFFFFFL) } }
        } catch (_: RuntimeException) { emptyList() }
    }

    // ── Sony CONTENT_LIST path (PTP_USB_SONY_0X92XX) ────────────────────

    private suspend fun listObjectsSonyContentList(
        ctx: CameraActionContext,
        storageId: Long,
        filter: PtpMediaFilter,
    ): List<PtpObject> {
        val targets = if (storageId == ALL_STORAGES_SENTINEL) {
            StoragesAction.execute(ctx).map { it.id }.filter { it != ALL_STORAGES_SENTINEL }
        } else {
            listOf(storageId)
        }
        if (targets.isEmpty()) return emptyList()
        delay(ctx.profile.preListDelayUsbMs)

        val result = ArrayList<PtpObject>(1024)
        for (target in targets) {
            val classByte = (target ushr 24).toInt()
            var cursor = 0L
            var pages = 0
            val seenHandles = mutableSetOf<Long>()
            while (pages < MAX_LIST_PAGES) {
                pages++
                val page = PtpExecutor.execute(
                    ctx = ctx, standard = null, vendor = PtpSony.CONTENT_LIST,
                    params = intArrayOf(cursor.toInt(), (cursor ushr 32).toInt(), PtpSony.CONTENT_LIST_PAGE_SIZE, classByte, 0),
                    expectDataIn = true, skipStandard = true,
                )
                val decoded = SonyContentListDecoderBridge.decodePage(page.data)
                if (decoded.recordCount <= 0 || decoded.entries.isEmpty()) break
                for (entry in decoded.entries) {
                    if (!seenHandles.add(entry.handle)) continue
                    val accepted = filter == PtpMediaFilter.ALL || filter.accepts(entry.format)
                    if (accepted) {
                        result += PtpObject(
                            handle = entry.handle, name = entry.name.ifEmpty { entry.handle.toString() },
                            format = entry.format, sizeBytes = entry.sizeBytes,
                            storageId = target, parent = 0L, hasThumbnail = entry.hasImageBlock,
                        )
                    }
                }
                if (decoded.recordCount < PtpSony.CONTENT_LIST_PAGE_SIZE) break
                cursor = if (decoded.maxCursor == cursor) cursor + 1 else decoded.maxCursor
            }
        }
        return result
    }

    // ── HTTP path (Sony NEX ScalarWebAPI) ───────────────────────────────

    private suspend fun executeHttp(
        ctx: CameraActionContext,
        storageId: Long,
        filter: PtpMediaFilter,
    ): List<PtpObject> {
        ctx.objectUrlCache.clear()

        val storages = StoragesAction.execute(ctx)
        val target = storages.firstOrNull { it.id == storageId } ?: storages.firstOrNull() ?: return emptyList()
        val sourceUri = buildSonySourceUri(target)
        val result = mutableListOf<PtpObject>()
        var start = 0
        val pageSize = 100
        while (true) {
            val response = SonyHttpExecutor.callAvContent(
                ctx = ctx, method = SonyHttp.AvContent.GET_CONTENT_LIST,
                params = listOf(mapOf("uri" to sourceUri, "stIdx" to start, "cnt" to pageSize, "view" to "flat", "sort" to "")),
                version = SonyHttp.VERSION_CONTENT_LIST,
            )
            val items = response.optJSONArray("result")?.optJSONArray(0) ?: break
            for (i in 0 until items.length()) {
                try {
                    val item = items.getJSONObject(i)
                    val handle = SonyHttpContentAdapter.makeSyntheticHandle(storageId, start + i)
                    ctx.objectUrlCache[handle] = SonyHttpContentAdapter.extractUrls(item)
                    val obj = SonyHttpContentAdapter.toPtpObject(item, storageId, handle)
                    val accepted = filter == PtpMediaFilter.ALL || filter.accepts(obj.format)
                    if (accepted) result += obj
                } catch (_: Exception) { /* skip malformed */ }
            }
            start += items.length()
            if (items.length() < pageSize) break
        }
        return result
    }

    private fun buildSonySourceUri(target: io.github.fotlab.fotlab.feature.camera.model.PtpStorage): String {
        val parts = target.description.split(':', limit = 2)
        return if (parts.size == 2) "${parts[0]}:${parts[1]}" else target.description
    }
}
