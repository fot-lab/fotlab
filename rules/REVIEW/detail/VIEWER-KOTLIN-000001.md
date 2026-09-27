# Library viewer is a navigation destination, not a Dialog — bar inset fixed at the architecture level

- ID: VIEWER-KOTLIN-000001
- Status: Implemented
- Priority: P1
- Created: 2026-09-27
- Owner: —
- Related: `FOTLAB-UIXDES` (viewer layout), `ACTION-KOTLIN-000003` (Compose state and lifecycle deviations)

## Background & Goal

The full-screen image/video viewer (`LibraryViewerDialog`) was a `Dialog` overlay hosted inside
`LibraryScreen` (and `LibraryScreenRecycleScreen`'s recycle views). The user reported on a real
device that the viewer's bottom operation bar sat **below the drawable region** — clipped by the
system navigation bar. Two inset patches to the Dialog (`ec5c9fd`, `497fbb9`) did not fix it.

Human decision (2026-09-27): stop patching the Dialog and mirror the StudioScreen architecture —
the viewer becomes a full-screen navigation destination that owns its own fun bar.

## Finding

A Compose `Dialog` runs in its own window, which does not receive the activity's
`WindowInsets.navigationBars` the way the main window's composition does. Any bar placed inside the
Dialog content cannot consume the navigation-bar inset, so it is laid out under the system
navigation bar and clipped. This is not fixable by padding tweaks on the Dialog content — the
inset information is absent from that window. The two prior inset patches were therefore no-ops on
device.

## Impact / Conflict

- **Impact**: the fix removes the Dialog hosting from both library screens, replaces the hand-off
  (constructor parameters) with a process-scoped session in `LibraryCore`, and deletes
  `LibraryViewerDialog.kt` + `OverlayOperationBar.kt` (done in the same change, not separately).
- **Conflict**: none with existing rules. `FOTLAB-UIXDES-000002` R3's nested-Scaffold conditions
  (a)–(c) are satisfied the same way `LibraryScreen` already satisfies them: the viewer's Scaffold
  consumes the navigation-bar inset exactly once, and the fun bar stays module-owned.

## Recommendation

Adopted (implemented in the working tree, pending manual commit):

1. **Viewer as destination** — `LibraryViewerScreen` composes a module-level M3 `Scaffold` with
   `contentWindowInsets = WindowInsets.navigationBars` and its own `ViewerFunBar` in the
   `bottomBar` slot (close button, `page / count` indicator, open-in-Studio, info toggle),
   exactly mirroring `StudioScreen`. Route `library/viewer` registered as `ViewerDestination` in
   `LibraryGraph`.
2. **Session hand-off** — `LibraryCore.viewerSession` (`ViewerSession(items, startIndex)`,
   `MutableStateFlow`, process-scoped like `selection`): the grid writes it the moment a tile is
   tapped, then calls the new `onOpenViewer` callback; the destination reads it back. A 1-second
   wait covers the first-frame `null` of `collectAsState` (and process restore straight into the
   destination dismisses).
3. **Screens slimmed** — `LibraryScreen` / `LibraryScreenRecycle` lose their local
   `viewerItems`/`viewerStart` state and Dialog blocks; the recycle screen's
   `onNavigateToStudio` parameter is replaced by `onOpenViewer`.
4. **Smoke tests follow the real path** — `ZoomableGestureTest` tests 4/5 now compose
   `LibraryViewerScreen` through the same session hand-off the navigation uses; method names kept
   (CI selectors reference them).

## Pending Deletions (this change)

The refactor leaves the two files below unreferenced. **They have NOT been deleted yet** — the
agent's environment blocks `rm`/`git rm`, so the deletion is deliberately left to the human and
must happen at commit time of this change:

- [ ] `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt` — the old
  Dialog-based viewer; after the refactor no references remain outside itself
  (`grep -r LibraryViewerDialog app/src` hits only this file).
- [ ] `app/src/main/kotlin/io/github/fotlab/fotlab/ui/operation/OverlayOperationBar.kt` — the old
  overlay bar; referenced only by `LibraryViewerDialog.kt`.

Until both are deleted, the tree still compiles (dead code only), but the refactor is incomplete:
committing without these deletions would leave the Dialog implementation in the codebase.

## Deletion Status (completed 2026-09-27)

Appended as a follow-up record — the "Pending Deletions" section above is left as the original
historical note, with its checkboxes deliberately not rewritten. The work it required is now done:

- [x] `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt` — **deleted**.
- [x] `app/src/main/kotlin/io/github/fotlab/fotlab/ui/operation/OverlayOperationBar.kt` — **deleted**.

Post-deletion verification: both files are gone from the tree (`git status` shows `D`), and a
whole-repo grep for `LibraryViewerDialog` / `OverlayOperationBar` across `app/src` returns zero
hits outside the strings already renamed in the smoke tests' comments. The refactor is now
complete: no Dialog-based viewer implementation remains.

Also removed in the same pass: the never-called `LibraryCore.clearViewerSession()` dead function
(the session is process-scoped and overwritten on every open; nothing ever cleared it).

**Scope note**: the deletion recorded here covers exactly the two files above plus the one
dead-code line. Any code added to the viewer afterwards is subsequent work, authored later — it
is NOT part of this deletion and must not be attributed to it.

## Change History

- 2026-09-27 — Created. Human directed the viewer-as-screen refactor after two failed Dialog
  inset patches; refactor implemented across 8 files (7 edited + 1 new) plus the two files to be
  deleted at commit time (see Pending Deletions).
- 2026-09-27 — Added the explicit "Pending Deletions" section listing the two files awaiting
  manual removal, per human request.
- 2026-09-27 — Executed the pending deletion: removed `LibraryViewerDialog.kt` and
  `OverlayOperationBar.kt` (zero references remain), removed the dead `clearViewerSession()`
  function, and appended the "Deletion Status" section. Later code added to the viewer is
  separate, subsequent work — not part of this deletion.
