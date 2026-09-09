package io.github.fotlab.fotlab.feature.gallery

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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

    suspend fun removeNode(node: FsNodeObject) {
        database.nodeObjectDao().delete(node)
    }

    suspend fun getByUri(uri: String): FsNodeObject? =
        database.nodeObjectDao().getByUri(uri)

    /** All file-entry nodes (non-folder); the refresh check filters out the missing ones (R10). */
    suspend fun fileEntryNodes(): List<FsNodeObject> =
        database.nodeObjectDao().fileEntryNodes(MimeCollection)

    /** All orphan nodes (no parent relation); archived by refresh (R10). */
    suspend fun orphanNodeIds(): List<Long?> =
        database.nodeRelationDao().orphanNodeIds()

    // --- Deletion and refresh archiving (`FOTLAB-DATABS-000002` R9–R13, `FOTLAB-UIXDES-000004` R10) ---

    /**
     * Remove nodes by **archiving** them — nothing is dropped silently and no physical
     * file is ever touched.
     *
     * Used by both the delete action and the refresh reconciliation (`FOTLAB-UIXDES-000004`
     * R10): every row this writes, in both recycle tables, carries [batchId], so the whole
     * operation can be recognised afterwards as one batch — identical in meaning whether the
     * trigger was a user delete or a refresh. The entire delete runs in a single transaction:
     * an interrupted delete leaves either the complete batch or nothing (R13).
     */
    suspend fun deleteNodes(nodeIds: Collection<Long>, batchId: Long) {
        database.withTransaction {
            val pending = ArrayDeque<Long>()
            pending.addAll(nodeIds)
            while (pending.isNotEmpty()) {
                archiveAndRemove(pending.removeLast(), batchId, pending)
            }
        }
    }

    /**
     * Archive one node and the relations that leave with it.
     *
     * 1. archive + remove the relations where the node is the child (its link up);
     * 2. for a collection, archive + remove every relation where it is the parent, and
     *    for each child: keep it when another parent survives, otherwise queue it as an
     *    orphan so the rule is applied to it in turn (recursion through [pending]);
     * 3. archive + remove the node row itself.
     */
    private suspend fun archiveAndRemove(
        nodeId: Long,
        batchId: Long,
        pending: ArrayDeque<Long>,
    ) {
        val relationDao = database.nodeRelationDao()

        for (relation in relationDao.relationsWithChild(nodeId)) {
            archiveRelation(relation, batchId)
            relationDao.delete(relation)
        }

        for (relation in relationDao.relationsWithParent(nodeId)) {
            archiveRelation(relation, batchId)
            relationDao.delete(relation)
            val childId = relation.fsNodeIdChild
            // Still has a parent: it stays exactly where it is (R12 step 2c).
            if (relationDao.parentCount(childId) == 0) {
                pending.addLast(childId)
            }
        }

        val node = database.nodeObjectDao().getById(nodeId) ?: return
        val nodeIdValue = node.fsNodeId ?: return
        database.nodeObjectRecycleDao().insert(
            FsNodeObjectRecycle(
                idRecycle = batchId,
                fsNodeId = nodeIdValue,
                nameDisplay = node.nameDisplay,
                typeMime = node.typeMime,
                uriStorage = node.uriStorage,
                timeModified = node.timeModified,
                timeCreated = node.timeCreated,
            ),
        )
        database.nodeObjectDao().delete(node)
    }

    private suspend fun archiveRelation(relation: FsNodeRelation, batchId: Long) {
        database.nodeRelationRecycleDao().insert(
            FsNodeRelationRecycle(
                idRecycle = batchId,
                fsNodeIdChild = relation.fsNodeIdChild,
                fsNodeIdParent = relation.fsNodeIdParent ?: FsNodeParentRootId,
            ),
        )
    }

    /** Reclaim space freed by archived rows (R10/R14); runs outside the archive transaction. */
    suspend fun vacuum() {
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL("VACUUM")
        }
    }
}
