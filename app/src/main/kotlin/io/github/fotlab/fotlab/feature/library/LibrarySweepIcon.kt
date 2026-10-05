package io.github.fotlab.fotlab.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import io.github.fotlab.fotlab.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * The sync / refresh icon, with a rotation that spans the reconcile it fires
 * (`FOTLAB-UIXDES-000004` R10, R11).
 *
 * Both fun bars carry this icon — the Library bar and the Recycle Bin bar — and both fire the same
 * `LibraryCore.refresh()`, so the icon and its animation live here rather than being written twice.
 * The two bars are never on screen at the same time (the drawer switches between them), which is
 * why the state below is per-call: each bar owns its own sweep, and switching views mid-sweep
 * drops the animation along with the bar that owned it.
 *
 * **Why the rotation is bounded.** The spin is not the reconcile's own progress report — it is a
 * re-entrancy guard made visible. Without a ceiling, a press whose sweep never returns (a wedged
 * `content://` resolver, a held database lock) would leave the icon spinning forever and leave the
 * user no way to tell a live app from a hung one. So the spin runs at most [SWEEP_SPIN_CEILING_MS]
 * and then stops on its own.
 *
 * **The ceiling does not cancel the sweep.** This is the whole subtlety of the design, and it is
 * why the timeout and the sweep are two independent coroutines rather than one sweep wrapped in
 * `withTimeoutOrNull`. `withTimeoutOrNull { LibraryCore.refresh() }` would cancel the reconcile at
 * the ceiling, ending it half-applied — some nodes archived, the rest not, with no single batch id
 * to make that state coherent. Instead the ceiling only clears the spinning flag; the sweep keeps
 * running to its own natural end, and its own completion flips the flag too. Whichever arrives
 * first stops the icon: the sweep returning (the normal case, usually well inside the first
 * second), or the ceiling (the hung case). The icon is therefore never left rotating, and the
 * reconcile is never cut in half.
 *
 * A press while a spin is already up is ignored rather than queued. The sweep is idempotent — a
 * second run would find nothing left to do — but stacking one would race the first for the same
 * rows and make the spin's stop time meaningless.
 */
@Composable
internal fun SweepSyncIcon(onSweep: suspend () -> Unit) {
    // Whether this bar's icon is currently spinning; false whenever no sweep of it is in flight.
    var spinning by remember { mutableStateOf(false) }
    // The drawn angle. `Animatable` rather than `rememberInfiniteTransition` because an infinite
    // transition has no natural stop: it can only be muted by ignoring its value, which freezes
    // the angle wherever it happened to be. Driving the value explicitly lets the loop end and
    // the icon settle back to upright.
    val rotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Keyed on `spinning`, so setting the flag starts the loop and clearing it cancels the loop
    // mid-turn and hands over to the settle below.
    LaunchedEffect(spinning) {
        if (spinning) {
            // One full turn per second reads as a spinner rather than a strobe. Each turn
            // re-anchors at 0 first, so the angle never accumulates unbounded float error over a
            // long sweep.
            while (true) {
                rotation.snapTo(0f)
                rotation.animateTo(
                    targetValue = 360f,
                    animationSpec = tween(durationMillis = 1_000, easing = LinearEasing),
                )
            }
        } else {
            // Cancelling the loop above strands the angle mid-turn, and a sync icon frozen at a
            // random tilt reads as a glitch. So finish the turn in progress — forward, to the next
            // whole turn, which the `% 360f` at the draw site renders as upright — and only fall
            // back to an instant reset when a spin is stopped before it ever began.
            val nextWholeTurn = ceil(rotation.value / 360f) * 360f
            val remaining = nextWholeTurn - rotation.value
            if (remaining > 0f) {
                rotation.animateTo(
                    targetValue = nextWholeTurn,
                    animationSpec = tween(
                        durationMillis = (remaining / 360f * 1_000f).toInt().coerceAtLeast(1),
                        easing = LinearEasing,
                    ),
                )
            }
            rotation.snapTo(0f)
        }
    }

    IconButton(
        onClick = {
            if (spinning) return@IconButton
            spinning = true
            scope.launch {
                try {
                    onSweep()
                } finally {
                    // Reached when the sweep returns *and* when it throws; either way this bar has
                    // nothing in flight, so the icon stops. Clearing an already-false flag (the
                    // ceiling got there first) is a harmless no-op.
                    spinning = false
                }
            }
            // The ceiling, deliberately not wrapping `onSweep` — see the type's doc comment.
            scope.launch {
                delay(SWEEP_SPIN_CEILING_MS)
                spinning = false
            }
        },
    ) {
        Icon(
            imageVector = Icons.Filled.Sync,
            contentDescription = stringResource(id = R.string.library_cd_sync),
            modifier = Modifier.graphicsLayer { rotationZ = rotation.value % 360f },
        )
    }
}

/**
 * The longest the sync icon spins, in milliseconds — one minute (`FOTLAB-UIXDES-000004` R11).
 *
 * A sweep over a large library can legitimately take a while, so this is a ceiling on the
 * *animation*, picked long enough that a healthy sweep always ends on its own terms. It is not a
 * statement about how long a sweep may take, and it never truncates one.
 */
private const val SWEEP_SPIN_CEILING_MS = 60_000L
