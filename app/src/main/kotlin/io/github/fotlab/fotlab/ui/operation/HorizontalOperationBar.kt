/*
 * Copyright 2026 The FotLab Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.fotlab.fotlab.ui.operation

import androidx.compose.foundation.gestures.snapping.LazyListSnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A single operation hosted inside a [HorizontalOperationBar]. The button's visual and interactive
 * behaviour is fully owned by [content] — the container only lays the items out, so function and
 * layout stay decoupled: reordering the buttons means reordering the list.
 *
 * @param id stable identifier, used as the [LazyRow] key.
 * @param content the button composable; receives the slot [Modifier] (a fixed-width box) and may
 *   render anything (an [IconButton], a chip, a dropdown anchor, ...).
 */
data class OperationalButton(
    val id: String,
    val content: @Composable (Modifier) -> Unit,
)

/**
 * Wraps [LazyListSnapLayoutInfoProvider] (which snaps the closest item to the viewport start) with
 * an end-boundary guard.
 *
 * `SnapPosition.Start` alone has an end-of-list flaw: when the list is already scrolled to its
 * maximum offset (the last item sits flush at the right edge), a low-velocity release makes the
 * delegate snap the *previous* item to the start, which pulls the list back and leaves the last
 * item partially clipped — re-showing the right arrow even though the user had just reached the
 * end.
 *
 * This wrapper detects that case (`currentScroll` at `maxScroll`) and, unless the user is
 * actively flinging backward (`velocity < 0`), returns a zero delta so the list stays at the end
 * where the last item is fully visible in the last slot. All other positions delegate unchanged,
 * so the start boundary (first item always in the first slot) and the middle snap behaviour stay
 * exactly as the official provider defines.
 */
private class EndBoundarySnapLayoutInfoProvider(
    private val state: LazyListState,
) : SnapLayoutInfoProvider {

    private val delegate = LazyListSnapLayoutInfoProvider(state, SnapPosition.Start)

    override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float =
        delegate.calculateApproachOffset(velocity, decayOffset)

    override fun calculateSnapOffset(velocity: Float): Float {
        val layoutInfo = state.layoutInfo
        val items = layoutInfo.visibleItemsInfo
        if (items.isEmpty()) return delegate.calculateSnapOffset(velocity)

        val viewportSize = layoutInfo.viewportSize.width
        val avgSize = items.sumOf { it.size } / items.size
        if (avgSize <= 0) return delegate.calculateSnapOffset(velocity)

        val currentScroll = state.firstVisibleItemIndex * avgSize + state.firstVisibleItemScrollOffset
        val maxScroll = (layoutInfo.totalItemsCount * avgSize - viewportSize).coerceAtLeast(0)

        // Already at the end: stay there (last item fully visible in the last slot) unless the
        // user is flinging away from it. A half-pixel epsilon absorbs rounding.
        val atEnd = currentScroll >= maxScroll - 0.5f
        if (atEnd && velocity >= 0f) {
            return (maxScroll - currentScroll).toFloat()
        }
        return delegate.calculateSnapOffset(velocity)
    }
}

/**
 * A horizontally scrollable row of operation buttons with scaffold-style leading/trailing slots and
 * auto-managed scroll indicators.
 *
 * Layout (default left-aligned):
 * ```
 * [ leading slot ] [ < ] [ item item item ... ] [ > ] [ trailing slot ]
 * ```
 *
 * The middle [LazyRow] snaps its items to fixed-width slots ([slotWidth]) via the official
 * `LazyListSnapLayoutInfoProvider` + `snapFlingBehavior`, so after a fling the leftmost visible
 * item aligns to the scroll area's left edge. A small [EndBoundarySnapLayoutInfoProvider] guard
 * keeps the list at the end offset (last item flush in the last slot) instead of snapping back
 * and clipping it, unless the user flings backward. The leading/trailing arrow buttons appear
 * only when content overflows in that direction:
 *  - left arrow (`ArrowBack`) when there is still content before the leftmost visible item;
 *  - right arrow (`ArrowForward`) when the last item is not fully visible.
 *
 * @param items the buttons in display order; reorder the list to change the layout.
 * @param slotWidth width of each item slot; all items share one width so snapping is stable.
 * @param leading optional slot pinned to the far left (outside the scroll arrows).
 * @param trailing optional slot pinned to the far right (outside the scroll arrows).
 */
@Composable
fun HorizontalOperationBar(
    items: List<OperationalButton>,
    modifier: Modifier = Modifier,
    slotWidth: Dp = 56.dp,
    height: Dp = 56.dp,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val flingBehavior = remember(state) {
        snapFlingBehavior(EndBoundarySnapLayoutInfoProvider(state))
    }
    val canScrollBackward by remember { derivedStateOf { state.canScrollBackward } }
    val canScrollForward by remember { derivedStateOf { state.canScrollForward } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.invoke()

            if (canScrollBackward) {
                IconButton(onClick = {
                    val prev = (state.firstVisibleItemIndex - 1).coerceAtLeast(0)
                    scope.launch { state.animateScrollToItem(prev) }
                }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = null,
                    )
                }
            }

            LazyRow(
                state = state,
                flingBehavior = flingBehavior,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                items(items, key = { it.id }) { button ->
                    Box(
                        modifier = Modifier
                            .width(slotWidth)
                            .height(height),
                        contentAlignment = Alignment.Center,
                    ) {
                        button.content(Modifier)
                    }
                }
            }

            if (canScrollForward) {
                IconButton(onClick = {
                    val next = (state.firstVisibleItemIndex + 1).coerceAtMost(items.lastIndex)
                    scope.launch { state.animateScrollToItem(next) }
                }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = null,
                    )
                }
            }

            trailing?.invoke()
        }
    }
}
