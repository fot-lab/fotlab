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
     * IGNORE keeps the "same edge cannot be inserted twice" guarantee of the unique
     * `(child, parent)` index usable from code: re-linking an existing child/parent pair
     * is a no-op instead of an abort (`FOTLAB-DATABS-000002` R3). Every stored edge names
     * a real parent node — the root is the node row `LibraryRoot.ID`, not a `NULL`.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(relation: FsNodeRelation)

    /**
     * Clear the soft-delete stamp on an existing dead edge between this child/parent pair, so a
     * re-imported node is reattached where it used to live. Without this the dead row's unique
     * key makes the IGNORE [insert] a no-op and the revived node stays an orphan (its live node
     * row JOINs no live edge, so the grid/directory never lists it).
     */
    @Query(
        "UPDATE fs_node_relation SET time_deleted = NULL " +
            "WHERE fs_node_id_child = :childId AND fs_node_id_parent = :parentId " +
            "AND time_deleted IS NOT NULL",
    )
    suspend fun reviveRelation(childId: Long, parentId: Long)

    @Update
    suspend fun update(relation: FsNodeRelation)

    /**
     * Children of a parent — the top level is the root node, queried by its own id
     * ([LibraryRoot.ID]) like any other directory, so there is no separate root listing.
     */
    @Query(
        "SELECT child.* FROM fs_node_object AS child " +
            "JOIN fs_node_relation AS r ON child.fs_node_id = r.fs_node_id_child " +
            "WHERE r.fs_node_id_parent = :parentId AND r.time_deleted IS NULL " +
            "AND child.time_deleted IS NULL " +
            "ORDER BY child.time_created",
    )
    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>>

    /** All parents of a node (high-frequency: which collections contain a file). */
    @Query(
        "SELECT parent.* FROM fs_node_object AS parent " +
            "JOIN fs_node_relation AS r ON parent.fs_node_id = r.fs_node_id_parent " +
            "WHERE r.fs_node_id_child = :childId AND r.time_deleted IS NULL " +
            "AND parent.time_deleted IS NULL " +
            "ORDER BY parent.name_display",
    )
    fun parentsOf(childId: Long): Flow<List<FsNodeObject>>

    /**
     * The live edge between [childId] and its parent [parentId], or null when there is none.
     * The parent is always a real node id ([LibraryRoot.ID] at the top level), so the
     * predicate is a plain equality.
     */
    @Query(
        "SELECT * FROM fs_node_relation " +
            "WHERE fs_node_id_child = :childId AND fs_node_id_parent = :parentId " +
            "AND time_deleted IS NULL",
    )
    suspend fun getRelation(childId: Long, parentId: Long): FsNodeRelation?

    /**
     * Unlink a child from a parent by soft-deleting the edge (`FOTLAB-DATABS-000002` R10,
     * revised): the row stays but its `time_deleted` is stamped, so the node id space is
     * never touched.
     */
    @Query(
        "UPDATE fs_node_relation SET time_deleted = :timeDeleted " +
            "WHERE fs_node_id_child = :childId AND fs_node_id_parent = :parentId",
    )
    suspend fun removeRelation(childId: Long, parentId: Long, timeDeleted: Long)

    /** Relations where the node is the child — its own links to its parents (live only). */
    @Query("SELECT * FROM fs_node_relation WHERE fs_node_id_child = :childId AND time_deleted IS NULL")
    suspend fun relationsWithChild(childId: Long): List<FsNodeRelation>

    /**
     * Live parent ids of a node — the upward edges used by cycle detection. The root holds
     * no parent edge at all, so an upward walk from it simply ends; the `IS NOT NULL` filter
     * keeps that walk on concrete node ids.
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

    /**
     * Live nodes with no live parent relation at all — recycled by refresh.
     *
     * The root kind is excluded by its MIME (`LibraryRoot.MIME`): the root is the one node that
     * legitimately has no parent edge, so without this filter every refresh would soft-delete
     * the library's anchor. Note the test is on the node's *kind*, not on the absence of a
     * parent — a node is never swept for being parentless, only for having lost every edge it
     * had.
     */
    @Query(
        "SELECT fs_node_id FROM fs_node_object " +
            "WHERE time_deleted IS NULL AND type_mime <> :rootMime " +
            "AND fs_node_id NOT IN (SELECT DISTINCT fs_node_id_child FROM fs_node_relation WHERE time_deleted IS NULL)",
    )
    suspend fun orphanNodeIds(rootMime: String): List<Long?>

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
