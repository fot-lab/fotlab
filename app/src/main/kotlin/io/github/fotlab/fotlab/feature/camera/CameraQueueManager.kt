package io.github.fotlab.fotlab.feature.camera

import android.content.Context
import androidx.room.Room
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueBatch
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueItem
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueStates
import io.github.fotlab.fotlab.feature.camera.model.CameraTransferDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The camera transfer queue (`FOTLAB-DATABS-000004`).
 *
 * The queue is a near-stateless machine: **everything the user can observe lives in the
 * `camera_file_queue` table**, and only two things are held in memory.
 *
 * - The **running** item — the transfer currently in flight. It is not in the table, because
 *   "running" is a property of this process, not of the transfer.
 * - The **table handle** itself.
 *
 * Every other state is a row:
 *
 * | `timestamp_itemfinish` | Meaning |
 * | --- | --- |
 * | `NULL` | queued, not started |
 * | a decimal millisecond timestamp | finished at that moment |
 * | `paused` / `canceled` / `failed` | stopped in that state |
 *
 * The table is the queue's memory, so a row survives the process, the screen and the camera
 * link: a transfer interrupted half-way is still queued, and the queue picks it up again the
 * next time a camera is connected.
 */
class CameraQueueManager(context: Context) {

    private val database: CameraDatabase = Room.databaseBuilder(
        context.applicationContext,
        CameraDatabase::class.java,
        "camera",
    ).build()

    private val repository = CameraQueueRepository(database)

    /**
     * The transfer currently in flight.
     *
     * `null` when nothing is running. The row behind it stays in the table with a `NULL`
     * finish stamp until it completes or stops.
     */
    private val _running = MutableStateFlow<CameraQueueItem?>(null)
    val running: StateFlow<CameraQueueItem?> = _running.asStateFlow()

    /** The batches, newest first — the cards the queue list renders. */
    fun observeBatches(): Flow<List<CameraQueueBatch>> = repository.observeBatches()

    /** Every row, newest batch first. */
    fun observeAll(): Flow<List<CameraQueueItem>> = repository.observeAll()

    /**
     * The rows that are queued and not finished.
     *
     * These are what the transfer service picks up: the queue is the driver, not the
     * screen's selection.
     */
    suspend fun pending(): List<CameraQueueItem> = repository.pending()

    /**
     * Queues transfers, after checking each of them against what is already queued.
     *
     * The identity of a transfer is its direction plus both URIs. A candidate that matches an
     * existing row on all three is a duplicate: the caller decides whether to overwrite it
     * (delete the old row, queue a fresh one) or to leave it alone.
     *
     * @return the candidates whose identity already existed.
     */
    suspend fun findDuplicates(candidates: List<QueueEntry>): List<QueueEntry> {
        val duplicates = mutableListOf<QueueEntry>()
        for (candidate in candidates) {
            val existing = repository.find(candidate.direction, candidate.uriCamera, candidate.uriPhone)
            if (existing != null) {
                duplicates += candidate
            }
        }
        return duplicates
    }

    /**
     * Appends transfers to the queue.
     *
     * Every entry of one user action shares a batch key, so the queue list groups them into
     * one card.
     */
    suspend fun enqueue(batchAdd: String, entries: List<QueueEntry>): List<Long> =
        repository.enqueueAll(
            batchAdd,
            entries.map { entry ->
                CameraQueueRepository.QueueEntry(
                    direction = entry.direction,
                    uriCamera = entry.uriCamera,
                    uriPhone = entry.uriPhone,
                )
            },
        )

    /**
     * Removes the rows that carry the given transfers' identity, then queues them again.
     *
     * This is what "overwrite" means: the old row goes, a fresh one takes its place.
     */
    suspend fun overwrite(batchAdd: String, entries: List<QueueEntry>): List<Long> {
        for (entry in entries) {
            repository.remove(entry.direction, entry.uriCamera, entry.uriPhone)
        }
        return enqueue(batchAdd, entries)
    }

    /** Removes a whole batch. */
    suspend fun removeBatch(batch: String) {
        repository.removeBatch(batch)
    }

    /**
     * Records how one item left the queue.
     *
     * [state] is a millisecond timestamp when the transfer completed, or a state word such as
     * `paused`, `canceled` or `failed`.
     */
    suspend fun markFinished(queueId: Long, state: String) {
        repository.markFinished(queueId, state)
    }

    /** Marks the transfer in flight. */
    fun setRunning(item: CameraQueueItem?) {
        _running.value = item
    }

    // --- The user's controls over the queue -------------------------------------

    /**
     * Pauses the given items.
     *
     * A paused row keeps its place in the table and carries the word `paused` instead of a
     * finish stamp, so the user can see that it was held back on purpose — and a link that
     * later fails does not touch it.
     */
    suspend fun pause(ids: List<Long>) {
        repository.markStates(ids, CameraQueueStates.PAUSED)
    }

    /** Cancels the given items: their finish stamp becomes `canceled`. */
    suspend fun cancel(ids: List<Long>) {
        repository.markStates(ids, CameraQueueStates.CANCELED)
    }

    /**
     * Puts the given items back into the queue.
     *
     * The finish stamp is cleared, so the rows are waiting again and the next run picks them
     * up. This is the user's way of resuming a batch that a dead link had failed.
     */
    suspend fun resume(ids: List<Long>) {
        repository.clearFinished(ids)
    }

    /** Removes the given items from the queue. */
    suspend fun removeItems(ids: List<Long>) {
        repository.removeByIds(ids)
    }

    /**
     * Marks every row that is still queued as failed.
     *
     * This is the end of the road for a link that could not be recovered: the rows that
     * never ran are stamped `failed` rather than waiting forever for a camera that is not
     * coming back. The user still decides whether to resume them — which is also how a
     * batch built on one camera is carried over to another.
     */
    suspend fun failUnfinished() {
        repository.markUnfinished(CameraQueueStates.FAILED)
    }

    /** One queued transfer. */
    data class QueueEntry(
        val direction: CameraTransferDirection,
        val uriCamera: String,
        val uriPhone: String,
    )
}
