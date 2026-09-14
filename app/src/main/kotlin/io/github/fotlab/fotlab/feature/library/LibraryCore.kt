package io.github.fotlab.fotlab.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** MIME value that marks a collection (`FOTLAB-DATABS-000002` R4). */
const val MimeCollection = "application/folder"

/** MIME for the virtual per-batch folder shown at the recycle root; presentation only, not an fs node. */
const val MimeFolderDeleted = "application/folder_deleted"

/** Fallback MIME when the platform cannot tell us the type of a picked file. */
private const val MimeUnknown = "application/octet-stream"

/** A collection is a node with the folder MIME; anything else is a file entry. */
fun FsNodeObject.isCollection(): Boolean = typeMime == MimeCollection

/**
 * Lower layer of the library feature (`FOTLAB-STRUCT-000001`).
 *
 * Builds and owns the library's Room database (the two-table `fs_node` schema of
 * `FOTLAB-DATABS-000002`) and exposes it through [LibraryRepository]. This object also
 * owns the single [ListSelectionOfLibrary] instance, which is therefore process-scoped
 * (`FOTLAB-UIXDES-000004` R3), and the current [LibraryLayoutMode], a process-scoped observable
 * that is persisted to a `DataStore` preference (`FOTLAB-UIXDES-000004` R9). The UI
 * (`LibraryScreen`) depends on this class; this class never depends on `ui` or `navigation` (R3).
 */
object LibraryCore {

    /**
     * The one selection instance of the process. Read by the screen, mutated only
     * through here; never persisted and never restored from saved state
     * (`FOTLAB-UIXDES-000004` R3/C6).
     */
    val selection: ListSelectionOfLibrary = ListSelectionOfLibrary()

    /**
     * Whether the library is in the selection action mode. Unlike the bare selection set, this
     * is an explicit UI mode: it is switched on by a long-press ([enterSelectionMode]) and switched
     * off only by the top bar's close button ([exitSelectionMode]). Therefore the mode survives the
     * selection being emptied — deselecting everything (or "deselect all") keeps the mode active so
     * the user can keep picking; leaving it is a deliberate X press, not the count reaching zero.
     * Process-scoped like [selection], never persisted and never restored (`FOTLAB-UIXDES-000004` R3).
     */
    private val selectionModeState = MutableStateFlow(false)
    val selectionModeActive: StateFlow<Boolean> = selectionModeState.asStateFlow()

    /** Enter the selection action mode, optionally selecting [nodeId] as the first pick. */
    fun enterSelectionMode(nodeId: Long? = null) {
        selectionModeState.value = true
        if (nodeId != null) selection.select(nodeId)
    }

    /** Leave the selection action mode and clear any selection it held. */
    fun exitSelectionMode() {
        selection.clear()
        selectionModeState.value = false
    }

    /**
     * Observable display mode of the content region. Read by the screen, advanced through
     * [cycleLayoutMode]; the chosen mode is persisted to a `DataStore` and restored on start
     * (`FOTLAB-UIXDES-000004` R9).
     */
    private val layoutModeState = MutableStateFlow(LibraryLayoutMode.DEFAULT)
    val layoutMode: StateFlow<LibraryLayoutMode> = layoutModeState.asStateFlow()

    /**
     * The directory the Library is currently showing in its normal (non-recycle) view, shared so
     * other features can target it. `null` means the virtual root. An import fired from another
     * screen (e.g. Studio's open action) lands here; the recycle-bin view never writes to it
     * (`FOTLAB-UIXDES-000004`). Process-scoped like [selection].
     */
    private val currentDirectoryIdState = MutableStateFlow<Long?>(null)
    val currentDirectoryId: StateFlow<Long?> = currentDirectoryIdState.asStateFlow()
    fun setCurrentDirectory(id: Long?) { currentDirectoryIdState.value = id }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var layoutPreference: LibraryLayoutPreference

    private var repository: LibraryRepository? = null
    private lateinit var applicationContext: Context

    /** Build the library database and the layout preference. Call once from the application context. */
    fun prepare(context: Context) {
        if (repository != null) return
        applicationContext = context.applicationContext
        val database = Room.databaseBuilder(
            applicationContext,
            LibraryDatabase::class.java,
            "library",
        )
            .addMigrations(LibraryDatabase.MIGRATION_1_2, LibraryDatabase.MIGRATION_2_3)
            .build()
        repository = LibraryRepository(database)
        layoutPreference = LibraryLayoutPreference(applicationContext)
        // Restore the persisted mode once at start; default is Grid 3 (R9).
        layoutModeState.value = runBlocking(Dispatchers.IO) { layoutPreference.mode.first() }
    }

    private fun repo(): LibraryRepository =
        repository ?: error("LibraryCore.prepare(context) must be called before use")

    // --- Tree queries, delegated to the repository ---

    fun rootChildren(): Flow<List<FsNodeObject>> = repo().rootChildren()

    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>> = repo().childrenOf(parentId)

    fun parentsOf(childId: Long): Flow<List<FsNodeObject>> = repo().parentsOf(childId)

    fun collections(): Flow<List<FsNodeObject>> = repo().collections()

    // --- Recycle bin queries (`FOTLAB-DATABS-000002`, per-batch soft deletion) ---

    /** Distinct delete-batch timestamps, newest first; drives the recycle root listing. */
    fun deletedBatchTimes(): Flow<List<Long>> = repo().deletedBatchTimes()

    /** Nodes soft-deleted in the batch stamped at [time]. */
    fun recycleBatchNodes(time: Long): Flow<List<FsNodeObject>> = repo().deletedNodesAt(time)

    /** Edges soft-deleted in the batch stamped at [time]; rebuilds the batch's directory tree. */
    fun recycleBatchRelations(time: Long): Flow<List<FsNodeRelation>> = repo().deletedRelationsAt(time)

    /** Restore soft-deleted nodes (and their parent links) back into the live library. */
    suspend fun restoreFromBin(ids: List<Long>) = repo().restoreFromBin(ids)

    /** Permanently remove nodes and their edges from the bin (irreversible). */
    suspend fun deleteForever(ids: List<Long>) = repo().deleteForever(ids)

    suspend fun addNode(
        nameDisplay: String,
        typeMime: String,
        uriStorage: String? = null,
        timeCreated: Long,
        timeModified: Long? = null,
    ): Long = repo().addNode(nameDisplay, typeMime, uriStorage, timeCreated, timeModified)

    suspend fun link(childId: Long, parentId: Long?) = repo().link(childId, parentId)

    suspend fun unlink(childId: Long, parentId: Long?) = repo().unlink(childId, parentId)

    /**
     * True if linking [childId] under [parentId] would create a cycle in the virtual tree
     * (`FOTLAB-DATABS-000002`, cycle invariant). Read-only; callers decide what to do with the
     * result. A `null` [parentId] (root) never cycles; linking a node under itself does.
     */
    suspend fun wouldCreateCycle(childId: Long, parentId: Long?): Boolean =
        repo().wouldCreateCycle(childId, parentId)

    suspend fun removeNode(node: FsNodeObject) = repo().removeNode(node)

    suspend fun getByUri(uri: String): FsNodeObject? = repo().getByUri(uri)

    /**
     * Rename a node by id (display name only). Driven by the single-selection edit action in
     * the top bar (`FOTLAB-UIXDES-000004`); the physical file is never touched.
     */
    suspend fun renameNode(id: Long, name: String) = repo().renameNode(id, name)

    /** Cycle to the next display mode and persist it (`FOTLAB-UIXDES-000004` R9). */
    suspend fun cycleLayoutMode() {
        val next = LibraryLayoutMode.cycle(layoutModeState.value)
        layoutModeState.value = next
        ioScope.launch { layoutPreference.setMode(next) }
    }

    /**
     * Whether the full-screen viewer shows its detail panel on open. Read by the viewer; persisted
     * (`FOTLAB-UIXDES`, viewer layout) so the last choice is remembered. Defaults to `false` (hidden).
     */
    val viewerShowInfo: Flow<Boolean> get() = layoutPreference.showViewerInfo

    /** Persist the viewer detail-panel visibility; the viewer calls this when the info icon is tapped. */
    suspend fun setViewerShowInfo(show: Boolean) = layoutPreference.setShowViewerInfo(show)

    /**
     * Reconcile the virtual tree with the real world (`FOTLAB-UIXDES-000004` R10): soft-delete
     * every live non-folder node whose real object is gone and every live orphan node, reusing
     * the delete path so they share one `time_deleted` timestamp. No physical row is removed and
     * no `VACUUM` is needed — soft deletion leaves the page in place (`FOTLAB-DATABS-000002` R10/R14,
     * revised).
     */
    suspend fun refresh() {
        val now = System.currentTimeMillis()
        val missing = repo().fileEntryNodes().filter { node ->
            node.uriStorage != null && !uriExists(node.uriStorage)
        }.mapNotNull { it.fsNodeId }
        val orphans = repo().orphanNodeIds().mapNotNull { it }
        val toDelete = (missing + orphans).toSet()
        if (toDelete.isNotEmpty()) {
            repo().deleteNodes(toDelete)
        }
    }

    /** True when the real object behind [uriString] is still resolvable; false on any failure. */
    private fun uriExists(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return runCatching {
            applicationContext.contentResolver
                .query(uri, null, null, null, null)
                ?.use { it.count >= 0 } ?: false
        }.getOrDefault(false)
    }

    // --- Top bar actions (`FOTLAB-UIXDES-000004` R7) ---

    /**
     * Create one collection node under [parentId] (`null` = the virtual root).
     * One node row plus one relation row; no physical folder is created.
     */
    suspend fun createCollection(parentId: Long?, name: String): Long {
        val id = repo().addNode(
            nameDisplay = name,
            typeMime = MimeCollection,
            uriStorage = null,
            timeCreated = System.currentTimeMillis(),
        )
        repo().link(id, parentId)
        return id
    }

    /**
     * Reference the picked files under [parentId]. The files themselves are never moved
     * or copied (`FOTLAB-IMGMGR-000001` R1/R3): each becomes one file-entry node whose
     * `uri_storage` points at the original location, and the same physical file is
     * recorded once thanks to the UNIQUE index (`FOTLAB-DATABS-000002` R7).
     */
    suspend fun importUris(parentId: Long?, uris: List<Uri>) {
        for (uri in uris) {
            takeReadPermission(uri)
            val text = uri.toString()
            val existing = repo().getByUri(text)
            val childId = existing?.fsNodeId
                ?: repo().addNode(
                    nameDisplay = displayNameOf(uri) ?: uri.lastPathSegment ?: text,
                    typeMime = applicationContext.contentResolver.getType(uri) ?: MimeUnknown,
                    uriStorage = text,
                    timeCreated = System.currentTimeMillis(),
                )
            // Re-linking an existing edge is ignored by the DAO rather than aborting.
            repo().link(childId, parentId)
        }
    }

    /**
     * Remove every selected node from the virtual tree. Relations go with the node
     * (the delete routine stamps them) and the physical file is never touched — deletion of a
     * file on disk remains an explicit, separately confirmed action (`FOTLAB-IMGMGR-000001` R7).
     */
    suspend fun deleteSelected() {
        val ids = selection.selected.value
        if (ids.isEmpty()) return
        // One timestamp for the whole operation, so every row it stamps belongs to it
        // (`FOTLAB-DATABS-000002` R10/R13, revised).
        repo().deleteNodes(ids)
        selection.clear()
    }

    /**
     * Gate for the delete press: every selected node must be a direct child of [parentId]
     * (null = root) — i.e. belong to the directory currently on screen. Only the selected
     * level is checked; recursively removed descendants are not re-validated.
     */
    suspend fun selectionDirectlyUnder(parentId: Long?): Boolean {
        val ids = selection.selected.value
        if (ids.isEmpty()) return false
        return repo().selectedDirectlyUnder(ids, parentId)
    }

    private fun takeReadPermission(uri: Uri) {
        // Persistence of the grant is best-effort: a provider that does not offer it
        // simply means the reference may stop resolving later (see Q7).
        runCatching {
            applicationContext.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun displayNameOf(uri: Uri): String? =
        applicationContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
}
