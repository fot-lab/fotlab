package io.github.fotlab.fotlab.feature.library

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

    /** Rename a node in place (R-name only); selection rename path (`FOTLAB-UIXDES-000004`). */
    @Query("UPDATE fs_node_object SET name_display = :name WHERE fs_node_id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("SELECT * FROM fs_node_object WHERE type_mime = 'application/folder' AND time_deleted IS NULL ORDER BY name_display")
    fun observeCollections(): Flow<List<FsNodeObject>>

    /** All live file-entry nodes (everything that is not a virtual folder); used by refresh. */
    @Query("SELECT * FROM fs_node_object WHERE type_mime <> :folderMime AND time_deleted IS NULL")
    suspend fun fileEntryNodes(folderMime: String): List<FsNodeObject>

    /**
     * Soft-delete: stamp `time_deleted` instead of dropping the row (`FOTLAB-DATABS-000002`
     * R10, revised). The id keeps its slot in the live id space; nothing is archived into a
     * separate table, so live and removed ids never collide.
     */
    @Query("UPDATE fs_node_object SET time_deleted = :timeDeleted WHERE fs_node_id = :id")
    suspend fun markDeleted(id: Long, timeDeleted: Long)
}
