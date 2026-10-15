package io.github.fotlab.fotlab.feature.studio

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Crop & Rotate — pure-functional, Kotlin-side transform on the rendered PNG.
 *
 * Mirrors the design in `rules/DESIGN/detail/FOTLAB-ROTATE-000001.md`:
 * - The transform is a pure function: [applyCropRotate] is `Bitmap -> CropRotateParams -> Bitmap`,
 *   with no hidden state and no I/O. The Rust-provided PNG is the *source* and is never mutated, so
 *   any re-adjustment re-runs this function on the retained source and never re-renders in Rust.
 * - Every geometric field is **normalized to the source PNG** as a float ratio in `[0, 1]`, so the
 *   same params produce the identical proportional result on any incoming PNG regardless of
 *   resolution or aspect ratio.
 *
 * Pipeline order (kept source-independent): **crop-on-source → flip → rotate**. The crop rect is
 * taken from the un-rotated source (its ratios are fixed by [cropNormalized]); the cropped region
 * is then flipped and rotated. The result is deterministic and orientation-agnostic.
 */

/** Minimal crop-rect dimension (normalized) so a corner drag can never collapse the rect. */
private const val CROP_MIN = 0.05f

/** Corner hit radius (normalized) for grabbing a resize handle vs. moving the body. */
private const val CORNER_HIT = 0.07f

/**
 * Fully-relative crop & rotate parameters. All coordinates are normalized to the source PNG
 * (`[0, 1]`); [cropNormalized] is `(left, top, right, bottom)` with `right = left + width`,
 * `bottom = top + height`.
 */
data class CropRotateParams(
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val cropNormalized: RectF = RectF(0f, 0f, 1f, 1f),
) {
    /** True when nothing is applied — the source is shown unchanged. */
    val isIdentity: Boolean
        get() = rotationDegrees % 360f == 0f &&
            !flipHorizontal &&
            !flipVertical &&
            cropNormalized.left <= 0f &&
            cropNormalized.top <= 0f &&
            cropNormalized.right >= 1f &&
            cropNormalized.bottom >= 1f

    companion object {
        val Identity = CropRotateParams()
    }
}

/**
 * Pure crop & rotate. Returns a new [Bitmap]; the [src] is never modified.
 *
 * - Crop: the normalized rect is clamped to `[0, 1]`, converted to source pixels, and extracted.
 *   A full-frame crop short-circuits (the source is reused, not copied).
 * - Flip + rotate: a single [Matrix] (translate-to-centre → scale(flip) → rotate → translate-back)
 *   is applied with bilinear filtering (`filter = true`). A no-op transform (0°, no flip) returns
 *   the cropped bitmap unchanged.
 */
fun applyCropRotate(src: Bitmap, params: CropRotateParams): Bitmap {
    val n = params.cropNormalized
    val sw = src.width
    val sh = src.height

    val left = (n.left.coerceIn(0f, 1f) * sw).roundToInt()
    val top = (n.top.coerceIn(0f, 1f) * sh).roundToInt()
    val right = ((n.left + n.width()).coerceIn(0f, 1f) * sw).roundToInt()
    val bottom = ((n.top + n.height()).coerceIn(0f, 1f) * sh).roundToInt()
    val cw = (right - left).coerceAtLeast(1)
    val ch = (bottom - top).coerceAtLeast(1)

    // Full-frame crop: reuse the source instead of allocating a copy.
    val cropped = if (left == 0 && top == 0 && cw == sw && ch == sh) {
        src
    } else {
        Bitmap.createBitmap(src, left, top, cw, ch)
    }

    val flipH = if (params.flipHorizontal) -1f else 1f
    val flipV = if (params.flipVertical) -1f else 1f
    val noTransform = params.rotationDegrees % 360f == 0f && flipH == 1f && flipV == 1f
    if (noTransform) return cropped

    val matrix = Matrix().apply {
        val cx = cw / 2f
        val cy = ch / 2f
        postTranslate(-cx, -cy)
        postScale(flipH, flipV)
        postRotate(params.rotationDegrees)
        postTranslate(cx, cy)
    }
    val result = Bitmap.createBitmap(cropped, 0, 0, cw, ch, matrix, true)
    if (cropped != src) cropped.recycle()
    return result
}

/**
 * Crop range editor drawn as a new layer directly over the canvas (Image Toolbox-style): a dim
 * scrim outside the selected rect, a white border, and drag gestures that move the rect (drag
 * inside) or resize it from any corner. Coordinates are kept in [cropNormalized] (`[0, 1]`).
 */
@Composable
internal fun CropRotateCanvasLayer(
    cropNormalized: RectF,
    onCropNormalizedChange: (RectF) -> Unit,
    modifier: Modifier = Modifier,
) {
    var layerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { layerSize = it }
            .pointerInput(Unit) {
                var mode: DragMode? = null
                detectDragGestures(
                    onDragStart = { start ->
                        val r = cropNormalized
                        val nx = (start.x / layerSize.width).coerceIn(0f, 1f)
                        val ny = (start.y / layerSize.height).coerceIn(0f, 1f)
                        mode = when {
                            near(nx, ny, r.left, r.top) -> DragMode.TL
                            near(nx, ny, r.right, r.top) -> DragMode.TR
                            near(nx, ny, r.left, r.bottom) -> DragMode.BL
                            near(nx, ny, r.right, r.bottom) -> DragMode.BR
                            nx in r.left..r.right && ny in r.top..r.bottom -> DragMode.Move
                            else -> null
                        }
                    },
                    onDrag = { change, _ ->
                        val m = mode ?: return@detectDragGestures
                        val r = cropNormalized
                        val nx = (change.position.x / layerSize.width).coerceIn(0f, 1f)
                        val ny = (change.position.y / layerSize.height).coerceIn(0f, 1f)
                        val nr = when (m) {
                            DragMode.Move -> {
                                val w = r.width()
                                val h = r.height()
                                val nl = (nx - w / 2).coerceIn(0f, 1f - w)
                                val nt = (ny - h / 2).coerceIn(0f, 1f - h)
                                RectF(nl, nt, nl + w, nt + h)
                            }
                            DragMode.TL -> RectF(
                                nx.coerceAtMost(r.right - CROP_MIN),
                                ny.coerceAtMost(r.bottom - CROP_MIN),
                                r.right,
                                r.bottom,
                            )
                            DragMode.TR -> RectF(
                                r.left,
                                ny.coerceAtMost(r.bottom - CROP_MIN),
                                nx.coerceAtLeast(r.left + CROP_MIN),
                                r.bottom,
                            )
                            DragMode.BL -> RectF(
                                nx.coerceAtMost(r.right - CROP_MIN),
                                r.top,
                                r.right,
                                ny.coerceAtLeast(r.top + CROP_MIN),
                            )
                            DragMode.BR -> RectF(
                                r.left,
                                r.top,
                                nx.coerceAtLeast(r.left + CROP_MIN),
                                ny.coerceAtLeast(r.top + CROP_MIN),
                            )
                        }
                        onCropNormalizedChange(nr)
                    },
                    onDragEnd = { mode = null },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cl = (cropNormalized.left * w).coerceIn(0f, w)
            val ct = (cropNormalized.top * h).coerceIn(0f, h)
            val cr = (cropNormalized.right * w).coerceIn(0f, w)
            val cb = (cropNormalized.bottom * h).coerceIn(0f, h)
            val dim = Color.Black.copy(alpha = 0.5f)
            // Scrim outside the crop rect (top / bottom / left / right).
            drawRect(dim, topLeft = Offset(0f, 0f), size = Size(w, ct))
            drawRect(dim, topLeft = Offset(0f, cb), size = Size(w, h - cb))
            drawRect(dim, topLeft = Offset(0f, ct), size = Size(cl, cb - ct))
            drawRect(dim, topLeft = Offset(cr, ct), size = Size(w - cr, cb - ct))
            // Selection border.
            drawRect(
                color = Color.White,
                topLeft = Offset(cl, ct),
                size = Size(cr - cl, cb - ct),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

/** Which part of the crop rect a drag is acting on. */
private enum class DragMode { Move, TL, TR, BL, BR }

/** True when `(px, py)` is within [CORNER_HIT] of the crop corner `(cx, cy)` (normalized). */
private fun near(px: Float, py: Float, cx: Float, cy: Float): Boolean =
    kotlin.math.abs(px - cx) < CORNER_HIT && kotlin.math.abs(py - cy) < CORNER_HIT

/**
 * Crop & rotate control surface shown in the bottom-sheet slot (height matches the operation bar).
 * Holds the rotation slider plus flip toggles, a reset, and a done (apply) action; the crop range
 * itself is edited on the canvas via [CropRotateCanvasLayer].
 */
@Composable
internal fun CropRotateControlPanel(
    params: CropRotateParams,
    onParamsChange: (CropRotateParams) -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onReset) {
            Text(text = "⟲", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = "${params.rotationDegrees.roundToInt()}°",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = params.rotationDegrees,
            onValueChange = { onParamsChange(params.copy(rotationDegrees = it)) },
            valueRange = -45f..45f,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onParamsChange(params.copy(flipHorizontal = !params.flipHorizontal)) }) {
            Text(text = "↔")
        }
        TextButton(onClick = { onParamsChange(params.copy(flipVertical = !params.flipVertical)) }) {
            Text(text = "↕")
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Done",
            )
        }
    }
}
