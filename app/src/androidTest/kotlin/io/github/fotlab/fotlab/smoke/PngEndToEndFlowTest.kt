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
 *     and hands the parsed Uri to Coil (`LibraryViewerDialog.kt`). This test replays that exact
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
            LibraryCore.importUris(parentId = null, uris = listOf(uri))
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
            val roots = runBlocking { LibraryCore.rootChildren().first() }
            val matching = roots.count { it.uriStorage == uri.toString() }
            step("mapping", "rootChildren listing contains the node $matching time(s)")
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

            // The viewer's routing decision (LibraryViewerDialog.kt):
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
