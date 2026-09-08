package io.github.fotlab.fotlab.feature.gallery

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Archived copy of a removed [FsNodeRelation] row (`FOTLAB-DATABS-000002` R10/R11).
 *
 * Every relation the delete algorithm removes from `fs_node_relation` is written here
 * with the batch id of the operation, so a removed structure can be reconstructed.
 *
 * As with the object recycle table: no foreign keys, no cascade — the rows outlive the
 * live nodes they mention.
 */
@Entity(
    tableName = "fs_node_relation_recycle",
    primaryKeys = ["id_recycle", "fs_node_id_child", "fs_node_id_parent"],
    indices = [
        Index(value = ["id_recycle"]),
        Index(value = ["fs_node_id_parent"]),
    ],
)
data class FsNodeRelationRecycle(
    @ColumnInfo(name = "id_recycle") val idRecycle: Long,
    @ColumnInfo(name = "fs_node_id_child") val fsNodeIdChild: Long,
    @ColumnInfo(name = "fs_node_id_parent") val fsNodeIdParent: Long?,
)
