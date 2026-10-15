# LOG / LUT picker — two-tab bottom sheet with persisted Recent history

- ID: FOTLAB-LOTLUT-000001
- Status: Draft
- Priority: P2
- Created: 2026-10-15
- Owner: —
- Related: FOTLAB-UIXDES-000002

## Background & Goal

The Studio grade bar exposes two tools — **LOG** (log curve) and **LUT** (3D filter file) — whose
pickers were `DropdownMenu`s anchored to the tool icon. The LOG menu was a flat list of
`none` + every curve rawalchemy enumerates; the LUT menu was `Choose file…` (SAF) + `None (remove
LUT)`.

That shape has one structural flaw: it has no memory of what the user picked before, so re-applying a
previously used algorithm or filter file means re-walking the full catalogue or re-opening the system
file picker every time. The goal is to add a **persisted "recently used" history** and surface it as
the default view, while keeping the full catalogue / file picker one tap away.

We replace each dropdown with a `ModalBottomSheet` containing two tabs:

- **LOG** — left tab **近期使用 / Recent** (persisted FIFO history of chosen curves); right tab
  **选择算法 / Choose algorithm** (`none` + the full native catalogue, uncapped).
- **LUT** — left tab **近期使用 / Recent** (persisted FIFO history of picked filter file URIs); right
  tab **选择滤镜 / Choose filter** (`none` + the SAF file picker).

The on-press behaviour (the sheets plus their history store) is extracted into
`app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioLogLutSheets.kt` so the picker UI and
its persistence live in one place, separate from the icon buttons in `StudioOpBarButtons.kt`.

## Requirement

### Sheet structure

1. Pressing the LOG / LUT icon opens a `ModalBottomSheet` (≈ 72 % of screen height) with a `TabRow`
   of exactly two tabs. The sheet replaces the old `DropdownMenu` entirely — no dropdown remains.
2. The left tab is always **Recent** (近期使用); the right tab is the full picker
   (**选择算法** for LOG, **选择滤镜** for LUT).

### Recent history (both pickers)

3. Capacity is **60** entries. Older Android hardware cannot show 60 rows at once, so the Recent list
   **must scroll**.
4. The history is **persisted** across process death and app reinstall-free launches (DataStore
   `studio_grade_recent`, see `GradeRecentStore`).
5. **FIFO eviction** — the newest entry is always last; when the list exceeds 60 the oldest (first)
   entry is dropped.
6. **De-duplication on push** — when the user makes a choice, push only if the chosen value is *not
   already the most-recent entry*. Re-selecting the entry that is already last must **not** push a
   duplicate; a previously-used value that is not last is moved to the end (becomes most-recent)
   rather than duplicated. (This is the "只在最近一次使用的算法和上一次不同时进行压栈" rule.)
7. The `none` selection ("无" / remove) is **not** recorded in the Recent history — only real algorithm
   names (LOG) and real filter file URIs (LUT) are pushed. Picking `none` applies the clear and closes
   the sheet, but leaves the history untouched.

### Scroll position

8. The sheet **opens on the Recent tab**, and the Recent list is scrolled to the **last** (most-recent)
   row — i.e. the newest item is shown first, the older items are above it.
9. Switching to the **Choose algorithm / Choose filter** tab scrolls the list to the **first** row
   (the `none` entry).
10. Switching **back** to the Recent tab scrolls the list to the **last** (most-recent) row again.

### LOG — Choose algorithm tab

11. First row is `none` (无); following rows are the full native algorithm catalogue
    (`StudioEngine.supportedLogSpaces()`), **uncapped** (no 60 limit).
12. Tapping an entry applies it (`setGradeLogSpace`) and pushes it to the Recent history, then closes
    the sheet.

### LUT — Choose filter tab

13. First row is `none` (无（移除 LUT）), which removes the LUT (`clearGradeLut`) and closes the sheet
    **without** touching the history. Second row is `Choose file…` (选择文件), which opens the SAF picker
    and closes the sheet; the chosen file is pushed to the Recent history when the picker returns
    (handled in `StudioScreen`).
14. Tapping a URI in the Recent history re-applies that filter file (`setGradeLut` with the persisted
    URI) and pushes it to the history, then closes the sheet. The persisted URI permission must keep
    working across process death (already taken via `persistUriPermission`).

## Constraints

- The history store is its **own** `DataStore` file (`studio_grade_recent`). It must not reuse the
  name of `StudioDevelopPreference` (`studio_develop_prefs`) or `MediaPreference` (`studio_prefs`) —
  two `preferencesDataStore` delegates on the same name throw.
- Order is load-bearing (FIFO eviction depends on it), so each history is a single newline-joined
  string preference, **not** a `Set` (which is unordered).
- The LOG algorithm list is sourced from the native catalogue (`StudioEngine.supportedLogSpaces()`),
  never hand-written here, so a kernel ported in `rawtrp_demosaic`/`rawler` appears in both the picker
  and the pipeline (mirrors `FOTLAB-NATIVE-000004` D5).
- The screen still owns the dialogs/pickers (`FOTLAB-UIXDES-000002`); this change only swaps the LOG /
  LUT *selection* surface, it does not move the SAF launcher or the grade engine out of `StudioScreen`
  / `StudioEngine`.
- Capacity 60 is a product decision, not a UI limit — the list scrolls rather than truncates.

## Acceptance Criteria

- AC1: Pressing LOG opens a bottom sheet (not a dropdown) with tabs **近期使用** and **选择算法**.
- AC2: Pressing LUT opens a bottom sheet (not a dropdown) with tabs **近期使用** and **选择滤镜**.
- AC3: After choosing a real algorithm / filter file (but **not** `none`), re-pressing the button shows
  that value as the **last** row of the Recent tab (scrolled into view) on next open. Selecting `none`
  is never reflected in the Recent history.
- AC4: The Recent history survives process death (kill + relaunch) and shows the same ordering.
- AC5: Selecting the value that is already the most-recent entry does **not** duplicate it in the
  history (list size unchanged, order unchanged).
- AC6: When the history holds 60 entries, the 61st choice drops the oldest (first) entry; size never
  exceeds 60.
- AC7: Choosing the same value that sits *not* at the end moves it to the end (most-recent) without
  creating a duplicate.
- AC8: The sheet opens scrolled to the last Recent row; switching to the choose tab shows the `none`
  entry first; switching back to Recent shows the last row again.
- AC9: The **选择算法** tab lists `none` + the full algorithm catalogue (more than 60 entries if the
  catalogue ever exceeds 60 — no cap applied).
- AC10: Re-tapping a URI in the LUT Recent history re-applies that filter without re-opening the SAF
  picker, and works after process death (persisted URI permission).

## Impacted Modules

- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioLogLutSheets.kt` — **new**:
  `GradeRecentStore` (persistence) + `LogPickerSheet` + `LutPickerSheet`.
- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioOpBarButtons.kt` — `LogButton` /
  `LutButton` now host the sheet instead of a `DropdownMenu`.
- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioOpBars.kt` —
  `StudioOperationBarStyleFilter` gains `onApplyLutUri` and forwards it to `LutButton`.
- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioScreen.kt` — owns the SAF launcher,
  pushes the freshly picked LUT URI into `GradeRecentStore`, and passes `onApplyLutUri`.
- `app/src/main/res/values/strings.xml`, `app/src/main/res/values-zh/strings.xml` — new strings
  `studio_grade_recent`, `studio_grade_tab_algorithm`, `studio_grade_tab_filter`.

## Open Questions

- Q1: The instrumented smoke tests (`app/src/androidTest/.../RawRoutingTest.kt`) asserted on the old
  dropdown text/labels. They have been updated: after opening the LOG / LUT picker the journeys now
  switch to the **Choose algorithm** / **Choose filter** tab before asserting on / tapping the
  catalogue or file-picker item (`studio_grade_tab_algorithm`, `studio_grade_tab_filter`), matching the
  new bottom-sheet structure. Closed.
- Q2: Whether `none` should be recorded in the history — decided **no**: picking `none` applies the
  clear and closes the sheet but never pushes to the Recent stack (see rule 7 / AC3). Implemented by
  guarding the push call sites in `StudioLogLutSheets.kt` (`applyAndClose` for LOG, `clearAndClose` for
  LUT) and making `GradeRecentStore.push*` take a non-null value. Closed.

## Change History

- 2026-10-15 — Initial draft. Extracted LOG / LUT on-press behaviour into
  `StudioLogLutSheets.kt`; replaced both `DropdownMenu`s with two-tab `ModalBottomSheet`s backed by a
  persisted, FIFO, de-duplicated 60-entry Recent history (`GradeRecentStore`).
