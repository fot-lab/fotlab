# Platform API and data layer — Media3, N+1 queries, nullable primary key, duplicated plumbing

- ID: ACTION-KOTLIN-000007
- Status: Observation
- Priority: P2
- Created: 2026-09-16
- Owner: —
- Related: `ACTION-KOTLIN-000001` (master audit), `rules/DESIGN/detail/FOTLAB-DATABS-000001.md`, `rules/DESIGN/detail/FOTLAB-DATABS-000002.md`, `rules/DESIGN/detail/FOTLAB-STUDIO-000001.md` (referenced in KDoc as FOTLAB-STUDIO-000001), `rules/STRUCT/detail/FOTLAB-STRUCT-000002.md`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryRepository.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/media/FormatSniffer.kt`

## Background & Goal

The data layer was reviewed as correct in the master audit: every write is `suspend`, every read is `Flow`, all multi-statement mutations run inside `withTransaction`, and the SQLite `NULL` edge cases are handled with documented care. This item collects what is left: places where the code uses an older platform API than the current guidance, a query pattern that will not scale, a schema choice that leaks nullability into the whole feature, and plumbing that exists twice.

## Finding

### 1. `VideoView` instead of Media3 (K-08, P2)

```212:227:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt
@Composable
private fun ViewerVideo(uri: Uri, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            android.widget.VideoView(ctx).apply {
                setVideoURI(uri)
                …
```

`android.widget.VideoView` + `MediaController` is the legacy playback path. Beyond being superseded by Media3 / ExoPlayer, the lifecycle here is weak: `onRelease = { it.stopPlayback() }` only fires when the page leaves the composition, and the viewer's `HorizontalPager` keeps neighbouring pages composed — so a video the user has paged away from keeps playing.

### 2. N+1 queries inside `markDeleted` (K-13, P2)

```187:198:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryRepository.kt
        for (relation in relationDao.relationsWithParent(nodeId)) {
            relationDao.update(relation.copy(timeDeleted = now))
            val childId = relation.fsNodeIdChild
            // Still has a live parent: it stays exactly where it is (R12 step 2c).
            if (relationDao.activeParentCount(childId) == 0) {
                pending.addLast(childId)
            }
        }
```

One `SELECT COUNT(*)` per child relation, inside a transaction, on the deletion path. Correct today; the cost grows with the number of children and is paid on every delete.

### 3. Nullable Room primary key (K-12, P2)

`FsNodeObject.kt:25` — `@PrimaryKey @ColumnInfo(name = "fs_node_id") val fsNodeId: Long? = null`. The nullable key is what lets SQLite assign the rowid, but it also makes every consumer deal with `null`: `?.let { … }`, `mapNotNull { it.fsNodeId }`, `it.fsNodeId ?: it.nameDisplay` as a lazy-list key, and `node.fsNodeId?.let { id -> when { … } }` wrapping whole blocks. It is roughly thirty sites across the feature, and it is one of the reasons several composables nest more deeply than they need to (see `ACTION-KOTLIN-000005`).

### 4. Duplicated `readHeader` (K-07, P1)

`StudioEngine.kt:146-155` and `PngEndToEndFlowTest.kt:350-359` are character-for-character identical, including the KDoc. A test that re-implements the product function cannot detect a regression in the product function.

### 5. Unbounded, never-shut-down thread pool (K-17, P2)

`FormatSniffer.kt:83` — `Executors.newCachedThreadPool` with a daemon thread factory. The design is deliberate and correct (a wedged sniffer must not hang the caller; daemon threads cannot hold the process open), but the pool has no upper bound and is never shut down, so repeated timeouts accumulate threads.

### 6. Two isomorphic feature cores (K-18, P2)

`LibraryCore` and `StudioEngine` are both `object` singletons with the same shape: `prepare(context)` → `lateinit var applicationContext` → `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` → `MutableStateFlow` + `asStateFlow()`. There is no DI. The duplication is small today with two features and grows with each new one.

### 7. Smaller items

- K-11 (P2) — `FsNodeObjectDao.kt:30` hard-codes `'application/folder'` in SQL while `LibraryCore` defines `const val MimeCollection`. Room's `@Query` needs a literal (KSP cannot interpolate a Kotlin constant), so the two cannot be merged mechanically — but they should at least cross-reference each other in a comment, or the query should become a `@RawQuery`.
- K-19 (P2) — `LibraryCore.kt:198`, `val now = System.currentTimeMillis()` declared and never used.

## Impact / Conflict

- Item 2 is the only one with a scaling cost, and only on the delete path.
- Item 3 is the widest-reaching: it is the root cause of null-handling noise throughout the library feature. Changing it is a schema migration, so it is a decision to make deliberately, not a drive-by fix — and it would interact with `MIGRATION_2_3`.
- Item 1 is user-visible (audio from a paged-away video).
- Item 4 is a test-quality defect and is cheap to fix.
- Nothing here conflicts with `FOTLAB-DATABS-000001` / `000002`; item 2 in particular is a performance refinement of the delete path, not a change to its semantics.

## Recommendation

1. Item 4 first — it is one line: drop `private` from `StudioEngine`'s `readHeader` (or move it to a `media` package file as `internal fun InputStream.readHeader(max: Int)`) and have `PngEndToEndFlowTest` call it instead of its own copy. This also feeds the shared test fixture in `ACTION-KOTLIN-000004` group C27.
2. Item 2: replace the per-child `activeParentCount` call with a single query that returns the children of `nodeId` whose live-parent count is zero (`GROUP BY … HAVING COUNT(*) = 0`), keeping everything inside the existing transaction.
3. Item 1: move `ViewerVideo` to Media3 `ExoPlayer` + `PlayerView` when the viewer is next touched; until then, pause in `onRelease` *and* when the pager moves off the page, which is the cheaper half of the fix.
4. Item 5: bound the pool (`ThreadPoolExecutor` with a small max size) rather than leaving `newCachedThreadPool` unbounded; keep the daemon factory and the orphan-on-timeout behaviour, which are correct.
5. Item 3 and item 6 are decisions, not fixes: record them, and schedule a migration to a non-null auto-generating primary key and to DI (Hilt) as their own work. Neither should be smuggled into a bug-fix PR.
6. K-11 and K-19 are one-line cleanups; take them with whatever PR is already in the file.

## Change History

- 2026-09-16 — Recorded from the full-source Kotlin audit (`ACTION-KOTLIN-000001`). Findings: legacy `VideoView` instead of Media3 (P2), N+1 `activeParentCount` queries in `markDeleted` (P2), nullable Room primary key driving ~30 null-handling sites (P2), `readHeader` duplicated between product and test (P1), unbounded never-shut-down sniff thread pool (P2), two isomorphic feature-core singletons with no DI (P2), plus the `'application/folder'` SQL/Kotlin constant split and one unused local. Confirmed separately that the data layer's transaction and Flow discipline is correct. No code changed.
