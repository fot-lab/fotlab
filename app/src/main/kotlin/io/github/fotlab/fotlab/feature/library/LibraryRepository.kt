package io.github.fotlab.fotlab.feature.library

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Lower-layer access for the library's virtual file tree, owned by the feature
 * (`FOTLAB-DATABS-000001` R3, `FOTLAB-STRUCT-000001`). UI reaches it through
 * [LibraryCore]; it never depends on `ui` or `navigation`.
 *
 * Reads return [Flow] so the UI reacts to changes; writes are `suspend` and run
 * inside a transaction where they touch more than one table (`FOTLAB-DATABS-000001`
 * R4).
 */
class LibraryRepository(private val database: LibraryDatabase) {

    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>> =
        database.nodeRelationDao().childrenOf(parentId)

    fun rootChildren(): Flow<List<FsNodeObject>> =
        database.nodeRelationDao().rootChildren()

    fun parentsOf(childId: Long): Flow<List<FsNodeObject>> =
        database.nodeRelationDao().parentsOf(childId)

    fun collections(): Flow<List<FsNodeObject>> =
        database.nodeObjectDao().observeCollections()

    // --- Recycle bin reads (`FOTLAB-DATABS-000002`, per-batch soft deletion) ---

    /** Distinct delete-batch timestamps, newest first; drives the recycle root listing. */
    fun deletedBatchTimes(): Flow<List<Long>> =
        database.nodeObjectDao().deletedBatchTimes()

    /** Nodes soft-deleted in the batch stamped at [time]. */
    fun deletedNodesAt(time: Long): Flow<List<FsNodeObject>> =
        database.nodeObjectDao().deletedNodesAt(time)

    /** Edges soft-deleted in the batch stamped at [time]; rebuilds the batch's directory tree. */
    fun deletedRelationsAt(time: Long): Flow<List<FsNodeRelation>> =
        database.nodeRelationDao().deletedRelationsAt(time)

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

    /** Unlink a child from a parent; the edge is soft-deleted, never physically removed (R10, revised). */
    suspend fun unlink(childId: Long, parentId: Long?) {
        database.nodeRelationDao().removeRelation(childId, parentId, System.currentTimeMillis())
    }

    /**
     * True if linking [childId] under [parentId] would introduce a cycle into the virtual tree.
     *
     * Edges are directed (`child → parent`, "child is under parent"). A cycle appears exactly
     * when the current graph already contains a directed path from [parentId] to [childId] along
     * those upward edges — i.e. [childId] is a (transitive) ancestor of [parentId]. Linking then
     * closes the loop. A `NULL` [parentId] is the root and can never close a cycle; a node linked
     * under itself is a trivial self-cycle.
     *
     * The loose design allows a node to have several parents, so the upward walk is a BFS over all
     * live parent links rather than a single chain. The walk is purely read-only; it does not add
     * any edge (`FOTLAB-DATABS-000002`, cycle invariant, to be enforced by callers).
     */
    suspend fun wouldCreateCycle(childId: Long, parentId: Long?): Boolean {
        if (parentId == null) return false
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

    /** Soft-delete a single node and the subtree it orphans (`FOTLAB-DATABS-000002` R10/R12, revised). */
    suspend fun removeNode(node: FsNodeObject) {
        node.fsNodeId?.let { deleteNodes(setOf(it)) }
    }

    suspend fun getByUri(uri: String): FsNodeObject? =
        database.nodeObjectDao().getByUri(uri)

    /** Rename a node by id (display name only); reused by the single-selection rename action. */
    suspend fun renameNode(id: Long, name: String) =
        database.nodeObjectDao().rename(id, name)

    /** All live file-entry nodes (non-folder); the refresh check filters out the missing ones (R10). */
    suspend fun fileEntryNodes(): List<FsNodeObject> =
        database.nodeObjectDao().fileEntryNodes(MimeCollection)

    /** All live orphan nodes (no live parent relation); recycled by refresh (R10, revised). */
    suspend fun orphanNodeIds(): List<Long?> =
        database.nodeRelationDao().orphanNodeIds()

    // --- Soft deletion (`FOTLAB-DATABS-000002` R9–R13, `FOTLAB-UIXDES-000004` R10, revised) ---

    /**
     * Remove nodes by **soft-deleting** them — nothing is dropped or archived, no physical
     * file is ever touched. Each removed node (and each relation that leaves with it) just
     * gets its `time_deleted` stamped, so the node id and edge id spaces stay unique across
     * live and removed rows (`FOTLAB-DATABS-000002` R10, revised).
     *
     * Used by both the delete action and the refresh reconciliation (`FOTLAB-UIXDES-000004`
     * R10): the whole operation is one batch sharing the same timestamp, so it can be
     * recognised afterwards as one unit. The entire delete runs in a single transaction: an
     * interrupted delete leaves either the complete batch or nothing (R13).
     */
    suspend fun deleteNodes(nodeIds: Collection<Long>) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val pending = ArrayDeque<Long>()
            pending.addAll(nodeIds)
            while (pending.isNotEmpty()) {
                markDeleted(pending.removeLast(), now, pending)
            }
        }
    }

    /**
     * True when every id is a direct, live child of [parentId] (null = the implicit root).
     * Used to gate deletion on the delete press: only the selected level is checked, the
     * subtree removed by recursion is not re-validated (`FOTLAB-UIXDES-000004` R10, guard).
     */
    suspend fun selectedDirectlyUnder(ids: Collection<Long>, parentId: Long?): Boolean {
        val relationDao = database.nodeRelationDao()
        return ids.all { id -> relationDao.getRelation(id, parentId) != null }
    }

    /**
     * Soft-delete one node and the relations that leave with it.
     *
     * 1. stamp the relations where the node is the child (its link up);
     * 2. for a collection, stamp every relation where it is the parent, and for each child:
     *    keep it when another parent survives, otherwise queue it as an orphan so the rule
     *    is applied to it in turn (recursion through [pending]);
     * 3. stamp the node row itself.
     *
     * Idempotent: a node already stamped is skipped, so a node appearing more than once in
     * the work queue (or reached through two paths) is never processed twice (R12).
     */
    private suspend fun markDeleted(
        nodeId: Long,
        now: Long,
        pending: ArrayDeque<Long>,
    ) {
        val relationDao = database.nodeRelationDao()

        val node = database.nodeObjectDao().getById(nodeId) ?: return
        // Already soft-deleted (e.g. reached by two paths): leave it and its subtree alone.
        if (node.timeDeleted != null) return

        for (relation in relationDao.relationsWithChild(nodeId)) {
            relationDao.update(relation.copy(timeDeleted = now))
        }

        for (relation in relationDao.relationsWithParent(nodeId)) {
            relationDao.update(relation.copy(timeDeleted = now))
            val childId = relation.fsNodeIdChild
            // Still has a live parent: it stays exactly where it is (R12 step 2c).
            if (relationDao.activeParentCount(childId) == 0) {
                pending.addLast(childId)
            }
        }

        database.nodeObjectDao().markDeleted(nodeId, now)
    }

    // --- Recycle bin actions (`FOTLAB-DATABS-000002` R12, restore / delete forever) ---

    /**
     * Restore soft-deleted nodes back into the live library **by batch**: every node and every edge
     * stamped with the same `time_deleted` as the selected ids has its stamp cleared, so the whole
     * delete operation returns to the live tree exactly as it was — the batch becomes a normal,
     * undeleted object (`FOTLAB-DATABS-000002` R12, recycle restore). Runs in one transaction.
     *
     * A selected id is only used to discover its batch timestamp; restore is never limited to the
     * selected subset, because a delete is one atomic unit that must be undone as one unit.
     */
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

    /**
     * Permanently delete nodes from the bin — a real removal, not a soft-delete. The whole subtree
     * rooted at each selected id is collected and dropped together with every edge touching it, so
     * the record, its child-node records and the DAG edges among them are all gone
     * (`FOTLAB-DATABS-000002` R12, delete forever).
     *
     * The subtree is walked over edges stamped with the same `time_deleted` as the selected node
     * (`batchChildIdsOf`), which only follows into *other bin nodes* of that batch — a live child
     * that merely shared membership with a deleted collection keeps its live edge and is never
     * hard-deleted. Deleting a node row cascade-removes the relations referencing it (R8), and the
     * explicit edge delete covers the rest, so no dangling edge survives. Runs in one transaction.
     */
    suspend fun deleteForever(ids: List<Long>) {
        if (ids.isEmpty()) return
        val objectDao = database.nodeObjectDao()
        database.withTransaction {
            val relationDao = database.nodeRelationDao()
            val toDelete = mutableSetOf<Long>()
            val queue = ArrayDeque<Pair<Long, Long>>()
            for (id in ids) {
                val batch = objectDao.getById(id)?.timeDeleted ?: continue
                queue.addLast(id to batch)
            }
            while (queue.isNotEmpty()) {
                val (node, batch) = queue.removeFirst()
                if (!toDelete.add(node)) continue
                for (childId in relationDao.batchChildIdsOf(node, batch)) {
                    if (childId !in toDelete) queue.addLast(childId to batch)
                }
            }
            relationDao.deleteRelationsForever(toDelete.toList())
            objectDao.deleteNodesForever(toDelete.toList())
        }
    }
}
