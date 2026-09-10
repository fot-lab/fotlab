package io.github.fotlab.fotlab.navigation.library

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.fotlab.fotlab.feature.library.LibraryScreen

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
 */
fun NavGraphBuilder.libraryGraph() {
    composable(route = LibraryDestination.ROUTE) {
        LibraryScreen()
    }
}
