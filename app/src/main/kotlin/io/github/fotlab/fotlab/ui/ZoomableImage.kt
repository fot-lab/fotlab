package io.github.fotlab.fotlab.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import coil3.compose.rememberAsyncImagePainter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared zoom / pan state for one image, held by whichever screen shows it: [scale] (`1f` = fitted)
 * and [offset], both observable so a plain `graphicsLayer` can render them.
 *
 * The rendered transform is `screen = centre + (p - centre) * scale + offset`, which deliberately
 * keeps [offset] a translation **in the same coordinate space the gesture is measured in** — the
 * untransformed frame around the image. Because `graphicsLayer` applies its translation *after* the
 * scale, one pixel of [offset] moves the painted image one pixel at **any** [scale]: a drag of N
 * pixels moves the image N pixels whether it is fitted or zoomed to [maxScale].
 *
 * That 1:1 property holds only while the gesture is measured outside the layer this state renders
 * with — see [ZoomableAsyncImage]. [offset] is re-clamped on every change to the room the scaled
 * image actually has, so the image can neither be thrown off the viewport nor drift; at `1f` the
 * clamp collapses to zero, which is what makes the view return to its fit without a separate
 * "snap back below 1" branch.
 *
 * The state is a value holder only — the container size, the decoded image size and the pan limits
 * are fed in by [ZoomableAsyncImage] / the gesture modifier below.
 */
@Stable
class ZoomState internal constructor(
    private val minScale: Float,
    private val maxScale: Float,
) {
    /** Current zoom factor; `1f` fits the image into its container. */
    var scale by mutableFloatStateOf(minScale)
        private set

    /** Current screen-space translation of the zoomed image. */
    var offset by mutableStateOf(Offset.Zero)
        private set

    /**
     * A multi-finger transform gesture is running. A parent that scrolls horizontally (the Library
     * viewer's pager) must stand down while this is `true`, otherwise a pinch would page as well.
     */
    var isTransforming by mutableStateOf(false)
        private set

    private var containerSize by mutableStateOf(Size.Zero)
    private var imageSize by mutableStateOf(Size.Zero)

    private val zoomed = derivedStateOf { scale > SCALE_EPSILON }

    /**
     * `true` once the image is zoomed past its fit. Backed by a derived state, so observers are
     * invalidated on the fit boundary only — not on every frame of a pinch.
     */
    val isZoomed: Boolean by zoomed

    /** Back to the fitted default — the Studio overflow menu's "Reset view". */
    fun reset() {
        scale = minScale
        offset = Offset.Zero
    }

    /** The box the image is rendered in; the pan limits are derived from it. */
    internal fun onContainerSize(size: Size) {
        if (containerSize != size) {
            containerSize = size
            offset = clamp(offset, scale)
        }
    }

    /** Pixel size reported by the decoder; the pan limits follow its letter-boxed fit. */
    internal fun onImageSize(size: Size) {
        if (imageSize != size) {
            imageSize = size
            offset = clamp(offset, scale)
        }
    }

    /**
     * Applies one frame of a transform gesture: [zoomChange] is the relative scale change and
     * [panChange] the focal-point movement, both as measured by the official Compose gesture
     * helpers (`calculateZoom` / `calculatePan`). [focalPoint] is the position the zoom is anchored
     * to, so the pixel under the fingers stays under them.
     *
     * [panChange] must be expressed in the same space as [offset] — the untransformed frame around
     * the image, i.e. **screen pixels**. With [zoomChange] == 1f the anchor terms cancel and
     * [offset] grows by exactly [panChange], which is the 1:1 drag contract. Measured inside the
     * scaling layer instead, `calculatePan` reports `fingerDelta / scale` and the image crawls
     * further behind the finger the more it is zoomed in.
     */
    internal fun transform(focalPoint: Offset, zoomChange: Float, panChange: Offset) {
        val target = (scale * zoomChange).coerceIn(minScale, maxScale)
        val ratio = target / scale
        val anchor = Offset(containerSize.width / 2f, containerSize.height / 2f)
        val focal = if (focalPoint != Offset.Unspecified) focalPoint else anchor
        // Keep the focal point stationary, then apply the pan: solving
        // screen = anchor + (p - anchor) * scale + offset for the point under the finger.
        val moved = (focal - anchor) * (1f - ratio) + offset * ratio + panChange
        offset = clamp(moved, target)
        scale = target
    }

    /** Marks a multi-finger gesture as running, so a parent scroller stands down immediately. */
    internal fun beginTransform() {
        if (!isTransforming) isTransforming = true
    }

    /** Ends the gesture so a parent scroller may take over again. */
    internal fun endTransform() {
        if (isTransforming) isTransforming = false
    }

    /** The image as it is actually laid out: [ContentScale.Fit] inside the container. */
    private fun fittedSize(): Size {
        if (imageSize.isUnmeasured || containerSize.isUnmeasured) return containerSize
        val factor = min(containerSize.width / imageSize.width, containerSize.height / imageSize.height)
        return Size(imageSize.width * factor, imageSize.height * factor)
    }

    /**
     * Limits [candidate] to the slack the scaled image leaves around the container, per axis. A
     * letter-boxed axis (image narrower than the box) has no slack at all, so it cannot be dragged,
     * and at [minScale] both axes collapse to zero.
     */
    private fun clamp(candidate: Offset, forScale: Float): Offset {
        if (containerSize.isUnmeasured) return candidate
        val content = fittedSize()
        val slackX = max(0f, (content.width * forScale - containerSize.width) / 2f)
        val slackY = max(0f, (content.height * forScale - containerSize.height) / 2f)
        return Offset(
            candidate.x.coerceIn(-slackX, slackX),
            candidate.y.coerceIn(-slackY, slackY),
        )
    }
}

/** Remembers a [ZoomState] for the `1f` (fitted) .. [maxScale] range. */
@Composable
fun rememberZoomState(minScale: Float = 1f, maxScale: Float = 6f): ZoomState =
    remember(minScale, maxScale) { ZoomState(minScale = minScale, maxScale = maxScale) }

/**
 * An image that can be zoomed and panned: Coil-decoded, fitted to [modifier], clipped to it, and
 * driven by [state].
 *
 * Both the Library viewer and the Studio render through this single implementation, so the two share
 * one feel and one place to fix. Set [keepParentDraggable] when the image lives in a horizontally
 * scrolling parent (the viewer's pager): a one-finger drag at the fitted size is then left to that
 * parent — so it still switches items — and only a pinch or a drag while already zoomed moves the
 * image.
 *
 * **The gesture is measured on the frame, never inside the transform.** This composable is a
 * two-level pair on purpose:
 *
 * - the outer [Box] is untransformed. It carries [Modifier.zoomable] (the pointer input) and
 *   reports the container size, so every pointer position it reads is a plain screen pixel;
 * - the inner [Image] is the only node that carries the `graphicsLayer` scale / translation.
 *
 * Reading the pointers on the very node the layer transforms — `graphicsLayer` followed by
 * `pointerInput` in one chain — hands the detector positions already divided by [ZoomState.scale].
 * `calculateZoom` is a *ratio* and survives that, which is why pinching still felt right, but
 * `calculatePan` came back as `fingerDelta / scale`: at 4x a 100 px drag moved the image 25 px, and
 * the touch slop had to be crossed in shrunken units too, so panning looked like it barely started.
 * Keeping the two levels apart is what makes one pixel of finger travel move the image one pixel at
 * any zoom.
 */
@Composable
fun ZoomableAsyncImage(
    model: Any?,
    contentDescription: String?,
    state: ZoomState,
    modifier: Modifier = Modifier,
    keepParentDraggable: Boolean = false,
) {
    val painter = rememberAsyncImagePainter(model = model)
    // The decoded size is what the pan limits are measured against; it is known once painted.
    //
    // Coil reports `Size.Unspecified` until the decode finishes, and reading `width` / `height`
    // off an unspecified Size **throws** — they are not NaN fields but guarded accessors
    // (`IllegalStateException: Size is unspecified`). Touching them here crashed the whole
    // process the moment a zoomable composed for an image that was not decoded yet, which is
    // exactly what opening the Library viewer on a cold Coil cache did. Hence the explicit
    // guard: the size is forwarded only once the painter actually has one.
    val decoded = painter.intrinsicSize
    SideEffect {
        if (decoded != Size.Unspecified && decoded.width > 0f && decoded.height > 0f) {
            state.onImageSize(decoded)
        }
    }
    Box(
        modifier = modifier
            .clipToBounds()
            .zoomable(state = state, keepParentDraggable = keepParentDraggable),
    ) {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                },
        )
    }
}

/**
 * Ties [state] to the frame: reports the container size and detects the gesture.
 *
 * Must be applied to the **untransformed** node — see [ZoomableAsyncImage]. The measurement itself
 * is the official Compose gesture toolbox: `awaitEachGesture`, `calculateZoom`, `calculatePan`,
 * `calculateCentroid` and `viewConfiguration.touchSlop`. Those helpers ignore pointers that were not
 * down on the previous frame, which is what keeps the transform stable when a finger lands or lifts
 * mid-gesture.
 *
 * The reason this is a loop instead of the official `detectTransformGestures` is ownership: the
 * gesture has to stay available to a scrolling parent (the viewer's pager) until this image actually
 * takes it, and `detectTransformGestures` consumes unconditionally once it is past the slop.
 */
private fun Modifier.zoomable(
    state: ZoomState,
    keepParentDraggable: Boolean,
): Modifier = this
    .onSizeChanged { state.onContainerSize(Size(it.width.toFloat(), it.height.toFloat())) }
    .pointerInput(state, keepParentDraggable) {
        detectZoomAndPan(state, keepParentDraggable)
    }

/**
 * One gesture, from first down to last up.
 *
 * Two rules keep the image honest:
 *
 * - **Nothing moves until the touch slop is crossed** (`viewConfiguration.touchSlop`, in the same
 *   screen pixels the pan is measured in), so a finger resting on the glass, or a 1 px tremor, never
 *   nudges the picture.
 * - **A finger landing or lifting is a resync, not a movement.** The official centroid helpers
 *   average over the pointers that are down, so the centroid jumps the moment that set changes while
 *   the image itself has not moved at all. Feeding that jump through as a pan is what makes a zoomed
 *   image appear to lurch sideways mid-pinch, so that one frame is skipped.
 */
private suspend fun PointerInputScope.detectZoomAndPan(
    state: ZoomState,
    keepParentDraggable: Boolean,
) {
    val touchSlop = viewConfiguration.touchSlop
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        // An already zoomed image owns every gesture; a fitted one only owns multi-finger ones when
        // the parent is allowed to keep the single-finger drags (see keepParentDraggable).
        var owned = state.isZoomed || !keepParentDraggable
        var pastSlop = false
        var fingers = 0
        var zoom = 1f
        var pan = Offset.Zero
        try {
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) break

                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    owned = true
                    // A second finger means a pinch: tell a parent scroller to stand down before
                    // the gesture even passes the slop threshold.
                    state.beginTransform()
                }
                if (!owned || pressed != fingers) {
                    fingers = pressed
                    continue
                }

                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                // A centroid over no comparable pointers is `Offset.Unspecified` (NaN); letting it
                // through would poison the offset for the rest of the gesture.
                if (!zoomChange.isFinite() || !panChange.isUsable()) continue

                if (!pastSlop) {
                    // Same touch-slop gate as the official detector: accumulate until the gesture
                    // is unambiguous.
                    zoom *= zoomChange
                    pan += panChange
                    val zoomMotion =
                        abs(1f - zoom) * event.calculateCentroidSize(useCurrent = false)
                    if (zoomMotion > touchSlop || pan.getDistance() > touchSlop) {
                        pastSlop = true
                    }
                }
                if (pastSlop) {
                    state.transform(
                        focalPoint = event.calculateCentroid(useCurrent = true),
                        zoomChange = zoomChange,
                        panChange = panChange,
                    )
                    event.changes.forEach { change ->
                        // Consume only pointers that actually moved, as the official detector does.
                        if (change.position != change.previousPosition) change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
        } finally {
            state.endTransform()
        }
    }
}

/** Zoom is treated as "fitted" below this, so float noise never keeps a parent scroller disabled. */
private const val SCALE_EPSILON = 1.001f

/** A size that has not been measured yet — zero, or the unspecified size, whose accessors throw. */
private val Size.isUnmeasured: Boolean
    get() = this == Size.Unspecified || !(width > 0f && height > 0f)

/** A pan the transform can use: `Offset.Unspecified` and any NaN component would corrupt [ZoomState.offset]. */
private fun Offset.isUsable(): Boolean = x.isFinite() && y.isFinite()
