package io.github.fotlab.fotlab.feature.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Room
import io.github.fotlab.fotlab.feature.library.FsNodeObject
import io.github.fotlab.fotlab.feature.library.FsNodeRelation
import io.github.fotlab.fotlab.feature.library.LibraryLayoutMode
import io.github.fotlab.fotlab.feature.library.ListSelectionOfLibrary
import io.github.fotlab.fotlab.feature.library.MimeCollection
import io.github.fotlab.fotlab.feature.library.MimeFolderDeleted
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

/** Fallback MIME when the platform cannot tell us the type of a picked file. */
private const val MimeUnknown = "application/octet-stream"

/** A collection is a node with the folder MIME; anything else is a file entry. */
fun FsNodeObject.isGalleryCollection(): Boolean = typeMime == MimeCollection

/**
 * The gallery's root node — same design as the library root.
 * Fixed id [ID], own MIME kind, ordinary row in `fs_node_object`.
 */
object GalleryRoot {
    const val ID: Long = 0L
    const val NAME: String = "Gallery"
    const val MIME: String = "application/gallery-root"
}

/**
 * Lower layer of the gallery feature. Builds and owns the gallery's Room database
 * (schema identical to library, separate file so data is isolated).
 */
object GalleryCore {

    val selection: ListSelectionOfLibrary = ListSelectionOfLibrary()

    private val selectionModeState = MutableStateFlow(false)
    val selectionModeActive: StateFlow<Boolean> = selectionModeState.asStateFlow()

    fun enterSelectionMode(nodeId: Long? = null) {
        selectionModeState.value = true
        if (nodeId != null) selection.select(nodeId)
    }

    fun exitSelectionMode() {
        selection.clear()
        selectionModeState.value = false
    }

    private val layoutModeState = MutableStateFlow(LibraryLayoutMode.DEFAULT)
    val layoutMode: StateFlow<LibraryLayoutMode> = layoutModeState.asStateFlow()

    private val currentDirectoryIdState = MutableStateFlow(GalleryRoot.ID)
    val currentDirectoryId: StateFlow<Long> = currentDirectoryIdState.asStateFlow()
    fun setCurrentDirectory(id: Long) { currentDirectoryIdState.value = id }

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var layoutPreference: GalleryLayoutPreference

    private var repository: GalleryRepository? = null
    private lateinit var applicationContext: Context

    fun prepare(context: Context) {
        if (repository != null) return
        applicationContext = context.applicationContext
        val database = Room.databaseBuilder(
            applicationContext,
            GalleryDatabase::class.java,
            "gallery",
        ).build()
        repository = GalleryRepository(database)
        layoutPreference = GalleryLayoutPreference(applicationContext)
        runBlocking(Dispatchers.IO) { repository?.ensureRoot() }
        layoutModeState.value = runBlocking(Dispatchers.IO) { layoutPreference.mode.first() }
    }

    private fun repo(): GalleryRepository =
        repository ?: error("GalleryCore.prepare(context) must be called before use")

    // --- Tree queries ---

    fun childrenOf(parentId: Long): Flow<List<FsNodeObject>> = repo().childrenOf(parentId)

    suspend fun rootNode(): FsNodeObject? = repo().rootNode()

    fun parentsOf(childId: Long): Flow<List<FsNodeObject>> = repo().parentsOf(childId)

    fun collections(): Flow<List<FsNodeObject>> = repo().collections()

    // --- Recycle bin queries ---

    fun deletedBatchTimes(): Flow<List<Long>> = repo().deletedBatchTimes()

    fun recycleBatchNodes(time: Long): Flow<List<FsNodeObject>> = repo().deletedNodesAt(time)

    suspend fun recycleBatchNodeIds(time: Long): List<Long> = repo().deletedNodeIdsAt(time)

    fun recycleBatchRelations(time: Long): Flow<List<FsNodeRelation>> = repo().deletedRelationsAt(time)

    suspend fun restoreFromBin(ids: List<Long>) = repo().restoreFromBin(ids)

    suspend fun deleteForever(ids: List<Long>) = repo().deleteForever(ids)

    // --- Tree mutations ---

    suspend fun addNode(
        nameDisplay: String,
        typeMime: String,
        uriStorage: String? = null,
        timeCreated: Long,
        timeModified: Long? = null,
    ): Long = repo().addNode(nameDisplay, typeMime, uriStorage, timeCreated, timeModified)

    suspend fun link(childId: Long, parentId: Long) = repo().link(childId, parentId)

    suspend fun unlink(childId: Long, parentId: Long) = repo().unlink(childId, parentId)

    suspend fun wouldCreateCycle(childId: Long, parentId: Long): Boolean =
        repo().wouldCreateCycle(childId, parentId)

    suspend fun removeNode(node: FsNodeObject) = repo().removeNode(node)

    suspend fun getByUri(uri: String): FsNodeObject? = repo().getByUri(uri)

    suspend fun renameNode(id: Long, name: String) = repo().renameNode(id, name)

    suspend fun cycleLayoutMode() {
        val next = LibraryLayoutMode.cycle(layoutModeState.value)
        layoutModeState.value = next
        ioScope.launch { layoutPreference.setMode(next) }
    }

    val viewerShowInfo: Flow<Boolean> get() = layoutPreference.showViewerInfo

    suspend fun setViewerShowInfo(show: Boolean) = layoutPreference.setShowViewerInfo(show)

    data class ViewerSession(val items: List<FsNodeObject>, val startIndex: Int)
    private val viewerSessionState = MutableStateFlow<ViewerSession?>(null)
    val viewerSession: StateFlow<ViewerSession?> = viewerSessionState.asStateFlow()
    fun setViewerSession(items: List<FsNodeObject>, startIndex: Int) { viewerSessionState.value = ViewerSession(items, startIndex) }

    suspend fun refresh() {
        val missing = repo().fileEntryNodes().filter { node ->
            node.uriStorage != null && !uriExists(node.uriStorage)
        }.mapNotNull { it.fsNodeId }
        val orphans = repo().orphanNodeIds().mapNotNull { it }
        val toDelete = (missing + orphans).toSet()
        if (toDelete.isNotEmpty()) {
            repo().deleteNodes(toDelete)
        }
        repo().sweepOrphanRelations(System.currentTimeMillis())
    }

    private fun uriExists(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return runCatching {
            applicationContext.contentResolver
                .query(uri, null, null, null, null)
                ?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    // --- Top bar actions ---

    suspend fun createCollection(parentId: Long, name: String): Long {
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
     * Import a single URI into the gallery. Used by Studio to add exported works.
     */
    suspend fun importUri(parentId: Long, uri: Uri) {
        takeReadPermission(uri)
        val text = uri.toString()
        val name = displayNameOf(uri) ?: uri.lastPathSegment ?: text
        val mime = applicationContext.contentResolver.getType(uri) ?: MimeUnknown
        val existing = repo().getByUriAnyStatus(text)
        val childId = if (existing != null) {
            if (existing.timeDeleted != null) {
                repo().revive(existing.fsNodeId!!, name, mime)
            }
            existing.fsNodeId!!
        } else {
            repo().addNode(
                nameDisplay = name,
                typeMime = mime,
                uriStorage = text,
                timeCreated = System.currentTimeMillis(),
            )
        }
        repo().link(childId, parentId)
    }

    /** Reference multiple picked files under [parentId]. */
    suspend fun importUris(parentId: Long, uris: List<Uri>) {
        for (uri in uris) importUri(parentId, uri)
    }

    suspend fun deleteSelected() {
        val ids = selection.selected.value
        if (ids.isEmpty()) return
        repo().deleteNodes(ids)
        selection.clear()
    }

    suspend fun selectionDirectlyUnder(parentId: Long): Boolean {
        val ids = selection.selected.value
        if (ids.isEmpty()) return false
        return repo().selectedDirectlyUnder(ids, parentId)
    }

    private fun takeReadPermission(uri: Uri) {
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
