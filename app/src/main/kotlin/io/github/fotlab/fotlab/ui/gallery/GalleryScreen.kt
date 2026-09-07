package io.github.fotlab.fotlab.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R

/** Drawer width: 80% of this content region (`FOTLAB-UIXDES-000002` R3). */
private const val DrawerWidthFraction = 0.8f

/**
 * Placeholder gallery screen.
 *
 * The top app bar and the drawer are implemented **by this feature package**:
 * its own code, its own state and its own lifetime, per `FOTLAB-UIXDES-000002`
 * R1/R5 and the amended C1. Only the behaviour contract (drawer icon left,
 * overflow right, 80% width, never covering the bottom bar) is shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen() {
    var drawerOpen by rememberSaveable { mutableStateOf(false) }

    // With the drawer expanded, system back closes it before navigating (R5).
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(id = R.string.gallery_title),
                            maxLines = 1,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { drawerOpen = true }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = stringResource(id = R.string.gallery_cd_open_drawer),
                            )
                        }
                    },
                    actions = {
                        // Overflow menu: always present (C4); entries are TBD (Q2).
                        IconButton(onClick = { /* TODO: feature overflow entries */ }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = stringResource(id = R.string.gallery_cd_more_options),
                            )
                        }
                    },
                )
            },
        ) { contentPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(contentPadding))
        }

        if (drawerOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { drawerOpen = false },
                    ),
            )

            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(DrawerWidthFraction),
                tonalElevation = 3.dp,
            ) {
                Column {
                    // Feature-private drawer content (R5): no app-level entries here.
                    Text(text = stringResource(id = R.string.gallery_drawer_empty))
                }
            }
        }
    }
}
