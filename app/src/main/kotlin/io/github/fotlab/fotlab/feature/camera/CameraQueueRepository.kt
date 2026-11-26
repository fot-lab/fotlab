package io.github.fotlab.fotlab.feature.camera

import io.github.fotlab.fotlab.feature.camera.model.CameraQueueBatch
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueItem
import io.github.fotlab.fotlab.feature.camera.model.CameraTransferDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Lower-layer access to the camera transfer queue (`FOTLAB-DATABS-000004`).
 *
 * The feature owns the table in its own database (`CameraDatabase`); the library's `fs_node`
 * schema is untouched. The UI reaches the queue through [CameraCore] and never touches the
 * DAO, the entity or the database type directly (`FOTLAB-DATABS-000001` R2/C2) — everything
 * above this layer works with [CameraQueueItem] and [CameraQueueBatch].
 *
 * Reads return [Flow]; writes are `suspend`.
 */
class CameraQueueRepository(private val database: CameraDatabase) {

    private fun dao() = database.cameraFileQueueDao()

    /**
     * Every batch, newest first — the cards the queue list renders.
     *
     * The grouping happens here rather than in the DAO: one query returns every row, and the
     * rows of one batch are the ones sharing a `timestamp_batchadd`.
     */
    fun observeBatches(): Flow<List<CameraQueueBatch>> =
        dao().observeAll().map { rows ->
            rows.groupBy { row -> row.timestampBatchadd }
                .map { (batch, batchRows) ->
                    CameraQueueBatch(batch, batchRows.map { row -> row.toItem() })
                }
                .sortedByDescending { batch -> batch.batchAdd.toLongOrNull() ?: 0L }
        }

    /** All rows, newest batch first. */
    fun observeAll(): Flow<List<CameraQueueItem>> =
        dao().observeAll().map { rows -> rows.map { row -> row.toItem() } }

    /** The rows that are queued and not finished, oldest batch first. */
    suspend fun pending(): List<CameraQueueItem> =
        dao().pending().map { row -> row.toItem() }

    /**
     * The row that already carries this transfer's identity, or `null` when the transfer was
     * never queued.
     *
     * The identity is the direction plus both URIs: the same file going to the same place is
     * the same transfer, whatever else the table holds.
     */
    suspend fun find(
        direction: CameraTransferDirection,
        uriCamera: String,
        uriPhone: String,
    ): CameraQueueItem? = dao().find(direction.stored, uriCamera, uriPhone)?.toItem()

    /**
     * Queues one transfer.
     *
     * The row is replaced when the identity already exists, so re-queueing a transfer the
     * user confirmed to overwrite does not leave the old row behind.
     */
    suspend fun enqueue(
        batchAdd: String,
        direction: CameraTransferDirection,
        uriCamera: String,
        uriPhone: String,
    ): Long = dao().insert(
        CameraFileQueue(
            timestampBatchadd = batchAdd,
            timestampItemfinish = null,
            transferDirection = direction.stored,
            uriCamera = uriCamera,
            uriPhone = uriPhone,
        ),
    )

    /** Queues a batch of transfers; every row shares the batch key, so they render as one card. */
    suspend fun enqueueAll(
        batchAdd: String,
        items: List<QueueEntry>,
    ): List<Long> = dao().insertAll(
        items.map { entry ->
            CameraFileQueue(
                timestampBatchadd = batchAdd,
                timestampItemfinish = null,
                transferDirection = entry.direction.stored,
                uriCamera = entry.uriCamera,
                uriPhone = entry.uriPhone,
            )
        },
    )

    /** Removes the row that carries this transfer's identity. */
    suspend fun remove(
        direction: CameraTransferDirection,
        uriCamera: String,
        uriPhone: String,
    ) {
        dao().delete(direction.stored, uriCamera, uriPhone)
    }

    /** Removes a whole batch. */
    suspend fun removeBatch(batch: String) {
        dao().deleteBatch(batch)
    }

    /**
     * Records how one item left the queue.
     *
     * [state] is written verbatim: a decimal millisecond timestamp when the transfer
     * completed, or a state word such as `paused`, `canceled` or `failed`.
     */
    suspend fun markFinished(queueId: Long, state: String) {
        dao().markFinished(queueId, state)
    }

    /** One queued transfer, as the repository takes it. */
    data class QueueEntry(
        val direction: CameraTransferDirection,
        val uriCamera: String,
        val uriPhone: String,
    )

    private fun CameraFileQueue.toItem(): CameraQueueItem = CameraQueueItem(
        id = queueId ?: 0L,
        batchAdd = timestampBatchadd,
        itemFinish = timestampItemfinish,
        direction = CameraTransferDirection.fromStored(transferDirection)
            ?: CameraTransferDirection.CAMERA_TO_PHONE,
        uriCamera = uriCamera,
        uriPhone = uriPhone,
    )
}
