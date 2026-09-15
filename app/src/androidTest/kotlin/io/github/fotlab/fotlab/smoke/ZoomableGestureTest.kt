package io.github.fotlab.fotlab.smoke

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.library.FsNodeObject
import io.github.fotlab.fotlab.feature.library.LibraryViewerDialog
import io.github.fotlab.fotlab.ui.ZoomableAsyncImage
import io.github.fotlab.fotlab.ui.ZoomState
import io.github.fotlab.fotlab.ui.rememberZoomState
import java.io.File
import java.io.FileOutputStream
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
 *
 * Gestures are built from the injection primitives (`down`/`moveBy`/`up`) with explicit
 * pointer ids, which also lets us interleave finger add/lift exactly mid-stream.
 *
 * Everything logs under `GESTURE-E2E` with timestamps; smoke_emulator.yaml attaches logcat.txt
 * on failure, so an emulator failure here names the crashing line directly.
 */
@RunWith(AndroidJUnit4::class)
class ZoomableGestureTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val tag = "GESTURE-E2E"
    private var t0 = 0L

    private fun step(phase: String, detail: String) {
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i(tag, "[T+${ms}ms][$phase] $detail")
    }

    private lateinit var pngFile: File

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val CD = "gesture-test-image"
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
    }

    /** A real decodable PNG in the app cache, referenced by a file Uri (same-process reads only). */
    @Before
    fun setUp() {
        t0 = System.nanoTime()
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        pngFile = File(context.cacheDir, "gesture_test_${System.currentTimeMillis()}.png")
        FileOutputStream(pngFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        step("fixture", "PNG at ${pngFile.toURI()} (${pngFile.length()} bytes)")
    }

    @After
    fun tearDown() {
        runCatching { pngFile.delete() }.onFailure { Log.w(tag, "cleanup failed", it) }
    }

    /** Media list shaped like the Library grid's: distinct display names are the image CD. */
    private fun nodes(): List<FsNodeObject> = (0 until 3).map { i ->
        FsNodeObject(
            fsNodeId = i + 1L,
            nameDisplay = "gesture_$i.png",
            typeMime = "image/png",
            uriStorage = pngFile.toURI().toString(),
            timeCreated = System.currentTimeMillis(),
        )
    }

    /** Composition identical to what the viewer's image page creates. */
    private fun setContentWithZoomable(onState: (ZoomState) -> Unit) {
        composeRule.setContent {
            val s = rememberZoomState()
            onState(s)
            Box(Modifier.fillMaxSize()) {
                ZoomableAsyncImage(
                    model = pngFile.toURI().toString(),
                    contentDescription = CD,
                    state = s,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.waitForIdle()
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
        composeRule.setContent {
            LibraryViewerDialog(
                items = nodes(),
                startIndex = 0,
                onDismiss = {},
                onOpenInStudio = {},
            )
        }
        composeRule.waitForIdle()
        step("dialog", "LibraryViewerDialog composed over 3 nodes without throwing")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()

        // Forward: inject on the page we are on, then the next page becomes centered.
        composeRule.onNodeWithContentDescription("gesture_0.png")
            .performTouchInput { dragX(dx = -900f) }
        composeRule.waitForIdle()
        step("dialog", "paged to item 1")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()

        composeRule.onNodeWithContentDescription("gesture_1.png")
            .performTouchInput { dragX(dx = -900f) }
        composeRule.waitForIdle()
        step("dialog", "paged to item 2")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()

        // Back again.
        composeRule.onNodeWithContentDescription("gesture_2.png")
            .performTouchInput { dragX(dx = 900f) }
        composeRule.waitForIdle()
        step("dialog", "paged back to item 1")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()

        composeRule.onNodeWithContentDescription("gesture_1.png")
            .performTouchInput { dragX(dx = 900f) }
        composeRule.waitForIdle()
        step("dialog", "paged back to item 0")
        composeRule.onNodeWithContentDescription(closeDesc).assertExists()
    }
}
