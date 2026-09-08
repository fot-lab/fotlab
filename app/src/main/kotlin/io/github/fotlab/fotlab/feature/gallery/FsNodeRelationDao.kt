package io.github.fotlab.fotlab.feature.gallery

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FsNodeRelationDao {

    @Insert
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
}
