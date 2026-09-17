package io.github.fotlab.fotlab.feature.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FsNodeRelationDao {

    /**
     * IGNORE keeps the "same edge cannot be inserted twice" guarantee of the composite
     * primary key usable from code: re-linking an existing child/parent pair is a no-op
     * instead of an abort (`FOTLAB-DATABS-000002` R3). It also covers the root case,
     * where a `NULL` parent cannot be matched with `=` in a query.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(relation: FsNodeRelation)

    /**
     * Clear the soft-delete stamp on an existing dead edge between this child/parent pair, so a
     * re-imported node is reattached where it used to live. Without this the dead row's primary
     * key makes the IGNORE [insert] a no-op and the revived node stays an orphan (its live node
     * row JOINs no live edge, so the grid/directory never lists it).
     */
    @Query(
        "UPDATE fs_node_relation SET time_deleted = NULL " +
            "WHERE fs_node_id_child = :childId AND fs_node_id_parent = :parentId " +
            "AND time_deleted IS NOT NULL",
    )
    suspend fun reviveRelation(childId: Long, parentId: Long)

    /** Root-edge variant of [reviveRelation]: `= NULL` never matches, so the NULL parent needs `IS NULL`. */
    @Query(
        "UPDATE fs_node_relation SET time_deleted = NULL " +
            "WHERE fs_node_id_child = :childId AND fs_node_id_parent IS NULL " +
            "AND time_deleted IS NOT NULL",
    )
    suspend fun reviveRootLink(childId: Long)

    /**
     * Insert a root edge (`NULL` parent) only when the child has no live root edge yet.
     * The UNIQUE index cannot deduplicate `(child, NULL)` rows because SQLite treats
     * `NULL`s as distinct, so a plain `OnConflictStrategy.IGNORE` insert would let a
     * re-imported root file be listed twice by [rootChildren] (verified by the
     * `pngImportAndVirtualMapping` instrumented test). This guard makes the root link
     * idempotent (`FOTLAB-DATABS-000002` R3).
     */
    @Query(
        "INSERT INTO fs_node_relation (fs_node_id_child, fs_node_id_parent, time_deleted) " +
            "SELECT :childId, NULL, NULL " +
            "WHERE NOT EXISTS (" +
            "SELECT 1 FROM fs_node_relation WHERE fs_node_id_child = :childId " +
            "AND fs_node_id_parent IS NULL)",
    )
    suspend fun insertRootLinkIfAbsent(childId: Long)

    @Update
    suspend fun update(relation: FsNodeRelation)

    /** Children of a non-root parent (high-frequency: loading a directory). */
    @Query(
        "SELECT child.* FROM fs_node_object AS child " +
            "JOIN fs_node_relation AS r ON child.fs_node_id = r.fs_node_id_child " +
            "WHERE r.fs_node_id_parent = :parentId AND r.time_deleted IS NULL " +
            "AND child.time_deleted IS NULL " +
            "ORDER BY child.time_created",
    )
    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>>

    /** Children of the implicit root (rows whose parent is NULL). */
    @Query(
        "SELECT child.* FROM fs_node_object AS child " +
            "JOIN fs_node_relation AS r ON child.fs_node_id = r.fs_node_id_child " +
            "WHERE r.fs_node_id_parent IS NULL AND r.time_deleted IS NULL " +
            "AND child.time_deleted IS NULL " +
            "ORDER BY child.time_created",
    )
    fun rootChildren(): Flow<List<FsNodeObject>>

    /** All parents of a node (high-frequency: which collections contain a file). */
    @Query(
        "SELECT parent.* FROM fs_node_object AS parent " +
            "JOIN fs_node_relation AS r ON parent.fs_node_id = r.fs_node_id_parent " +
            "WHERE r.fs_node_id_child = :childId AND r.time_deleted IS NULL " +
            "AND parent.time_deleted IS NULL " +
            "ORDER BY parent.name_display",
    )
    fun parentsOf(childId: Long): Flow<List<FsNodeObject>>

    @Query(
        "SELECT * FROM fs_node_relation " +
            "WHERE fs_node_id_child = :childId AND fs_node_id_parent = :parentId " +
            "AND time_deleted IS NULL",
    )
    suspend fun getRelation(childId: Long, parentId: Long?): FsNodeRelation?

    /**
     * Unlink a child from a parent by soft-deleting the edge (`FOTLAB-DATABS-000002` R10,
     * revised): the row stays but its `time_deleted` is stamped, so the node id space is
     * never touched. `NULL` parent needs the `IS NULL` branch because `= NULL` never matches.
     */
    @Query(
        "UPDATE fs_node_relation SET time_deleted = :timeDeleted " +
            "WHERE fs_node_id_child = :childId AND " +
            "(fs_node_id_parent = :parentId OR (fs_node_id_parent IS NULL AND :parentId IS NULL))",
    )
    suspend fun removeRelation(childId: Long, parentId: Long?, timeDeleted: Long)

    /** Relations where the node is the child — its own links to its parents (live only). */
    @Query("SELECT * FROM fs_node_relation WHERE fs_node_id_child = :childId AND time_deleted IS NULL")
    suspend fun relationsWithChild(childId: Long): List<FsNodeRelation>

    /**
     * Live, non-null parent ids of a node — the upward edges used by cycle detection.
     * `NULL` parents (root edges) are excluded because they terminate an upward walk
     * without forming a cycle.
     */
    @Query(
        "SELECT fs_node_id_parent FROM fs_node_relation " +
            "WHERE fs_node_id_child = :childId AND time_deleted IS NULL " +
            "AND fs_node_id_parent IS NOT NULL",
    )
    suspend fun parentIdsOf(childId: Long): List<Long>

    /** Relations where the node is the parent — what sits directly under it (live only). */
    @Query("SELECT * FROM fs_node_relation WHERE fs_node_id_parent = :parentId AND time_deleted IS NULL")
    suspend fun relationsWithParent(parentId: Long): List<FsNodeRelation>

    /** How many live parents a node still has; 0 means it became an orphan (R12). */
    @Query("SELECT COUNT(*) FROM fs_node_relation WHERE fs_node_id_child = :childId AND time_deleted IS NULL")
    suspend fun activeParentCount(childId: Long): Int

    /** Live nodes with no live parent relation at all — neither root nor nested; recycled by refresh. */
    @Query(
        "SELECT fs_node_id FROM fs_node_object " +
            "WHERE time_deleted IS NULL " +
            "AND fs_node_id NOT IN (SELECT DISTINCT fs_node_id_child FROM fs_node_relation WHERE time_deleted IS NULL)",
    )
    suspend fun orphanNodeIds(): List<Long?>

    /** All edges soft-deleted in the batch stamped at [time] — the batch's own subtree edges. */
    @Query("SELECT * FROM fs_node_relation WHERE time_deleted = :time")
    fun deletedRelationsAt(time: Long): Flow<List<FsNodeRelation>>

    /**
     * Restore a whole delete batch's parent/child links: clear the soft-delete stamp on every edge
     * stamped with [time] so the batch's tree is reconnected (`FOTLAB-DATABS-000002` R12, recycle
     * restore — batch-level).
     */
    @Query("UPDATE fs_node_relation SET time_deleted = NULL WHERE time_deleted = :time")
    suspend fun restoreRelationsByBatch(time: Long)

    /**
     * Children of [parentId] reached through edges stamped with [batchTime] — the in-batch subtree
     * step. Edges stamped with a different (or null) timestamp are excluded, so a live child that
     * merely shared membership with a deleted collection is never pulled into the (hard) delete
     * (`delete forever` recursion).
     */
    @Query(
        "SELECT fs_node_id_child FROM fs_node_relation " +
            "WHERE fs_node_id_parent = :parentId AND time_deleted = :batchTime",
    )
    suspend fun batchChildIdsOf(parentId: Long, batchTime: Long): List<Long>

    /** Permanently remove edges touching the given nodes (`delete forever`). */
    @Query("DELETE FROM fs_node_relation WHERE fs_node_id_child IN (:ids) OR fs_node_id_parent IN (:ids)")
    suspend fun deleteRelationsForever(ids: List<Long>)
}
