package io.github.fotlab.feature.gallery

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/**
 * Route owned by the module; the shell only assembles the graph
 * (`FOTLAB-UIXDES-000001` R4).
 */
object GalleryDestination {
    const val ROUTE = "gallery"
}

/**
 * The module's single assembly entry point. The shell calls this and never
 * looks inside.
 */
fun NavGraphBuilder.galleryGraph() {
    composable(route = GalleryDestination.ROUTE) {
        GalleryScreen()
    }
}
