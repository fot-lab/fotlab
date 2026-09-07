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
 * The shell assembles the graphs contributed by each feature package; it never
 * knows what is inside them (`FOTLAB-UIXDES-000001` R4).
 */
@Composable
fun FotLabNavHost(
    navController: NavHostController = rememberNavController(),
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.START.route,
        modifier = modifier,
    ) {
        galleryGraph()
        // Further destinations: add `someGraph()` here, one per feature package.
    }
}
