package io.github.fotlab.fotlab.feature.gallery

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.fotlab.fotlab.feature.library.FsNodeObject
import io.github.fotlab.fotlab.feature.library.FsNodeObjectDao
import io.github.fotlab.fotlab.feature.library.FsNodeRelation
import io.github.fotlab.fotlab.feature.library.FsNodeRelationDao

/**
 * The gallery feature's own Room database.
 *
 * Schema is identical to [io.github.fotlab.fotlab.feature.library.LibraryDatabase] — the two live
 * tables with soft-delete timestamps (`time_deleted`). Gallery starts fresh (no migrations,
 * version = 1) because its database file has never existed before; the library's history is
 * not carried over.
 */
@Database(
    entities = [
        FsNodeObject::class,
        FsNodeRelation::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun nodeObjectDao(): FsNodeObjectDao
    abstract fun nodeRelationDao(): FsNodeRelationDao
}
