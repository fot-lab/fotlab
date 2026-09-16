# Compose state and lifecycle deviations — navigation state not observable, startup blocking, no state holder

- ID: ACTION-KOTLIN-000003
- Status: Observation
- Priority: P1
- Created: 2026-09-16
- Owner: —
- Related: `ACTION-KOTLIN-000001` (master audit), `rules/DESIGN/detail/FOTLAB-UIXDES-000004.md` (R3 process-scoped selection, R9 layout mode, R10 refresh), `rules/STRUCT/detail/FOTLAB-STRUCT-000001.md`, `app/src/main/kotlin/io/github/fotlab/fotlab/ui/MainWindowFrame.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryCore.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreen.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreenRecycle.kt`

## Background & Goal

The app's layering is clean and its process-scoped state (`LibraryCore.selection`, `layoutMode`, `currentDirectoryId`, `StudioEngine.renderResult`) is a deliberate, documented decision — `FOTLAB-UIXDES-000004` R3 explicitly wants it process-scoped and never restored. This review does **not** ask for that to change.

It does check the four places where the code departs from official Android / Compose guidance in ways that produce wrong behaviour or measurable startup cost, independent of that decision.

## Finding

### 1. Bottom navigation selected state is not observable (K-02)

```34:36:app/src/main/kotlin/io/github/fotlab/fotlab/ui/MainWindowFrame.kt
                currentRoute = { navController.currentDestination?.route },
```

`NavController.currentDestination` is a plain property over the back stack, not Compose snapshot state. Reading it in `MainNavigationBar` establishes no subscription, so the bar's `selected` flag is frozen at whatever it was on the first composition. The official Navigation Compose guidance requires `navController.currentBackStackEntryAsState()` (or collecting `currentBackStackEntryFlow`).

The lambda shape (`currentRoute: () -> String?`) hides this: it looks like deferred state, but calling it is still an untracked read.

### 2. `Application.onCreate` blocks on the first DataStore read (K-03)

```110:111:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryCore.kt
        // Restore the persisted mode once at start; default is Grid 3 (R9).
        layoutModeState.value = runBlocking(Dispatchers.IO) { layoutPreference.mode.first() }
```

DataStore's first read touches disk. Doing it with `runBlocking` inside `Application.onCreate` puts that disk wait on the main thread and counts against cold start. The value is already exposed as a `Flow` that the UI observes, so blocking to pre-seed it buys nothing.

`prepare` is guarded by `if (repository != null) return`, so an async collect cannot double-subscribe.

### 3. Fifteen `collectAsState`, zero `collectAsStateWithLifecycle` (K-04)

The full sweep of `collectAsState(...)` call sites: `LibraryScreen.kt:154,155,156,167`, `LibraryScreenRecycle.kt:360,361,419,420`, `LibraryViewerDialog.kt:97`, `StudioScreen.kt:78`, and the rest of the feature screens. `androidx.lifecycle.runtime.compose` is already a declared dependency and is unused; on Android, collecting a `Flow` in Compose should use `collectAsStateWithLifecycle` so the Room / DataStore subscription stops below `STARTED`.

### 4. No state holder; nothing survives a configuration change (K-05)

`LibraryScreen` holds eight `remember { mutableStateOf }` values (`currentDirectory`, `viewMode`, `deleteConfirmation`, `deleteInvalid`, `renameTarget`, `viewerItems`, `viewerStart`, drawer state) and `LibraryRecycleScreen` holds six more. `androidx.lifecycle.viewmodel.compose` is declared and unused.

Rotation or process recreation resets the user to the library root, closes the open viewer and drops any pending dialog. That is the documented choice for the *shared* selection (`FOTLAB-UIXDES-000004` R3/C6), but it was never a decision for navigation depth or dialog state — the spec says nothing, so the platform default (survive) should apply.

### 5. Derived data pushed back up through a callback (K-14, P2)

`RecycleRoot` and `RecycleBatch` report their visible ids upward:

```441:442:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreenRecycle.kt
    val visibleIds = currentChildren.mapNotNull { it.fsNodeId }
    LaunchedEffect(visibleIds) { onVisibleIds(visibleIds) }
```

`visibleIds` is a new `List` on every recomposition, so the effect re-fires every recomposition even when the contents are identical. Guidance is the reverse: hoist the inputs and derive the value in the parent.

## Impact / Conflict

- (1) is a visible defect: the bottom bar highlight stops tracking navigation.
- (2) is measurable on cold start and is the kind of thing that turns into an ANR on a slow device.
- (3) keeps Room / DataStore subscriptions alive while the app is backgrounded.
- (4) conflicts with no written rule, but contradicts the platform default; it will need an explicit decision either way.
- None of these touch the soft-delete data model, the one-level rendering rule (`ACTION-LIBRND-000001`) or the process-scoped selection contract — all three stay as they are.

## Recommendation

1. In `MainWindowFrame`, read `val entry by navController.currentBackStackEntryAsState()` and pass `entry?.destination?.route` down. Change `MainNavigationBar`'s parameter from `currentRoute: () -> String?` to a plain `currentRoute: String?` — the lambda adds indirection without deferring anything.
2. Replace the `runBlocking` in `LibraryCore.prepare` with `ioScope.launch { layoutPreference.mode.collect { layoutModeState.value = it } }`.
3. Sweep `collectAsState` → `collectAsStateWithLifecycle`, checking each `initial` value still means the same thing (this is the only part that can change behaviour — do it per call site, not with a blind replace).
4. Adopt `rememberSaveable` for navigation depth and dialog flags (`FsNodeObject` needs a `Saver`, or store only `fsNodeId`). Defer a full ViewModel migration; when it happens, it applies to both features at once and belongs in its own item.
5. Derive `candidateIds` in `LibraryRecycleScreen` from `location` instead of having children report it, or at minimum `remember` the list used as the `LaunchedEffect` key.

## Change History

- 2026-09-16 — Recorded from the full-source Kotlin audit (`ACTION-KOTLIN-000001`). Five findings: unobservable `currentDestination` (P1), `runBlocking` DataStore read in `Application.onCreate` (P1), fifteen `collectAsState` with no `collectAsStateWithLifecycle` (P1), no state holder / no `rememberSaveable` (P1), and derived ids pushed up through a per-recomposition `LaunchedEffect` (P2). Explicitly does not challenge the process-scoped selection decision of `FOTLAB-UIXDES-000004` R3. No code changed.
