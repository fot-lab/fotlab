package io.github.fotlab.fotlab.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Shared database support.
 *
 * Rules enforced by the code that uses it (`FOTLAB-DATABS-000001`):
 * - no `allowMainThreadQueries()`, ever (R4/C4)
 * - one database per feature, named after it (R3)
 * - `exportSchema = false`; the schema JSON is a build artifact and is not committed (R5)
 * - no destructive migration fallback in release builds (R5/C5)
 */
object RoomDatabases {

    /**
     * In-memory database for DAO tests, so tests never touch the on-device
     * database file (R8).
     */
    fun <T : RoomDatabase> inMemory(
        context: Context,
        database: Class<T>,
    ): RoomDatabase.Builder<T> = Room.inMemoryDatabaseBuilder(context, database)
}
