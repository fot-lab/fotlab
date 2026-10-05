package io.github.fotlab.fotlab.feature.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An edge of the virtual file tree (`FOTLAB-DATABS-000002`): "child is directly
 * under parent". Both foreign keys cascade, so deleting a node cleans up every relation
 * that references it (R3/R8).
 *
 * The parent is always a real node id: the top level of the tree is the root node
 * [LibraryRoot.ID], a normal row like any other, so a stored edge never needs a
 * "no parent" marker. The root is the single node that has no parent edge at all.
 *
 * Room forbids a nullable column in a `@PrimaryKey`, so instead of a composite
 * `(child, parent)` key the pair is guarded by a UNIQUE index over both columns
 * (plus a surrogate auto-generated `id` as the primary key), which preserves "the
 * same edge cannot be inserted twice" (R3) and, because neither column is ever
 * `NULL`, deduplicates the top level as reliably as any other level.
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
    @ColumnInfo(name = "fs_node_id_parent") val fsNodeIdParent: Long,
    /**
     * Soft-delete timestamp (`FOTLAB-DATABS-000002` R10, revised): `NULL` means the edge is
     * live; a non-null value marks it removed. Deleting a node stamps every relation that
     * leaves with it instead of removing the row, so the node id and edge id spaces stay
     * unique across live and removed rows (no recycle tables, R6).
     */
    @ColumnInfo(name = "time_deleted") val timeDeleted: Long? = null,
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
)
