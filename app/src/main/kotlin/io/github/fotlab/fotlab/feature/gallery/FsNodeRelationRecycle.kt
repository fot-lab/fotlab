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
 *
 * `fs_node_id_parent` is `NULL` on a live edge that denotes a root-level node (R5). A
 * composite primary key cannot hold a nullable column (Room forbids it, as on the live
 * table), so an archived root edge stores the sentinel [FsNodeParentRootId] here instead
 * of `NULL`. It is never a real node id (live ids are >= 1), so the value unambiguously
 * means "was a root-level edge". The composite primary key `(id_recycle, fs_node_id_child,
 * fs_node_id_parent)` therefore also expresses "one operation archives an edge once" (R11).
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
    @ColumnInfo(name = "fs_node_id_parent") val fsNodeIdParent: Long,
)
