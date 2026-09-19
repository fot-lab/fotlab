package io.github.fotlab.fotlab.smoke

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fotlab.fotlab.MainActivity
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.library.LibraryCore
import io.github.fotlab.fotlab.feature.library.LibraryScreen
import io.github.fotlab.fotlab.feature.studio.StudioEngine
import io.github.fotlab.fotlab.feature.studio.StudioRenderResult
import io.github.fotlab.fotlab.feature.studio.StudioScreen
import io.github.fotlab.fotlab.ui.theme.AppTheme
import io.github.fotlab.fotlab_rawler.DemosaicAlgorithm
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
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
        // Isolate each journey: the emulator screen is tiny (320x640) and the library grid is lazy,
        // so a node buried under imports from earlier tests would never be composed into the
        // semantics tree and the grid-wait would time out. Start every test from an empty library.
        runBlocking { clearLibrary() }
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
        /**
         * Text used to find the node in the grid. Must be the file EXTENSION: `MiddleEllipsisText`
         * keeps the trailing suffix whole and truncates the head, so the extension is the only
         * part of a long camera filename guaranteed to survive truncation on screen.
         */
        val gridText: String,
        val label: String,
    )

    // The grid is matched by the file EXTENSION, not by a leading prefix. `MiddleEllipsisText`
    // keeps the trailing suffix (the extension) whole and truncates the head, so on the
    // emulator's 320px-wide screen a long camera filename loses its head entirely — "Canon EOS
    // 5DS" is cut away and only ".CR2" is still on screen. A prefix can therefore never be
    // relied on; the extension always can. `clearLibrary()` leaves exactly one node in the grid
    // per test, so the extension is unambiguous even though the two Sony samples share ".ARW".
    private val canonCr2 = Sample(
        "Canon EOS 5DS_ISO_1000_RAW.CR2", "image/x-canon-cr2", ".CR2", "Canon EOS 5DS CR2",
    )
    private val sonyArw7r = Sample(
        "ILCE-7R_ISO_50_14bits_Sony ARW Compressed.ARW", "image/x-sony-arw", ".ARW", "Sony ILCE-7R ARW",
    )
    private val sonyArw7rm2 = Sample(
        "ILCE-7RM2_ISO_100_14bits_Compressed RAW.ARW", "image/x-sony-arw", ".ARW", "Sony ILCE-7RM2 ARW",
    )
    private val nikonNef = Sample(
        "NIKON D850_Large_ISO_64_14bits_Lossless.NEF", "image/x-nikon-nef", ".NEF", "Nikon D850 NEF",
    )
    private val panasonicRw2 = Sample(
        "DC-S1R_ISO_100_6fmt_8368x5584.RW2", "image/x-panasonic-rw2", ".RW2", "Panasonic DC-S1R RW2",
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
            val png = model as java.nio.ByteBuffer
            step("studio", "rawler returned ${png.remaining()} bytes")
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val dup = png.duplicate()
            val stream = object : java.io.InputStream() {
                override fun read(): Int = if (dup.hasRemaining()) (dup.get().toInt() and 0xFF) else -1
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!dup.hasRemaining()) return -1
                    val n = minOf(len, dup.remaining())
                    dup.get(b, off, n)
                    return n
                }
            }
            BitmapFactory.decodeStream(stream, null, opts)
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

    // ---------------------------------------------------------------- develop path

    // Every RAW corpus file must survive the COMPLETE user journey, develop included:
    // import → grid tap → viewer → Open in Studio → the as-shot-developed COLOR canvas
    // (the resident RawlerImageLoaded is developed once with DEFAULT on open) → bottom-bar
    // Demosaic sheet → pick PPG → a successful redevelop that keeps a full-frame COLOR png.
    // The develop stage used to panic inside rawler's crop stage on every file whose crop area
    // is offset in full-sensor coordinates (CR2's embedded sensor-area crop, DNG's
    // DefaultCropOrigin) — the catch_unwind boundary turned that into "Unsupported Format" in
    // the UI. Sony ILCE-7R alone has an uncropped active area, which is why develop previously
    // appeared to pass when only it was tested. One @Test per sample keeps the failing brand
    // visible in the CI report.
    @Test
    fun canonCr2DevelopsThroughDemosaicMenu() = developThroughUserJourney(canonCr2)

    @Test
    fun sonyIlce7rArwDevelopsThroughDemosaicMenu() = developThroughUserJourney(sonyArw7r)

    @Test
    fun sonyIlce7rm2ArwDevelopsThroughDemosaicMenu() = developThroughUserJourney(sonyArw7rm2)

    @Test
    fun nikonD850NefDevelopsThroughDemosaicMenu() = developThroughUserJourney(nikonNef)

    @Test
    fun panasonicDcS1rRw2DevelopsThroughDemosaicMenu() = developThroughUserJourney(panasonicRw2)

    /**
     * The demosaic develop path, driven through the REAL Studio UI the way a user triggers it.
     *
     * Since the resident-image refactor (`FOTLAB-RAWLER-000004`), opening a RAW already develops
     * it once with as-shot params and the CFA-default algorithm, so the first canvas frame must
     * already be a full-frame **linear COLOR** PNG (no more grayscale preview). The user then
     * opens the bottom-bar "Demosaic" sheet and picks PPG — on every Bayer camera in this corpus
     * DEFAULT already resolves to PPG, so the redevelop deterministically reproduces the same
     * image; the success criteria is therefore "a genuine redevelop ran (Loading → Ready via the
     * native bridge) and the canvas stays a full-frame color PNG", NOT byte difference. Run for
     * every RAW in the corpus (`FOTLAB-STUDIO-000001` R4).
     */
    private fun developThroughUserJourney(sample: Sample) {
        journey(sample, expectRawler = true) // routing + as-shot-developed Ready assertion

        // The journey leaves the as-shot-developed (DEFAULT) frame on the engine: it must already
        // be color, proving open-time develop works end to end.
        val initial = StudioEngine.renderResult.value as? StudioRenderResult.Ready
            ?: throw AssertionError("expected a developed Ready after the journey")
        val initialBytes = toBytes(initial.model)
        step("develop", "as-shot canvas frame captured: ${initialBytes.size} bytes")
        assertDevelopedIsColor(initialBytes, "${sample.label} as-shot canvas")

        // The user is now looking at the Studio canvas; host the REAL StudioScreen.
        hostContent { AppTheme { StudioScreen() } }
        composeRule.waitForIdle()

        // The top-bar gradient icon opens the demosaic algorithm dropdown.
        val demosaic = context.getString(R.string.studio_cd_demosaic)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithContentDescription(demosaic).fetchSemanticsNodes().isNotEmpty()
        }
        step("ui", "top-bar demosaic icon present")
        composeRule.onNodeWithContentDescription(demosaic).performClick()
        step("ui", "clicked the demosaic icon")

        // The dropdown offers the algorithms; PPG is the Bayer choice for every camera in
        // the corpus (Canon/Sony/Nikon/Panasonic sensors are Bayer RGB).
        val ppg = context.getString(R.string.studio_demosaic_ppg)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(ppg).fetchSemanticsNodes().isNotEmpty()
        }
        step("ui", "demosaic dropdown shows '$ppg'")
        composeRule.onNodeWithText(ppg).performClick()
        step("ui", "picked '$ppg' — triggers StudioEngine.develop redevelop")

        // The engine re-develops the resident image; wait for the Ready full-frame COLOR PNG.
        // Equal bytes are the correct outcome (PPG == the Bayer default); only Loading→Ready
        // plus a valid color frame are required.
        val developed = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame() }
        }
        val relation = if (developed.bytes.contentEquals(initialBytes)) "identical to as-shot (PPG is the Bayer default)" else "differs from as-shot"
        step(
            "develop",
            "redeveloped via UI menu: ${developed.outWidth}x${developed.outHeight} (${developed.bytes.size} bytes), $relation",
        )
        assertDevelopedIsColor(developed.bytes, sample.label)
    }

    /**
     * Every demosaic option the menu exposes must redevelop the resident RAW through the real native
     * `develop_rawler_image` path without error and keep a full-frame COLOR PNG. On a Bayer sensor
     * (this Sony ARW) the resolution contract is: DEFAULT and PPG both run PPG, while the incompatible
     * picks (BILINEAR4_CHANNEL, X_TRANS_BILINEAR) fall back to PPG — so every option must produce a
     * frame byte-identical to the as-shot DEFAULT frame. Deterministic equality is asserted on
     * purpose: it pins both the CFA resolution and the fallback behavior, and still proves each menu
     * option completes (no panic surfaced as Unsupported, no error frame).
     *
     * Finally, a parameter that MUST move pixels — exposure EV +1 stop via [StudioEngine.setExposureEv]
     * — has to produce a different (still color, full-frame) PNG and EV 0 must reproduce the original,
     * proving redevelop genuinely recomputes calibrate on the resident image rather than returning a
     * cached frame. Engine-level (same entry points the bottom bar calls) on a 36 MP Sony ARW to keep
     * the emulator budget sane; the per-brand UI journey is [developThroughUserJourney].
     */
    @Test
    fun everyDemosaicMenuOptionRedevelopsOnBayerAndExposureRecomputes() {
        val uri = indexAndFind(sonyArw7r)
            ?: throw AssertionError("Sony ARW not on the SD card at /sdcard/Pictures/rawdb/${sonyArw7r.file}")
        step("sdcard", "source=${sonyArw7r.file}")
        runBlocking { LibraryCore.importUris(parentId = null, uris = listOf(uri)) }
        val node = runBlocking { LibraryCore.getByUri(uri.toString()) }!!
        StudioEngine.setCurrentNode(node.uriStorage)

        val initial = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) {
                StudioEngine.renderResult
                    .filterNot { it is StudioRenderResult.Idle || it is StudioRenderResult.Loading }
                    .first()
            }
        }
        assertTrue("as-shot develop must reach Ready", initial is StudioRenderResult.Ready)
        val initialBytes = toBytes((initial as StudioRenderResult.Ready).model)
        step("studio", "as-shot DEFAULT frame = ${initialBytes.size} bytes")
        assertDevelopedIsColor(initialBytes, "Sony ILCE-7R as-shot DEFAULT")

        for (algo in listOf(
            DemosaicAlgorithm.DEFAULT,
            DemosaicAlgorithm.PPG,
            DemosaicAlgorithm.BILINEAR4_CHANNEL,
            DemosaicAlgorithm.X_TRANS_BILINEAR,
        )) {
            StudioEngine.develop(algo)
            val developed = runBlocking {
                withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame() }
            }
            step("develop", "$algo -> ${developed.outWidth}x${developed.outHeight} (${developed.bytes.size} bytes)")
            assertDevelopedIsColor(developed.bytes, "Sony ILCE-7R $algo")
            // Bayer: all four options resolve/fall back to PPG → deterministic identical output.
            assertTrue(
                "$algo on Bayer must resolve/fall back to PPG and reproduce the DEFAULT frame",
                developed.bytes.contentEquals(initialBytes),
            )
        }

        // +1 EV doubles linear light before clipping; a meaningful share of (dark) pixels must move,
        // so the PNG cannot stay identical — this is the "redevelop really recomputed" proof.
        StudioEngine.setExposureEv(1f)
        val brighter = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = initialBytes) }
        }
        step("develop", "EV +1 -> ${brighter.outWidth}x${brighter.outHeight} (${brighter.bytes.size} bytes), differs")
        assertDevelopedIsColor(brighter.bytes, "Sony ILCE-7R EV +1")

        // EV 0 reproduces the as-shot frame. Restore because StudioEngine is a process-wide singleton.
        StudioEngine.setExposureEv(0f)
        val restored = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = brighter.bytes) }
        }
        assertTrue("EV 0 must reproduce the as-shot frame", restored.bytes.contentEquals(initialBytes))
        step("develop", "EV 0 restored the as-shot frame")
    }

    /**
     * A manually entered Kelvin value must redevelop the resident RAW into a full-frame COLOR
     * (never black) PNG, and warm vs cool entries have to move the pixels in opposite chromatic
     * directions. This is the regression for the white-balance black-frame bug: the native
     * Kelvin→multiplier projection used the raw illuminant-specific color matrix unadapted and
     * zeroed every non-positive camera channel, so the develop returned an all-zero frame.
     * Engine-level on the 36 MP Sony ARW (same budget rationale as the exposure test); the
     * dialog that feeds this entry point is exercised by the UI journey suite.
     */
    @Test
    fun manualKelvinWhiteBalanceRedevelopsWithoutBlack() {
        val uri = indexAndFind(sonyArw7r)
            ?: throw AssertionError("Sony ARW not on the SD card at /sdcard/Pictures/rawdb/${sonyArw7r.file}")
        runBlocking { LibraryCore.importUris(parentId = null, uris = listOf(uri)) }
        val node = runBlocking { LibraryCore.getByUri(uri.toString()) }!!
        StudioEngine.setCurrentNode(node.uriStorage)

        val initial = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) {
                StudioEngine.renderResult
                    .filterNot { it is StudioRenderResult.Idle || it is StudioRenderResult.Loading }
                    .first()
            }
        }
        assertTrue("as-shot develop must reach Ready", initial is StudioRenderResult.Ready)
        val initialBytes = toBytes((initial as StudioRenderResult.Ready).model)
        assertDevelopedIsColor(initialBytes, "Sony ILCE-7R as-shot")

        StudioEngine.setWhiteBalanceKelvin(3200f) // warm tungsten
        val warm = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = initialBytes) }
        }
        step("develop", "WB 3200 K -> ${warm.outWidth}x${warm.outHeight} (${warm.bytes.size} bytes)")
        assertDevelopedIsColor(warm.bytes, "Sony ILCE-7R WB 3200K (must stay color, never black)")

        StudioEngine.setWhiteBalanceKelvin(9000f) // cool blue sky
        val cool = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = warm.bytes) }
        }
        step("develop", "WB 9000 K -> ${cool.outWidth}x${cool.outHeight} (${cool.bytes.size} bytes)")
        assertDevelopedIsColor(cool.bytes, "Sony ILCE-7R WB 9000K (must stay color, never black)")
    }

    // ---------------------------------------------------------------- grade fork (Boost / LOG)

    /**
     * The grade fork's engine-level user journey on a resident RAW (LUT, which needs a picked
     * file, is covered end to end by
     * [panasonicVLogAndDownloadedLutCubeBothTakeEffect]):
     *
     *  1. opening a RAW flips `isRawLoaded` true and leaves the Boost/LOG/LUT selection at the
     *     all-"none" default (no grade error);
     *  2. Boost ON re-renders through the native rawalchemy path (`develop_and_grade`) and MOVES
     *     pixels vs the as-shot sRGB develop while keeping a full-frame color PNG;
     *  3. picking a log curve (S-Log3) on top re-renders again and moves pixels (the log-encoded
     *     frame is intentionally not color-checked — log encoding flattens chroma);
     *  4. LOG back to "none" while Boost stays on deterministically reproduces the boost-only
     *     frame;
     *  5. Boost back to "none" returns the canvas to the develop fork, byte-identical to the
     *     as-shot frame (all-"none" is a plain reDevelop, not a graded black/linear buffer);
     *  6. switching the node away resets the grade selection, `isRawLoaded` and the error state.
     *
     * Every step also proves the new C++ grade path (log-space lookup, gamut matrix, log curve,
     * fused grading) executes inside the process without a native abort.
     */
    @Test
    fun boostAndLogGradesReRenderThroughEngineAndNoneRestoresDevelop() {
        val uri = indexAndFind(sonyArw7r)
            ?: throw AssertionError("Sony ARW not on the SD card at /sdcard/Pictures/rawdb/${sonyArw7r.file}")
        runBlocking { LibraryCore.importUris(parentId = null, uris = listOf(uri)) }
        val node = runBlocking { LibraryCore.getByUri(uri.toString()) }!!
        StudioEngine.setCurrentNode(node.uriStorage)

        val initial = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) {
                StudioEngine.renderResult
                    .filterNot { it is StudioRenderResult.Idle || it is StudioRenderResult.Loading }
                    .first()
            }
        }
        assertTrue("as-shot develop must reach Ready", initial is StudioRenderResult.Ready)
        val asShotBytes = toBytes((initial as StudioRenderResult.Ready).model)
        assertDevelopedIsColor(asShotBytes, "Sony ILCE-7R as-shot DEFAULT")

        runBlocking {
            assertTrue("a routed RAW must mark isRawLoaded", StudioEngine.isRawLoaded.first())
        }
        assertEquals(
            "grade selection starts at all-none on file open",
            StudioEngine.GradeSelection(),
            StudioEngine.gradeSelection.value,
        )
        assertNull(StudioEngine.gradeError.value)
        val spaces = StudioEngine.supportedLogSpaces()
        assertTrue("native log-space list must enumerate S-Log3, got $spaces", "S-Log3" in spaces)

        // 2) Boost ON: contrast/saturation enhancement, pixels must move, color must survive.
        StudioEngine.setGradeBoost(true)
        val boosted = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = asShotBytes) }
        }
        step("grade", "boost ON -> ${boosted.outWidth}x${boosted.outHeight} (${boosted.bytes.size} bytes), differs")
        assertDevelopedIsColor(boosted.bytes, "Sony ILCE-7R boost ON")
        assertTrue(StudioEngine.gradeSelection.value.boost)
        assertNull("boost grade must not surface a grade error", StudioEngine.gradeError.value)

        // 3) S-Log3 ON on top: gamut conversion + log encoding, pixels move again.
        StudioEngine.setGradeLogSpace("S-Log3")
        val logged = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = boosted.bytes) }
        }
        step("grade", "S-Log3 -> ${logged.outWidth}x${logged.outHeight} (${logged.bytes.size} bytes), differs")
        assertNull("log grade must not surface a grade error", StudioEngine.gradeError.value)
        assertTrue(StudioEngine.gradeSelection.value.logSpace == "S-Log3")

        // 4) LOG none, Boost still ON — deterministic reproduction of the boost-only frame.
        StudioEngine.setGradeLogSpace(null)
        val boostOnly = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = logged.bytes) }
        }
        assertTrue(
            "removing the log curve while boost stays on must reproduce the boost-only frame",
            boostOnly.bytes.contentEquals(boosted.bytes),
        )

        // 5) Boost none — all-"none" returns the plain develop fork, byte-identical to as-shot.
        StudioEngine.setGradeBoost(false)
        val restored = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = boostOnly.bytes) }
        }
        assertTrue(
            "all-none grade selection must restore the as-shot develop frame",
            restored.bytes.contentEquals(asShotBytes),
        )

        // 6) File switch resets every grade surface for the next file.
        StudioEngine.setCurrentNode(null)
        assertEquals(
            "leaving the node must reset the grade selection",
            StudioEngine.GradeSelection(),
            StudioEngine.gradeSelection.value,
        )
        assertFalse("leaving the node must clear isRawLoaded", StudioEngine.isRawLoaded.value)
        assertNull(StudioEngine.gradeError.value)
    }

    /**
     * UI-level grade journey through the REAL [StudioScreen]: with a RAW resident the grade bar is
     * rendered, the Boost chip dropdown drives [StudioEngine.setGradeBoost] end to end (ON moves
     * pixels, the chip relabels) and the LOG dropdown enumerates the natively-listed log curves;
     * picking S-Log3 from it drives a second real re-grade. Both chips are then returned to
     * "none" and the canvas must reproduce the as-shot develop frame. The popup is dismissed by
     * picking an item ON PURPOSE — system back can reach the hosted MainActivity once the popup
     * settles and finish it. LUT needs a picked file and is covered end to end (engine level) by
     * [panasonicVLogAndDownloadedLutCubeBothTakeEffect].
     */
    @Test
    fun gradeBarChipsDriveGradingThroughRealStudioUi() {
        journey(sonyArw7r, expectRawler = true)
        val initialBytes = toBytes((StudioEngine.renderResult.value as StudioRenderResult.Ready).model)

        hostContent { AppTheme { StudioScreen() } }
        val none = context.getString(R.string.studio_grade_none)
        val boostChipNone = context.getString(R.string.studio_grade_bar_boost, none)
        val logChipNone = context.getString(R.string.studio_grade_bar_log, none)
        val lutChipNone = context.getString(R.string.studio_grade_bar_lut, none)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(boostChipNone).fetchSemanticsNodes().isNotEmpty()
        }
        step("grade-ui", "boost chip rendered at all-none")
        // All three chips compose the bar (LUT chip excluded only from interaction, not rendering).
        assertTrue(
            "LOG chip must be rendered for a RAW",
            composeRule.onAllNodesWithText(logChipNone).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "LUT chip must be rendered for a RAW",
            composeRule.onAllNodesWithText(lutChipNone).fetchSemanticsNodes().isNotEmpty(),
        )

        // Boost chip -> "Boost" item -> a genuine native re-grade that moves pixels.
        composeRule.onNodeWithText(boostChipNone).performClick()
        val boostOn = context.getString(R.string.studio_grade_boost_on)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(boostOn).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(boostOn).performClick()
        step("grade-ui", "picked Boost ON from the chip dropdown")
        val boosted = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = initialBytes) }
        }
        assertDevelopedIsColor(boosted.bytes, "Sony ILCE-7R boost ON via UI")

        // The chip relabels to the active value.
        val boostChipActive = context.getString(R.string.studio_grade_bar_boost, boostOn)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(boostChipActive).fetchSemanticsNodes().isNotEmpty()
        }

        // LOG dropdown enumerates the native log-space names. Pick S-Log3 instead of dismissing
        // with system back: the menu is a Popup, and once its state settles the back press is
        // delivered to the hosted MainActivity and FINISHES it ("No compose hierarchies found"
        // on every later node lookup). Picking the item closes the popup deterministically and
        // additionally proves a log pick drives a real native re-grade through the UI.
        composeRule.onNodeWithText(logChipNone).performClick()
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText("S-Log3").fetchSemanticsNodes().isNotEmpty()
        }
        step("grade-ui", "LOG dropdown enumerates native spaces (S-Log3 present)")
        composeRule.onNodeWithText("S-Log3").performClick()
        val logged = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = boosted.bytes) }
        }
        step("grade-ui", "picked S-Log3 — native re-grade moved ${logged.bytes.size} bytes")

        // Boost chip -> none while S-Log3 stays on: still a graded frame, differs from boost+log.
        composeRule.onNodeWithText(boostChipActive).performClick()
        composeRule.onNodeWithText(none).performClick()
        step("grade-ui", "picked none from the boost dropdown")
        val logOnly = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = logged.bytes) }
        }

        // LOG chip -> none: all-"none" returns the canvas to the as-shot develop frame.
        val logChipActive = context.getString(R.string.studio_grade_bar_log, "S-Log3")
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(logChipActive).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(logChipActive).performClick()
        composeRule.onNodeWithText(none).performClick()
        step("grade-ui", "picked none from the LOG dropdown")
        val restored = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = logOnly.bytes) }
        }
        assertTrue(
            "all-none via the chips must restore the as-shot develop frame",
            restored.bytes.contentEquals(initialBytes),
        )
    }

    // ---------------------------------------------------------------- grade fork (LUT cube)

    /**
     * The grade fork's COMPLETE user journey with the one external input the grade bar takes:
     * a real `.cube` 3D LUT file, on the Panasonic RAW (DC-S1R RW2), following the user request
     * "import a Panasonic image, load it into Studio, pick the V-Log curve, pick the LUT cube
     * downloaded from GitHub, and observe whether the log and the LUT actually take effect":
     *
     *  1. the corpus RW2 is imported and loaded into Studio exactly like
     *     [panasonicDcS1rRw2OpensInStudioThroughRawler]; the canvas is the as-shot full-frame
     *     color develop;
     *  2. **V-Log** — [StudioEngine.setGradeLogSpace] re-renders through the native
     *     gamut-matrix + V-Log curve path; the frame must change and no grade error may surface;
     *  3. **LUT** — the cube (downloaded from fot-lab/V-Log-Alchemy by `smoke_emulator.yaml`,
     *     SHA-256 pinned, staged into the app's files via `run-as`) is republished as a
     *     `content://` uri — exactly the uri shape the system SAF picker returns — and handed to
     *     [StudioEngine.setGradeLut]; the engine copies it into its content-addressed cache
     *     (the copy is checksum-verified) and the native `loadCubeLUT` + tetrahedral apply runs
     *     in-process; the frame must change again, carry chroma (the VLog-input cube bakes the
     *     flat log image back to a display-referred CLASSIC Neg look), and no error may surface;
     *  4. clearing the LUT deterministically reproduces the V-Log-only frame;
     *  5. clearing the log returns the all-"none" develop fork, byte-identical to as-shot;
     *  6. leaving the node resets the whole grade selection.
     *
     * The cube is `FLog2C_to_CLASSIC-Neg_VLog.cube` from the V-Log-Alchemy repo: per that
     * repo's two-LUT workflow the `*_VLog.cube` files are LUT2-style creative cubes that expect
     * V-Log/V-Gamut INPUT and output a display-referred look — the exact output space of our
     * V-Log grade stage (`MAT_PROPHOTO_TO_V_GAMUT` + `LogCurve::V_Log` in rawalchemy's
     * color_data.h). This is a "both stages take effect" journey, not a Panasonic color-science
     * conformance check; its 33-point Resolve-generated header is parsed by the same
     * loadCubeLUT path RawAlchemyCpp itself exercises in its own Test fixtures.
     */
    @Test
    fun panasonicVLogAndDownloadedLutCubeBothTakeEffect() {
        val uri = indexAndFind(panasonicRw2)
            ?: throw AssertionError("Panasonic RW2 not on the SD card at /sdcard/Pictures/rawdb/${panasonicRw2.file}")
        runBlocking { LibraryCore.importUris(parentId = null, uris = listOf(uri)) }
        val node = runBlocking { LibraryCore.getByUri(uri.toString()) }!!
        StudioEngine.setCurrentNode(node.uriStorage)

        // ---- 1) as-shot full-frame color canvas ----
        val initial = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) {
                StudioEngine.renderResult
                    .filterNot { it is StudioRenderResult.Idle || it is StudioRenderResult.Loading }
                    .first()
            }
        }
        assertTrue("as-shot develop must reach Ready", initial is StudioRenderResult.Ready)
        val asShotBytes = toBytes((initial as StudioRenderResult.Ready).model)
        step("lut-e2e", "Panasonic as-shot frame = ${asShotBytes.size} bytes")
        assertDevelopedIsColor(asShotBytes, "Panasonic DC-S1R as-shot DEFAULT")

        runBlocking {
            assertTrue("a routed RAW must mark isRawLoaded", StudioEngine.isRawLoaded.first())
        }
        assertEquals(StudioEngine.GradeSelection(), StudioEngine.gradeSelection.value)
        assertNull(StudioEngine.gradeError.value)
        val spaces = StudioEngine.supportedLogSpaces()
        assertTrue("native log-space list must enumerate V-Log, got $spaces", "V-Log" in spaces)

        // ---- 2) V-Log curve takes effect (flat log image, pixels move, no error) ----
        StudioEngine.setGradeLogSpace("V-Log")
        val vlogOnly = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = asShotBytes) }
        }
        step("lut-e2e", "V-Log -> ${vlogOnly.outWidth}x${vlogOnly.outHeight}, differs from as-shot")
        assertNull("V-Log grade must not surface a grade error", StudioEngine.gradeError.value)
        assertEquals("V-Log", StudioEngine.gradeSelection.value.logSpace)

        // ---- 3) the downloaded cube takes effect through the real SAF-shaped content uri ----
        val lutUri = pushedLutAsContentUri()
        StudioEngine.setGradeLut(lutUri)
        val lutted = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = vlogOnly.bytes) }
        }

        val selection = StudioEngine.gradeSelection.value
        assertNull("LUT grade must not surface a grade error", StudioEngine.gradeError.value)
        assertEquals("V-Log stays selected while the LUT is on", "V-Log", selection.logSpace)
        assertEquals(LUT_FIXTURE_NAME, selection.lutName)
        val lutPath = selection.lutPath
            ?: throw AssertionError("grade selection must carry the cached cube path after setGradeLut")

        // The engine copied the picked bytes verbatim into its content-addressed cache.
        val cachedHash = sha256Hex(java.io.File(lutPath).readBytes())
        assertEquals(
            "the cached cube must be the exact downloaded artifact (content-addressed copy)",
            LUT_FIXTURE_SHA256, cachedHash,
        )
        step("lut-e2e", "cube copied to cache: $lutPath sha256=$cachedHash")

        // The VLog-input cube outputs a display-referred color image — the visible proof the LUT
        // stage actually ran after the log stage, rather than leaving the flat log encoding.
        assertDevelopedIsColor(lutted.bytes, "Panasonic V-Log + $LUT_FIXTURE_NAME")
        step("lut-e2e", "V-Log + cube -> ${lutted.outWidth}x${lutted.outHeight}, color survived")

        // ---- 4) clear LUT: deterministic return to the V-Log-only frame ----
        StudioEngine.clearGradeLut()
        val backToVLog = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = lutted.bytes) }
        }
        assertTrue(
            "removing the cube must reproduce the V-Log-only frame",
            backToVLog.bytes.contentEquals(vlogOnly.bytes),
        )

        // ---- 5) clear LOG: all-none returns the as-shot develop frame ----
        StudioEngine.setGradeLogSpace(null)
        val restored = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = backToVLog.bytes) }
        }
        assertTrue(
            "all-none must restore the as-shot develop frame",
            restored.bytes.contentEquals(asShotBytes),
        )

        // ---- 6) node switch resets the grade surfaces ----
        StudioEngine.setCurrentNode(null)
        assertEquals(StudioEngine.GradeSelection(), StudioEngine.gradeSelection.value)
        assertFalse(StudioEngine.isRawLoaded.value)
        assertNull(StudioEngine.gradeError.value)
    }

    /**
     * The LUT fixture staged by `smoke_emulator.yaml`: the workflow downloads the cube from
     * fot-lab/V-Log-Alchemy, verifies its SHA-256, then — because shell-created directories on
     * the emulated SD card are not traversable by the app and SELinux blocks app reads of
     * `/data/local/tmp` — streams it into the app's internal files with
     * `adb shell run-as <pkg> cat > files/lut-fixture/...`.
     *
     * Here the bytes are republished through `MediaStore.Downloads`, which hands back the same
     * `content://` uri shape the system file picker's SAF callback delivers — so
     * [StudioEngine.setGradeLut] runs the exact production path (resolver DISPLAY_NAME query,
     * stream copy into the content-addressed cache) rather than a test-only file uri.
     */
    private fun pushedLutAsContentUri(): Uri {
        val src = java.io.File(context.filesDir, "lut-fixture/$LUT_FIXTURE_NAME")
        if (!src.isFile) {
            throw AssertionError(
                "LUT fixture missing at ${src.absolutePath} — the smoke workflow's " +
                    "installDebug + run-as staging step must place it before the tests run",
            )
        }
        val bytes = src.readBytes()
        val hash = sha256Hex(bytes)
        assertEquals(
            "staged cube must match the SHA-256 pinned from fot-lab/V-Log-Alchemy@$LUT_FIXTURE_REF",
            LUT_FIXTURE_SHA256, hash,
        )
        step("lut-fixture", "staged cube ${bytes.size} bytes sha256=$hash")

        assumeTrue(
            "MediaStore.Downloads requires API 29 (the smoke AVD is 36)",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, LUT_FIXTURE_NAME)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/FotLabE2E")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw AssertionError("MediaStore.Downloads insert returned null")
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        step("lut-fixture", "republished the cube as a content uri: $uri")
        return uri
    }

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * The USER's LUT journey at UI level: tap the grade bar's LUT chip → the "pick" menu item →
     * the [androidx.activity.result.contract.ActivityResultContracts.OpenDocument] contract fires
     * the system file picker → the picked `content://` uri flows through the real
     * `rememberLauncherForActivityResult` callback into [StudioEngine.setGradeLut] → the canvas
     * re-renders.
     *
     * The system DocumentsUI is an activity in ANOTHER process that a Compose test cannot drive
     * for real; the standard instrumentation technique is espresso-intents: [Intents.intending]
     * stubs the picker's response with a `content://` uri that points at the REAL downloaded cube
     * (republished into MediaStore by [pushedLutAsContentUri]), and [Intents.intended] afterwards
     * proves the app actually launched the picker intent. Everything app-side is production code:
     * chip click, menu item, contract launch, result delivery, cache copy, native LUT apply.
     *
     * Assertions: the picker intent fired and was answered, pixels moved, no grade error, the
     * selection carries the cube's display name, the chip relabels to the picked file, and the
     * chip's "clear" item deterministically restores the as-shot develop frame.
     */
    @Test
    fun lutChipOpensSystemPickerAndAppliesDownloadedCube() {
        // Engine-level setup (same entry points the grid journey ends in; the grid/viewer detour
        // is already covered by the other tests and each RAW develop costs emulator minutes).
        val uri = indexAndFind(panasonicRw2)
            ?: throw AssertionError("Panasonic RW2 not on the SD card at /sdcard/Pictures/rawdb/${panasonicRw2.file}")
        runBlocking { LibraryCore.importUris(parentId = null, uris = listOf(uri)) }
        val node = runBlocking { LibraryCore.getByUri(uri.toString()) }!!
        StudioEngine.setCurrentNode(node.uriStorage)
        val initial = runBlocking {
            withTimeout(DECODE_TIMEOUT_MS) {
                StudioEngine.renderResult
                    .filterNot { it is StudioRenderResult.Idle || it is StudioRenderResult.Loading }
                    .first()
            }
        }
        assertTrue("as-shot develop must reach Ready", initial is StudioRenderResult.Ready)
        val asShotBytes = toBytes((initial as StudioRenderResult.Ready).model)

        // The REAL Studio grade bar, with a RAW resident so the LUT chip exists.
        hostContent { AppTheme { StudioScreen() } }
        val none = context.getString(R.string.studio_grade_none)
        val lutChipNone = context.getString(R.string.studio_grade_bar_lut, none)
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithText(lutChipNone).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(StudioEngine.GradeSelection(), StudioEngine.gradeSelection.value)

        // Stub the system file picker to answer with the downloaded cube's content uri, then
        // walk the real UI: chip -> "pick" item -> OpenDocument -> result delivery.
        val lutUri = pushedLutAsContentUri()
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_OPEN_DOCUMENT))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(lutUri)))

            composeRule.onNodeWithText(lutChipNone).performClick()
            val pick = context.getString(R.string.studio_grade_lut_pick)
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithText(pick).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(pick).performClick()
            step("lut-ui", "tapped the LUT chip and the pick item — OpenDocument launched")

            // The launcher really fired (and only our stub answered it — no real picker ran).
            intended(hasAction(Intent.ACTION_OPEN_DOCUMENT))

            // setGradeLut ran through the real callback: re-grade moves pixels, no error.
            val graded = runBlocking {
                withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = asShotBytes) }
            }
            assertNull("LUT pick via the UI must not surface a grade error", StudioEngine.gradeError.value)
            assertEquals(LUT_FIXTURE_NAME, StudioEngine.gradeSelection.value.lutName)
            // MiddleEllipsis-style labels keep the trailing suffix; ".cube" is the part guaranteed
            // to still be on the chip after the long cube name truncates.
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithText(".cube", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            step("lut-ui", "chip relabeled to the picked cube; graded ${graded.bytes.size} bytes")

            // The chip's clear item returns the canvas to the as-shot develop frame.
            composeRule.onAllNodesWithText(".cube", substring = true)[0].performClick()
            val clear = context.getString(R.string.studio_grade_lut_clear)
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithText(clear).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(clear).performClick()
            step("lut-ui", "tapped the clear item")
            val restored = runBlocking {
                withTimeout(DECODE_TIMEOUT_MS) { waitForDevelopedFrame(requireDifferentFrom = graded.bytes) }
            }
            assertTrue(
                "clearing the LUT via the chip must restore the as-shot develop frame",
                restored.bytes.contentEquals(asShotBytes),
            )
            assertNull(StudioEngine.gradeSelection.value.lutPath)
        } finally {
            Intents.release()
            StudioEngine.setCurrentNode(null)
        }
    }

    /** The grade bar is a RAW-only surface: the Coil/PNG route must keep it off-screen. */
    @Test
    fun gradeBarIsHiddenOnPngRoute() {
        journey(pngControl(), expectRawler = false)
        hostContent { AppTheme { StudioScreen() } }
        composeRule.waitForIdle()
        assertTrue(
            "no grade-bar chip may exist for the PNG/Coil route",
            composeRule.onAllNodesWithText("Boost:", substring = true).fetchSemanticsNodes().isEmpty(),
        )
        assertFalse("isRawLoaded must stay false for the PNG/Coil route", StudioEngine.isRawLoaded.value)
    }

    /**
     * Assert the developed PNG actually carries chroma: independent R/G/B channels. The grayscale
     * raw preview ([io.github.fotlab.fotlab.media.RawDecoder.decodeToPng]) writes R == G == B for
     * every pixel; a frame that merely "differs" from the preview could still be a re-encoded
     * grayscale, so a developed image must show a channel spread on a meaningful share of pixels.
     *
     * The 36-50 MP frame is decoded at [COLOR_CHECK_SAMPLE_SHIFT] downsampling — a full ARGB8
     * bitmap would not fit the emulator heap, but chroma presence survives a 32x downsample and
     * the int[] stays a few hundred KB.
     */
    private fun assertDevelopedIsColor(bytes: ByteArray, label: String) {
        val opts = BitmapFactory.Options().apply { inSampleSize = COLOR_CHECK_SAMPLE_SHIFT }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        assertTrue("$label: developed PNG is not decodable for the color check", bmp != null)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        bmp.recycle()

        var colored = 0
        var maxSpread = 0
        for (c in pixels) {
            val r = android.graphics.Color.red(c)
            val g = android.graphics.Color.green(c)
            val b = android.graphics.Color.blue(c)
            val spread = maxOf(kotlin.math.abs(r - g), kotlin.math.abs(g - b), kotlin.math.abs(r - b))
            if (spread > maxSpread) maxSpread = spread
            if (spread >= COLOR_SPREAD_MIN_LEVELS) colored++
        }
        val coloredRatio = colored.toDouble() / pixels.size
        step(
            "develop",
            "color check: $colored/${pixels.size} sampled pixels (${"%.2f".format(coloredRatio * 100)}%) " +
                "with channel spread >= $COLOR_SPREAD_MIN_LEVELS, max spread $maxSpread",
        )
        assertTrue(
            "$label: developed frame is grayscale — demosaic/white-balance/calibrate did not produce " +
                "color ($colored/${pixels.size} sampled pixels with channel spread >= $COLOR_SPREAD_MIN_LEVELS)",
            colored >= pixels.size / COLOR_COLORED_MIN_FRACTION,
        )
    }

    /** Read a `ByteBuffer` model out of [StudioRenderResult.Ready] without disturbing the buffer. */
    private fun toBytes(model: Any?): ByteArray {
        val buf = model as java.nio.ByteBuffer
        val dup = buf.asReadOnlyBuffer()
        val arr = ByteArray(dup.remaining())
        dup.get(arr)
        return arr
    }

    /**
     * Wait for the engine to finish a develop pass and return the decoded PNG dimensions. A develop always
     * starts with a `Loading` transition, so we wait for that first — otherwise `first { Ready }` would
     * immediately match the frame already on canvas. The `Loading` transition also proves the click really
     * drove [StudioEngine.develop]/reDevelop through the native bridge (it is set before the coroutine
     * launches), so identical output can still be a genuine redevelop.
     *
     * Byte equality is EXPECTED for some valid user choices: the canvas is opened already developed with
     * the CFA-default algorithm (`DEFAULT`, which resolves to PPG on every Bayer camera here), and the
     * incompatible menu picks (bilinear-4 / X-Trans on a Bayer sensor) fall back to that same PPG — so
     * redeveloping with them deterministically reproduces the same PNG. Only callers that changed a
     * parameter guaranteed to move pixels (e.g. exposure EV) set [requireDifferentFrom].
     */
    private suspend fun waitForDevelopedFrame(
        requireDifferentFrom: ByteArray? = null,
    ): DevelopedFrame {
        StudioEngine.renderResult.filter { it is StudioRenderResult.Loading }.first()
        val ready = StudioEngine.renderResult
            .filterNot { it is StudioRenderResult.Loading }
            .filterIsInstance<StudioRenderResult.Ready>()
            .first()
        val bytes = toBytes(ready.model)
        if (requireDifferentFrom != null) {
            assertTrue(
                "developed frame must differ after the parameter change (redevelop actually recomputed)",
                !bytes.contentEquals(requireDifferentFrom),
            )
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        assertTrue("developed output is not a decodable PNG", opts.outWidth > 0)
        assertTrue(
            "developed frame is ${opts.outWidth}x${opts.outHeight} — that is an embedded preview, not a " +
                "demosaiced full frame",
            opts.outWidth >= FULL_FRAME_MIN_WIDTH,
        )
        return DevelopedFrame(opts.outWidth, opts.outHeight, bytes)
    }

    private data class DevelopedFrame(val outWidth: Int, val outHeight: Int, val bytes: ByteArray)

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
        // The pushed RAW files live on the SD card: scanFile indexes them and returns the content://
        // uri the scanner produced. Use that directly — it is the authoritative result and avoids the
        // race where a DISPLAY_NAME re-query right after the scan returns nothing (the 5 raw failures:
        // scan returned media/19, the re-query a moment later found no row).
        val scanned = scanFile(path, sample.mime)
        if (scanned != null) {
            step("mediaStore", "content uri=$scanned")
            return scanned
        }
        // The PNG control is published straight into MediaStore (not on the SD card), so scanning the
        // sdcard path yields nothing. Fall back to a DISPLAY_NAME lookup, which is stable here because
        // the insert committed long before this call.
        step("mediaStore", "sdcard scan empty for ${sample.file}, querying MediaStore by name")
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

    /**
     * Index [path] into MediaStore via MediaScannerConnection and return the content:// uri the
     * scanner hands back. The callback uri is authoritative — re-querying MediaStore by DISPLAY_NAME
     * immediately afterwards races the scanner's own transaction commit and can return null. Returns
     * null when the scanner has nothing to index (e.g. the PNG control fixture, not on the SD card).
     */
    private fun scanFile(path: String, mime: String): Uri? {
        val latch = CountDownLatch(1)
        var result: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime)) { scanned, uri ->
            step("scan", "scanned='$scanned' -> $uri")
            result = uri
            latch.countDown()
        }
        latch.await(60, TimeUnit.SECONDS)
        return result
    }

    /** Remove every live node so each test starts from an empty library grid. */
    private suspend fun clearLibrary() {
        LibraryCore.rootChildren().first().forEach { LibraryCore.removeNode(it) }
        LibraryCore.collections().first().forEach { LibraryCore.removeNode(it) }
    }

    /**
     * A tiny PNG published through MediaStore, as the Coil-branch control.
     *
     * The name is kept SHORT on purpose: the library grid renders names through
     * MiddleEllipsisText, which middle-truncates on the emulator's tiny 320px-wide screen, and
     * `onAllNodesWithText` matches the DISPLAYED (possibly truncated) string — so a long name like
     * `png_control.png` never satisfies the substring wait. `e2e.png` fits and matches, the same
     * convention the gesture tests use (`gNNN.png`).
     */
    private fun pngControl(): Sample {
        val name = "e2e.png"
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

        /** A 36..50 MP software demosaic on the emulator is slow; 8 min per sample (safety margin). */
        const val DECODE_TIMEOUT_MS = 480_000L

        /** BitmapFactory inSampleSize for the chroma check — keeps the decoded probe a few hundred KB. */
        const val COLOR_CHECK_SAMPLE_SHIFT = 32

        /** Min per-pixel R/G/B spread (8-bit levels) counted as "colored" on the linear (dark) PNG. */
        const val COLOR_SPREAD_MIN_LEVELS = 6

        /** At least 1/50 (2 %) of sampled pixels must be colored for the frame to count as demosaiced. */
        const val COLOR_COLORED_MIN_FRACTION = 50

        /**
         * The grade-LUT fixture, fetched from fot-lab/V-Log-Alchemy by `smoke_emulator.yaml`:
         * a 33-point creative cube that expects V-Log/V-Gamut input. [LUT_FIXTURE_REF] is the
         * submodule commit the SHA-256 was taken from (external/V-Log-Alchemy tracks main).
         *
         * The hash pins the repository BLOB (LF line endings). Do NOT re-derive it from a
         * Windows working-tree copy of the submodule: git may check the cube out with CRLF
         * (that wrong hash silently shipped once and the CI checksum guard caught it). Rebuild
         * it with `git cat-file blob HEAD:<path> | sha256sum` or from the raw.githubusercontent
         * download.
         */
        const val LUT_FIXTURE_NAME = "FLog2C_to_CLASSIC-Neg_VLog.cube"
        const val LUT_FIXTURE_SHA256 = "3e2da957fc86cbe06d382ec68ae653df7a70d2de577d521c829b48d7c5a37d02"
        const val LUT_FIXTURE_REF = "e51ba9a23458ff5c316f630b860eb0201139d126"
    }
}
