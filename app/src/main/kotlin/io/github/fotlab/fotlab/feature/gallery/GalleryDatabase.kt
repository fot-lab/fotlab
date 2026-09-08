package io.github.fotlab.fotlab.feature.gallery

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The gallery feature's own Room database (`FOTLAB-DATABS-000001` R3), realising the
 * two-table `fs_node` schema of `FOTLAB-DATABS-000002`.
 *
 * `exportSchema = false`: the schema JSON is a build artifact and is not committed
 * (`FOTLAB-DATABS-000001` R5). Migration safety comes from Room's runtime validation.
 * No destructive migration fallback is configured (R5/C5).
 */
@Database(
    entities = [FsNodeObject::class, FsNodeRelation::class],
    version = 1,
    exportSchema = false,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun nodeObjectDao(): FsNodeObjectDao
    abstract fun nodeRelationDao(): FsNodeRelationDao
}
