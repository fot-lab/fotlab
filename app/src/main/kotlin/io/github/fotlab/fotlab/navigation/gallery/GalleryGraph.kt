package io.github.fotlab.fotlab.navigation.gallery

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.fotlab.fotlab.feature.gallery.GalleryScreen
import io.github.fotlab.fotlab.feature.library.LibraryViewerScreen
import io.github.fotlab.fotlab.feature.studio.StudioEngine
import io.github.fotlab.fotlab.navigation.studio.StudioDestination

object GalleryDestination {
    const val ROUTE = "gallery"
}

object GalleryViewerDestination {
    const val ROUTE = "gallery/viewer"
}

fun NavGraphBuilder.galleryGraph(navController: NavHostController) {
    composable(route = GalleryDestination.ROUTE) {
        GalleryScreen(
            onOpenViewer = { navController.navigate(GalleryViewerDestination.ROUTE) },
        )
    }

    composable(route = GalleryViewerDestination.ROUTE) {
        LibraryViewerScreen(
            onDismiss = { navController.popBackStack() },
            onOpenInStudio = { node ->
                node.uriStorage?.let { StudioEngine.setCurrentNode(it) }
                navController.navigate(StudioDestination.ROUTE) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            },
        )
    }
}
