package io.github.fotlab.fotlab.feature.gallery

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Lower-layer access for the gallery's virtual file tree, owned by the feature
 * (`FOTLAB-DATABS-000001` R3, `FOTLAB-STRUCT-000001`). UI reaches it through
 * [GalleryCore]; it never depends on `ui` or `navigation`.
 *
 * Reads return [Flow] so the UI reacts to changes; writes are `suspend` and run
 * inside a transaction where they touch more than one table (`FOTLAB-DATABS-000001`
 * R4).
 */
class GalleryRepository(private val database: GalleryDatabase) {

    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>> =
        database.nodeRelationDao().childrenOf(parentId)

    fun rootChildren(): Flow<List<FsNodeObject>> =
        database.nodeRelationDao().rootChildren()

    fun parentsOf(childId: Long): Flow<List<FsNodeObject>> =
        database.nodeRelationDao().parentsOf(childId)

    fun collections(): Flow<List<FsNodeObject>> =
        database.nodeObjectDao().observeCollections()

    /**
     * Insert a node. `uriStorage` must be unique (UNIQUE index) — call [getByUri]
     * first to dedupe a physical file (`FOTLAB-DATABS-000002` R7).
     */
    suspend fun addNode(
        nameDisplay: String,
        typeMime: String,
        uriStorage: String? = null,
        timeCreated: Long,
        timeModified: Long? = null,
    ): Long = database.nodeObjectDao().insert(
        FsNodeObject(
            nameDisplay = nameDisplay,
            typeMime = typeMime,
            uriStorage = uriStorage,
            timeCreated = timeCreated,
            timeModified = timeModified,
        ),
    )

    /** Add an edge (child under parent); use `parentId = null` for a root node. */
    suspend fun link(childId: Long, parentId: Long?) {
        database.withTransaction {
            database.nodeRelationDao().insert(FsNodeRelation(childId, parentId))
        }
    }

    suspend fun unlink(childId: Long, parentId: Long?) {
        database.nodeRelationDao().removeRelation(childId, parentId)
    }

    /** Deleting a node cascades to its relations (FK CASCADE); the file is untouched. */
    suspend fun removeNode(node: FsNodeObject) {
        database.nodeObjectDao().delete(node)
    }

    suspend fun getByUri(uri: String): FsNodeObject? =
        database.nodeObjectDao().getByUri(uri)
}
