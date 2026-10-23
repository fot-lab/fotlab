package io.github.fotlab.fotlab.feature.studio

import io.github.fotlab.fotlab.R

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Rotate & flip — pure-functional, Kotlin-side transform on the rendered PNG.
 *
 * Mirrors the design in `rules/DESIGN/detail/FOTLAB-ROTATE-000001.md`:
 * - The transform is a pure function: [applyCropRotate] is `Bitmap -> CropRotateParams -> Bitmap`,
 *   with no hidden state and no I/O. The Rust-provided PNG is the *source* and is never mutated, so
 *   any re-adjustment re-runs this function on the retained source and never re-renders in Rust.
 * - Rotation is expressed in degrees (90° steps from the bar); flipping is a ±1 scale on each axis.
 */

/**
 * Rotate & flip parameters. Rotation is in degrees (positive = clockwise, matching Compose);
 * flipping is a per-axis boolean mirror.
 */
data class CropRotateParams(
    val rotationDegrees: Float = 0f,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
) {
    /** True when nothing is applied — the source is shown unchanged. */
    val isIdentity: Boolean
        get() = rotationDegrees % 360f == 0f &&
            !flipHorizontal &&
            !flipVertical

    /** 90° counter-clockwise step (positive Compose rotation is clockwise). */
    fun rotateLeft(): CropRotateParams = copy(rotationDegrees = (rotationDegrees - 90f).mod(360f))

    /** 90° clockwise step. */
    fun rotateRight(): CropRotateParams = copy(rotationDegrees = (rotationDegrees + 90f).mod(360f))

    companion object {
        val Identity = CropRotateParams()
    }
}

/**
 * Pure rotate & flip. Returns a new [Bitmap]; the [src] is never modified.
 *
 * A single [Matrix] (translate-to-centre → scale(flip) → rotate → translate-back) is applied with
 * bilinear filtering (`filter = true`). A no-op transform (0°, no flip) returns [src] unchanged
 * (not copied), so a session with no adjustment is allocation-free.
 */
fun applyCropRotate(src: Bitmap, params: CropRotateParams): Bitmap {
    val flipH = if (params.flipHorizontal) -1f else 1f
    val flipV = if (params.flipVertical) -1f else 1f
    val noTransform = params.rotationDegrees % 360f == 0f && flipH == 1f && flipV == 1f
    if (noTransform) return src

    val sw = src.width
    val sh = src.height
    val matrix = Matrix().apply {
        val cx = sw / 2f
        val cy = sh / 2f
        postTranslate(-cx, -cy)
        postScale(flipH, flipV)
        postRotate(params.rotationDegrees)
        postTranslate(cx, cy)
    }
    return Bitmap.createBitmap(src, 0, 0, sw, sh, matrix, true)
}

/**
 * Rotate & flip operation bar — a horizontal strip of icon buttons (its height matches the
 * operation bar it covers). The bar edits a *draft* of [CropRotateParams]; the host owns the
 * draft / committed split and wires these callbacks:
 *
 * - `onCloseDiscard`: X / close — discard the draft and revert to the last committed value, then
 *   close the bar.
 * - `onRotateLeft` / `onRotateRight`: 90° counter-clockwise / clockwise steps.
 * - `onCancel`: cancel **all** rotation and mirroring (even historically applied ones) and restore
 *   the initial state — a hard reset of both the draft and the committed value.
 * - `onSwapHoriz` / `onSwapVert`: toggle the horizontal / vertical mirror.
 * - `onApply`: save the draft into the committed value (the fallback for a future X / close), then
 *   close the bar.
 *
 * The glyphs are the Material `material-icons-extended` icons the product spec calls for.
 */
@Composable
internal fun CropRotateControlPanel(
    onCloseDiscard: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onCancel: () -> Unit,
    onSwapHoriz: () -> Unit,
    onSwapVert: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onCloseDiscard) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(id = R.string.studio_cr_close),
            )
        }
        IconButton(onClick = onRotateLeft) {
            Icon(
                imageVector = Icons.Filled.RotateLeft,
                contentDescription = stringResource(id = R.string.studio_cr_rotate_left),
            )
        }
        IconButton(onClick = onRotateRight) {
            Icon(
                imageVector = Icons.Filled.RotateRight,
                contentDescription = stringResource(id = R.string.studio_cr_rotate_right),
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = Icons.Filled.Cancel,
                contentDescription = stringResource(id = R.string.studio_cr_cancel),
            )
        }
        IconButton(onClick = onSwapHoriz) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = stringResource(id = R.string.studio_cr_flip_h),
            )
        }
        IconButton(onClick = onSwapVert) {
            Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = stringResource(id = R.string.studio_cr_flip_v),
            )
        }
        IconButton(onClick = onApply) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = stringResource(id = R.string.studio_cr_apply),
            )
        }
    }
}
