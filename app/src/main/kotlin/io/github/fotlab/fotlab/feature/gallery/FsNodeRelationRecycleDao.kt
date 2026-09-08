package io.github.fotlab.fotlab.feature.gallery

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FsNodeRelationRecycleDao {

    /** IGNORE: archiving the same edge twice in one batch is a no-op (R11). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: FsNodeRelationRecycle)

    /** Every relation one delete operation removed, for inspection or a future restore. */
    @Query("SELECT * FROM fs_node_relation_recycle WHERE id_recycle = :batchId")
    suspend fun batch(batchId: Long): List<FsNodeRelationRecycle>
}
