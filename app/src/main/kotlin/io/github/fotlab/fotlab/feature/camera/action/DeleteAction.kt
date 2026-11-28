package io.github.fotlab.fotlab.feature.camera.action

import io.github.fotlab.fotlab.feature.camera.model.toPtpInt

/**
 * Delete one object from the device.
 *
 * PTP: standard DeleteObject (0x100B) for ISO and most vendor blocks;
 * Sony 0x9250 needs a class byte in its parameter array on top of the handle.
 * Opcodes come from [Opcodes.delete].
 *
 * HTTP: Sony ScalarWebAPI exposes no delete method — NEX users who need
 * this have to plug into PTP/IP if the camera supports both.
 */
object DeleteAction {

    suspend fun execute(ctx: CameraActionContext, handle: Long) = when (ctx.path) {
        TransportPath.HTTP_IP_SONY_NEX -> throw UnsupportedOperationException(
            "Sony HTTP API does not support delete",
        )
        TransportPath.PTP_USB_STD_ISO,
        TransportPath.PTP_IP_STD_ISO,
        TransportPath.PTP_USB_SONY_0X92XX,
        TransportPath.PTP_USB_CANON_0X91XX,
        TransportPath.PTP_IP_CANON_0X91XX,
        TransportPath.PTP_USB_NIKON_0X90XX,
        TransportPath.PTP_IP_NIKON_0X90XX,
        TransportPath.PTP_USB_FUJI_0XD2XX -> executePtp(ctx, handle)
    }

    private suspend fun executePtp(ctx: CameraActionContext, handle: Long) {
        val pair = Opcodes.delete(ctx)
        val skipStandard = ctx.path.isVendorSegment && pair.standard == null

        // Sony 0x9250 needs a class byte after the handle — standard shape is (handle) only.
        val params = when (ctx.path) {
            TransportPath.PTP_USB_SONY_0X92XX -> intArrayOf(
                (handle and 0x00FFFFFFL).toPtpInt(),
                1, // still-image class
            )
            else -> intArrayOf(handle.toPtpInt())
        }

        PtpExecutor.executeNoRetry(
            ctx = ctx, standard = pair.standard, vendor = pair.vendor,
            params = params, expectDataIn = false, skipStandard = skipStandard,
        )
    }
}
