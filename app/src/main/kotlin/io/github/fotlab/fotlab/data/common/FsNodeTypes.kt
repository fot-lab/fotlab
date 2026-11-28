package io.github.fotlab.fotlab.data.common

/**
 * Per-library root configuration.
 *
 * Every feature that owns an `fs_node` database (Library, Gallery, ...) creates one
 * `RootConfig` naming its root node, then passes it into the shared [FsNodeRepository]
 * — so the repository never needs to know which feature it is operating on.
 */
data class RootConfig(
    /** The root's fixed primary key. */
    val id: Long,
    /** Display name shown as the root. */
    val name: String,
    /** Distinct MIME kind that marks this tree's root (never mistaken for a collection or file). */
    val mime: String,
)

/** MIME value that marks a collection (a user folder). */
const val MimeCollection = "application/folder"

/** MIME for the virtual per-batch folder shown at the recycle root; presentation only. */
const val MimeFolderDeleted = "application/folder_deleted"

/** A collection is a node with the folder MIME; anything else is a file entry. */
fun FsNodeObject.isCollection(): Boolean = typeMime == MimeCollection
