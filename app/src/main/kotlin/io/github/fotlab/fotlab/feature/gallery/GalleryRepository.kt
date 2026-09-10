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
}
