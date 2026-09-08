package io.github.fotlab.fotlab

import android.app.Application
import io.github.fotlab.fotlab.feature.gallery.GalleryCore

/**
 * Application entry point.
 *
 * Builds process-wide providers. The gallery's Room database is prepared here so
 * the virtual file tree is available to the feature (`FOTLAB-DATABS-000002`,
 * `FOTLAB-STRUCT-000001`).
 */
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GalleryCore.prepare(this)
    }
}
