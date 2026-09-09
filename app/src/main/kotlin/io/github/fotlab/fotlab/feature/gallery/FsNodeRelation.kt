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
 * `NULL` parent denotes a root-level node (`FOTLAB-DATABS-000002` R5). Both foreign
 * keys cascade, so deleting a node cleans up every relation that references it
 * (R3/R8).
 *
 * Room forbids nullable columns in a `@PrimaryKey`, so the original composite key
 * `(fs_node_id_child, fs_node_id_parent)` cannot be expressed directly (a `NULL`
 * parent is a first-class value here). Instead a surrogate auto-generated `id` is
 * the primary key and the `(fs_node_id_child, fs_node_id_parent)` pair is guarded by
 * a UNIQUE index, preserving "the same edge cannot be inserted twice" (R3). Note:
 * SQLite treats `NULL`s as distinct under a UNIQUE index, so two `(child, NULL)`
 * rows are not rejected by the index — the `OnConflictStrategy.IGNORE` insert and
 * the app's single-link-per-child usage make this unreachable in practice.
 */
@Entity(
    tableName = "fs_node_relation",
    indices = [
        Index(value = ["fs_node_id_parent"]),
        Index(value = ["fs_node_id_child"]),
        Index(value = ["fs_node_id_child", "fs_node_id_parent"], unique = true),
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
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
)
