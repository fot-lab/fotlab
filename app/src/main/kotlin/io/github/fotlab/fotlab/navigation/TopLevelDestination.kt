package io.github.fotlab.fotlab.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraRoll
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.navigation.camera.CameraDestination
import io.github.fotlab.fotlab.navigation.gallery.GalleryDestination
import io.github.fotlab.fotlab.navigation.library.LibraryDestination
import io.github.fotlab.fotlab.navigation.studio.StudioDestination

/**
 * The set of destinations shown in the only persistent UI element of the app
 * (see `FOTLAB-UIXDES-000001` R2/R3).
 *
 * Open question Q1 of that item: the definitive destination set, its order and
 * its start destination are still TBD. Adding a destination means adding one
 * entry here, its label in the `app` string block, and one graph contribution in
 * [RootNavHost] — nothing else.
 */
enum class TopLevelDestination(
    val route: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    CAMERA(
        route = CameraDestination.ROUTE,
        label = R.string.app_nav_camera_label,
        icon = Icons.Filled.PhotoCamera,
    ),
    LIBRARY(
        route = LibraryDestination.ROUTE,
        label = R.string.app_nav_library_label,
        icon = Icons.Filled.PermMedia,
    ),
    GALLERY(
        route = GalleryDestination.ROUTE,
        label = R.string.app_nav_gallery_label,
        icon = Icons.Filled.PhotoLibrary,
    ),
    STUDIO(
        route = StudioDestination.ROUTE,
        label = R.string.app_nav_studio_label,
        icon = Icons.Filled.CameraRoll,
    ),
    ;

    companion object {
        /**
         * The destination the app opens on. It is intentionally [LIBRARY], even
         * though the nav bar lists Camera first: the leading nav slot is a
         * layout choice, while the start destination is the app's home. The
         * two are decided independently — do not "fix" this to match the
         * declaration order.
         */
        val START: TopLevelDestination = LIBRARY

        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route }
    }
}
