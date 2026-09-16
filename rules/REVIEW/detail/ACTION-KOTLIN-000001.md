# First-party Kotlin / Compose code audit — scope, method and the complete finding index

- ID: ACTION-KOTLIN-000001
- Status: Observation
- Priority: P2
- Created: 2026-09-16
- Owner: —
- Related: `ACTION-KOTLIN-000002` (P0 refresh defect), `ACTION-KOTLIN-000003` (Compose state & lifecycle), `ACTION-KOTLIN-000004` (duplication), `ACTION-KOTLIN-000005` (control flow), `ACTION-KOTLIN-000006` (localization), `ACTION-KOTLIN-000007` (platform & data layer), `rules/STRUCT/detail/FOTLAB-STRUCT-000001.md`

## Background & Goal

After the large CI/test-driven rework landed green, the whole first-party Kotlin source was reviewed for three questions:

1. Does it follow Google's official Android / Compose best practices?
2. Are there `if` branches nested deeply enough that they should be flattened into a `when` / sealed dispatch?
3. Are there duplicated blocks worth extracting?

This file is the **master record** of that audit: scope, method, statistics, what was confirmed correct, and the complete index of all findings with their location and severity. Each actionable cluster is then tracked as its own item (`ACTION-KOTLIN-000002` … `000007`) so it can be approved, implemented and closed independently.

No code is changed by this review.

## Scope

- **Included** (`app/src`, 35 files, ~5 500 lines):
  - `app/src/main/kotlin` — 30 files
  - `app/src/binding/kotlin` — 1 file (`RawlerFotlabBridge.kt`, the only hand-written file in that package)
  - `app/src/androidTest/kotlin` — 4 files
- **Excluded**:
  - `external/` — upstream constraint, out of scope by `rules/REVIEW.md` principle 5
  - `log/rawler_fotlab/kotlin`, `tmp/ci/*/rawler_fotlab/kotlin` — UniFFI generated output / build artifacts, not source
  - `app/build/generated/uniffi/main/kotlin` — generated, never tracked (`FOTLAB-STRUCT-000002` R1)

Line references below are against the tree as of 2026-09-16.

## Method

Full read of every file above (not a sample), cross-checked against:

- Android architecture guidance (UI layer, state holders, configuration change)
- Compose API guidance (state hoisting, `collectAsStateWithLifecycle`, list `key`, side effects)
- Navigation Compose guidance (`currentBackStackEntryAsState`)
- Room guidance (transactions, main-thread queries, primary keys)
- Android Lint / ktlint categories that apply (`SetTextI18n`, `DefaultLocale`, `SimpleDateFormat`, unused symbols)
- `gradle.properties` (`kotlin.code.style=official`) and the declared dependency set

## Statistics

| Severity | Count |
|---|---|
| P0 (blocker) | 1 |
| P1 (high) | 6 |
| P2 (normal) | 12 |
| P3 (nice to have) | 11 |
| **Total** | **30** |
| Control-flow sites worth flattening | 6 |
| Duplication groups worth extracting | 14 |

## Finding index

Severity is per finding; "Tracked by" names the item that owns the analysis and the fix.

| # | Sev | Location | Finding | Tracked by |
|---|---|---|---|---|
| K-01 | P0 | `feature/library/LibraryCore.kt:210` | `uriExists` tests `cursor.count >= 0`, which is always true; `refresh()` therefore never detects a deleted source file and the R10 reconciliation is a no-op | `ACTION-KOTLIN-000002` |
| K-02 | P1 | `ui/MainWindowFrame.kt:35` | Bottom bar reads `navController.currentDestination`, which is not snapshot state — the selected tab never recomposes | `ACTION-KOTLIN-000003` |
| K-03 | P1 | `feature/library/LibraryCore.kt:111` | `runBlocking(Dispatchers.IO)` reads DataStore inside `Application.onCreate`, blocking cold start | `ACTION-KOTLIN-000003` |
| K-04 | P1 | 15 call sites | 15 × `collectAsState`, 0 × `collectAsStateWithLifecycle` although `lifecycle-runtime-compose` is a declared dependency | `ACTION-KOTLIN-000003` |
| K-05 | P1 | `LibraryScreen.kt:136-149`, `LibraryScreenRecycle.kt:93-104` | No ViewModel and no `rememberSaveable`; every screen state is lost on rotation / process recreation | `ACTION-KOTLIN-000003` |
| K-06 | P1 | `LibraryViewerDialog.kt:370,376,387,395,421-431` | User-facing EXIF detail values hard-coded in English while every other string goes through `stringResource` | `ACTION-KOTLIN-000006` |
| K-07 | P1 | `feature/studio/StudioEngine.kt:146` ↔ `androidTest/…/PngEndToEndFlowTest.kt:350` | `InputStream.readHeader` implemented twice, character for character; the test copies the product implementation | `ACTION-KOTLIN-000007` |
| K-08 | P2 | `LibraryViewerDialog.kt:213` | `android.widget.VideoView` + `MediaController` in `AndroidView` instead of Media3 / ExoPlayer; playback keeps running while a pager keeps the page composed | `ACTION-KOTLIN-000007` |
| K-09 | P2 | `LibraryScreen.kt:626`, `LibraryScreenRecycle.kt:75`, `LibraryViewerDialog.kt:443` | Three shared `SimpleDateFormat` instances — not thread-safe; `java.time` is available at `minSdk = 26` | `ACTION-KOTLIN-000006` |
| K-10 | P2 | `LibraryViewerDialog.kt:364,410` | `"%.5f".format()` / `"%d:%02d".format()` with implicit default `Locale` | `ACTION-KOTLIN-000006` |
| K-11 | P2 | `feature/library/FsNodeObjectDao.kt:30` | SQL hard-codes `'application/folder'` while `LibraryCore` defines `const val MimeCollection` — the two can drift silently | `ACTION-KOTLIN-000007` |
| K-12 | P2 | `feature/library/FsNodeObject.kt:25` | Nullable `@PrimaryKey` forces `?.let` / `mapNotNull` / `?: nameDisplay` at ~30 call sites | `ACTION-KOTLIN-000007` |
| K-13 | P2 | `feature/library/LibraryRepository.kt:187-198` | `markDeleted` issues one `activeParentCount` query per child relation — N+1 inside a transaction | `ACTION-KOTLIN-000007` |
| K-14 | P2 | `LibraryScreenRecycle.kt:364,442` | `onVisibleIds` writes derived data back up from a child via `LaunchedEffect`, and the key is a freshly built `List` on every recomposition | `ACTION-KOTLIN-000003` |
| K-15 | P2 | `LibraryScreen.kt:93` ↔ `feature/studio/StudioScreen.kt:232` | Drawer width `0.8f` defined independently in two files | `ACTION-KOTLIN-000004` |
| K-16 | P2 | `LibraryScreen.kt:757` | `when (layoutMode)` uses `else`, swallowing `Grid1` / `Grid2` / `Grid3` — the compiler cannot flag a new mode | `ACTION-KOTLIN-000005` |
| K-17 | P2 | `media/FormatSniffer.kt:83` | `Executors.newCachedThreadPool` is unbounded and never shut down | `ACTION-KOTLIN-000007` |
| K-18 | P2 | `LibraryCore.kt` ↔ `StudioEngine.kt` | Two `object` singletons with identical `prepare(context)` / `lateinit` / `CoroutineScope(SupervisorJob() + IO)` / `MutableStateFlow` plumbing; no DI | `ACTION-KOTLIN-000007` |
| K-19 | P2 | `feature/library/LibraryCore.kt:198` | `val now = System.currentTimeMillis()` declared and never used | `ACTION-KOTLIN-000002` |
| K-20 | P3 | `LibraryScreen.kt:87-90`, `LibraryScreenRecycle.kt:19` | Import order violates `kotlin.code.style=official` | `ACTION-KOTLIN-000005` |
| K-21 | P3 | `LibraryScreen.kt:194-233` | Block body under-indented by 4 spaces | `ACTION-KOTLIN-000005` |
| K-22 | P3 | `LibraryScreen.kt:297,354`, `LibraryScreenRecycle.kt:187` | `!!` forced unwrap immediately after a `!= null` check | `ACTION-KOTLIN-000004` |
| K-23 | P3 | `feature/studio/StudioEngine.kt:129` | `Constants` nested object is inconsistent with every other top-level `private const` | `ACTION-KOTLIN-000005` |
| K-24 | P3 | `LibraryScreen.kt:793` | `indication = null` disables the ripple in the grid branch but not in the `ListItem` branch | `ACTION-KOTLIN-000004` |
| K-25 | P3 | `LibraryScreen.kt:812` | `Checkbox` nested inside a clickable `Card` — two click targets | `ACTION-KOTLIN-000004` |
| K-26 | P3 | `LibraryScreen.kt:672-683` | `onTextLayout` measures and writes state — guarded, but a measurement loop risk | `ACTION-KOTLIN-000005` |
| K-27 | P3 | `PngEndToEndFlowTest.kt:119-156` ↔ `ZoomableGestureTest.kt:124-167` | MediaStore PNG fixture publish + cleanup duplicated across two test classes | `ACTION-KOTLIN-000004` |
| K-28 | P3 | `LibraryThumbnail.kt:92`, `LibraryScreen.kt:247,250`, `LibraryScreenRecycle.kt:463`, `LibraryViewerDialog.kt:130,132,313,315` | "is this media, and image or video" expressed three different ways | `ACTION-KOTLIN-000005` |
| K-29 | P3 | `LibraryLayoutPreference.kt` ↔ `media/MediaPreference.kt` | Two DataStore preference classes with identical `dataStore` / `map` / `edit` plumbing | `ACTION-KOTLIN-000004` |
| K-30 | P3 | `feature/library/LibraryRepository.kt:94-108` ↔ `240-261` | Two hand-rolled BFS walks (`ArrayDeque` + `visited`) | `ACTION-KOTLIN-000004` |

## Confirmed correct — do not change

Recorded so a later refactor does not "fix" these away:

- **Layering.** `feature/{screen, core, repository, dao}` is strict; `LibraryRepository` never depends on `ui` or `navigation` (`FOTLAB-STRUCT-000001`).
- **Room discipline.** Every write is `suspend`; reads are `Flow`; no `allowMainThreadQueries`; all multi-statement mutations wrapped in `withTransaction` (`FOTLAB-DATABS-000001` R4).
- **SQLite NULL semantics.** `insertRootLinkIfAbsent`'s `NOT EXISTS` guard and `removeRelation`'s `IS NULL` branch are both correct and both documented — this is the easiest thing in the schema to get wrong.
- **Sniff timeout contract.** `FormatSniffer` degrades a *throwing* sniffer to an empty `Verdict` while treating *timeout* as a hard error, and orphans daemon worker threads because neither `BitmapFactory` nor the native call can be interrupted. Correct, and the reasoning is in the KDoc.
- **Sealed modelling.** `RecycleLocation`, `SniffResult`, `Route`, `StudioRenderResult` all make `when` exhaustive.
- **Coil `Size.Unspecified` guard.** `Size.isUnmeasured` in `ui/ZoomableImage.kt` exists because the accessor throws; the comment records the crash it fixed.
- **List keys.** Both `LazyColumn` and `LazyVerticalGrid` pass `key`.

## Recommendation

Work the items in this order:

1. `ACTION-KOTLIN-000002` — one-line P0 fix, restores a behaviour that is currently dead.
2. `ACTION-KOTLIN-000003` — two correctness defects (navigation state, startup blocking).
3. `ACTION-KOTLIN-000004` — the four largest duplication groups; pure structural moves, ~200 lines removed, zero behaviour change.
4. `ACTION-KOTLIN-000006` — mechanical: `collectAsStateWithLifecycle` sweep and i18n.
5. `ACTION-KOTLIN-000005`, `ACTION-KOTLIN-000007` — cross-file refactors; one PR each.

## Change History

- 2026-09-16 — Audit recorded. Full read of all 35 first-party Kotlin files under `app/src` (main 30, binding 1, androidTest 4; ~5 500 lines), excluding `external/`, `log/` and `tmp/ci/` generated output. 30 findings (P0 ×1, P1 ×6, P2 ×12, P3 ×11), 6 control-flow sites, 14 duplication groups. Split into `ACTION-KOTLIN-000002` … `000007` for independent tracking; category `KOTLIN` added to `rules/REVIEW.md`; seven rows appended to `rules/REVIEW/index.md`. An earlier draft placed at `docs/review-kotlin-2026-09-16.md` was removed — review records belong under `rules/REVIEW/`.
