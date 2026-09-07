package io.github.fotlab.fotlab.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.github.fotlab.fotlab.navigation.gallery.galleryGraph

/**
 * Root navigation host.
 *
 * The shell assembles the graphs contributed by each feature package and knows
 * nothing about their contents (`FOTLAB-UIXDES-000001` R4).
 */
@Composable
fun RootNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.START.route,
        modifier = modifier,
    ) {
        galleryGraph()
        // Further destinations: one `xxxGraph()` call per feature package.
    }
}
