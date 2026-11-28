package io.github.fotlab.fotlab.data.common

import androidx.room.RoomDatabase

/**
 * Abstract base for any RoomDatabase that owns an `fs_node` virtual file tree schema.
 *
 * Concrete databases (LibraryDatabase, GalleryDatabase, ...) inherit this class,
 * add their own `@Database` annotation listing [FsNodeObject] and [FsNodeRelation],
 * and build it with a distinct file name so each tree's data is physically isolated.
 *
 * The shared DAOs ([FsNodeObjectDao], [FsNodeRelationDao]) and the parameterised
 * [FsNodeRepository] live in `data/common` and are reused by every feature — the
 * only thing that changes between trees is the [RootConfig] the caller passes in.
 */
abstract class FsNodeDatabase : RoomDatabase() {
    abstract fun nodeObjectDao(): FsNodeObjectDao
    abstract fun nodeRelationDao(): FsNodeRelationDao
}
