package io.github.fotlab.fotlab.feature.gallery

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FsNodeObjectRecycleDao {

    /**
     * IGNORE keeps the "one operation archives a node once" property of the composite
     * primary key safe: a repeated write within the same batch is a no-op
     * (`FOTLAB-DATABS-000002` R11).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: FsNodeObjectRecycle)

    /** Everything one delete operation archived, for inspection or a future restore. */
    @Query("SELECT * FROM fs_node_object_recycle WHERE id_recycle = :batchId")
    suspend fun batch(batchId: Long): List<FsNodeObjectRecycle>
}
