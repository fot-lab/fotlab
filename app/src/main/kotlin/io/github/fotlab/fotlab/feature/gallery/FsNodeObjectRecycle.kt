package io.github.fotlab.fotlab.feature.gallery

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * Archived copy of a removed [FsNodeObject] row (`FOTLAB-DATABS-000002` R10/R11).
 *
 * Nothing of the virtual tree is ever dropped silently: deletion moves the row here so
 * the operation can be inspected — and later undone — as a whole.
 *
 * `idRecycle` is the batch id: every row written by one delete operation carries the same
 * value. The composite primary key `(id_recycle, fs_node_id)` therefore also expresses
 * "one operation archives a node once".
 *
 * This table carries **no foreign keys** and takes part in no cascade: it is an
 * append-only archive that must stay readable after the live row it mirrors is gone.
 */
@Entity(
    tableName = "fs_node_object_recycle",
    primaryKeys = ["id_recycle", "fs_node_id"],
    indices = [
        Index(value = ["id_recycle"]),
    ],
)
data class FsNodeObjectRecycle(
    @ColumnInfo(name = "id_recycle") val idRecycle: Long,
    @ColumnInfo(name = "fs_node_id") val fsNodeId: Long,
    @ColumnInfo(name = "name_display") val nameDisplay: String,
    @ColumnInfo(name = "type_mime") val typeMime: String,
    @ColumnInfo(name = "uri_storage") val uriStorage: String? = null,
    @ColumnInfo(name = "time_modified") val timeModified: Long? = null,
    @ColumnInfo(name = "time_created") val timeCreated: Long,
)
