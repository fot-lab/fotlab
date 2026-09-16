package io.github.fotlab.fotlab.smoke

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fotlab.fotlab.MainActivity
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.library.LibraryCore
import io.github.fotlab.fotlab.feature.library.LibraryScreen
import io.github.fotlab.fotlab.feature.studio.StudioEngine
import io.github.fotlab.fotlab.feature.studio.StudioRenderResult
import io.github.fotlab.fotlab.ui.theme.AppTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real user journey for every image class we ship, with the render pipeline named at the end.
 *
 * Everything before this only ever fed the app a PNG, so nothing showed which backend actually
 * produces the pixels for a RAW. There are two candidates and they are NOT equivalent:
 *
 *  * **Coil on the original** — Android/Coil can at best pull a RAW container's *embedded
 *    preview* (a few hundred to ~2k px across). It looks like "the image opened" but it is not
 *    the photograph.
 *  * **rawler (dnglab) → PNG** — the real demosaic, full frame.
 *
 * `FOTLAB-STUDIO-000001` / R8 says PNG takes the first path and a camera RAW takes the second.
 * This test walks the journey a user walks and then reads the pipeline's answer off
 * [StudioEngine.renderResult]:
 *
 *  1. the file already sits on the emulated SD card (`/sdcard/Pictures/rawdb`, pushed by
 *     `smoke_emulator.yaml` from the public `fot-lab/rawdb` corpus);
 *  2. it is indexed into MediaStore and imported at the library root through
 *     [LibraryCore.importUris] — the same call the picker's callback makes, and the only place
 *     the virtual `uri_storage` mapping is created (no file is ever copied or moved);
 *  3. the real [LibraryScreen] grid is tapped on the imported thumbnail, which opens
 *     `LibraryViewerDialog` exactly as on a device;
 *  4. the viewer's "Open in Studio" button is pressed, which is what hands the node to
 *     [StudioEngine.setCurrentNode];
 *  5. the resulting [StudioRenderResult] names the pipeline: `Ready(Uri)` is the Coil branch,
 *     `Ready(ByteBuffer)` is rawler's decoded PNG. For the rawler branch the decoded frame is
 *     measured too — below [FULL_FRAME_MIN_WIDTH] px it could only be an embedded preview.
 *
 * Expected: PNG → Coil; CR2 / NEF / ARW (and RW2) → rawler.
 *
 * Every state on the way is logged under `RAW-E2E`; the job ships per-test logcat on failure.
 */
@RunWith(AndroidJUnit4::class)
class RawRoutingTest {

    /**
     * The app's own [MainActivity]: the grid needs a real application (Room/DataStore prepared by
     * `MainApplication`) and the theme, and `setContent` is illegal on an activity that already
     * composed in `onCreate` — [hostContent] replaces the content view instead.
     */
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val tag = "RAW-E2E"
    private var t0 = 0L

    private fun step(phase: String, detail: String) {
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(tag, "[T+${ms}ms][$phase] $detail")
    }

    /** Reading the corpus back out of MediaStore needs the media-read permission. */
    @Before
    fun setUp() {
        t0 = System.nanoTime()
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, permission)
        }.onFailure { Log.w(tag, "grantRuntimePermission failed", it) }
    }

    @After
    fun tearDown() {
        runCatching { StudioEngine.setCurrentNode(null) }
    }

    // ---------------------------------------------------------------- corpus

    /** One sample on the SD card: file name, the MIME to index it under, and how to find it. */
    private data class Sample(
        val file: String,
        val mime: String,
        /** Short leading text that survives the grid's middle-ellipsis truncation. */
        val gridText: String,
        val label: String,
    )

    private val canonCr2 = Sample(
        "Canon EOS 5DS_ISO_1000_RAW.CR2", "image/x-canon-cr2", "Canon EOS 5DS", "Canon EOS 5DS CR2",
    )
    private val sonyArw7r = Sample(
        "ILCE-7R_ISO_50_14bits_Sony ARW Compressed.ARW", "image/x-sony-arw", "ILCE-7R", "Sony ILCE-7R ARW",
    )
    private val sonyArw7rm2 = Sample(
        "ILCE-7RM2_ISO_100_14bits_Compressed RAW.ARW", "image/x-sony-arw", "ILCE-7RM2", "Sony ILCE-7RM2 ARW",
    )
    private val nikonNef = Sample(
        "NIKON D850_Large_ISO_64_14bits_Lossless.NEF", "image/x-nikon-nef", "NIKON D850", "Nikon D850 NEF",
    )
    private val panasonicRw2 = Sample(
        "DC-S1R_ISO_100_6fmt_8368x5584.RW2", "image/x-panasonic-rw2", "DC-S1R", "Panasonic DC-S1R RW2",
    )

    // ---------------------------------------------------------------- tests

    @Test
    fun pngOpensInStudioThroughCoil() = journey(pngControl(), expectRawler = false)

    @Test
    fun canonCr2OpensInStudioThroughRawler() = journey(canonCr2, expectRawler = true)

    @Test
    fun sonyIlce7rArwOpensInStudioThroughRawler() = journey(sonyArw7r, expectRawler = true)

    @Test
    fun sonyIlce7rm2ArwOpensInStudioThroughRawler() = journey(sonyArw7rm2, expectRawler = true)

    @Test
    fun nikonD850NefOpensInStudioThroughRawler() = journey(nikonNef, expectRawler = true)

    @Test
    fun panasonicDcS1rRw2OpensInStudioThroughRawler() = journey(panasonicRw2, expectRawler = true)

    // ---------------------------------------------------------------- the journey

    /**
     * Steps 1–5 above, in production order. Every intermediate state is logged, so a failure says
     * WHICH stage settled wrongly instead of just "the image did not open".
     */
    private fun journey(sample: Sample, expectRawler: Boolean) {
        // ---- 1) the file on the SD card, indexed into MediaStore ----
        val path = "/sdcard/Pictures/rawdb/${sample.file}"
        step("sdcard", "source=$path exists=${java.io.File(path).isFile}")
        val uri = indexAndFind(sample)
            ?: throw AssertionError("${sample.label} was not indexed into MediaStore — is it on the SD card at $path?")
        step("mediaStore", "content uri=$uri")

        assertTrue(
            "the stored uri must be readable (virtual mapping only, the file is never copied)",
            runCatching { context.contentResolver.openInputStream(uri)!!.use { it.read() } }.isSuccess,
        )

        // ---- 2) import at the library root, exactly as the picker's callback does ----
        runBlocking { LibraryCore.importUris(parentId = null, uris = listOf(uri)) }
        val node = runBlocking { LibraryCore.getByUri(uri.toString()) }
            ?: throw AssertionError("import produced no fs_node for $uri")
        step("import", "node id=${node.fsNodeId} name='${node.nameDisplay}' mime='${node.typeMime}'")

        // ---- 3) the real LibraryScreen: tap the imported thumbnail ----
        var navigatedToStudio = false
        hostContent { AppTheme { LibraryScreen(onNavigateToStudio = { navigatedToStudio = true }) } }
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(sample.gridText, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        step("grid", "thumbnail reachable in the grid by '${sample.gridText}'")
        composeRule.onAllNodesWithText(sample.gridText, substring = true)[0].performClick()

        val closeDesc = context.getString(R.string.library_viewer_cd_close)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithContentDescription(closeDesc).fetchSemanticsNodes().isNotEmpty()
        }
        step("viewer", "LibraryViewerDialog opened from the tap")

        // ---- 4) the viewer's "Open in Studio" button ----
        val studioDesc = context.getString(R.string.library_viewer_cd_open_in_studio)
        composeRule.onNodeWithContentDescription(studioDesc).performClick()
        step("viewer", "pressed '$studioDesc'")

        // ---- 5) read the pipeline's answer off the engine ----
        val states = mutableListOf<String>()
        val ready = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) {
                StudioEngine.renderResult
                    .onEach { states += it.javaClass.simpleName }
                    .filterNot { it is StudioRenderResult.Idle || it is StudioRenderResult.Loading }
                    .first()
            }
        }
        step("studio", "renderResult transitions: $states")
        step("studio", "navigated to Studio=$navigatedToStudio")
        assertTrue("pressing '$studioDesc' must navigate to Studio", navigatedToStudio)

        val model = (ready as StudioRenderResult.Ready).model
        step("studio", "final=${ready.javaClass.simpleName} model=${model.javaClass.simpleName}")

        if (expectRawler) {
            // rawler's branch hands the renderer decoded PNG BYTES; the Coil branch hands it the Uri.
            assertTrue(
                "${sample.label} must render rawler's decoded PNG bytes, got ${model.javaClass.simpleName} " +
                    "(that is the Coil branch)",
                model is java.nio.ByteBuffer,
            )
            val png = (model as java.nio.ByteBuffer).let { b -> ByteArray(b.remaining()).also { b.get(it) } }
            step("studio", "rawler returned ${png.size} bytes")
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, opts)
            step("decode", "decoded PNG = ${opts.outWidth}x${opts.outHeight} (${opts.outMimeType})")
            assertTrue("rawler output is not a decodable PNG for ${sample.label}", opts.outWidth > 0)
            assertTrue(
                "decoded frame is ${opts.outWidth}x${opts.outHeight} — that is an embedded preview, not a " +
                    "demosaiced full frame (${sample.label})",
                opts.outWidth >= FULL_FRAME_MIN_WIDTH,
            )
            step("decode", "FULL FRAME confirmed: ${opts.outWidth}x${opts.outHeight}")
        } else {
            assertTrue(
                "PNG must take the Coil branch and hand the original Uri to the renderer, " +
                    "got ${model.javaClass.simpleName}",
                model is Uri,
            )
            step("studio", "Coil branch confirmed: original ${model.javaClass.simpleName} handed to the renderer")
        }
    }

    // ---------------------------------------------------------------- staging

    /**
     * Makes the pushed file visible to the app the way a photo on the device is: index it into
     * MediaStore and return its `content://` uri.
     *
     * The uri MUST be a `content://` one: `LibraryCore.importUris` types the node with
     * `contentResolver.getType(uri)`, and a `file://` uri answers null — the node is then stored
     * as an unknown type and the grid treats it as "not media", so a tap would only select it.
     */
    private fun indexAndFind(sample: Sample): Uri? {
        val path = "/sdcard/Pictures/rawdb/${sample.file}"
        scanFile(path, sample.mime)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf(sample.file),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                step(
                    "mediaStore",
                    "row: name='${cursor.getString(1)}' mime='${cursor.getString(2)}' size=${cursor.getLong(3)}",
                )
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            }
        }
        step("mediaStore", "no MediaStore row for ${sample.file}")
        return null
    }

    /** MediaScanner is async; the callback is awaited so the query below cannot race it. */
    private fun scanFile(path: String, mime: String) {
        val latch = CountDownLatch(1)
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime)) { scanned, uri ->
            step("scan", "scanned='$scanned' -> $uri")
            latch.countDown()
        }
        latch.await(60, TimeUnit.SECONDS)
    }

    /** A tiny PNG published through MediaStore, as the Coil-branch control. */
    private fun pngControl(): Sample {
        val name = "png_control.png"
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
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
        step("fixture", "published PNG control at $uri (${bytes.size} bytes)")
        return Sample(name, "image/png", name, "PNG control")
    }

    // ---------------------------------------------------------------- host

    /**
     * Replaces MainActivity's content view with [content]. The rule's `setContent` cannot be used:
     * MainActivity already composed in `onCreate` ("has already set content").
     */
    private fun hostContent(content: @Composable () -> Unit) {
        composeRule.runOnUiThread {
            val activity = composeRule.activity
            ComposeView(activity).let { cv ->
                cv.setContent(content)
                activity.setContentView(cv)
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        /** An embedded preview is at most ~2k px wide; every RAW here is 36 MP and up (7360 px+). */
        const val FULL_FRAME_MIN_WIDTH = 3000

        /** A 36..50 MP software demosaic on the emulator is slow; 5 min per sample. */
        const val DECODE_TIMEOUT_MS = 300_000L
    }
}
