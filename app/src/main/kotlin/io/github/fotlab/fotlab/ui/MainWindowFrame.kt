package io.github.fotlab.fotlab.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import io.github.fotlab.fotlab.navigation.RootNavHost
import io.github.fotlab.fotlab.navigation.TopLevelDestination

/**
 * The application main window: exactly two vertical regions.
 *
 * ```
 * ┌───────────────────────────┐
 * │ content region            │ ← owned by the active module
 * ├───────────────────────────┤
 * │ bottom navigation region  │ ← the only persistent UI
 * └───────────────────────────┘
 * ```
 *
 * See `FOTLAB-UIXDES-000001` R2: nothing else is persistent at app level.
 */
@Composable
fun MainWindowFrame() {
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            MainNavigationBar(
                destinations = TopLevelDestination.entries,
                currentRoute = { navController.currentDestination?.route },
                onDestinationSelected = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { innerPadding ->
        RootNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        )
    }
}
