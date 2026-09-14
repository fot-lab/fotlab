package io.github.fotlab.fotlab.feature.studio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lower layer of the Studio feature (`FOTLAB-STRUCT-000001`), analogous to `LibraryCore`.
 *
 * Studio is a Snapseed-style editor. The node it shows on its canvas is produced by the Studio top
 * bar's open action, which adds the picked file to the Library's current directory
 * (`LibraryCore.importUris`) and then surfaces it here through its virtual `uri_storage` path — the
 * physical file is never touched (`FOTLAB-IMGMGR-000001` R1/R3). Studio reads that path and renders it.
 *
 * TODO: the current render path is Coil `AsyncImage` over the raw `uri_storage` (the same path the
 * Library viewer uses). Replace it with format sniffing + RAW decode (dnglab / rawler, see
 * `FOTLAB-IMGMGR-000001` / `FOTLAB-NATIVE-000001`) so DNG/RAW land on the canvas instead of being
 * handed to the platform decoder. Keep this node reference as the decode target when that lands.
 */
object StudioEngine {

    /**
     * The virtual `uri_storage` of the node currently shown on the Studio canvas, or `null` when
     * nothing has been opened yet. Process-scoped; never persisted.
     */
    private val currentNodeUriState = MutableStateFlow<String?>(null)
    val currentNodeUri: StateFlow<String?> = currentNodeUriState.asStateFlow()

    /** Point the canvas at [uri] (the virtual node's `uri_storage`). */
    fun setCurrentNode(uri: String?) {
        currentNodeUriState.value = uri
    }
}
