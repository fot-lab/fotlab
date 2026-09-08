package io.github.fotlab.fotlab.feature.gallery

import android.content.Context
import androidx.room.Room
import io.github.fotlab.fotlab.R
import kotlinx.coroutines.flow.Flow

/**
 * Lower layer of the gallery feature (`FOTLAB-STRUCT-000001`).
 *
 * Builds and owns the gallery's Room database (the two-table `fs_node` schema of
 * `FOTLAB-DATABS-000002`) and exposes it through [GalleryRepository]. The UI
 * (`GalleryScreen`) depends on this class; this class never depends on `ui` or
 * `navigation` (R3).
 */
object GalleryCore {

    /** Resource id of the gallery's display name, owned by the feature core. */
    val titleRes: Int = R.string.gallery_title

    private var repository: GalleryRepository? = null

    /** Build the gallery database. Call once from the application context. */
    fun prepare(context: Context) {
        if (repository != null) return
        val database = Room.databaseBuilder(
            context,
            GalleryDatabase::class.java,
            "gallery",
        ).build()
        repository = GalleryRepository(database)
    }

    private fun repo(): GalleryRepository =
        repository ?: error("GalleryCore.prepare(context) must be called before use")

    // --- Tree queries, delegated to the repository ---

    fun rootChildren(): Flow<List<FsNodeObject>> = repo().rootChildren()

    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>> = repo().childrenOf(parentId)

    fun parentsOf(childId: Long): Flow<List<FsNodeObject>> = repo().parentsOf(childId)

    fun collections(): Flow<List<FsNodeObject>> = repo().collections()

    suspend fun addNode(
        nameDisplay: String,
        typeMime: String,
        uriStorage: String? = null,
        timeCreated: Long,
        timeModified: Long? = null,
    ): Long = repo().addNode(nameDisplay, typeMime, uriStorage, timeCreated, timeModified)

    suspend fun link(childId: Long, parentId: Long?) = repo().link(childId, parentId)

    suspend fun unlink(childId: Long, parentId: Long?) = repo().unlink(childId, parentId)

    suspend fun removeNode(node: FsNodeObject) = repo().removeNode(node)

    suspend fun getByUri(uri: String): FsNodeObject? = repo().getByUri(uri)
}
