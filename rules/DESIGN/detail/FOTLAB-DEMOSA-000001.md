# Demosaic picker — two-tab bottom sheet with persisted Recent history

- ID: FOTLAB-DEMOSA-000001
- Status: Draft
- Priority: P2
- Created: 2026-10-15
- Owner: —
- Related: FOTLAB-LOTLUT-000001, FOTLAB-UIXDES-000002, FOTLAB-NATIVE-000004

## Background & Goal

The Studio DevelopFilm bar exposes a **Demosaic** tool (the gradient icon) whose picker was a
`DropdownMenu` anchored to the icon, listing every entry of the native catalogue
(`StudioEngine.demosaicCandidates`). Like the old LOG / LUT dropdowns (see `FOTLAB-LOTLUT-000001`),
that shape has no memory of what the user picked before, so re-applying a previously used demosaic
algorithm means re-walking the full catalogue every time.

The goal is to add a **persisted "recently used" history** and surface it as the default view, while
keeping the full catalogue one tap away — and to extract the on-press behaviour into its own file,
mirroring the LOG / LUT refactor.

We replace the dropdown with a `ModalBottomSheet` containing two tabs:

- Left tab **近期使用 / Recent** (persisted FIFO history of chosen demosaic algorithm ids).
- Right tab **选择算法 / Choose algorithm** (the full native catalogue, uncapped).

The on-press behaviour (the sheet plus its history store) is extracted into
`app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioDemosaicSheet.kt`, separate from the
icon button in `StudioOpBarButtons.kt`. `demosaicLabel` and `SUPERPIXEL_ID` move here too, since they
belong to the picker, not the bar.

## Requirement

### Sheet structure

1. Pressing the Demosaic icon opens a `ModalBottomSheet` (≈ 72 % of screen height) with a `TabRow` of
   exactly two tabs. The sheet replaces the old `DropdownMenu` entirely — no dropdown remains.
2. The left tab is always **Recent** (近期使用); the right tab is the full catalogue
   (**选择算法 / Choose algorithm**).

### Recent history

3. Capacity is **60** entries. Older Android hardware cannot show 60 rows at once, so the Recent list
   **must scroll**.
4. The history is **persisted** across process death and app reinstall-free launches (its own
   DataStore `studio_demosaic_recent`, see `DemosaicRecentStore`). Each entry is the candidate **id**
   (`DemosaicCandidate.id`, e.g. `rawler:ppg`), not the display label — ids are locale- and
   rename-stable, matching how `StudioEngine` persists the choice (`developPreference.setDemosaicId`).
5. **FIFO eviction** — the newest entry is always last; when the list exceeds 60 the oldest (first)
   entry is dropped.
6. **De-duplication on push** — when the user makes a choice, push only if the chosen id is *not* already
   the most-recent entry. Re-selecting the entry that is already last must **not** push a duplicate; a
   previously-used id that is not last has its **stale occurrence deleted immediately at push time**
   (not deferred until the 60-capacity eviction) and is then appended as the most-recent entry, so the
   Recent list never shows a duplicate. (This mirrors the LOG / LUT picker's rule 6.)
7. Every real pick is recorded, **including the default** (`rawler:default`, "CFA auto"). Demosaic has no
   "none" state — a selection is always active — so unlike the LOG / LUT picker there is no entry that
   must be excluded from the history.

### Scroll position

8. The sheet **opens on the Recent tab**, and the Recent list is scrolled to the **last** (most-recent)
   row — i.e. the newest item is shown first, the older items are above it.
9. Switching to the **Choose algorithm** tab scrolls the list to the **first** row (in the native
   catalogue order).
10. Switching **back** to the Recent tab scrolls the list to the **last** (most-recent) row again.

### Choose algorithm tab

11. Lists the **full native catalogue** `StudioEngine.demosaicCandidates` in its **original (dropdown)
    order**, **uncapped** (no 60 limit). First row is whatever the catalogue lists first
    (`rawler:default`), not a synthetic "none".
12. The superpixel entry (`id == SUPERPIXEL_ID == "rawler:superpixel"`) is **disabled** (greyed) when
    `superpixelSupported == false`, exactly as the old dropdown did — picking it on an unsupported
    sensor would silently fall back to the CFA default. The same `enabled` gate applies in the Recent
    tab (a previously-picked superpixel that the current sensor cannot run stays disabled).
13. Tapping an enabled entry applies it (`StudioEngine.develop`) and pushes its id to the Recent history,
    then closes the sheet.
14. The current selection is highlighted in **both** tabs (`id == currentDemosaicId`), tinted with the
    primary colour, consistent with the LOG / LUT sheets.

## Constraints

- The history store is its **own** `DataStore` file (`studio_demosaic_recent`). It must not reuse the
  name of `StudioDevelopPreference` (`studio_develop_prefs`), `MediaPreference` (`studio_prefs`), or the
  LOG / LUT `GradeRecentStore` (`studio_grade_recent`) — two `preferencesDataStore` delegates on the
  same name throw.
- Order is load-bearing (FIFO eviction depends on it), so the history is a single newline-joined string
  preference, **not** a `Set` (which is unordered).
- The catalogue is sourced from the native catalogue (`StudioEngine.demosaicCandidates`), never
  hand-written here, so a kernel ported in `rawtrp_demosaic`/`rawler` appears in both the picker and the
  pipeline (mirrors `FOTLAB-NATIVE-000004` D5).
- The screen still owns the dialogs/pickers (`FOTLAB-UIXDES-000002`); this change only swaps the demosaic
  *selection* surface, it does not move the develop engine out of `StudioEngine`.
- Capacity 60 is a product decision, not a UI limit — the list scrolls rather than truncates.
- The tab strings **复用** the LOG / LUT picker's `studio_grade_recent` (近期使用) and
  `studio_grade_tab_algorithm` (选择算法) rather than adding duplicates with identical text.

## Acceptance Criteria

- AC1: Pressing Demosaic opens a bottom sheet (not a dropdown) with tabs **近期使用** and **选择算法**.
- AC2: After picking any algorithm (including the default), re-pressing the button shows that id as the
  **last** row of the Recent tab (scrolled into view) on next open.
- AC3: The Recent history survives process death (kill + relaunch) and shows the same ordering.
- AC4: Selecting the value that is already the most-recent entry does **not** duplicate it in the history
  (list size unchanged, order unchanged).
- AC5: When the history holds 60 entries, the 61st choice drops the oldest (first) entry; size never
  exceeds 60.
- AC6: Choosing the same value that sits *not* at the end moves it to the end (most-recent) without
  creating a duplicate.
- AC7: The sheet opens scrolled to the last Recent row; switching to the choose tab shows the first
  catalogue row; switching back to Recent shows the last row again.
- AC8: The **选择算法** tab lists the full native catalogue in its original order (uncapped), with the
  superpixel entry disabled on unsupported sensors and the current selection highlighted.
- AC9: The current selection is highlighted in the Recent tab too (a previously used entry that is still
  active shows primary-tinted).

## Impacted Modules

- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioDemosaicSheet.kt` — **new**:
  `DemosaicRecentStore` (persistence) + `DemosaicPickerSheet`; `demosaicLabel` and `SUPERPIXEL_ID` move
  here from `StudioOpBarButtons.kt`.
- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioOpBarButtons.kt` — `DemosaicButton`
  hosts the sheet instead of a `DropdownMenu`; drops `demosaicLabel` / `SUPERPIXEL_ID` (moved).
- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioOpBars.kt` —
  `StudioOperationBarDevelopFilm` gains `currentDemosaicId` and forwards it to `DemosaicButton`.
- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt` — exposes
  `currentDemosaicId: Flow<String?>` (read through `developPreference.demosaicId`) for the highlight.
- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioScreen.kt` — collects
  `currentDemosaicId` and passes it to the bar.
- `app/src/androidTest/.../RawRoutingTest.kt` — `developThroughUserJourney` must switch to the
  **选择算法** tab before asserting on / tapping `studio_demosaic_ppg` (mirrors the LOG / LUT test fix).
- Strings **reused**: `studio_grade_recent`, `studio_grade_tab_algorithm` (no new strings).

## Open Questions

- Q1: Whether the default (`rawler:default`, "CFA auto") should be recorded in the history — decided
  **yes**: demosaic has no "none", every pick is a real, re-applicable choice, so recording the default
  keeps the history complete and lets the user re-apply it from Recent. Implemented by pushing the id on
  every `applyAndClose`. Closed.

## Change History

- 2026-10-15 — Initial draft. Extracted the Demosaic on-press behaviour into `StudioDemosaicSheet.kt`;
  replaced the `DropdownMenu` with a two-tab `ModalBottomSheet` backed by a persisted, FIFO,
  de-duplicated 60-entry Recent history (`DemosaicRecentStore`), reusing the LOG / LUT tab strings.
