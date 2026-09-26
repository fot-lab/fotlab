package io.github.fotlab.fotlab.ui.operation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A single-row operation bar that floats on top of full-bleed content (a photo, a video), built in
 * the same container-plus-slots style as [HorizontalOperationBar]: the container owns the frame —
 * scrim, height, colours — and every control arrives through a slot, so the call site decides what
 * the bar *does* while the container decides how a bar *looks*.
 *
 * Layout:
 * ```
 * [ above slot — stacked, outside the scrim ]
 * [ leading slot | content slot (takes the rest) | trailing slot ]
 * ```
 *
 * The scrim is translucent by default because this bar sits directly over media: without a backing
 * of its own the controls disappear into whatever they happen to overlay.
 *
 * @param above optional content stacked above the control row. It is drawn outside the scrim (a
 *   panel brings its own background) and is placed above the row so the controls stay reachable.
 * @param leading slot pinned to the far left of the row.
 * @param content slot spanning the width between the two ends; left-aligned content lands right
 *   after [leading], and the slot pushes [trailing] to the far right even when it is empty.
 * @param trailing slot pinned to the far right of the row.
 */
@Composable
fun OverlayOperationBar(
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Black.copy(alpha = 0.5f),
    contentColor: Color = Color.White,
    height: Dp = 56.dp,
    above: @Composable (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        above?.invoke()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = containerColor,
            contentColor = contentColor,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading?.invoke()
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    content?.invoke()
                }
                trailing?.invoke()
            }
        }
    }
}
