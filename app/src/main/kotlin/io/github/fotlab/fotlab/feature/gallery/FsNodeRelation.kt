package io.github.fotlab.fotlab.feature.gallery

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An edge of the virtual file tree (`FOTLAB-DATABS-000002`): "child is directly
 * under parent".
 *
 * The composite primary key `(fs_node_id_child, fs_node_id_parent)` means the same
 * edge cannot be inserted twice; a `NULL` parent denotes a root-level node. Both
 * foreign keys cascade, so deleting a node cleans up every relation that references
 * it (`FOTLAB-DATABS-000002` R3/R8).
 */
@Entity(
    tableName = "fs_node_relation",
    primaryKeys = ["fs_node_id_child", "fs_node_id_parent"],
    indices = [
        Index(value = ["fs_node_id_parent"]),
        Index(value = ["fs_node_id_child"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = FsNodeObject::class,
            parentColumns = ["fs_node_id"],
            childColumns = ["fs_node_id_child"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FsNodeObject::class,
            parentColumns = ["fs_node_id"],
            childColumns = ["fs_node_id_parent"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FsNodeRelation(
    @ColumnInfo(name = "fs_node_id_child") val fsNodeIdChild: Long,
    @ColumnInfo(name = "fs_node_id_parent") val fsNodeIdParent: Long?,
)
