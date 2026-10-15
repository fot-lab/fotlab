package io.github.fotlab.fotlab.feature.studio

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.fotlab.fotlab.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * On-press behaviour for the Studio grade bar's LOG and LUT buttons, extracted out of
 * [StudioOpBarButtons] so the bottom-sheet UI and its persisted "recently used" history live in one
 * place.
 *
 * Both buttons used to open a `DropdownMenu` (a flat list of the algorithm catalogue / the two-item
 * file-picker). That made re-selecting a previously used algorithm or filter file awkward — there
 * was no memory of what the user had chosen before. This module replaces each dropdown with a
 * two-tab [ModalBottomSheet]: a FIFO, persisted **Recent** history on the left and the full
 * catalogue / file picker on the right. See `rules/DESIGN/detail/FOTLAB-LOTLUT-000001.md`.
 */

private val Context.gradeRecentDataStore by preferencesDataStore(name = "studio_grade_recent")

private val KEY_RECENT_LOG = stringPreferencesKey("studio_recent_log_spaces")
private val KEY_RECENT_LUT = stringPreferencesKey("studio_recent_lut_uris")

/** Entries are newline-joined; order is the FIFO order (newest last). */
private const val RECENT_SEPARATOR = "\n"

/** Max entries kept in each Recent history before the oldest is evicted. */
internal const val RECENT_CAPACITY = 60

/** Tab indices: Recent history (left) and the full catalogue / file picker (right). */
private const val RECENT_TAB = 0
private const val CHOOSE_TAB = 1

/**
 * Durable, FIFO-bounded "recently used" history for the grade bar's LOG and LUT pickers.
 *
 * Its own `DataStore` file (`studio_grade_recent`), kept separate from the develop-pipeline prefs
 * ([StudioDevelopPreference]) and the media-layer `studio_prefs` ([MediaPreference]) so the three
 * never name the same delegate (a second `preferencesDataStore` on the same name throws). Each
 * history is a single newline-joined string preference — a `Set` would lose order, which the FIFO
 * eviction rule depends on.
 *
 * **Eviction** — the newest entry is always last; when the list exceeds [RECENT_CAPACITY] the
 * oldest (first) entry is dropped. **De-duplication** — if the just-used value already *is* the
 * most-recent entry, nothing is pushed (re-selecting the same item only re-orders when it was not
 * already last). A previously-used value that is not last is moved to the end (becomes most-recent)
 * rather than duplicated.
 */
class GradeRecentStore(private val context: Context) {

    private val store = context.applicationContext.gradeRecentDataStore

    /** Recently used LOG curve names, newest last. "None" is never recorded here. */
    val recentLogSpaces: Flow<List<String>> = store.data.map { prefs ->
        prefs[KEY_RECENT_LOG]?.split(RECENT_SEPARATOR).orEmpty()
    }

    /** Recently used LUT filter file URIs, newest last. "None" is never recorded here. */
    val recentLutUris: Flow<List<String>> = store.data.map { prefs ->
        prefs[KEY_RECENT_LUT]?.split(RECENT_SEPARATOR).orEmpty()
    }

    /** Records a real, non-null LOG curve name as most-recently-used. */
    suspend fun pushLogSpace(value: String) = push(KEY_RECENT_LOG, value)

    /** Records a real, non-null LUT filter file URI as most-recently-used. */
    suspend fun pushLutUri(value: String) = push(KEY_RECENT_LUT, value)

    private suspend fun push(key: Preferences.Key<String>, token: String) {
        store.edit { prefs ->
            val list = prefs[key]?.split(RECENT_SEPARATOR).orEmpty().toMutableList()
            if (list.lastOrNull() == token) return@edit
            list.remove(token)
            list.add(token)
            while (list.size > RECENT_CAPACITY) list.removeAt(0)
            prefs[key] = list.joinToString(RECENT_SEPARATOR)
        }
    }
}

/**
 * LOG curve picker sheet. Left tab = **Recent** (persisted FIFO history, capped at
 * [RECENT_CAPACITY]); right tab = **Choose algorithm** (none + the full native catalogue, no cap).
 *
 * Opens on the Recent tab and scrolls to the last (most-recent) row; switching to Choose algorithm
 * scrolls to the first row, and switching back to Recent scrolls to the last row again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogPickerSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    logSpace: String?,
    logSpaces: List<String>,
    onLogSpace: (String?) -> Unit,
) {
    if (!open) return
    val context = LocalContext.current
    val store = remember { GradeRecentStore(context) }
    val recent by store.recentLogSpaces.collectAsState(emptyList())
    var selectedTab by remember { mutableStateOf(RECENT_TAB) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val noneLabel = stringResource(R.string.studio_grade_none)
    val recentLabel = stringResource(R.string.studio_grade_recent)
    val chooseLabel = stringResource(R.string.studio_grade_tab_algorithm)

    LaunchedEffect(selectedTab, recent.size) {
        if (selectedTab == RECENT_TAB) {
            if (recent.isEmpty()) return@LaunchedEffect
            listState.scrollToItem(recent.lastIndex)
        } else {
            listState.scrollToItem(0)
        }
    }

    val applyAndClose: (String?) -> Unit = { value ->
        onLogSpace(value)
        if (value != null) scope.launch { store.pushLogSpace(value) }
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.72f)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == RECENT_TAB,
                    onClick = { selectedTab = RECENT_TAB },
                    text = { Text(recentLabel) },
                )
                Tab(
                    selected = selectedTab == CHOOSE_TAB,
                    onClick = { selectedTab = CHOOSE_TAB },
                    text = { Text(chooseLabel) },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (selectedTab == RECENT_TAB) {
                        itemsIndexed(recent, key = { _, v -> "log-recent-$v" }) { _, value ->
                            RecentRow(
                                label = value,
                                selected = value == logSpace,
                                onClick = { applyAndClose(value) },
                            )
                        }
                    } else {
                        item(key = "log-none") {
                            RecentRow(
                                label = noneLabel,
                                selected = logSpace == null,
                                onClick = { applyAndClose(null) },
                            )
                        }
                        items(logSpaces, key = { it }) { name ->
                            RecentRow(
                                label = name,
                                selected = logSpace == name,
                                onClick = { applyAndClose(name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * LUT picker sheet. Left tab = **Recent** (persisted FIFO history of picked filter file URIs, capped
 * at [RECENT_CAPACITY]); right tab = **Choose filter** (none + the SAF file picker).
 *
 * Same scroll contract as [LogPickerSheet]: opens on Recent scrolled to the last row, Choose filter
 * scrolls to the first row, and back to Recent scrolls to the last row again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LutPickerSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    lutName: String?,
    onPickFile: () -> Unit,
    onApplyUri: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (!open) return
    val context = LocalContext.current
    val store = remember { GradeRecentStore(context) }
    val recent by store.recentLutUris.collectAsState(emptyList())
    var selectedTab by remember { mutableStateOf(RECENT_TAB) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val noneLabel = stringResource(R.string.studio_grade_lut_clear)
    val pickLabel = stringResource(R.string.studio_grade_lut_pick)
    val recentLabel = stringResource(R.string.studio_grade_recent)
    val chooseLabel = stringResource(R.string.studio_grade_tab_filter)

    LaunchedEffect(selectedTab, recent.size) {
        if (selectedTab == RECENT_TAB) {
            if (recent.isEmpty()) return@LaunchedEffect
            listState.scrollToItem(recent.lastIndex)
        } else {
            listState.scrollToItem(0)
        }
    }

    val applyUriAndClose: (String) -> Unit = { uriStr ->
        onApplyUri(uriStr)
        scope.launch { store.pushLutUri(uriStr) }
        onDismiss()
    }
    val clearAndClose: () -> Unit = {
        onClear()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxHeight(0.72f)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == RECENT_TAB,
                    onClick = { selectedTab = RECENT_TAB },
                    text = { Text(recentLabel) },
                )
                Tab(
                    selected = selectedTab == CHOOSE_TAB,
                    onClick = { selectedTab = CHOOSE_TAB },
                    text = { Text(chooseLabel) },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (selectedTab == RECENT_TAB) {
                        itemsIndexed(recent, key = { _, v -> "lut-recent-$v" }) { _, value ->
                            RecentRow(
                                label = runCatching { Uri.parse(value).lastPathSegment }.getOrNull() ?: value,
                                selected = false,
                                onClick = { applyUriAndClose(value) },
                            )
                        }
                    } else {
                        item(key = "lut-none") {
                            RecentRow(
                                label = noneLabel,
                                selected = lutName == null,
                                onClick = clearAndClose,
                            )
                        }
                        item(key = "lut-pick") {
                            RecentRow(
                                label = pickLabel,
                                selected = false,
                                onClick = { onDismiss(); onPickFile() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One tappable text row inside a picker sheet; [selected] tints it with the primary colour. */
@Composable
private fun RecentRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}
