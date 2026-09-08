package io.github.fotlab.fotlab.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.navigation.gallery.GalleryDestination

/**
 * The set of destinations shown in the only persistent UI element of the app
 * (see `FOTLAB-UIXDES-000001` R2/R3).
 *
 * Open question Q1 of that item: the definitive destination set, its order and
 * its start destination are still TBD. Adding a destination means adding one
 * entry here plus one graph contribution in [RootNavHost] — nothing else.
 */
enum class TopLevelDestination(
    val route: String,
    @param:StringRes val label: Int,
    val icon: ImageVector,
) {
    GALLERY(
        route = GalleryDestination.ROUTE,
        label = R.string.app_nav_gallery_label,
        icon = Icons.Filled.PhotoLibrary,
    ),
    ;

    companion object {
        val START: TopLevelDestination = GALLERY

        fun fromRoute(route: String?): TopLevelDestination? =
            entries.firstOrNull { it.route == route }
    }
}
