package io.github.fotlab.fotlab.feature.camera.action

/**
 * Create a subfolder on the device (send-object with format=ASSOCIATION).
 *
 * Only standard ISO 15740 (0x1002 with PTP_FORM_ASSOCIATION) is universally
 * supported. Sony 0x92xx vendor block and Sony HTTP have no mkdir API.
 */
object CreateFolderAction {

    private const val PTP_FORM_ASSOCIATION: Long = 0x3001L

    suspend fun execute(
        ctx: CameraActionContext,
        storageId: Long,
        parent: Long,
        name: String,
    ): Long = when (ctx.path) {
        TransportPath.HTTP_IP_SONY_NEX -> throw UnsupportedOperationException(
            "Sony HTTP API does not support folder creation",
        )
        TransportPath.PTP_USB_SONY_0X92XX -> throw UnsupportedOperationException(
            "Sony 0x92xx has no folder creation API",
        )
        in TransportPath.values().filter { it.isPtp } -> {
            val session = ctx.ptpSession ?: throw java.io.IOException("No PTP session")
            val result = session.execute(
                code = io.github.fotlab.fotlab.feature.camera.protocol.opcode.PtpStd.SEND_OBJECT,
                params = intArrayOf(storageId.toInt(), parent.toInt(), PTP_FORM_ASSOCIATION.toInt()),
                dataOut = null, expectDataIn = false,
            )
            result.phase2ObjectHandle ?: -1L
        }
    }
}
