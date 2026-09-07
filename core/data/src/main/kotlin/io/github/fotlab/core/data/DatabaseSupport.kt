package io.github.fotlab.core.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Shared database support.
 *
 * Rules enforced by the modules that use it (`FOTLAB-DATABS-000001`):
 * - no `allowMainThreadQueries()`, ever (R4/C4)
 * - one database per module, named `<Module>Database` (R3)
 * - `exportSchema = true`, schema JSON committed per module (R5)
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
