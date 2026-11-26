package io.github.fotlab.fotlab.feature.camera.model

/**
 * Which way a queued transfer moves.
 *
 * The stored value is the wire form the `camera_file_queue` table carries
 * (`FOTLAB-DATABS-000004`); the enum exists so the rest of the feature never writes the
 * literal by hand.
 */
enum class CameraTransferDirection(val stored: String) {
    /** From the camera to the phone. */
    CAMERA_TO_PHONE("cameraTOphone"),

    /** From the phone to the camera. */
    PHONE_TO_CAMERA("phoneTOcamera"),
    ;

    companion object {
        /** The direction a stored value names, or `null` when the value is unknown. */
        fun fromStored(stored: String): CameraTransferDirection? =
            entries.firstOrNull { it.stored == stored }
    }
}

/**
 * One queued transfer, as the feature's UI sees it.
 *
 * The Room entity is deliberately not exposed: the UI works with this value type, and the
 * repository maps between the two (`FOTLAB-DATABS-000001` R2/C2).
 *
 * [itemFinish] carries the same flexible text the table column does: `null` while the item
 * is still queued, a decimal millisecond timestamp once it finished, or a state word such as
 * `paused`, `canceled` or `failed`.
 */
data class CameraQueueItem(
    val id: Long,
    /** Groups the items of one user action into a card. */
    val batchAdd: String,
    val itemFinish: String?,
    val direction: CameraTransferDirection,
    val uriCamera: String,
    val uriPhone: String,
) {
    /** The file name, taken from the last segment of the camera-side URI. */
    val fileName: String get() = uriCamera.substringAfterLast('/')

    /** Whether the item left the queue — finished, or stopped in a named state. */
    val isFinished: Boolean get() = itemFinish != null

    /** The state word, when the item was stopped rather than completed. */
    val stopState: String?
        get() = itemFinish?.takeIf { !it.all { char -> char.isDigit() } }
}

/**
 * The items of one batch, as the queue list renders them.
 *
 * A card is one batch: the rows sharing a `timestamp_batchadd`.
 */
data class CameraQueueBatch(
    val batchAdd: String,
    val items: List<CameraQueueItem>,
) {
    val finishedCount: Int get() = items.count { it.isFinished }
}

/**
 * The state words a queue row may carry instead of a timestamp.
 *
 * The table's timestamp columns are text for exactly this reason: a row records both *when*
 * something happened and *what* happened to it, and the two cannot share one numeric column.
 */
object CameraQueueStates {
    /** The transfer is paused and can be picked up again. */
    const val PAUSED = "paused"

    /** The transfer was cancelled. */
    const val CANCELED = "canceled"

    /** The transfer failed. */
    const val FAILED = "failed"

    /** The current time as the decimal millisecond timestamp the columns carry. */
    fun now(): String = System.currentTimeMillis().toString()
}

/**
 * Encodes and decodes the two URIs a queued transfer is identified by.
 *
 * The camera side is a `ptp://` URI whose **last segment is the file name** — which is what
 * the queue list shows, since the list shows the file name only, not the full URI.
 *
 * Two shapes exist, told apart by the number of segments:
 *
 * - `ptp://<storageId>/<parent>/<handle>/<name>` — an object that is read from the device.
 * - `ptp://<storageId>/<parent>/<name>` — the place an upload writes to.
 *
 * The download shape carries the storage and the folder as well as the handle, and that is
 * deliberate: **a PTP handle is a device-local number**, so a row queued on one camera
 * cannot be transferred to another by its handle alone. With the folder and the file name
 * in the row, the transfer can be re-resolved against whichever camera is connected now
 * (`FOTLAB-DATABS-000004` R5).
 *
 * The phone side is the local URI — the destination folder plus file name for a download,
 * the source document for an upload.
 */
object CameraQueueUri {

    private const val SCHEME = "ptp://"

    /** The camera-side URI of an object that is being read from the device. */
    fun cameraObject(
        storageId: Long,
        parent: Long,
        handle: Long,
        fileName: String,
    ): String = "$SCHEME$storageId/$parent/$handle/$fileName"

    /** The camera-side URI of the place an upload writes to. */
    fun cameraTarget(storageId: Long, parent: Long, fileName: String): String =
        "$SCHEME$storageId/$parent/$fileName"

    /** The phone-side URI of a download's destination: the folder plus the file name. */
    fun phoneTarget(folderUri: String, fileName: String): String =
        "${folderUri.trimEnd('/')}/$fileName"

    /**
     * The decoded camera side of a queued transfer.
     *
     * @param handle the object handle on the device; the parent handle for an upload target.
     * @param storageId the storage the object lives on.
     * @param parent the folder the object lives in.
     */
    data class CameraRef(
        val handle: Long,
        val storageId: Long,
        val parent: Long,
        val fileName: String,
    )

    /**
     * The camera side of [uri].
     *
     * The scheme is dropped and the body is split into its segments: three segments are an
     * upload target (storage, parent, name), four are an object (storage, parent, handle,
     * name).
     */
    fun parseCamera(uri: String): CameraRef? {
        if (!uri.startsWith(SCHEME)) return null
        val segments = uri.removePrefix(SCHEME).split('/')
        return when (segments.size) {
            3 -> {
                val storageId = segments[0].toLongOrNull() ?: return null
                val parent = segments[1].toLongOrNull() ?: return null
                val name = segments[2]
                if (name.isEmpty()) {
                    null
                } else {
                    CameraRef(handle = parent, storageId = storageId, parent = parent, fileName = name)
                }
            }
            4 -> {
                val storageId = segments[0].toLongOrNull() ?: return null
                val parent = segments[1].toLongOrNull() ?: return null
                val handle = segments[2].toLongOrNull() ?: return null
                val name = segments[3]
                if (name.isEmpty()) {
                    null
                } else {
                    CameraRef(handle = handle, storageId = storageId, parent = parent, fileName = name)
                }
            }
            else -> null
        }
    }
}
