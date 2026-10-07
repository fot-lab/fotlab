package io.github.fotlab.fotlab.smoke

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.fotlab.fotlab.feature.library.FsNodeObject
import io.github.fotlab.fotlab.feature.library.LibraryCore
import io.github.fotlab.fotlab.feature.library.LibraryRoot
import io.github.fotlab.fotlab.feature.studio.StudioEngine
import io.github.fotlab.fotlab.feature.studio.StudioRenderResult
import io.github.fotlab.fotlab.media.DEFAULT_SNIFF_TIMEOUT_MS
import io.github.fotlab.fotlab.media.FormatSniffer
import io.github.fotlab.fotlab.media.Route
import io.github.fotlab.fotlab.media.SniffResult
import io.github.fotlab.fotlab.media.Sniffer
import io.github.fotlab.fotlab.media.route
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end simulation of the REAL user journey for a PNG photo, on a device/emulator
 * (`FOTLAB-STUDIO-000001` data path). Unlike [RawlerNativeSmokeTest], which pokes the native
 * bridge with synthetic bytes, this class drives every stage the product code runs in
 * production, in production order, with verbose `PNG-E2E` logcat output at each step so a
 * real-device crash can be localized afterwards:
 *
 *  1. **Import** — a real `content://` PNG (MediaStore, the same provider a gallery pick comes
 *     from) is imported through [LibraryCore.importUris], exactly as the document picker flow
 *     does (`LibraryCore.kt` importUris).
 *  2. **Virtual mapping** — the imported `fs_node.uri_storage` string must round-trip: it must
 *     equal the source Uri string, and re-opening it through `ContentResolver` must yield the
 *     exact bytes that were written (`FOTLAB-IMGMGR-000001` R1/R3 — the mapping is the stored
 *     string; there is no path rewriting).
 *  3. **Library viewer** — the viewer picks the renderer by the MIME recorded at import time
 *     and hands the parsed Uri to Coil (`LibraryViewerScreen.kt`). This test replays that exact
 *     branch and executes the same Coil request.
 *  4. **Studio sniff + route** — the full parallel sniff ([FormatSniffer]: Coil-side
 *     BitmapFactory probe + rawler-side native probe, bounded by the timeout) runs on real PNG
 *     bytes read the way `StudioEngine.runPipeline` reads them (1 MiB header). For a PNG the
 *     contract is: COIL canDecode=true (`image/png`), RAWLER canDecode=false, `route` -> ToCoil.
 *  5. **Studio render** — [StudioEngine.setCurrentNode] drives the real pipeline; the result
 *     must reach `Ready` carrying the original Uri (the ToCoil branch), and the same Coil
 *     request StudioScreen issues must succeed.
 *
 * WARNING: the tests run in the app process against the app's REAL `library` Room database and
 * DataStore. On the CI emulator this is a clean environment; do not run them on a personal
 * device — they would insert rows into that device's library (rows are cleaned of the MediaStore
 * source, but library nodes persist).
 */
@RunWith(AndroidJUnit4::class)
class PngEndToEndFlowTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // ---------------------------------------------------------------- logging

    /** Logcat tag for the whole journey; smoke_emulator.yaml ships logcat.txt on failure. */
    private val tag = "PNG-E2E"
    private var t0 = 0L

    private fun startClock() {
        t0 = System.nanoTime()
    }

    /** One timestamped journey step. Everything worth diagnosing goes through here. */
    private fun step(phase: String, detail: String) {
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(tag, "[T+${ms}ms][$phase] $detail")
    }

    private fun stepError(phase: String, detail: String, t: Throwable? = null) {
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (t == null) Log.wtf(tag, "[T+${ms}ms][$phase] FAIL: $detail") else Log.wtf(tag, "[T+${ms}ms][$phase] FAIL: $detail", t)
    }

    // ---------------------------------------------------------------- fixture

    /** A real, decodable PNG (256x256, gradient + text-free) as in-memory bytes. */
    private fun pngBytes(size: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            Color.rgb(x * 255 / (size - 1), y * 255 / (size - 1), (x xor y) and 0xFF)
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    private var sourceUri: Uri? = null

    /**
     * Publish [bytes] as a gallery-like source the same way a real photo lives on a device:
     * MediaStore on API 29+ (content://, IS_PENDING write then publish), a plain file Uri below.
     * Returns the Uri and the exact bytes that must survive every later read.
     */
    private fun createSourcePng(): Pair<Uri, ByteArray> {
        val bytes = pngBytes(256)
        step("source", "created PNG in memory: ${bytes.size} bytes")
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "fotlab_e2e_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FotLabE2E")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw AssertionError("MediaStore insert returned null")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            step("source", "MediaStore content uri = $uri")
            uri to bytes
        } else {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, "fotlab_e2e_${System.currentTimeMillis()}.png")
            file.writeBytes(bytes)
            step("source", "file uri (pre-29 fallback) = ${Uri.fromFile(file)}")
            Uri.fromFile(file) to bytes
        }
    }

    /** Delete whatever [createSourcePng] published. */
    private fun deleteSource(uri: Uri) {
        runCatching {
            if (uri.scheme == "content") {
                context.contentResolver.delete(uri, null, null)
            } else {
                uri.path?.let { File(it).delete() }
            }
            step("cleanup", "deleted source $uri")
        }.onFailure { stepError("cleanup", "could not delete source $uri", it) }
    }

    /** Import [uri] at the library root like the picker flow does; returns the created node. */
    private fun importAtRoot(uri: Uri): FsNodeObject =
        runBlocking {
            val t = System.nanoTime()
            LibraryCore.importUris(parentId = LibraryRoot.ID, uris = listOf(uri))
            step("import", "importUris done in ${(System.nanoTime() - t) / 1_000_000} ms")
            val node = LibraryCore.getByUri(uri.toString())
                ?: run {
                    stepError("import", "no fs_node for uri $uri after import")
                    throw AssertionError("import produced no fs_node for $uri")
                }
            step(
                "import",
                "node fsNodeId=${node.fsNodeId} name='${node.nameDisplay}' " +
                    "typeMime='${node.typeMime}' uriStorage='${node.uriStorage}'",
            )
            node
        }

    /**
     * Regression: a node listed at the virtual root must pass the delete gate.
     *
     * The gate ([LibraryCore.selectionDirectlyUnder]) asked the DAO for the live edge
     * between the node and the directory on screen with `fs_node_id_parent = :parentId`.
     * At the root that parameter is `null` (there is no root node row), and SQL `= NULL`
     * never matches, so the lookup always missed and the UI answered "cannot delete" for
     * a file that was plainly in front of the user. Pinned here at the level the delete
     * press actually calls it, together with the negative case the gate must still reject.
     */
    @Test
    fun rootChildPassesTheDeleteGate() {
        startClock()
        val (uri, _) = createSourcePng()
        sourceUri = uri
        try {
            val node = importAtRoot(uri)
            val id = requireNotNull(node.fsNodeId)

            runBlocking {
                LibraryCore.enterSelectionMode(id)

                // Exactly the call LibraryScreen's delete press makes at the top level.
                val atRoot = LibraryCore.selectionDirectlyUnder(LibraryRoot.ID)
                step("delete-gate", "top-level child fsNodeId=$id gate(root)=${atRoot}")
                assertTrue("a node listed at the top level must pass the delete gate", atRoot)

                // The gate must stay a gate: an unrelated parent is still a rejection.
                val unrelatedParent = id + 1_000_000L
                val elsewhere = LibraryCore.selectionDirectlyUnder(unrelatedParent)
                step("delete-gate", "gate(parentId=$unrelatedParent)=$elsewhere")
                assertFalse("a node must not pass the gate for an unrelated parent", elsewhere)

                // And the accepted delete really removes it from the top-level listing.
                LibraryCore.deleteSelected()
            }

            val after = runBlocking { LibraryCore.childrenOf(LibraryRoot.ID).first() }
            val stillListed = after.count { it.uriStorage == uri.toString() }
            step("delete-gate", "top level after delete lists the node $stillListed time(s)")
            assertEquals("the deleted top-level node must leave the listing", 0, stillListed)

            // The root itself is a real node row (id 0) with its own node kind, and it must
            // survive a reconcile — it is the one node with no parent edge, so a sweep that
            // treated "no parent" as garbage would delete the whole library's anchor.
            val root = runBlocking { LibraryCore.rootNode() }
            step("root", "root=$root")
            assertNotNull("the root node row must exist", root)
            assertEquals("the root node id is fixed", LibraryRoot.ID, root!!.fsNodeId)
            assertEquals("the root carries its own node kind", LibraryRoot.MIME, root.typeMime)
            runBlocking { LibraryCore.refresh() }
            val rootAfterRefresh = runBlocking { LibraryCore.rootNode() }
            assertNotNull("refresh must not take the root node away", rootAfterRefresh)

            // The delete gate is the node's kind, so even a selection that names the root
            // outright must be refused — the root is never a deletable item.
            runBlocking {
                LibraryCore.enterSelectionMode(LibraryRoot.ID)
                LibraryCore.deleteSelected()
            }
            val rootAfterDelete = runBlocking { LibraryCore.rootNode() }
            step("root", "root after a delete aimed at it: $rootAfterDelete")
            assertNotNull("a delete naming the root must not remove it", rootAfterDelete)

            // Orphan semantics: a node is an orphan when it has no effective live parent link —
            // no edge at all, an edge naming a null parent, or an edge whose parent node is
            // itself removed. The reachability that matters most is the negative half: a node
            // that *does* hang under a live parent must never be reaped. That is exactly what a
            // sweep written as "no live parent edge" (without asking whether the parent node is
            // alive) would get wrong, and what one written as "no parent edge at all" would
            // also get wrong. The null-parent and dead-parent cases cannot be produced through
            // the public API any more (nothing writes a null parent, and deleting a parent
            // stamps its child edges), so they are asserted by the DAO's own predicate.
            val (childId, siblingId, childSurvived) = runBlocking {
                val child = LibraryCore.createCollection(LibraryRoot.ID, "orphan-probe")
                val sibling = LibraryCore.createCollection(LibraryRoot.ID, "sibling-probe")
                LibraryCore.refresh()
                val survivors = LibraryCore.collections().first().mapNotNull { it.fsNodeId }.toSet()
                Triple(child, sibling, child in survivors && sibling in survivors)
            }
            step("orphan", "child=$childId sibling=$siblingId bothSurvived=$childSurvived")
            assertTrue("nodes under a live parent must survive the sweep", childSurvived)

            // The relation table is many-to-many, and the node and relation verdicts differ on
            // purpose: removing one parent of a two-parent child must sweep that one dead edge
            // and keep the live one, and the child — having a live parent left — must survive
            // both the delete recursion and the reconcile. A child of the removed parent
            // *only* has no live parent left, so it goes with it.
            val probes = runBlocking {
                val shared = LibraryCore.createCollection(LibraryRoot.ID, "mp-child")
                val lone = LibraryCore.createCollection(LibraryRoot.ID, "mp-lone")
                val parentA = LibraryCore.createCollection(LibraryRoot.ID, "mp-parent-a")
                val parentB = LibraryCore.createCollection(LibraryRoot.ID, "mp-parent-b")
                LibraryCore.link(shared, parentA)
                LibraryCore.link(shared, parentB)
                LibraryCore.link(lone, parentA)
                // `createCollection` already hung both children off the root, and `link` only
                // *adds* an edge, so `lone` still has that root edge. It is a "lone" child only
                // once the root edge is dropped — without this it has two live parents and would
                // (correctly) survive its second parent's removal, which is the many-to-many rule
                // this whole block exists to pin.
                LibraryCore.unlink(lone, LibraryRoot.ID)
                listOf(shared, lone, parentA, parentB)
            }
            val (sharedId, loneId, removedParentId, keptParentId) = probes
            runBlocking {
                LibraryCore.enterSelectionMode(removedParentId)
                LibraryCore.deleteSelected()
                LibraryCore.refresh()
            }
            val underKept = runBlocking { LibraryCore.childrenOf(keptParentId).first() }
                .mapNotNull { it.fsNodeId }
            val underRemoved = runBlocking { LibraryCore.childrenOf(removedParentId).first() }
                .mapNotNull { it.fsNodeId }
            val alive = runBlocking { LibraryCore.collections().first() }
                .mapNotNull { it.fsNodeId }
                .toSet()
            step(
                "multi-parent",
                "shared=$sharedId lone=$loneId underKept=${sharedId in underKept} " +
                    "underRemoved=${sharedId in underRemoved} sharedAlive=${sharedId in alive}",
            )
            assertTrue("a child keeps the edge to its surviving parent", sharedId in underKept)
            assertTrue("a child with one live parent left stays alive", sharedId in alive)
            // Only reachable because of the `unlink` above: with the root edge still present
            // this node has a second live parent and must survive.
            assertTrue("a child whose only parent was removed goes with it", loneId !in alive)
            assertTrue(
                "the dead edge is swept, so the removed parent lists nothing",
                underRemoved.isEmpty(),
            )
            runBlocking {
                LibraryCore.enterSelectionMode(sharedId)
                LibraryCore.selection.toggle(keptParentId)
                LibraryCore.deleteSelected()
            }

            val cleanedUp = runBlocking {
                LibraryCore.enterSelectionMode(childId)
                LibraryCore.selection.toggle(siblingId)
                LibraryCore.deleteSelected()
                LibraryCore.collections().first().mapNotNull { it.fsNodeId }.toSet()
            }
            step("orphan", "probe nodes cleaned up=${childId !in cleanedUp && siblingId !in cleanedUp}")
            assertTrue(
                "the probe nodes must be removable again afterwards",
                childId !in cleanedUp && siblingId !in cleanedUp,
            )
        } finally {
            runBlocking { LibraryCore.exitSelectionMode() }
            sourceUri?.let(::deleteSource)
        }
    }

    // ---------------------------------------------------------- delete-forever correctness

    /**
     * Regression for the recycle-bin "delete forever" path: a collection [c] is soft-deleted, and
     * one of its children [f] also hangs under a *live* collection [d]. Permanent-deleting [c] from
     * the bin must hard-delete [c] only — [f] keeps its live edge to [d] and must survive. The
     * subtree walk must not pull [f] in via the stamped `f->c` edge just because that edge shares
     * [c]'s batch stamp.
     */
    @Test
    fun deleteForeverKeepsLiveSiblingChildren() {
        val (c, d, f) = runBlocking {
            val cId = LibraryCore.createCollection(LibraryRoot.ID, "dfc-parent")
            val dId = LibraryCore.createCollection(LibraryRoot.ID, "dfc-keep")
            val fId = LibraryCore.createCollection(cId, "dfc-child")
            LibraryCore.link(fId, dId) // f now has two live parents: cId and dId
            Triple(cId, dId, fId)
        }

        try {
            // Soft-delete only [c] (the node a user would pick in the bin).
            runBlocking {
                LibraryCore.enterSelectionMode(c)
                LibraryCore.deleteSelected()
            }

            // Permanent-delete [c] from the bin.
            runBlocking { LibraryCore.deleteForever(listOf(c)) }

            val live = runBlocking { LibraryCore.collections().first() }
                .mapNotNull { it.fsNodeId }.toSet()
            val fParents = runBlocking { LibraryCore.parentsOf(f).first() }
                .mapNotNull { it.fsNodeId }.toSet()

            assertTrue("the permanently deleted collection must be gone", c !in live)
            assertTrue("a child with a surviving live parent must not be hard-deleted", f in live)
            assertTrue("the surviving live edge must remain attached", d in fParents)
        } finally {
            // [c] is already hard-deleted; sweep the two probes to keep the DB clean.
            runBlocking {
                LibraryCore.enterSelectionMode(f)
                LibraryCore.selection.toggle(d)
                LibraryCore.deleteSelected()
                LibraryCore.exitSelectionMode()
            }
        }
    }

    /**
     * Regression for the directory sort order (UX change 2026-10-07): live children of a directory
     * must resolve to `ORDER BY child.time_created DESC, child.name_display ASC` — newest first, and
     * when creation times tie, alphabetical ascending by `name_display`. Three nodes with distinct
     * creation timestamps plus a pair sharing the same timestamp are linked to the root; the returned
     * order is asserted to descend by time_created and, within the equal-time pair, to ascend by name.
     * The assertion filters to just these five ids, so any other rows the shared library DB already
     * holds cannot flip it.
     */
    @Test
    fun libraryChildrenOrderedByTimeCreatedDescThenNameAsc() = runBlocking {
        val older = LibraryCore.addNode("sort-older", "image/png", null, timeCreated = 100L)
        val middle = LibraryCore.addNode("sort-middle", "image/png", null, timeCreated = 200L)
        val newer = LibraryCore.addNode("sort-newer", "image/png", null, timeCreated = 300L)
        // A pair that shares the same creation time exercises the secondary alphabetical tie-break.
        val tieA = LibraryCore.addNode("sort-tie-a", "image/png", null, timeCreated = 400L)
        val tieB = LibraryCore.addNode("sort-tie-b", "image/png", null, timeCreated = 400L)
        LibraryCore.link(older, LibraryRoot.ID)
        LibraryCore.link(middle, LibraryRoot.ID)
        LibraryCore.link(newer, LibraryRoot.ID)
        LibraryCore.link(tieA, LibraryRoot.ID)
        LibraryCore.link(tieB, LibraryRoot.ID)

        val ids = LibraryCore.childrenOf(LibraryRoot.ID).first().mapNotNull { it.fsNodeId }
        assertTrue("newest node must sort before oldest (DESC time_created)", ids.indexOf(newer) < ids.indexOf(older))
        assertTrue("newest node must sort before middle", ids.indexOf(newer) < ids.indexOf(middle))
        assertTrue("middle node must sort before oldest", ids.indexOf(middle) < ids.indexOf(older))
        // Equal time_created -> alphabetical ascending by name_display (a before b).
        assertTrue("equal time_created sorts alphabetically (a before b)", ids.indexOf(tieA) < ids.indexOf(tieB))
        // The equal-time pair sits after the strictly-newer node (300 < 400).
        assertTrue("equal-time pair after strictly-newer node", ids.indexOf(tieA) > ids.indexOf(newer))

        // Sweep the five probes (soft-delete) to keep the shared DB clean.
        LibraryCore.enterSelectionMode(newer)
        LibraryCore.selection.toggle(middle)
        LibraryCore.selection.toggle(older)
        LibraryCore.selection.toggle(tieA)
        LibraryCore.selection.toggle(tieB)
        LibraryCore.deleteSelected()
        LibraryCore.exitSelectionMode()
    }

    /**
     * Regression for the recycle-bin sort order (UX change 2026-10-07): `deletedNodesAt` resolves to
     * `ORDER BY time_deleted DESC, name_display ASC`. Nodes deleted in the same batch share one
     * `time_deleted`, so the secondary alphabetical key is what actually orders them. Three nodes with
     * deliberately non-alphabetical names are soft-deleted in a single batch; within that batch they
     * must come back ordered by `name_display` ascending. The assertion filters to just these three
     * ids across all batches, so other deleted rows already in the shared DB cannot flip it.
     */
    @Test
    fun recycleBinNodesOrderedByTimeDeletedDescThenNameAsc() = runBlocking {
        // Names deliberately non-alphabetical so the secondary sort is observable.
        val c = LibraryCore.addNode("rb-c", "image/png", null, timeCreated = 1L)
        val a = LibraryCore.addNode("rb-a", "image/png", null, timeCreated = 2L)
        val b = LibraryCore.addNode("rb-b", "image/png", null, timeCreated = 3L)
        LibraryCore.link(c, LibraryRoot.ID)
        LibraryCore.link(a, LibraryRoot.ID)
        LibraryCore.link(b, LibraryRoot.ID)

        // Soft-delete all three in ONE batch so they share the same time_deleted stamp.
        LibraryCore.enterSelectionMode(c)
        LibraryCore.selection.toggle(a)
        LibraryCore.selection.toggle(b)
        LibraryCore.deleteSelected()
        LibraryCore.exitSelectionMode()

        // Gather every deleted node across all batches and check the relative name order of our trio.
        val batches = LibraryCore.deletedBatchTimes().first()
        val allDeleted = batches.flatMap { LibraryCore.recycleBatchNodes(it).first() }
            .mapNotNull { it.fsNodeId }
        assertTrue("recycle bin orders by name when time_deleted ties (a before b)", allDeleted.indexOf(a) < allDeleted.indexOf(b))
        assertTrue("recycle bin orders by name when time_deleted ties (b before c)", allDeleted.indexOf(b) < allDeleted.indexOf(c))

        // Hard-delete the probes to keep the shared DB clean.
        LibraryCore.deleteForever(listOf(c, a, b))
    }

    // ---------------------------------------------------------------- 1+2: import & mapping

    /** Journey steps 1–2: import via the real entry, then prove the virtual mapping round-trips. */
    @Test
    fun pngImportAndVirtualMapping() {
        startClock()
        step("env", "device=${Build.MANUFACTURER} ${Build.MODEL} API=${Build.VERSION.SDK_INT} " +
            "abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        val (uri, bytes) = createSourcePng()
        sourceUri = uri
        try {
            // The source must be readable and typed BEFORE import — this is what the picker gives us.
            val type = context.contentResolver.getType(uri)
            step("source", "contentResolver.getType=$type")
            assertEquals("image/png", type)
            context.contentResolver.openInputStream(uri)!!.use { stream ->
                val read = stream.readBytes()
                step("source", "direct read: ${read.size} bytes, matches source=${read.contentEquals(bytes)}")
                assertTrue("source bytes not round-trippable before import", read.contentEquals(bytes))
            }

            val node = importAtRoot(uri)

            // Mapping invariant 1: the stored string IS the source string (no transformation).
            step("mapping", "uriStorage='${node.uriStorage}' expected='${uri}'")
            assertEquals("uri_storage must equal the picked uri string", uri.toString(), node.uriStorage)

            // Mapping invariant 2: resolving the STORED string re-delivers the exact bytes.
            val reparsed = Uri.parse(node.uriStorage)
            step("mapping", "Uri.parse(uriStorage)=$reparsed")
            val reopened = context.contentResolver.openInputStream(reparsed)!!.use { it.readBytes() }
            step("mapping", "reopened via stored string: ${reopened.size} bytes, " +
                "identical=${reopened.contentEquals(bytes)}")
            assertTrue("stored uri does not re-deliver the source bytes", reopened.contentEquals(bytes))

            // Dedupe: a second import of the same Uri must reuse the node, not create a twin.
            importAtRoot(uri)
            val again = runBlocking { LibraryCore.getByUri(uri.toString()) }
            step("dedupe", "after re-import fsNodeId=${again?.fsNodeId} (first=${node.fsNodeId})")
            assertEquals("re-import must reuse the existing node", node.fsNodeId, again?.fsNodeId)

            // The node must be listed at the virtual root.
            val roots = runBlocking { LibraryCore.childrenOf(LibraryRoot.ID).first() }
            val matching = roots.count { it.uriStorage == uri.toString() }
            step("mapping", "top-level listing contains the node $matching time(s)")
            assertEquals("node must appear exactly once at root", 1, matching)
        } finally {
            sourceUri?.let(::deleteSource)
        }
    }

    // ---------------------------------------------------------------- 3: viewer render path

    /**
     * Journey step 3: the library viewer does NOT sniff — it dispatches on the MIME recorded at
     * import and hands the parsed Uri to Coil. Replay that exact branch.
     */
    @Test
    fun pngViewerRenderPath() {
        startClock()
        val (uri, bytes) = createSourcePng()
        sourceUri = uri
        try {
            val node = importAtRoot(uri)

            // The viewer's routing decision (LibraryViewerScreen.kt):
            step("viewer", "viewer branch decision on typeMime='${node.typeMime}'")
            assertTrue("viewer must take the image/ branch", node.typeMime.startsWith("image/"))
            val viewerUri = node.uriStorage?.let(Uri::parse)
            step("viewer", "viewer model uri=$viewerUri")
            assertEquals("viewer renders the parsed uri_storage", uri, viewerUri)

            // Same decode path ZoomableAsyncImage/rememberAsyncImagePainter uses: the shared
            // ImageLoader executing an ImageRequest over the content uri.
            val request = ImageRequest.Builder(context).data(viewerUri).build()
            val t = System.nanoTime()
            val result = runBlocking { context.imageLoader.execute(request) }
            step("viewer", "Coil execute took ${(System.nanoTime() - t) / 1_000_000} ms, " +
                "result=${result.javaClass.simpleName}")
            assertTrue("Coil must succeed on the PNG", result is SuccessResult)
            // coil3 replaced Drawable with Image, which carries the intrinsic dimensions directly.
            val image = (result as SuccessResult).image
            step(
                "viewer",
                "decoded image=${image.javaClass.simpleName} " +
                    "intrinsic=${image.width}x${image.height}",
            )
            assertTrue("decoded bitmap must have real dimensions", image.width > 0)
            assertEquals(256, image.height)
        } finally {
            sourceUri?.let(::deleteSource)
        }
    }

    // ---------------------------------------------------------------- 4+5: sniff, route, studio

    /**
     * Journey steps 4–5: read the header the way StudioEngine does, run the real parallel sniff,
     * assert the PNG contract (COIL yes / RAWLER no / route=ToCoil), then drive StudioEngine and
     * render the result with Coil. Expected verdicts, per the routing contract:
     * COIL canDecode=true (`image/png`), RAWLER canDecode=false -> [Route.ToCoil].
     */
    @Test
    fun pngStudioSniffRouteAndRender() {
        startClock()
        val (uri, bytes) = createSourcePng()
        sourceUri = uri
        try {
            val node = importAtRoot(uri)

            // StudioEngine.runPipeline reads the header through ContentResolver (1 MiB, API-safe loop).
            val header = runCatching {
                context.contentResolver.openInputStream(uri)!!.use { it.readHeader(HEADER_BYTES) }
            }.getOrNull()
            step("sniff", "header read: ${header?.size ?: -1} bytes of ${bytes.size}")
            assertTrue("header read failed", header != null && header.isNotEmpty())

            // The real parallel sniff (COIL + RAWLER workers, bounded by the sniff timeout).
            val t = System.nanoTime()
            val sniff = runBlocking {
                FormatSniffer.sniff(header!!, timeoutMillis = DEFAULT_SNIFF_TIMEOUT_MS)
            }
            step("sniff", "FormatSniffer.sniff took ${(System.nanoTime() - t) / 1_000_000} ms")
            assertTrue("sniff must not time out on a PNG", sniff is SniffResult.Ok)
            val verdicts = (sniff as SniffResult.Ok).verdicts
            val coil = verdicts[Sniffer.COIL]
            val rawler = verdicts[Sniffer.RAWLER]
            step("sniff", "COIL verdict: format=${coil?.format} canDecode=${coil?.canDecode}")
            step("sniff", "RAWLER verdict: format=${rawler?.format} canDecode=${rawler?.canDecode}")

            assertTrue("COIL must decode a PNG", coil != null && coil.canDecode)
            assertEquals("image/png", coil!!.format)
            assertFalse("RAWLER must decline a PNG", rawler != null && rawler.canDecode)

            // The pure routing decision over the dictionary.
            val r = route(verdicts)
            step("route", "route()=${r.javaClass.simpleName} format=${(r as? Route.ToCoil)?.format}")
            assertTrue("PNG must route to ToCoil", r is Route.ToCoil)
            assertEquals("image/png", (r as Route.ToCoil).format)

            // Journey step 5: drive the REAL engine, logging every renderResult transition.
            val states = mutableListOf<String>()
            StudioEngine.setCurrentNode(node.uriStorage)
            val ready = runBlocking {
                withTimeout(45_000) {
                    StudioEngine.renderResult
                        .onEach { states += "${it.javaClass.simpleName}" }
                        .filterNot { it is StudioRenderResult.Idle || it is StudioRenderResult.Loading }
                        .first()
                }
            }
            step("studio", "renderResult transitions: $states")
            step("studio", "final renderResult=${ready.javaClass.simpleName} model=${(ready as? StudioRenderResult.Ready)?.model}")
            assertTrue("Studio must reach Ready for a PNG", ready is StudioRenderResult.Ready)
            val model = (ready as StudioRenderResult.Ready).model
            assertTrue("ToCoil branch must hand the original Uri to the renderer", model is Uri)
            assertEquals(uri, model)

            // What StudioScreen then does with a Ready(uri): Coil renders it.
            val request = ImageRequest.Builder(context).data(model as Uri).build()
            val t2 = System.nanoTime()
            val result = runBlocking { context.imageLoader.execute(request) }
            step("studio", "Studio Coil render took ${(System.nanoTime() - t2) / 1_000_000} ms, " +
                "result=${result.javaClass.simpleName}")
            assertTrue("Studio render must succeed on the PNG", result is SuccessResult)
        } finally {
            // Reset the process-wide engine so later tests start clean.
            runCatching { StudioEngine.setCurrentNode(null) }
            sourceUri?.let(::deleteSource)
        }
    }

    /** Mirrors the API-safe header loop in StudioEngine (`InputStream.readNBytes` needs API 33). */
    private fun InputStream.readHeader(max: Int): ByteArray {
        val buffer = ByteArray(max)
        var filled = 0
        while (filled < max) {
            val read = read(buffer, filled, max - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == max) buffer else buffer.copyOf(filled)
    }

    private companion object {
        /** Same bound StudioEngine hands to the sniffer (1 MiB). */
        const val HEADER_BYTES = 1024 * 1024
    }
}
