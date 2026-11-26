package io.github.fotlab.fotlab.feature.camera

import androidx.annotation.StringRes

/**
 * The two views the camera drawer's tabs switch between.
 *
 * `Storage` is the camera's own file tree; `Queue` is the transfer queue
 * (`FOTLAB-DATABS-000004`) — the transfers the user asked for and the ones still to come.
 *
 * The state lives in the screen, like the library's view mode (`FOTLAB-UIXDES-000004`): it is
 * a presentation choice, not something worth persisting.
 */
enum class CameraViewMode(@StringRes val labelRes: Int) {
    /** The camera's storages and the files in them. */
    STORAGE(R.string.camera_tab_storage),

    /** The transfer queue. */
    QUEUE(R.string.camera_tab_queue),
}
