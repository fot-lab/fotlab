package io.github.fotlab.fotlab.ui

import androidx.compose.foundation.layout.WindowInsets
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
 * │ nav bar region            │ ← the only persistent UI ([MainWindowNavBar])
 * ├───────────────────────────┤
 * │ content region            │ ← owned by the active module (its fun bar included)
 * └───────────────────────────┘
 * ```
 *
 * See `FOTLAB-UIXDES-000001` R2: nothing else is persistent at app level. The Scaffold slot is
 * called `topBar` (Material3 API), but what it hosts is identified by function — the nav bar;
 * it is not named after its position anywhere in our code.
 *
 * `contentWindowInsets` is zeroed so the scaffold only pads the content for the nav bar: each
 * screen owns its own fun bar and consumes the system navigation-bar inset there, otherwise the
 * same inset would be padded twice.
 */
@Composable
fun MainWindowFrame() {
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Scaffold API slot name; the hosted element is the nav bar (position-independent).
        // The screens own the bottom inset on their own fun bars; the scaffold must not also
        // add it as content padding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            MainWindowNavBar(
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
