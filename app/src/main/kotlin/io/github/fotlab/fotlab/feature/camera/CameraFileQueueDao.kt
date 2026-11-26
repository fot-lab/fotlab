package io.github.fotlab.fotlab.feature.camera

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The camera transfer queue's DAO (`FOTLAB-DATABS-000004`).
 *
 * It is deliberately a file of its own: the queue is a self-contained concern and does not
 * share a DAO with anything else, here or in the library feature.
 *
 * Reads return [Flow] so the queue list reacts to changes; writes are `suspend`
 * (`FOTLAB-DATABS-000001` R4).
 */
@Dao
interface CameraFileQueueDao {

    /** Every queued row, newest batch first. */
    @Query("SELECT * FROM camera_file_queue ORDER BY timestamp_batchadd DESC, queue_id ASC")
    fun observeAll(): Flow<List<CameraFileQueue>>

    /** The rows of one batch, in insertion order. */
    @Query(
        "SELECT * FROM camera_file_queue WHERE timestamp_batchadd = :batch " +
            "ORDER BY queue_id ASC",
    )
    fun observeBatch(batch: String): Flow<List<CameraFileQueue>>

    /** Distinct batch keys, newest first — the groups the queue list renders as cards. */
    @Query("SELECT DISTINCT timestamp_batchadd FROM camera_file_queue ORDER BY timestamp_batchadd DESC")
    fun observeBatches(): Flow<List<String>>

    /**
     * The rows that are queued and not finished, oldest batch first.
     *
     * A row is finished when its `timestamp_itemfinish` holds anything — a millisecond
     * timestamp or a state word — so an unfinished row is exactly one that still has to run.
     */
    @Query(
        "SELECT * FROM camera_file_queue WHERE timestamp_itemfinish IS NULL " +
            "ORDER BY timestamp_batchadd ASC, queue_id ASC",
    )
    suspend fun pending(): List<CameraFileQueue>

    /** A row by its queue key, or `null` when the transfer was never queued. */
    @Query(
        "SELECT * FROM camera_file_queue " +
            "WHERE transfer_direction = :direction AND uri_camera = :uriCamera AND uri_phone = :uriPhone " +
            "LIMIT 1",
    )
    suspend fun find(
        direction: String,
        uriCamera: String,
        uriPhone: String,
    ): CameraFileQueue?

    /** Queues one transfer, replacing any row that carries the same key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: CameraFileQueue): Long

    /** Queues a batch of transfers; every row of one user action shares the batch key. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CameraFileQueue>): List<Long>

    /** Removes the rows of one batch. */
    @Query("DELETE FROM camera_file_queue WHERE timestamp_batchadd = :batch")
    suspend fun deleteBatch(batch: String)

    /** Removes one row by its queue key. */
    @Query(
        "DELETE FROM camera_file_queue " +
            "WHERE transfer_direction = :direction AND uri_camera = :uriCamera AND uri_phone = :uriPhone",
    )
    suspend fun delete(
        direction: String,
        uriCamera: String,
        uriPhone: String,
    )

    @Delete
    suspend fun deleteRow(row: CameraFileQueue)

    /**
     * Records how one item left the queue.
     *
     * [state] is written verbatim into `timestamp_itemfinish`, so it may be a millisecond
     * timestamp or a state word such as `paused` or `canceled`.
     */
    @Query("UPDATE camera_file_queue SET timestamp_itemfinish = :state WHERE queue_id = :queueId")
    suspend fun markFinished(queueId: Long, state: String)

    /**
     * Marks the given rows with the given state.
     *
     * The rows are addressed by their queue key, which is how a multi-selection acts on
     * exactly the items the user picked.
     */
    @Query("UPDATE camera_file_queue SET timestamp_itemfinish = :state WHERE queue_id IN (:queueIds)")
    suspend fun markStates(queueIds: List<Long>, state: String)

    /**
     * Marks every row that is still queued with the given state.
     *
     * This is what a link that could not be recovered does: the rows that never ran are
     * stamped `failed`, and the user decides afterwards whether to put them back.
     */
    @Query(
        "UPDATE camera_file_queue SET timestamp_itemfinish = :state " +
            "WHERE timestamp_itemfinish IS NULL",
    )
    suspend fun markUnfinished(state: String)

    /**
     * Puts the given rows back into the queue.
     *
     * The finish stamp is cleared, which is exactly what "resume" means: the row is waiting
     * again, and the next run picks it up.
     */
    @Query(
        "UPDATE camera_file_queue SET timestamp_itemfinish = NULL " +
            "WHERE queue_id IN (:queueIds)",
    )
    suspend fun clearFinished(queueIds: List<Long>)

    /** Removes the given rows. */
    @Query("DELETE FROM camera_file_queue WHERE queue_id IN (:queueIds)")
    suspend fun deleteByIds(queueIds: List<Long>)
}
