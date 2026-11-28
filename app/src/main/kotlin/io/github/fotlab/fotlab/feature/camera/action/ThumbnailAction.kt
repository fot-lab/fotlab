package io.github.fotlab.fotlab.feature.camera.action

import java.io.IOException

/**
 * Thumbnail bytes for one object.
 *
 * PTP paths use GetThumb (0x100A) with vendor overrides pulled from [Opcodes.thumbnail].
 * HTTP path pulls the thumbnailUrl from [ObjectUrlCache] — every Sony
 * getContentList item carries it inline, filled during [ListObjectsAction.executeHttp].
 */
object ThumbnailAction {

    suspend fun execute(ctx: CameraActionContext, handle: Long): ByteArray = when (ctx.path) {
        TransportPath.HTTP_IP_SONY_NEX -> executeHttp(ctx, handle)
        in TransportPath.values().filter { it.isPtp } -> executePtp(ctx, handle)
    }

    private suspend fun executePtp(ctx: CameraActionContext, handle: Long): ByteArray {
        val pair = Opcodes.thumbnail(ctx)
        val skipStandard = ctx.path.isVendorSegment && pair.standard == null
        val result = PtpExecutor.executeNoRetry(
            ctx = ctx, standard = pair.standard, vendor = pair.vendor,
            params = intArrayOf(handle.toInt()), expectDataIn = true, skipStandard = skipStandard,
        )
        return result.data ?: throw IOException("Device returned no thumbnail for $handle")
    }

    private suspend fun executeHttp(ctx: CameraActionContext, handle: Long): ByteArray {
        val urls = ctx.objectUrlCache[handle]
            ?: throw IllegalStateException(
                "No URL cache entry for handle $handle — list objects before fetching thumbnails",
            )
        val url = urls.thumbnailUrl
            ?: throw IOException("Sony HTTP object $handle has no thumbnailUrl")
        return SonyHttpExecutor.download(ctx, url)
    }
}
