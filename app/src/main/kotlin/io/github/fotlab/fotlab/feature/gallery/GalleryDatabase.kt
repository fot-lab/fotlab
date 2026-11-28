package io.github.fotlab.fotlab.feature.gallery

import androidx.room.Database
import io.github.fotlab.fotlab.data.common.FsNodeDatabase
import io.github.fotlab.fotlab.data.common.FsNodeObject
import io.github.fotlab.fotlab.data.common.FsNodeRelation

/**
 * The gallery feature's own Room database.
 *
 * Schema is identical to LibraryDatabase — the two live tables with soft-delete timestamps
 * (`time_deleted`). Gallery starts fresh (no migrations, version = 1) because its database file
 * has never existed before; the library's history is not carried over.
 *
 * Inherits [FsNodeDatabase] so the shared [io.github.fotlab.fotlab.data.common.FsNodeRepository]
 * can reach the DAOs without knowing which feature owns the database file.
 */
@Database(
    entities = [
        FsNodeObject::class,
        FsNodeRelation::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class GalleryDatabase : FsNodeDatabase()
