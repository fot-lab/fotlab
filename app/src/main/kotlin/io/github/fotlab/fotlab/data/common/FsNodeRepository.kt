package io.github.fotlab.fotlab.data.common

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow

/**
 * Parameterised lower-layer access for any feature's virtual file tree.
 *
 * One [FsNodeDatabase] instance (one SQLite file) + one [RootConfig] = one tree.
 * The class contains zero feature-specific knowledge: which root MIME to protect
 * during deletes, which root id to query for the top level, and which root name to
 * insert — all of that comes from [root].
 *
 * Reads return [Flow] so callers react to changes; writes are `suspend` and run
 * inside a transaction where they touch more than one table. Every write path is
 * idempotent so the repository can safely be driven from retry code.
 */
class FsNodeRepository(
    private val database: FsNodeDatabase,
    private val root: RootConfig,
) {

    // --- DAO shortcuts ---

    private fun objectDao(): FsNodeObjectDao = database.nodeObjectDao()
    private fun relationDao(): FsNodeRelationDao = database.nodeRelationDao()

    // --- Root bootstrap ---

    /**
     * Create the root node row when it is missing.
     *
     * The root is an ordinary row with the fixed [RootConfig.id] and its own node kind
     * ([RootConfig.mime]), so nothing else in the tree needs to know about it. The insert
     * is idempotent — the primary key turns a later call into a no-op — which is what makes
     * it safe to run on every process start.
     *
     * Deliberately does **not** attempt to repair a database written before the root became a
     * real row: no edge is re-pointed, nothing is migrated. That older data is not repaired by
     * design; reinstalling gives a clean tree.
     */
    suspend fun ensureRoot() {
        objectDao().insertRootIfAbsent(
            rootId = root.id,
            name = root.name,
            typeMime = root.mime,
            timeCreated = System.currentTimeMillis(),
        )
    }

    // --- Tree queries ---

    /** The root node row; `null` only before [ensureRoot] has run. */
    suspend fun rootNode(): FsNodeObject? = objectDao().getById(root.id)

    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>> =
        relationDao().childrenOf(parentId)

    fun parentsOf(childId: Long): Flow<List<FsNodeObject>> =
        relationDao().parentsOf(childId)

    /** Every live user collection, ordered by name. The root is excluded by its own MIME kind. */
    fun collections(): Flow<List<FsNodeObject>> =
        objectDao().observeCollections()

    // --- Recycle bin reads (per-batch soft deletion) ---

    /** Distinct delete-batch timestamps, newest first; drives the recycle root listing. */
    fun deletedBatchTimes(): Flow<List<Long>> =
        objectDao().deletedBatchTimes()

    /** Nodes soft-deleted in the batch stamped at [time]. */
    fun deletedNodesAt(time: Long): Flow<List<FsNodeObject>> =
        objectDao().deletedNodesAt(time)

    /** Just the ids of every node in the soft-delete batch stamped at [time]. */
    suspend fun deletedNodeIdsAt(time: Long): List<Long> =
        objectDao().deletedNodeIdsAt(time)

    /** Edges soft-deleted in the batch stamped at [time]; rebuilds the batch's directory tree. */
    fun deletedRelationsAt(time: Long): Flow<List<FsNodeRelation>> =
        relationDao().deletedRelationsAt(time)

    // --- Node CRUD ---

    /**
     * Insert a node. `uriStorage` must be unique (UNIQUE index) — call [getByUri]
     * first to dedupe a physical file.
     */
    suspend fun addNode(
        nameDisplay: String,
        typeMime: String,
        uriStorage: String? = null,
        timeCreated: Long,
        timeModified: Long? = null,
    ): Long = objectDao().insert(
        FsNodeObject(
            nameDisplay = nameDisplay,
            typeMime = typeMime,
            uriStorage = uriStorage,
            timeCreated = timeCreated,
            timeModified = timeModified,
        ),
    )

    /** Add an edge (child under parent); pass the root's id for the top level. */
    suspend fun link(childId: Long, parentId: Long) {
        database.withTransaction {
            // A re-imported (revived) node keeps its old, soft-deleted edge: revive it first,
            // otherwise the unique index rejects the insert and the live node would stay an
            // orphan the grid never lists.
            relationDao().reviveRelation(childId, parentId)
            relationDao().insert(FsNodeRelation(childId, parentId))
        }
    }

    /** Unlink a child from a parent; the edge is soft-deleted, never physically removed. */
    suspend fun unlink(childId: Long, parentId: Long) {
        relationDao().removeRelation(childId, parentId, System.currentTimeMillis())
    }

    /** Get a live node by URI. */
    suspend fun getByUri(uri: String): FsNodeObject? =
        objectDao().getByUri(uri)

    /**
     * Find any node with this URI regardless of soft-delete status. Used by import routines to
     * revive a soft-deleted node when the same file is re-imported, instead of hitting the
     * UNIQUE index on `uri_storage` with a duplicate INSERT.
     */
    suspend fun getByUriAnyStatus(uri: String): FsNodeObject? =
        objectDao().getByUriAnyStatus(uri)

    /** Clear the soft-delete stamp so a previously removed node becomes live again. */
    suspend fun revive(id: Long, nameDisplay: String, typeMime: String) =
        objectDao().revive(id, nameDisplay, typeMime)

    /** Rename a node by id (display name only). */
    suspend fun renameNode(id: Long, name: String) =
        objectDao().rename(id, name)

    // --- Cycle detection ---

    /**
     * True if linking [childId] under [parentId] would introduce a cycle into the virtual tree.
     *
     * Edges are directed (`child → parent`, "child is under parent"). A cycle appears exactly
     * when the current graph already contains a directed path from [parentId] to [childId] along
     * those upward edges — i.e. [childId] is a (transitive) ancestor of [parentId]. Linking then
     * closes the loop. The root has no parent edge, so a walk up from it ends immediately and
     * linking under the root can never cycle; a node linked under itself is a trivial self-cycle.
     *
     * The loose design allows a node to have several parents, so the upward walk is a BFS over all
     * live parent links rather than a single chain. The walk is purely read-only.
     */
    suspend fun wouldCreateCycle(childId: Long, parentId: Long): Boolean {
        val visited = mutableSetOf<Long>()
        val queue = ArrayDeque<Long>().apply { add(parentId) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node == childId) return true
            if (!visited.add(node)) continue
            for (parent in relationDao().parentIdsOf(node)) {
                if (parent !in visited) queue.addLast(parent)
            }
        }
        return false
    }

    // --- Refresh reconciliation ---

    /** All live file-entry nodes (neither a collection nor the root); the refresh check filters out the missing ones. */
    suspend fun fileEntryNodes(): List<FsNodeObject> =
        objectDao().fileEntryNodes(MimeCollection, root.mime)

    /** All live orphan nodes (no effective live parent edge); reaped by refresh. */
    suspend fun orphanNodeIds(): List<Long?> =
        relationDao().orphanNodeIds(root.mime)

    /**
     * Sweep the relation records that no longer describe a place in the tree: a live edge whose
     * parent is `NULL`, or whose parent node is itself soft-deleted. Stamped, never dropped,
     * and batched under [timeDeleted] so it can be recognised — and undone — as one unit.
     */
    suspend fun sweepOrphanRelations(timeDeleted: Long): Int =
        relationDao().stampRelationsWithDeadParent(timeDeleted)

    // --- Soft deletion ---

    /** Soft-delete a single node and the subtree it orphans. */
    suspend fun removeNode(node: FsNodeObject) {
        node.fsNodeId?.let { deleteNodes(setOf(it)) }
    }

    /**
     * The subset of [ids] the delete gates may act on: everything whose node kind is not the
     * root's. The root is refused by its kind ([RootConfig.mime]) rather than by its id, so
     * the protection is a property of the node, not a hard-coded key.
     */
    private suspend fun deletableIds(ids: Collection<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        return objectDao().idsExcludingRootKind(ids.toList(), root.mime)
    }

    /**
     * Remove nodes by **soft-deleting** them — nothing is dropped or archived, no physical
     * file is ever touched. Each removed node (and each relation that leaves with it) just
     * gets its `time_deleted` stamped, so the node id and edge id spaces stay unique across
     * live and removed rows.
     *
     * Used by both the delete action and the refresh reconciliation. The whole operation is
     * one batch sharing the same timestamp, so it can be recognised afterwards as one unit.
     * Runs in a single transaction: an interrupted delete leaves either the complete batch or
     * nothing.
     */
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

    /**
     * True when every id is a direct, live child of [parentId] — i.e. belongs to the directory
     * currently on screen. Used to gate deletion on the delete press: only the selected level
     * is checked, the subtree removed by recursion is not re-validated.
     */
    suspend fun selectedDirectlyUnder(ids: Collection<Long>, parentId: Long): Boolean {
        return ids.all { id -> relationDao().getRelation(id, parentId) != null }
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
     * the work queue (or reached through two paths) is never processed twice.
     */
    private suspend fun markDeleted(
        nodeId: Long,
        now: Long,
        pending: ArrayDeque<Long>,
    ) {
        val node = objectDao().getById(nodeId) ?: return
        // Already soft-deleted (e.g. reached by two paths): leave it and its subtree alone.
        if (node.timeDeleted != null) return

        for (relation in relationDao().relationsWithChild(nodeId)) {
            relationDao().update(relation.copy(timeDeleted = now))
        }

        for (relation in relationDao().relationsWithParent(nodeId)) {
            relationDao().update(relation.copy(timeDeleted = now))
            val childId = relation.fsNodeIdChild
            // Many-to-many: a child can sit under several parents, and only this one edge just
            // died. If any other parent link is still live the child itself is alive and stays
            // exactly where it is (R12 step 2c); the child joins this delete only once its last
            // live parent link is gone.
            //
            // The count is over *edges*, deliberately, and that is not the same test the
            // reconcile's orphan sweep uses. Here the parent whose edge was just stamped is
            // being deleted in this very transaction, so asking "does the parent node still look
            // alive" would still answer yes for a parent that is about to be stamped, and the
            // child would outlive the delete until the next sweep. Counting the edge we just
            // stamped instead makes the recursion self-consistent; a node that was already
            // orphaned by some *earlier* inconsistency is the sweep's business, not ours.
            if (relationDao().activeParentCount(childId) == 0) {
                pending.addLast(childId)
            }
        }

        objectDao().markDeleted(nodeId, now)
    }

    // --- Recycle bin actions (batch-level restore / delete forever) ---

    /**
     * Restore soft-deleted nodes back into the live tree **by batch**: every node and every edge
     * stamped with the same `time_deleted` as the selected ids has its stamp cleared, so the whole
     * delete operation returns to the live tree exactly as it was — the batch becomes a normal,
     * undeleted object. Runs in one transaction.
     *
     * A selected id is only used to discover its batch timestamp; restore is never limited to the
     * selected subset, because a delete is one atomic unit that must be undone as one unit.
     */
    suspend fun restoreFromBin(ids: List<Long>) {
        if (ids.isEmpty()) return
        val batches = ids.mapNotNull { objectDao().getById(it)?.timeDeleted }.toSet()
        if (batches.isEmpty()) return
        database.withTransaction {
            for (time in batches) {
                objectDao().restoreNodesByBatch(time)
                relationDao().restoreRelationsByBatch(time)
            }
        }
    }

    /**
     * Permanently delete nodes from the bin — a real removal, not a soft-delete. The whole subtree
     * rooted at each selected id is collected and dropped together with every edge touching it, so
     * the record, its child-node records and the DAG edges among them are all gone. Runs in one
     * transaction.
     *
     * The subtree is walked over edges stamped with the same `time_deleted` as the selected node
     * ([FsNodeRelationDao.batchChildIdsOf]), which only follows into *other bin nodes* of that
     * batch — a live child that merely shared membership with a deleted collection keeps its live
     * edge and is never hard-deleted. Deleting a node row cascade-removes the relations referencing
     * it, and the explicit edge delete covers the rest.
     */
    suspend fun deleteForever(ids: List<Long>) {
        val targets = deletableIds(ids)
        if (targets.isEmpty()) return
        database.withTransaction {
            val toDelete = mutableSetOf<Long>()
            val queue = ArrayDeque<Pair<Long, Long>>()
            for (id in targets) {
                val batch = objectDao().getById(id)?.timeDeleted ?: continue
                queue.addLast(id to batch)
            }
            while (queue.isNotEmpty()) {
                val (node, batch) = queue.removeFirst()
                if (!toDelete.add(node)) continue
                for (childId in relationDao().batchChildIdsOf(node, batch)) {
                    // Only follow into a child that is itself part of this delete batch — i.e. its
                    // node row is soft-deleted with the same timestamp. A child that still has a live
                    // parent elsewhere is NOT stamped with [batch], so it must survive the hard delete;
                    // pulling it in here would wrongly destroy a node and the edges that belong to the
                    // live library.
                    if (childId !in toDelete && objectDao().getById(childId)?.timeDeleted == batch) {
                        queue.addLast(childId to batch)
                    }
                }
            }
            relationDao().deleteRelationsForever(toDelete.toList())
            objectDao().deleteNodesForever(toDelete.toList())
        }
    }
}
