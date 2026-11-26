package io.github.fotlab.fotlab.feature.camera

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * The camera feature's own Room database (`FOTLAB-DATABS-000001` R3, `FOTLAB-DATABS-000004`).
 *
 * The camera feature keeps its own library so the transfer queue never touches the source
 * library's `fs_node` schema: the two features have different lifetimes and different
 * failure modes, and a queue table has no business in the file tree's database.
 *
 * `exportSchema = false`: the schema JSON is a build artifact and is not committed
 * (`FOTLAB-DATABS-000001` R5). No destructive migration fallback is configured (R5/C5) — the
 * queue is a cache of what the user asked to transfer, and a schema change must ship an
 * explicit migration rather than silently dropping the history.
 */
@Database(
    entities = [
        CameraFileQueue::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class CameraDatabase : RoomDatabase() {
    abstract fun cameraFileQueueDao(): CameraFileQueueDao
}
