package io.github.fotlab.fotlab.feature.studio

import android.content.Context
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
import io.github.fotlab.fotlab_rawler.DemosaicCandidate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * On-press behaviour for the Studio DevelopFilm bar's Demosaic button, extracted out of
 * [StudioOpBarButtons] so the bottom-sheet UI and its persisted "recently used" history live in one
 * place — mirroring the LOG / LUT refactor ([StudioLogLutSheets]).
 *
 * The button used to open a `DropdownMenu` (a flat list of the native catalogue). That had no memory
 * of what the user picked before. This module replaces it with a two-tab [ModalBottomSheet]: a FIFO,
 * persisted **Recent** history on the left and the full catalogue on the right. See
 * `rules/DESIGN/detail/FOTLAB-DEMOSA-000001.md`.
 */

private val Context.demosaicRecentDataStore by preferencesDataStore(name = "studio_demosaic_recent")

private val KEY_RECENT_DEMOSAIC = stringPreferencesKey("studio_recent_demosaic_ids")

/** Entries are newline-joined; order is the FIFO order (newest last). */
private const val RECENT_SEPARATOR = "\n"

/** Max entries kept in the Recent history before the oldest is evicted. */
internal const val DEMOSAIC_RECENT_CAPACITY = 60

/** Tab indices: Recent history (left) and the full catalogue (right). */
private const val RECENT_TAB = 0
private const val CHOOSE_TAB = 1

/**
 * The superpixel entry's catalogue id — the one pick whose applicability is sensor-dependent.
 * Kept here (with the picker) because both tabs need the `enabled` gate it drives.
 */
internal const val SUPERPIXEL_ID = "rawler:superpixel"

/**
 * Durable, FIFO-bounded "recently used" history for the demosaic picker.
 *
 * Its own `DataStore` file (`studio_demosaic_recent`), kept separate from the develop-pipeline prefs
 * ([StudioDevelopPreference]), the media-layer `studio_prefs` ([MediaPreference]), and the LOG / LUT
 * `studio_grade_recent` ([GradeRecentStore]) so none of the four name the same delegate (a second
 * `preferencesDataStore` on the same name throws). Each history is a single newline-joined string
 * preference — a `Set` would lose order, which the FIFO eviction rule depends on.
 *
 * **Eviction** — the newest entry is always last; when the list exceeds [DEMOSAIC_RECENT_CAPACITY] the
 * oldest (first) entry is dropped. **De-duplication** — if the just-used id already *is* the
 * most-recent entry, nothing is pushed; a previously-used id that is not last has its stale occurrence
 * removed immediately (not deferred until the list is full) and is re-appended as most-recent, so the
 * Recent list never shows a duplicate.
 */
class DemosaicRecentStore(private val context: Context) {

    private val store = context.applicationContext.demosaicRecentDataStore

    /** Recently used demosaic candidate ids, newest last. */
    val recentIds: Flow<List<String>> = store.data.map { prefs ->
        prefs[KEY_RECENT_DEMOSAIC]?.split(RECENT_SEPARATOR).orEmpty()
    }

    /** Records a real, non-null demosaic candidate id as most-recently-used. */
    suspend fun pushId(value: String) = push(KEY_RECENT_DEMOSAIC, value)

    private suspend fun push(key: Preferences.Key<String>, token: String) {
        store.edit { prefs ->
            val list = prefs[key]?.split(RECENT_SEPARATOR).orEmpty().toMutableList()
            if (list.lastOrNull() == token) return@edit
            list.removeAll { it == token }
            list.add(token)
            while (list.size > DEMOSAIC_RECENT_CAPACITY) list.removeAt(0)
            prefs[key] = list.joinToString(RECENT_SEPARATOR)
        }
    }
}

/**
 * Demosaic picker sheet. Left tab = **Recent** (persisted FIFO history of chosen algorithm ids, capped
 * at [DEMOSAIC_RECENT_CAPACITY]); right tab = **Choose algorithm** (the full native catalogue, no cap).
 *
 * Opens on the Recent tab and scrolls to the last (most-recent) row; switching to Choose algorithm
 * scrolls to the first row, and switching back to Recent scrolls to the last row again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DemosaicPickerSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    candidates: List<DemosaicCandidate>,
    superpixelSupported: Boolean?,
    currentId: String?,
    onAlgorithmPicked: (DemosaicCandidate) -> Unit,
) {
    if (!open) return
    val context = LocalContext.current
    val store = remember { DemosaicRecentStore(context) }
    val recent by store.recentIds.collectAsState(emptyList())
    var selectedTab by remember { mutableStateOf(RECENT_TAB) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
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

    val applyAndClose: (DemosaicCandidate) -> Unit = { candidate ->
        onAlgorithmPicked(candidate)
        scope.launch { store.pushId(candidate.id) }
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
                        itemsIndexed(recent, key = { _, v -> "demosaic-recent-$v" }) { _, id ->
                            val candidate = candidates.firstOrNull { it.id == id }
                            if (candidate != null) {
                                val enabled = candidate.id != SUPERPIXEL_ID || superpixelSupported != false
                                DemosaicRow(
                                    label = demosaicLabel(candidate),
                                    selected = id == currentId,
                                    enabled = enabled,
                                    onClick = { if (enabled) applyAndClose(candidate) },
                                )
                            }
                        }
                    } else {
                        items(candidates, key = { it.id }) { candidate ->
                            val enabled = candidate.id != SUPERPIXEL_ID || superpixelSupported != false
                            DemosaicRow(
                                label = demosaicLabel(candidate),
                                selected = candidate.id == currentId,
                                enabled = enabled,
                                onClick = { if (enabled) applyAndClose(candidate) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One tappable text row inside the demosaic picker; [selected] tints it primary, [enabled]=false greys it. */
@Composable
private fun DemosaicRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * Display text for one demosaic candidate, **always naming the source library** except for the
 * default entry.
 *
 * The menu lists two independent implementations under similar names — `amaze` and `fast` exist on
 * both sides — so an entry that did not say where it came from would be ambiguous. The native
 * catalogue already prefixes every label with `RAWLER` / `RAWTRP` (`rawtrp_demosaic::algo`); this
 * keeps that prefix while still showing the *translated* algorithm name for the entries Studio has
 * localised. The default is the one exception: it is not an algorithm but "whatever the sensor's CFA
 * calls for", so it carries no source.
 *
 * The prefix is a parameter rather than part of each translated string so the source name stays a
 * single fact: translating "RAWLER" or "RAWTRP" is not a thing, and duplicating it across five
 * strings is how the two halves would drift apart.
 */
@Composable
internal fun demosaicLabel(candidate: DemosaicCandidate): String {
    val localized = when (candidate.id) {
        "rawler:default" -> return stringResource(id = R.string.studio_demosaic_default)
        "rawler:ppg" -> stringResource(id = R.string.studio_demosaic_ppg)
        "rawler:bilinear4" -> stringResource(id = R.string.studio_demosaic_bilinear4)
        "rawler:xtrans_bilinear" -> stringResource(id = R.string.studio_demosaic_xtrans)
        SUPERPIXEL_ID -> stringResource(id = R.string.studio_demosaic_superpixel)
        // A kernel ported later: no translated string exists yet, so it keeps the catalogue's own
        // already-prefixed label rather than vanishing from the menu.
        else -> return candidate.label
    }
    return stringResource(id = R.string.studio_demosaic_source_prefix, candidate.label.substringBefore(' ')) +
        " " + localized
}
