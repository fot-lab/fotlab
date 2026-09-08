package io.github.fotlab.fotlab.feature.gallery

import io.github.fotlab.fotlab.R

/**
 * Lower layer of the gallery feature (`FOTLAB-STRUCT-000001`).
 *
 * Holds the gallery's business logic and persistence access for the virtual file
 * tree — the two-table `fs_node` schema defined in `FOTLAB-DATABS-000002`. The UI
 * (`GalleryScreen`) depends on this class; this class never depends on `ui` or
 * `navigation` (R3). Repository/DAO wiring lands here once the schema is built.
 */
object GalleryCore {

    /** Resource id of the gallery's display name, owned by the feature core. */
    val titleRes: Int = R.string.gallery_title

    // TODO: repository / data access for the virtual file tree (FOTLAB-DATABS-000002).
}
