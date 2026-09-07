package io.github.fotlab.core.data

import androidx.room.TypeConverter
import java.time.Instant

/**
 * Converters shared by every module database.
 *
 * Feature modules own their entities and DAOs; only cross-cutting conversions
 * live here (`FOTLAB-DATABS-000001` R3).
 */
class RoomConverters {

    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}
