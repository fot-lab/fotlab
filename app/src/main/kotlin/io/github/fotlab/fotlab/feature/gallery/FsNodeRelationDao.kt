package io.github.fotlab.fotlab.feature.gallery

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Delete
    suspend fun delete(relation: FsNodeRelation)

    /** Children of a non-root parent (high-frequency: loading a directory). */
    @Query(
        "SELECT child.* FROM fs_node_object AS child " +
            "JOIN fs_node_relation AS r ON child.fs_node_id = r.fs_node_id_child " +
            "WHERE r.fs_node_id_parent = :parentId " +
            "ORDER BY child.time_created",
    )
    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>>

    /** Children of the implicit root (rows whose parent is NULL). */
    @Query(
        "SELECT child.* FROM fs_node_object AS child " +
            "JOIN fs_node_relation AS r ON child.fs_node_id = r.fs_node_id_child " +
            "WHERE r.fs_node_id_parent IS NULL " +
            "ORDER BY child.time_created",
    )
    fun rootChildren(): Flow<List<FsNodeObject>>

    /** All parents of a node (high-frequency: which collections contain a file). */
    @Query(
        "SELECT parent.* FROM fs_node_object AS parent " +
            "JOIN fs_node_relation AS r ON parent.fs_node_id = r.fs_node_id_parent " +
            "WHERE r.fs_node_id_child = :childId " +
            "ORDER BY parent.name_display",
    )
    fun parentsOf(childId: Long): Flow<List<FsNodeObject>>

    @Query(
        "SELECT * FROM fs_node_relation " +
            "WHERE fs_node_id_child = :childId AND fs_node_id_parent = :parentId",
    )
    suspend fun getRelation(childId: Long, parentId: Long?): FsNodeRelation?

    @Query(
        "DELETE FROM fs_node_relation " +
            "WHERE fs_node_id_child = :childId AND fs_node_id_parent = :parentId",
    )
    suspend fun removeRelation(childId: Long, parentId: Long?)

    /** Relations where the node is the child — its own links to its parents. */
    @Query("SELECT * FROM fs_node_relation WHERE fs_node_id_child = :childId")
    suspend fun relationsWithChild(childId: Long): List<FsNodeRelation>

    /** Relations where the node is the parent — what sits directly under it. */
    @Query("SELECT * FROM fs_node_relation WHERE fs_node_id_parent = :parentId")
    suspend fun relationsWithParent(parentId: Long): List<FsNodeRelation>

    /** How many parents a node still has; 0 means it became an orphan (R12). */
    @Query("SELECT COUNT(*) FROM fs_node_relation WHERE fs_node_id_child = :childId")
    suspend fun parentCount(childId: Long): Int
}
