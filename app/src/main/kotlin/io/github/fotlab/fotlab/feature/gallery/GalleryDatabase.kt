package io.github.fotlab.fotlab.feature.gallery

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The gallery feature's own Room database (`FOTLAB-DATABS-000001` R3), realising the
 * `fs_node` schema of `FOTLAB-DATABS-000002` — the two live tables plus the two recycle
 * tables deletion moves rows into (R10/R11).
 *
 * `exportSchema = false`: the schema JSON is a build artifact and is not committed
 * (`FOTLAB-DATABS-000001` R5). Migration safety comes from Room's runtime validation
 * against these entities. No destructive migration fallback is configured (R5/C5).
 */
@Database(
    entities = [
        FsNodeObject::class,
        FsNodeRelation::class,
        FsNodeObjectRecycle::class,
        FsNodeRelationRecycle::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun nodeObjectDao(): FsNodeObjectDao
    abstract fun nodeRelationDao(): FsNodeRelationDao
    abstract fun nodeObjectRecycleDao(): FsNodeObjectRecycleDao
    abstract fun nodeRelationRecycleDao(): FsNodeRelationRecycleDao

    companion object {

        /** Adds the two recycle tables that deletion archives into (R10). */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fs_node_object_recycle` (" +
                        "`id_recycle` INTEGER NOT NULL, " +
                        "`fs_node_id` INTEGER NOT NULL, " +
                        "`name_display` TEXT NOT NULL, " +
                        "`type_mime` TEXT NOT NULL, " +
                        "`uri_storage` TEXT, " +
                        "`time_modified` INTEGER, " +
                        "`time_created` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id_recycle`, `fs_node_id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_fs_node_object_recycle_id_recycle` " +
                        "ON `fs_node_object_recycle` (`id_recycle`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fs_node_relation_recycle` (" +
                        "`id_recycle` INTEGER NOT NULL, " +
                        "`fs_node_id_child` INTEGER NOT NULL, " +
                        "`fs_node_id_parent` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id_recycle`, `fs_node_id_child`, `fs_node_id_parent`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_fs_node_relation_recycle_id_recycle` " +
                        "ON `fs_node_relation_recycle` (`id_recycle`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_fs_node_relation_recycle_fs_node_id_parent` " +
                        "ON `fs_node_relation_recycle` (`fs_node_id_parent`)",
                )
            }
        }
    }
}
