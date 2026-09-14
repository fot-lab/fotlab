package io.github.fotlab.fotlab.navigation.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.fotlab.fotlab.feature.library.LibraryScreen
import io.github.fotlab.fotlab.navigation.studio.StudioDestination

/**
 * Route owned by the library feature; the shell only assembles the graph
 * (`FOTLAB-UIXDES-000001` R4).
 */
object LibraryDestination {
    const val ROUTE = "library"
}

/**
 * The feature's single assembly entry point. The shell calls this and never
 * looks inside.
 *
 * [navController] is threaded in so the viewer's "open in Studio" action can switch the
 * bottom navigation to Studio the same way a nav-bar tap would (`FOTLAB-UIXDES`, viewer layout).
 */
fun NavGraphBuilder.libraryGraph(navController: NavHostController) {
    composable(route = LibraryDestination.ROUTE) {
        LibraryScreen(
            onNavigateToStudio = {
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
