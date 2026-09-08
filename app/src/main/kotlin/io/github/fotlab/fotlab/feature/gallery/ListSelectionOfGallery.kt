package io.github.fotlab.fotlab.feature.gallery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The selection that drives the gallery top bar (`FOTLAB-UIXDES-000004` R2/R3).
 *
 * **Process-scoped**: the single instance is owned by [GalleryCore] and lives exactly
 * as long as the process. It is never persisted and never written to saved instance
 * state, so every process starts with an empty selection and nothing is restored
 * behind the app's back (R3, C6).
 *
 * It holds **node identities** (`fs_node_id`), never node copies, so a node whose
 * properties change while selected stays the same selection (C7).
 *
 * The full name is deliberate: another feature may drive its own top bar from its own
 * selection list, and a short generic name would collide (R2).
 */
class ListSelectionOfGallery internal constructor() {

    private val mutableIds = MutableStateFlow<Set<Long>>(emptySet())

    /** Currently selected node ids. The UI observes this to render the top bar. */
    val selected: StateFlow<Set<Long>> = mutableIds.asStateFlow()

    val size: Int get() = mutableIds.value.size

    fun isEmpty(): Boolean = mutableIds.value.isEmpty()

    operator fun contains(nodeId: Long): Boolean = nodeId in mutableIds.value

    /** Add the node when absent, remove it when present. */
    fun toggle(nodeId: Long) {
        val current = mutableIds.value
        mutableIds.value = if (nodeId in current) current - nodeId else current + nodeId
    }

    /** Select every node of the given directory listing, leaving other selections alone. */
    fun selectAll(candidates: Collection<Long>) {
        mutableIds.value = mutableIds.value + candidates
    }

    /** Swap selected and unselected within the given directory listing. */
    fun invert(candidates: Collection<Long>) {
        val before = mutableIds.value
        mutableIds.value =
            (candidates.filterNot { it in before } + before.filterNot { it in candidates }).toSet()
    }

    fun clear() {
        mutableIds.value = emptySet()
    }
}
