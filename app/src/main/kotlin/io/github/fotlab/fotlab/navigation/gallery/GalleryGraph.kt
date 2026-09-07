package io.github.fotlab.fotlab.navigation.gallery

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.fotlab.fotlab.ui.gallery.GalleryScreen

/**
 * Route owned by the gallery feature; the shell only assembles the graph
 * (`FOTLAB-UIXDES-000001` R4).
 */
object GalleryDestination {
    const val ROUTE = "gallery"
}

/**
 * The feature's single assembly entry point. The shell calls this and never
 * looks inside.
 */
fun NavGraphBuilder.galleryGraph() {
    composable(route = GalleryDestination.ROUTE) {
        GalleryScreen()
    }
}
