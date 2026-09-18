package io.github.fotlab.fotlab.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
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
 * The only persistent element of the application (`FOTLAB-UIXDES-000001` R2).
 *
 * Selection is derived from the current back stack route, not from local state,
 * so system back and deep links stay in sync (R3).
 */
@Composable
fun MainNavigationBar(
    destinations: List<TopLevelDestination>,
    currentRoute: () -> String?,
    onDestinationSelected: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedRoute = currentRoute()

    NavigationBar(
        modifier = modifier.windowInsetsPadding(WindowInsets.navigationBars),
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
