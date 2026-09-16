package io.github.fotlab.fotlab.smoke

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fotlab.fotlab.MainActivity
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.library.FsNodeObject
import io.github.fotlab.fotlab.feature.library.LibraryCore
import io.github.fotlab.fotlab.feature.library.LibraryScreen
import io.github.fotlab.fotlab.feature.library.LibraryViewerDialog
import io.github.fotlab.fotlab.ui.theme.AppTheme
import io.github.fotlab.fotlab.ui.ZoomableAsyncImage
import io.github.fotlab.fotlab.ui.ZoomState
import io.github.fotlab.fotlab.ui.rememberZoomState
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the shared zoom/pan gesture component ([ZoomableAsyncImage] +
 * [ZoomState]) — the code `93e1796` ("drive zoom/pan by the official Compose gesture math")
 * rewrote and the Library viewer now renders every image through.
 *
 * The reported real-device crash happens when tapping a Library thumbnail to open the viewer,
 * BEFORE any finger touches the image and before Studio is ever opened. These tests therefore
 * cover both phases on the debug emulator, where a Java exception carries a full stack trace:
 *
 *  1. **Open at fitted size** — the exact composition the dialog creates (no touch at all).
 *  2. **Finger added/lifted mid-gesture** — the precise scenario the rewrite targets, where a
 *     hand-rolled centroid jumps; here `calculateCentroid` can return `Offset.Unspecified`,
 *     which `ZoomState.transform` must fall back from.
 *  3. **Clamping** — a wide two-finger spread must clamp inside `[minScale, maxScale]`.
 *  4. **The REAL dialog** — [LibraryViewerDialog] itself (Dialog + HorizontalPager +
 *     ZoomableAsyncImage with `keepParentDraggable`), paged with drags, the literal user
 *     journey that crashes on the device.
 *  5. **Tap thumbnail → viewer opens** — the crash window BEFORE the viewer comes up:
 *     the REAL [LibraryScreen] grid, a real click on a real thumbnail cell, driving the
 *     screen's own `onNodeClick` → `viewerItems` state → dialog composition path.
 *
 * Gestures are built from the injection primitives (`down`/`moveBy`/`up`) with explicit
 * pointer ids, which also lets us interleave finger add/lift exactly mid-stream.
 *
 * Everything logs under `GESTURE-E2E` with timestamps; smoke_emulator.yaml attaches logcat.txt
 * on failure, so an emulator failure here names the crashing line directly.
 */
@RunWith(AndroidJUnit4::class)
class ZoomableGestureTest {

    /**
     * Hosted on the app's own [MainActivity] rather than ui-test-manifest's empty activity:
     * an activity declared in the test APK either resolves to the `.test` process (rejected by
     * `Instrumentation#startActivitySync`, "Intent in process ... resolved to different
     * process ...") or, when pinned into the app process via `android:process`, dies with
     * ClassNotFoundException because the app process' classloader cannot see the test APK's
     * classes. Launching the real MainActivity avoids both — its package, process and
     * classloader are all the app's own (proven green by MainActivitySmokeTest). `setContent`
     * then replaces the activity's content view with the composition under test.
     */
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val tag = "GESTURE-E2E"
    private var t0 = 0L

    private fun step(phase: String, detail: String) {
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(tag, "[T+${ms}ms][$phase] $detail")
    }

    /** The published fixture: a real decodable PNG reachable under a uri the app can read. */
    private lateinit var sourceUri: Uri

    /** Its display name — short, see [setUp] for why that matters. */
    private lateinit var pngName: String

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val CD = "gesture-test-image"
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
    }

    /**
     * A real decodable PNG published the way a real photo lives on a device: MediaStore
     * (`content://`, API 29+) or a file below that.
     *
     * Two properties of the fixture are load-bearing, and both were got wrong before:
     *
     * - **It must be a `content://` uri.** `LibraryCore.importUris` types the node with
     *   `contentResolver.getType(uri)`, and a `file://` uri answers `null` → the node is stored
     *   as an unknown type → `LibraryScreen` no longer sees it as media, so a tap *selects* it
     *   instead of opening the viewer. That is why the viewer never appeared here.
     * - **Its name must be short.** The grid renders names through `MiddleEllipsisText`, which
     *   middle-truncates on overflow, and `onNodeWithText` matches the DISPLAYED string.
     */
    @Before
    fun setUp() {
        t0 = System.nanoTime()
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        bitmap.recycle()

        pngName = "g${System.currentTimeMillis() % 1000}.png"
        sourceUri = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, pngName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FotLabE2E")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
            ) ?: throw AssertionError("MediaStore insert returned null")
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            uri
        } else {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, pngName).apply { writeBytes(bytes) }.let { Uri.fromFile(it) }
        }
        step("fixture", "PNG at $sourceUri (${bytes.size} bytes)")
    }

    @After
    fun tearDown() {
        runCatching {
            if (sourceUri.scheme == "content") {
                context.contentResolver.delete(sourceUri, null, null)
            } else {
                sourceUri.path?.let { File(it).delete() }
            }
        }.onFailure { Log.w(tag, "cleanup failed", it) }
    }

    /** Media list shaped like the Library grid's: distinct display names are the image CD. */
    private fun nodes(): List<FsNodeObject> = (0 until 3).map { i ->
        FsNodeObject(
            fsNodeId = i + 1L,
            nameDisplay = "gesture_$i.png",
            typeMime = "image/png",
            uriStorage = sourceUri.toString(),
            timeCreated = System.currentTimeMillis(),
        )
    }

    /**
     * Install [content] into the real MainActivity. MainActivity already populated its content
     * view in onCreate (AppTheme + nav scaffold), so the test rule's `setContent` is illegal
     * ("has already set content"). Replace the activity's content view with a brand-new
     * ComposeView instead — setContentView detaches and disposes the old composition, and the
     * compose test framework discovers semantics owners by walking the window, so all node
     * lookup and gesture assertions keep working against our replacement.
     */
    private fun hostContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.runOnUiThread {
            val activity = composeRule.activity
            ComposeView(activity).let { cv ->
                cv.setContent(content)
                activity.setContentView(cv)
            }
        }
        composeRule.waitForIdle()
    }

    /** Composition identical to what the viewer's image page creates. */
    private fun setContentWithZoomable(onState: (ZoomState) -> Unit) {
        hostContent {
            val s = rememberZoomState()
            onState(s)
            Box(Modifier.fillMaxSize()) {
                ZoomableAsyncImage(
                    model = sourceUri,
                    contentDescription = CD,
                    state = s,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    /** Two-finger spread from `span0` to `span1` around the center, built from primitives. */
    private fun TouchInjectionScope.spread(span0: Float, span1: Float, steps: Int = 10) {
        down(0, center - Offset(span0 / 2f, 0f))
        down(1, center + Offset(span0 / 2f, 0f))
        val halfDelta = (span1 - span0) / 2f / steps
        repeat(steps) {
            moveBy(0, Offset(-halfDelta, 0f))
            moveBy(1, Offset(halfDelta, 0f))
        }
        up(0)
        up(1)
    }

    /** One-finger horizontal drag of `dx` px built from primitives. */
    private fun TouchInjectionScope.dragX(dx: Float, steps: Int = 10) {
        down(0, center)
        val delta = dx / steps
        repeat(steps) { moveBy(0, Offset(delta, 0f)) }
        up(0)
    }

    /**
     * A fast horizontal fling across 70% of the node, used to page the viewer's pager.
     *
     * A pager settles on *velocity* as much as on travel, and a step-drag from the centre also
     * leaves the screen half-way through (the injected pointer stops at x=0), which is why paging
     * never happened with [dragX]. Start and end are taken from the visible bounds so no injection
     * ever falls outside the node.
     */
    private fun TouchInjectionScope.flingX(dx: Float) {
        val w = visibleSize().width.toFloat()
        val fromX = if (dx < 0) w * 0.85f else w * 0.15f
        val toX = if (dx < 0) w * 0.15f else w * 0.85f
        swipeWithVelocity(
            start = Offset(fromX, visibleSize().height / 2f),
            end = Offset(toX, visibleSize().height / 2f),
            endVelocity = if (dx < 0) -2_000f else 2_000f,
        )
    }

    // -------------------------------------------------- 1: open, no touch

    /** Journey phase 1: the dialog's image composition, opened and left idle — no input. */
    @Test
    fun openZoomableAtFittedSizeDoesNotCrash() {
        var state: ZoomState? = null
        setContentWithZoomable { state = it }
        step("open", "ZoomableAsyncImage composed at fitted size without throwing")
        // One more idle pass so SideEffect/onSizeChanged/pointerInput have all run.
        composeRule.waitForIdle()
        step("open", "idle pass done, scale=${state?.scale} offset=${state?.offset}")
        assertTrue("scale must start fitted", state?.scale == MIN_SCALE)
        assertTrue("offset must start clamped to zero", state?.offset == Offset.Zero)
    }

    // -------------------------------------------------- 2: mid-gesture finger add/lift

    /**
     * The rewrite's target scenario, ten rounds: a second finger joins the drag, moves with it,
     * then one of the two fingers lifts while the gesture continues. This is exactly when a
     * hand-rolled centroid jumped — and when `calculateCentroid(useCurrent = true)` can return
     * `Offset.Unspecified` (all pointers momentarily up), which `ZoomState.transform` must
     * survive via its anchor fallback.
     */
    @Test
    fun fingerAddAndLiftMidGestureDoesNotCrash() {
        var state: ZoomState? = null
        setContentWithZoomable { state = it }
        val node = composeRule.onNodeWithContentDescription(CD)

        repeat(10) { round ->
            val survivor = if (round % 2 == 0) 0 else 1
            node.performTouchInput {
                down(0, center)
                moveBy(0, Offset(40f, 0f))
                down(1, center - Offset(0f, 80f))
                moveBy(0, Offset(30f, 0f))
                moveBy(1, Offset(0f, -40f))
                up(1 - survivor) // lift one finger MID-gesture
                moveBy(survivor, Offset(30f, 0f))
                up(survivor)
            }
            composeRule.waitForIdle()
            val s = state!!
            step("midGesture", "round $round: scale=${s.scale} offset=${s.offset} " +
                "zoomed=${s.isZoomed} transforming=${s.isTransforming}")
            assertTrue(
                "scale escaped bounds after round $round: ${s.scale}",
                s.scale >= MIN_SCALE - 0.001f && s.scale <= MAX_SCALE + 0.001f,
            )
            assertTrue("transform must end when pointers are up", !s.isTransforming)
        }
    }

    // -------------------------------------------------- 3: pinch clamping

    /** A violent pinch far beyond the bounds must clamp to [MAX_SCALE]; pinch-in back to fit. */
    @Test
    fun violentPinchStaysClamped() {
        var state: ZoomState? = null
        setContentWithZoomable { state = it }
        val node = composeRule.onNodeWithContentDescription(CD)

        node.performTouchInput { spread(span0 = 100f, span1 = 6000f) }
        composeRule.waitForIdle()
        val zoomed = state!!
        step("clamp", "after pinch-out: scale=${zoomed.scale} offset=${zoomed.offset}")
        assertTrue("pinch-out must clamp at $MAX_SCALE", zoomed.scale <= MAX_SCALE + 0.001f)
        assertTrue("pinch-out must actually zoom", zoomed.scale > 1.5f)

        node.performTouchInput { spread(span0 = 4000f, span1 = 10f) }
        composeRule.waitForIdle()
        val fitted = state!!
        step("clamp", "after pinch-in: scale=${fitted.scale} offset=${fitted.offset}")
        assertTrue("pinch-in must clamp at fitted", fitted.scale >= MIN_SCALE - 0.001f)
    }

    // -------------------------------------------------- 4: the real dialog journey

    /**
     * The literal user journey that crashes on the device: [LibraryViewerDialog] opens on the
     * tapped image (Dialog + HorizontalPager + ZoomableAsyncImage with `keepParentDraggable`),
     * then pages back and forth with one-finger drags. Each drag is injected on the CURRENT
     * page's image (centered and visible); paging itself must hand the gesture to the pager
     * because the image stays fitted. Composition and paging must survive.
     */
    @Test
    fun realViewerDialogOpensAndPages() {
        val closeDesc = context.getString(R.string.library_viewer_cd_close)
        hostContent {
            LibraryViewerDialog(
                items = nodes(),
                startIndex = 0,
                onDismiss = {},
                onOpenInStudio = {},
            )
        }
        step("dialog", "LibraryViewerDialog composed over 3 nodes without throwing")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()

        // Forward: fling from the page we are on, then the next page becomes centered.
        composeRule.onNodeWithContentDescription("gesture_0.png")
            .performTouchInput { flingX(dx = -900f) }
        composeRule.waitForIdle()
        step("dialog", "paged to item 1")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()

        composeRule.onNodeWithContentDescription("gesture_1.png")
            .performTouchInput { flingX(dx = -900f) }
        composeRule.waitForIdle()
        step("dialog", "paged to item 2")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()

        // Back again.
        composeRule.onNodeWithContentDescription("gesture_2.png")
            .performTouchInput { flingX(dx = 900f) }
        composeRule.waitForIdle()
        step("dialog", "paged back to item 1")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()

        composeRule.onNodeWithContentDescription("gesture_1.png")
            .performTouchInput { flingX(dx = 900f) }
        composeRule.waitForIdle()
        step("dialog", "paged back to item 0")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()
    }

    // -------------------------------------------------- 5: tap thumbnail → viewer opens

    /**
     * The reported crash window BEFORE the viewer comes up: tapping a Library thumbnail must
     * flip the screen's `viewerItems` state and compose [LibraryViewerDialog] without dying.
     * This drives the REAL [LibraryScreen] — grid cell `onNodeClick`, state transition, dialog
     * composition — with a real click, inside the app process (MainApplication prepared the
     * core, so the import below hits the same Room/DataStore path the device uses). Any
     * exception in the tap-to-open window fails here with a debug-emulator stack trace.
     */
    @Test
    fun tapThumbnailInLibraryScreenOpensViewer() {
        // Import the fixture at the library root, like a picker import would.
        val source: Uri = sourceUri
        val t = System.nanoTime()
        runBlocking { LibraryCore.importUris(parentId = null, uris = listOf(source)) }
        step("import", "importUris(${source}) done in ${(System.nanoTime() - t) / 1_000_000} ms")

        // Split the two possible failure modes before waiting: an empty row set means the
        // import/flow never reached the DB, a non-empty one means only the on-screen text is
        // missing (truncation, wrong query) — the log says which.
        val children = runBlocking { LibraryCore.rootChildren().first() }
        step("import", "rootChildren=${children.size}: ${children.joinToString { it.nameDisplay }}")

        val closeDesc = context.getString(R.string.library_viewer_cd_close)
        hostContent { AppTheme { LibraryScreen(onNavigateToStudio = {}) } }

        // Wait for the real rootChildren flow to emit the imported node into the grid.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText(pngName, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        step("grid", "thumbnail '$pngName' visible in the real LibraryScreen grid")

        // The actual user gesture: tap the thumbnail cell (the Card's clickable wraps the cell).
        composeRule.onAllNodesWithText(pngName, substring = true)[0].performClick()
        step("tap", "clicked the thumbnail cell")

        // The viewer dialog must appear; on the way there, every composition must survive.
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithContentDescription(closeDesc).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()
        composeRule.onNodeWithContentDescription(pngName).assertExists()
        step("dialog", "LibraryViewerDialog opened from the tap with the tapped image loaded")

        // One gesture on the live dialog for good measure.
        composeRule.onNodeWithContentDescription(pngName)
            .performTouchInput { dragX(dx = -200f) }
        composeRule.waitForIdle()
        step("dialog", "post-open drag injected, dialog still up")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()
    }
}
