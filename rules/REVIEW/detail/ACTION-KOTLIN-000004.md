# Duplicated Compose and data blocks — ten extraction groups across the two library screens and the data layer

- ID: ACTION-KOTLIN-000004
- Status: Observation
- Priority: P2
- Created: 2026-09-16
- Owner: —
- Related: `ACTION-KOTLIN-000001` (master audit), `rules/DESIGN/detail/FOTLAB-UIXDES-000004.md` (top bar layout, selection overflow trio), `rules/DESIGN/detail/FOTLAB-UIXDES-000002.md` (R3 drawer width, R6 close affordance), `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreen.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreenRecycle.kt`

## Background & Goal

`LibraryScreen` (878 lines) and `LibraryScreenRecycle` (476 lines) were built as two views of the same feature and each carries its own top bar. That is intentional per `FOTLAB-UIXDES-000004` — the Recycle bar has different actions — but the *shared* parts of the two bars were written twice instead of extracted.

This item lists every duplicated block found in the full-source audit, sized so the cheap, zero-risk extractions can be done first.

## Finding

### Group C4 — selection overflow trio (~40 lines × 2, near-identical)

`LibraryScreen.kt:579-621` and `LibraryScreenRecycle.kt:309-345`. Same `var overflowOpen by remember { mutableStateOf(false) }`, same `Box` + `IconButton(Icons.Filled.MoreVert)` + `DropdownMenu`, same three `DropdownMenuItem`s with the same icons (`SelectAll` / `FlipToBack` / `Deselect`) and the same three string resources, same `overflowOpen = false` before each callback. The only difference is where the three callbacks come from: `LibraryScreen` calls `LibraryCore.selection.selectAll/invert/clear(candidateIds)` directly, `LibraryRecycleScreen` calls three passed-in lambdas.

### Group C2 — idle leading icon cluster (~20 lines × 2, identical)

`LibraryScreen.kt:496-515` and `LibraryScreenRecycle.kt:255-274`. Menu / GridView / Sync `IconButton`s, same icons, same four content-description resources, same order.

### Group C3 — selection-mode leading cluster (~15 lines × 2)

`LibraryScreen.kt:517-540` and `LibraryScreenRecycle.kt:276-288`. Close button + count `Text` in `titleLarge`. `LibraryScreen` additionally swaps the count for a rename pencil when exactly one node is selected; `LibraryRecycleScreen` always shows the count.

### Group C1 — viewer host block (~20 lines × 2)

`LibraryScreen.kt:294-308` and `LibraryScreenRecycle.kt:185-198`. Both declare `viewerItems` / `viewerStart`, both build `LibraryViewerDialog`, and `onOpenInStudio` is character-for-character the same three statements (`StudioEngine.setCurrentNode(uri)` → `onNavigateToStudio()` → `viewerItems = null`). Both also use `viewerItems!!` after a `!= null` check (K-22).

### Group C5 — confirmation dialog skeleton (~25 lines × 2)

`LibraryScreen.kt:312-346` (delete confirm and delete-invalid) and `LibraryScreenRecycle.kt:200-220` (delete forever). Same `AlertDialog` + title + text + confirm + dismiss shape.

### Smaller groups

| Group | Where | Size |
|---|---|---|
| C15 / K-15 | Drawer width `0.8f` — `LibraryScreen.kt:93` (named `DrawerWidthFraction`) and `StudioScreen.kt:232` (bare literal) | 2 sites |
| C29 / K-29 | `LibraryLayoutPreference` and `media/MediaPreference` — identical `preferencesDataStore` delegate + `store.data.map {}` + `store.edit {}` plumbing | 2 classes |
| C30 / K-30 | Two hand-rolled BFS walks over `ArrayDeque` + `visited` — `LibraryRepository.wouldCreateCycle:94-108` and `deleteForever:240-261` | ~15 lines × 2 |
| C27 / K-27 | MediaStore PNG fixture publish (`IS_PENDING` insert → write → publish) and cleanup — `PngEndToEndFlowTest.kt:119-156` and `ZoomableGestureTest.kt:124-167` | ~35 lines × 2 |
| K-24 | `indication = null` in the grid `NodeCell` but not in the `ListItem` branch — ripple behaviour differs for the same interaction | 1 line |
| K-25 | `Checkbox` nested inside the clickable `Card` in `LibraryScreen.kt:812` — two click targets in one node | 1 site |

## Impact / Conflict

- Groups C1–C5 are ~200 lines of near-duplicate Compose. The risk is drift, not correctness: the two overflow menus already differ only by callback source, so any future change to the trio (a fourth item, a new icon, a confirm step) has to be made in two places and will eventually be made in one.
- C4 is the sharpest case because the trio is user-facing behaviour specified by `FOTLAB-UIXDES-000004`; a divergence between the two bars would be a spec violation that no test would catch.
- Extractions C1–C5 are pure structural moves with no behaviour change — the right first work item after the P0 and the two P1 correctness fixes.
- C30 and C27 are internal and lower value; C30 in particular touches two algorithms with different termination semantics (`wouldCreateCycle` returns early, `deleteForever` collects everything), so a shared `traverse()` must take the "next ids" step as a parameter rather than assuming either shape.
- No conflict with `ACTION-LIBRND-000001`: none of these blocks affect level scoping or the query shape.

## Recommendation

1. `SelectionOverflowMenu(onSelectAll, onInvert, onDeselectAll, modifier)` — kills C4. `LibraryScreen` passes `selection::selectAll` style lambdas bound to `candidateIds`; `LibraryRecycleScreen` passes its existing lambdas.
2. `LibraryLeadingCluster(...)` — kills C2 and C3 together. Take a small sealed input (`Idle` vs `Selecting(count, onRename?)`) so the one place that differs between the two bars is expressed as data rather than as a second copy of the layout.
3. `rememberViewerHost()` returning the open/close state plus the dialog composable — kills C1 and the three `!!` unwraps.
4. `ConfirmDialog(title, message, confirmLabel, onConfirm, onDismiss)` — kills C5.
5. Then, separately and later: hoist `0.8f` into a shared dimension constant (also fixes the `FOTLAB-UIXDES-000002` R3 single-definition intent), extract the MediaStore fixture into a shared test helper, and only then consider the `traverse()` helper for C30.

## Change History

- 2026-09-16 — Recorded from the full-source Kotlin audit (`ACTION-KOTLIN-000001`). Ten duplication groups identified, dominated by the selection overflow trio (C4, ~40 lines × 2, near-identical) and the two leading icon clusters (C2/C3). Sized so the ~200-line pure-move extraction of C1–C5 can be done as one zero-behaviour-change step. No code changed.
