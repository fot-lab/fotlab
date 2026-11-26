package io.github.fotlab.fotlab.feature.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fotlab.fotlab.R
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueBatch
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueItem
import io.github.fotlab.fotlab.feature.camera.model.CameraQueueStates

/**
 * The transfer queue (`FOTLAB-DATABS-000004`).
 *
 * One card per batch — the rows that share a `timestamp_batchadd` — newest batch first. The
 * card's right-hand arrow expands it to the individual files, each shown by the file name
 * only: the last segment of the camera-side URI.
 *
 * The list is a read of the table, not of any in-memory transfer state, so it shows the same
 * thing whether the app was just started or has been running for hours.
 */
@Composable
internal fun CameraQueueList(
    batches: List<CameraQueueBatch>,
    runningId: Long?,
    modifier: Modifier = Modifier,
) {
    if (batches.isEmpty()) {
        Text(
            text = stringResource(id = R.string.camera_queue_empty),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(batches, key = { batch -> batch.batchAdd }) { batch ->
            CameraQueueBatchCard(batch = batch, runningId = runningId)
        }
    }
}

/** One batch: a header with the progress and the expand arrow, then the files. */
@Composable
private fun CameraQueueBatchCard(
    batch: CameraQueueBatch,
    runningId: Long?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(batch.batchAdd) { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(
                            id = R.string.camera_queue_batch_summary,
                            batch.finishedCount,
                            batch.items.size,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = batch.batchAdd,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = stringResource(
                            id = if (expanded) {
                                R.string.camera_cd_collapse
                            } else {
                                R.string.camera_cd_expand
                            },
                        ),
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    batch.items.forEach { item ->
                        CameraQueueItemRow(item = item, running = item.id == runningId)
                    }
                }
            }
        }
    }
}

/** One queued file: its name and where it stands. */
@Composable
private fun CameraQueueItemRow(
    item: CameraQueueItem,
    running: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(id = item.stateLabel(running)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
    }
}

/**
 * The label of a queued item's state.
 *
 * A row that has not finished is still queued unless it is the one running right now; a
 * finished row carries either the time it finished or the state it stopped in.
 */
private fun CameraQueueItem.stateLabel(running: Boolean): Int = when {
    running -> R.string.camera_queue_item_running
    stopState == CameraQueueStates.PAUSED -> R.string.camera_queue_item_paused
    stopState == CameraQueueStates.CANCELED -> R.string.camera_queue_item_canceled
    stopState == CameraQueueStates.FAILED -> R.string.camera_queue_item_failed
    itemFinish == null -> R.string.camera_queue_item_pending
    else -> R.string.camera_queue_item_done
}
