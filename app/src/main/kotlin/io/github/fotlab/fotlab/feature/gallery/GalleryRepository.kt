package io.github.fotlab.fotlab.feature.gallery

import androidx.room.withTransaction
import io.github.fotlab.fotlab.feature.library.FsNodeObject
import io.github.fotlab.fotlab.feature.library.FsNodeRelation
import kotlinx.coroutines.flow.Flow

/**
 * Lower-layer access for the gallery's virtual file tree, owned by the gallery feature.
 * Schema and algorithms are identical to the library — only the database file differs, so gallery
 * data is isolated from library data.
 */
class GalleryRepository(private val database: GalleryDatabase) {

    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>> =
        database.nodeRelationDao().childrenOf(parentId)

    fun parentsOf(childId: Long): Flow<List<FsNodeObject>> =
        database.nodeRelationDao().parentsOf(childId)

    fun collections(): Flow<List<FsNodeObject>> =
        database.nodeObjectDao().observeCollections()

    /** The root node row; `null` only before [ensureRoot] has run. */
    suspend fun rootNode(): FsNodeObject? = database.nodeObjectDao().getById(GalleryRoot.ID)

    suspend fun ensureRoot() {
        database.nodeObjectDao().insertRootIfAbsent(
            rootId = GalleryRoot.ID,
            name = GalleryRoot.NAME,
            typeMime = GalleryRoot.MIME,
            timeCreated = System.currentTimeMillis(),
        )
    }

    // --- Recycle bin reads ---

    fun deletedBatchTimes(): Flow<List<Long>> =
        database.nodeObjectDao().deletedBatchTimes()

    fun deletedNodesAt(time: Long): Flow<List<FsNodeObject>> =
        database.nodeObjectDao().deletedNodesAt(time)

    suspend fun deletedNodeIdsAt(time: Long): List<Long> =
        database.nodeObjectDao().deletedNodeIdsAt(time)

    fun deletedRelationsAt(time: Long): Flow<List<FsNodeRelation>> =
        database.nodeRelationDao().deletedRelationsAt(time)

    // --- Tree mutations ---

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

    suspend fun link(childId: Long, parentId: Long) {
        database.withTransaction {
            val dao = database.nodeRelationDao()
            dao.reviveRelation(childId, parentId)
            dao.insert(FsNodeRelation(childId, parentId))
        }
    }

    suspend fun unlink(childId: Long, parentId: Long) {
        database.nodeRelationDao().removeRelation(childId, parentId, System.currentTimeMillis())
    }

    suspend fun wouldCreateCycle(childId: Long, parentId: Long): Boolean {
        val relationDao = database.nodeRelationDao()
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>().apply { add(parentId) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node == childId) return true
            if (!visited.add(node)) continue
            for (parent in relationDao.parentIdsOf(node)) {
                if (parent !in visited) queue.addLast(parent)
            }
        }
        return false
    }

    suspend fun removeNode(node: FsNodeObject) {
        node.fsNodeId?.let { deleteNodes(setOf(it)) }
    }

    suspend fun getByUri(uri: String): FsNodeObject? =
        database.nodeObjectDao().getByUri(uri)

    suspend fun getByUriAnyStatus(uri: String): FsNodeObject? =
        database.nodeObjectDao().getByUriAnyStatus(uri)

    suspend fun revive(id: Long, nameDisplay: String, typeMime: String) =
        database.nodeObjectDao().revive(id, nameDisplay, typeMime)

    suspend fun renameNode(id: Long, name: String) =
        database.nodeObjectDao().rename(id, name)

    suspend fun fileEntryNodes(): List<FsNodeObject> =
        database.nodeObjectDao().fileEntryNodes(MimeCollection, GalleryRoot.MIME)

    suspend fun orphanNodeIds(): List<Long?> =
        database.nodeRelationDao().orphanNodeIds(GalleryRoot.MIME)

    suspend fun sweepOrphanRelations(timeDeleted: Long): Int =
        database.nodeRelationDao().stampRelationsWithDeadParent(timeDeleted)

    private suspend fun deletableIds(ids: Collection<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        return database.nodeObjectDao().idsExcludingRootKind(ids.toList(), GalleryRoot.MIME)
    }

    // --- Soft deletion ---

    suspend fun deleteNodes(nodeIds: Collection<Long>) {
        val targets = deletableIds(nodeIds)
        if (targets.isEmpty()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            val pending = ArrayDeque<Long>()
            pending.addAll(targets)
            while (pending.isNotEmpty()) {
                markDeleted(pending.removeLast(), now, pending)
            }
        }
    }

    suspend fun selectedDirectlyUnder(ids: Collection<Long>, parentId: Long): Boolean {
        val relationDao = database.nodeRelationDao()
        return ids.all { id -> relationDao.getRelation(id, parentId) != null }
    }

    private suspend fun markDeleted(
        nodeId: Long,
        now: Long,
        pending: ArrayDeque<Long>,
    ) {
        val relationDao = database.nodeRelationDao()

        val node = database.nodeObjectDao().getById(nodeId) ?: return
        if (node.timeDeleted != null) return

        for (relation in relationDao.relationsWithChild(nodeId)) {
            relationDao.update(relation.copy(timeDeleted = now))
        }

        for (relation in relationDao.relationsWithParent(nodeId)) {
            relationDao.update(relation.copy(timeDeleted = now))
            val childId = relation.fsNodeIdChild
            if (relationDao.activeParentCount(childId) == 0) {
                pending.addLast(childId)
            }
        }

        database.nodeObjectDao().markDeleted(nodeId, now)
    }

    // --- Recycle bin actions ---

    suspend fun restoreFromBin(ids: List<Long>) {
        if (ids.isEmpty()) return
        val objectDao = database.nodeObjectDao()
        val relationDao = database.nodeRelationDao()
        val batches = ids.mapNotNull { objectDao.getById(it)?.timeDeleted }.toSet()
        if (batches.isEmpty()) return
        database.withTransaction {
            for (time in batches) {
                objectDao.restoreNodesByBatch(time)
                relationDao.restoreRelationsByBatch(time)
            }
        }
    }

    suspend fun deleteForever(ids: List<Long>) {
        val targets = deletableIds(ids)
        if (targets.isEmpty()) return
        val objectDao = database.nodeObjectDao()
        database.withTransaction {
            val relationDao = database.nodeRelationDao()
            val toDelete = mutableSetOf<Long>()
            val queue = ArrayDeque<Pair<Long, Long>>()
            for (id in targets) {
                val batch = objectDao.getById(id)?.timeDeleted ?: continue
                queue.addLast(id to batch)
            }
            while (queue.isNotEmpty()) {
                val (node, batch) = queue.removeFirst()
                if (!toDelete.add(node)) continue
                for (childId in relationDao.batchChildIdsOf(node, batch)) {
                    if (childId !in toDelete && objectDao.getById(childId)?.timeDeleted == batch) {
                        queue.addLast(childId to batch)
                    }
                }
            }
            relationDao.deleteRelationsForever(toDelete.toList())
            objectDao.deleteNodesForever(toDelete.toList())
        }
    }
}
