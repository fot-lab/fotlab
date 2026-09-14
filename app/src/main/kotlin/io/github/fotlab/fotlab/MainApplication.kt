package io.github.fotlab.fotlab

import android.app.Application
import io.github.fotlab.fotlab.feature.library.LibraryCore
import io.github.fotlab.fotlab.feature.studio.StudioEngine

/**
 * Application entry point.
 *
 * Builds process-wide providers. The library's Room database is prepared here so
 * the virtual file tree is available to the feature (`FOTLAB-DATABS-000002`,
 * `FOTLAB-STRUCT-000001`). The Studio engine is also prepared here so it owns a
 * process-wide `Context` for its render pipeline (`FOTLAB-STUDIO-000001`, R8).
 */
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LibraryCore.prepare(this)
        StudioEngine.prepare(this)
    }
}
