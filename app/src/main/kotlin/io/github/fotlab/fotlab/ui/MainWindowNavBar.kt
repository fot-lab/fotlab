package io.github.fotlab.fotlab.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.fotlab.fotlab.navigation.TopLevelDestination

/**
 * The app's persistent nav bar (`FOTLAB-UIXDES-000001` R2) — the only app-level persistent
 * element, currently pinned to the top of the window. Named by function (nav bar), not by
 * position, so moving it again does not require a rename.
 *
 * Selection is derived from the current back stack route, not from local state,
 * so system back and deep links stay in sync (R3).
 */
@Composable
fun MainWindowNavBar(
    destinations: List<TopLevelDestination>,
    currentRoute: () -> String?,
    onDestinationSelected: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedRoute = currentRoute()

    NavigationBar(
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBars),
    ) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = destination.route == selectedRoute,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null,
                    )
                },
                label = { Text(text = stringResource(id = destination.label)) },
                alwaysShowLabel = true,
            )
        }
    }
}
