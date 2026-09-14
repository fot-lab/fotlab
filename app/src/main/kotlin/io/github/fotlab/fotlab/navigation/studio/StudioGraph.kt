package io.github.fotlab.fotlab.navigation.studio

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.fotlab.fotlab.feature.studio.StudioScreen

/**
 * Route owned by the Studio feature; the shell only assembles the graph
 * (`FOTLAB-UIXDES-000001` R4).
 */
object StudioDestination {
    const val ROUTE = "studio"
}

/**
 * The feature's single assembly entry point. The shell calls this and never
 * looks inside.
 */
fun NavGraphBuilder.studioGraph() {
    composable(route = StudioDestination.ROUTE) {
        StudioScreen()
    }
}
