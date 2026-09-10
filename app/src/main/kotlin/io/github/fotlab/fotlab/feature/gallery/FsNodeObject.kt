package io.github.fotlab.fotlab.feature.gallery

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A node of the virtual file tree (`FOTLAB-IMGMGR-000001`, `FOTLAB-DATABS-000002`).
 *
 * One row per node — a collection (`type_mime = "application/folder"`) or a file
 * entry (`uri_storage` holds the `content://` reference). The primary key is a
 * nullable Long without `AUTOINCREMENT`, so SQLite assigns the rowid and reuses a
 * freed id after deletion (`FOTLAB-DATABS-000002` R6). `uri_storage` is `UNIQUE`
 * so the same physical file is recorded once (`FOTLAB-DATABS-000002` R7).
 */
@Entity(
    tableName = "fs_node_object",
    indices = [
        Index(value = ["uri_storage"], unique = true),
        Index(value = ["type_mime"]),
    ],
)
data class FsNodeObject(
    @PrimaryKey @ColumnInfo(name = "fs_node_id") val fsNodeId: Long? = null,
    @ColumnInfo(name = "name_display") val nameDisplay: String,
    @ColumnInfo(name = "type_mime") val typeMime: String,
    @ColumnInfo(name = "uri_storage") val uriStorage: String? = null,
    @ColumnInfo(name = "time_modified") val timeModified: Long? = null,
    @ColumnInfo(name = "time_created") val timeCreated: Long,
    /**
     * Soft-delete timestamp (`FOTLAB-DATABS-000002` R10, revised): `NULL` means the node
     * is live; any non-null value marks it removed. Deletion never drops or archives a row
     * — it only stamps this column, so the node id stays unique and the live and removed
     * id spaces never collide.
     */
    @ColumnInfo(name = "time_deleted") val timeDeleted: Long? = null,
)
