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
            "ORDER BY child.time_created DESC, child.name_display ASC",
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
     * Live nodes that have no effective live parent edge — the orphans the refresh reconcile
     * reaps. An edge counts as a live parent link only when it is itself live, names a
     * **non-null** parent, and that parent node is still live; so all three of these are
     * orphans:
     *
     *  - the node has no parent edge at all;
     *  - its only parent edge is a `NULL`-parent edge (the shape the old implicit-root scheme
     *    wrote, kept sweepable rather than trusted);
     *  - its parent edge is live but the parent node is soft-deleted (a dead parent leaves a
     *    dead relationship).
     *
     * The root kind is excluded by its MIME (`LibraryRoot.MIME`): the root is the one node that
     * legitimately has no parent, so it is protected by what it *is*, never by being swept for
     * what it lacks. A node is only ever reaped for having lost every effective parent link.
     */
    @Query(
        "SELECT fs_node_id FROM fs_node_object " +
            "WHERE time_deleted IS NULL AND type_mime <> :rootMime " +
            "AND fs_node_id NOT IN (" +
            "SELECT r.fs_node_id_child FROM fs_node_relation AS r " +
            "WHERE r.time_deleted IS NULL AND r.fs_node_id_parent IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM fs_node_object AS p " +
            "WHERE p.fs_node_id = r.fs_node_id_parent AND p.time_deleted IS NULL))",
    )
    suspend fun orphanNodeIds(rootMime: String): List<Long?>

    /**
     * Stamp every live relation whose parent is gone: a `NULL` parent, or a parent node that is
     * itself soft-deleted. One predicate covers both — a `NULL` parent matches no parent row at
     * all, so the `NOT EXISTS` is already true for it.
     *
     * The relation table is many-to-many, so this is deliberately **per relation, never per
     * child**: a child that also sits under a live parent keeps that edge, and only the dead
     * one is stamped. The child's own verdict is a separate question, answered by
     * [orphanNodeIds] — a child is only an orphan once *every* parent link is gone.
     *
     * Swept like any other removal: a `time_deleted` stamp, never a physical drop (R10).
     */
    @Query(
        "UPDATE fs_node_relation SET time_deleted = :timeDeleted " +
            "WHERE time_deleted IS NULL AND NOT EXISTS (" +
            "SELECT 1 FROM fs_node_object AS p " +
            "WHERE p.fs_node_id = fs_node_relation.fs_node_id_parent AND p.time_deleted IS NULL)",
    )
    suspend fun stampRelationsWithDeadParent(timeDeleted: Long): Int

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
