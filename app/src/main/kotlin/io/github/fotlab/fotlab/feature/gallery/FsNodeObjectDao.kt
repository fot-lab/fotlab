package io.github.fotlab.fotlab.feature.gallery

import androidx.room.Dao
import androidx.room.Delete
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

    @Delete
    suspend fun delete(node: FsNodeObject)

    @Query("SELECT * FROM fs_node_object WHERE fs_node_id = :id")
    suspend fun getById(id: Long): FsNodeObject?

    /** Used to honour the UNIQUE `uri_storage` index and avoid duplicate files. */
    @Query("SELECT * FROM fs_node_object WHERE uri_storage = :uri")
    suspend fun getByUri(uri: String): FsNodeObject?

    @Query("SELECT * FROM fs_node_object WHERE type_mime = 'application/folder' ORDER BY name_display")
    fun observeCollections(): Flow<List<FsNodeObject>>

    /** All file-entry nodes (everything that is not a virtual folder); used by refresh (R10). */
    @Query("SELECT * FROM fs_node_object WHERE type_mime <> :folderMime")
    suspend fun fileEntryNodes(folderMime: String): List<FsNodeObject>
}
