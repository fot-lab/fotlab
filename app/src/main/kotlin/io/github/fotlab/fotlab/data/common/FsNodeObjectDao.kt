package io.github.fotlab.fotlab.data.common

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FsNodeObjectDao {

    @Insert
    suspend fun insert(node: FsNodeObject): Long

    @Update
    suspend fun update(node: FsNodeObject)

    /** Full row including `time_deleted`; used by the delete routine to detect an already-removed node. */
    @Query("SELECT * FROM fs_node_object WHERE fs_node_id = :id")
    suspend fun getById(id: Long): FsNodeObject?

    /** Used to honour the UNIQUE `uri_storage` index and avoid duplicate files (live nodes only). */
    @Query("SELECT * FROM fs_node_object WHERE uri_storage = :uri AND time_deleted IS NULL")
    suspend fun getByUri(uri: String): FsNodeObject?

    /**
     * Create the root node row when it is missing. Idempotent, so it is safe to call on every
     * process start: the `fs_node_id` primary key turns a second call into a no-op rather than
     * a constraint failure.
     */
    @Query(
        "INSERT OR IGNORE INTO fs_node_object " +
            "(fs_node_id, name_display, type_mime, uri_storage, time_modified, time_created, time_deleted) " +
            "VALUES (:rootId, :name, :typeMime, NULL, NULL, :timeCreated, NULL)",
    )
    suspend fun insertRootIfAbsent(rootId: Long, name: String, typeMime: String, timeCreated: Long)

    /**
     * Find any node with this URI regardless of soft-delete status. Used by `importUris` to
     * revive a soft-deleted node when the same file is re-imported, instead of hitting the
     * UNIQUE index on `uri_storage` with a duplicate INSERT.
     */
    @Query("SELECT * FROM fs_node_object WHERE uri_storage = :uri ORDER BY time_deleted IS NULL DESC LIMIT 1")
    suspend fun getByUriAnyStatus(uri: String): FsNodeObject?

    /** Clear the soft-delete stamp so a previously removed node becomes live again. */
    @Query("UPDATE fs_node_object SET time_deleted = NULL, name_display = :nameDisplay, type_mime = :typeMime WHERE fs_node_id = :id")
    suspend fun revive(id: Long, nameDisplay: String, typeMime: String)

    /** Rename a node in place (R-name only); selection rename path. */
    @Query("UPDATE fs_node_object SET name_display = :name WHERE fs_node_id = :id")
    suspend fun rename(id: Long, name: String)

    /**
     * Every live user collection, ordered by name. The root is excluded by its own MIME kind.
     */
    @Query(
        "SELECT * FROM fs_node_object WHERE type_mime = 'application/folder' " +
            "AND time_deleted IS NULL ORDER BY name_display",
    )
    fun observeCollections(): Flow<List<FsNodeObject>>

    /**
     * All live file-entry nodes — what the refresh reconcile treats as a real file.
     */
    @Query(
        "SELECT * FROM fs_node_object WHERE time_deleted IS NULL " +
            "AND type_mime <> :folderMime AND type_mime <> :rootMime",
    )
    suspend fun fileEntryNodes(folderMime: String, rootMime: String): List<FsNodeObject>

    /**
     * The ids of [ids] that are **not** the root node kind.
     */
    @Query(
        "SELECT fs_node_id FROM fs_node_object " +
            "WHERE fs_node_id IN (:ids) AND type_mime <> :rootMime",
    )
    suspend fun idsExcludingRootKind(ids: List<Long>, rootMime: String): List<Long>

    /** Distinct soft-delete timestamps, newest first — one virtual batch folder per value. */
    @Query(
        "SELECT DISTINCT time_deleted FROM fs_node_object " +
            "WHERE time_deleted IS NOT NULL ORDER BY time_deleted DESC",
    )
    fun deletedBatchTimes(): Flow<List<Long>>

    /** All nodes soft-deleted in the batch stamped at [time] (the batch's content). */
    @Query(
        "SELECT * FROM fs_node_object " +
            "WHERE time_deleted = :time ORDER BY time_deleted DESC, name_display ASC",
    )
    fun deletedNodesAt(time: Long): Flow<List<FsNodeObject>>

    /** Just the ids of every node in the soft-delete batch stamped at [time]. */
    @Query("SELECT fs_node_id FROM fs_node_object WHERE time_deleted = :time AND fs_node_id IS NOT NULL")
    suspend fun deletedNodeIdsAt(time: Long): List<Long>

    /**
     * Soft-delete: stamp `time_deleted` instead of dropping the row.
     */
    @Query("UPDATE fs_node_object SET time_deleted = :timeDeleted WHERE fs_node_id = :id")
    suspend fun markDeleted(id: Long, timeDeleted: Long)

    /**
     * Restore a whole delete batch from the recycle bin: clear the soft-delete stamp on every node
     * stamped with [time] so the entire batch becomes live again (batch-level restore).
     */
    @Query("UPDATE fs_node_object SET time_deleted = NULL WHERE time_deleted = :time")
    suspend fun restoreNodesByBatch(time: Long)

    /** Permanently remove nodes from the bin — a real delete, not a soft-delete (`delete forever`). */
    @Query("DELETE FROM fs_node_object WHERE fs_node_id IN (:ids)")
    suspend fun deleteNodesForever(ids: List<Long>)
}
