package io.github.fotlab.fotlab.feature.camera

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One queued camera transfer (`FOTLAB-DATABS-000004`).
 *
 * The camera feature owns this table in its own database ([CameraDatabase]), so the library's
 * `fs_node` schema is untouched.
 *
 * **The two timestamp columns hold text, not numbers.** A row's life cycle is written into
 * `timestamp_itemfinish`: `NULL` means the item is still queued, a decimal string is the
 * millisecond timestamp at which it finished, and a word such as `paused`, `canceled` or
 * `failed` is the state the item was left in. The same flexibility applies to
 * `timestamp_batchadd`, which groups the rows of one user action into a batch.
 *
 * `transfer_direction` is `cameraTOphone` or `phoneTOcamera`. The three columns
 * `transfer_direction` + `uri_camera` + `uri_phone` form the queue's identity: they carry a
 * unique index, so the same transfer cannot be queued twice. `uri_camera` is the camera-side
 * object and always ends with the file name, which is what the queue list shows.
 */
@Entity(
    tableName = "camera_file_queue",
    indices = [
        Index(value = ["transfer_direction", "uri_camera", "uri_phone"], unique = true),
        Index(value = ["timestamp_itemfinish"]),
    ],
)
data class CameraFileQueue(
    @PrimaryKey @ColumnInfo(name = "queue_id") val queueId: Long? = null,
    /** Groups the rows of one user action; a decimal millisecond timestamp, or a state word. */
    @ColumnInfo(name = "timestamp_batchadd") val timestampBatchadd: String,
    /** `NULL` while queued; a millisecond timestamp, or a state word, once it left the queue. */
    @ColumnInfo(name = "timestamp_itemfinish") val timestampItemfinish: String? = null,
    /** `cameraTOphone` or `phoneTOcamera`. */
    @ColumnInfo(name = "transfer_direction") val transferDirection: String,
    /** The camera-side object; its last path segment is the file name. */
    @ColumnInfo(name = "uri_camera") val uriCamera: String,
    /** The phone-side target or source. */
    @ColumnInfo(name = "uri_phone") val uriPhone: String,
)
