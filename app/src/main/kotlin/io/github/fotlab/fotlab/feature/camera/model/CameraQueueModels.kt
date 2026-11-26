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
 * The camera side is a `ptp://` URI: its last segment is the file name, which is what the
 * queue list shows, and the segments before it address the object on the device. The phone
 * side is the local URI — the destination folder plus file name for a download, the source
 * document for an upload.
 */
object CameraQueueUri {

    private const val SCHEME = "ptp://"

    /** The camera-side URI of an object that is being read from the device. */
    fun cameraObject(handle: Long, fileName: String): String = "$SCHEME$handle/$fileName"

    /** The camera-side URI of the place an upload writes to. */
    fun cameraTarget(storageId: Long, parent: Long, fileName: String): String =
        "$SCHEME$storageId/$parent/$fileName"

    /** The phone-side URI of a download's destination: the folder plus the file name. */
    fun phoneTarget(folderUri: String, fileName: String): String =
        "${folderUri.trimEnd('/')}/$fileName"

    /** The decoded camera side of a queued transfer. */
    data class CameraRef(
        /** The object handle on the device; the parent handle for an upload target. */
        val handle: Long,
        val storageId: Long,
        val fileName: String,
    )

    /**
     * The camera side of [uri].
     *
     * The scheme is dropped and the body is split into its segments. Two segments are an
     * object on the device — the handle and the file name; three segments are the place an
     * upload writes to — the storage, the parent folder and the file name.
     */
    fun parseCamera(uri: String): CameraRef? {
        if (!uri.startsWith(SCHEME)) return null
        val segments = uri.removePrefix(SCHEME).split('/')
        return when (segments.size) {
            2 -> {
                val handle = segments[0].toLongOrNull() ?: return null
                val name = segments[1]
                if (name.isEmpty()) null else CameraRef(handle = handle, storageId = 0L, fileName = name)
            }
            3 -> {
                val storageId = segments[0].toLongOrNull() ?: return null
                val parent = segments[1].toLongOrNull() ?: return null
                val name = segments[2]
                if (name.isEmpty()) {
                    null
                } else {
                    CameraRef(handle = parent, storageId = storageId, fileName = name)
                }
            }
            else -> null
        }
    }
}
