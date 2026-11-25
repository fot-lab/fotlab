package io.github.fotlab.fotlab.navigation.camera

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.fotlab.fotlab.feature.camera.CameraScreen

/**
 * Route owned by the camera feature; the shell only assembles the graph
 * (`FOTLAB-UIXDES-000001` R4).
 */
object CameraDestination {
    const val ROUTE = "camera"
}

/**
 * The feature's single assembly entry point. The shell calls this and never
 * looks inside.
 */
fun NavGraphBuilder.cameraGraph() {
    composable(route = CameraDestination.ROUTE) {
        CameraScreen()
    }
}
