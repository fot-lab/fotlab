# Library refresh is a no-op — `uriExists` tests `count >= 0`, which is always true

- ID: ACTION-KOTLIN-000002
- Status: Observation
- Priority: P0
- Created: 2026-09-16
- Owner: —
- Related: `ACTION-KOTLIN-000001` (master audit), `rules/DESIGN/detail/FOTLAB-DATABS-000002.md` (R10 soft-delete / R14), `rules/DESIGN/detail/FOTLAB-UIXDES-000004.md` (R10 refresh action), `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryCore.kt`

## Background & Goal

`FOTLAB-DATABS-000002` R10 (revised) and `FOTLAB-UIXDES-000004` R10 define the refresh (reconcile) action: on every press, every live non-folder node whose real object is gone — plus every live orphan node — must be soft-deleted with one shared `time_deleted`, so the virtual tree stops showing files that no longer exist on the device.

The refresh icon is wired in the library top bar and calls `LibraryCore.refresh()`. This review checks whether that path can ever actually delete anything.

## Finding

It cannot. `refresh()` collects the doomed ids through `uriExists`:

```197:207:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryCore.kt
    suspend fun refresh() {
        val now = System.currentTimeMillis()
        val missing = repo().fileEntryNodes().filter { node ->
            node.uriStorage != null && !uriExists(node.uriStorage)
        }.mapNotNull { it.fsNodeId }
```

```209:217:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryCore.kt
    private fun uriExists(uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return runCatching {
            applicationContext.contentResolver
                .query(uri, null, null, null, null)
                ?.use { it.count >= 0 } ?: false
        }.getOrDefault(false)
    }
```

`Cursor.count` is the number of rows — it is `0` or greater by construction, so `it.count >= 0` is **always `true`**. The whole expression collapses to "the provider returned a non-null cursor".

The `?: false` branch only fires when `query` returns `null`. For a MediaStore entry that has been deleted, `query` normally returns a **cursor with zero rows**, not `null` — so the deleted file is reported as existing. The provider throwing (which `runCatching` would turn into `false`) is the rare case, not the normal one.

Consequences:

- `missing` is always empty, so `refresh()` only ever recycles orphans — the "file deleted behind our back" half of R10 never runs.
- The feature looks implemented and is covered by an icon, a string resource and a KDoc; only the predicate is wrong, so nothing in the UI signals the failure.

Secondary, same function: `val now = System.currentTimeMillis()` on line 198 is declared and never used — `deleteNodes` stamps its own timestamp. Dead local (K-19).

## Impact / Conflict

- Directly defeats `FOTLAB-DATABS-000002` R10 (reconciliation) and `FOTLAB-UIXDES-000004` R10 (refresh action). No other rule conflicts; the delete path itself (`deleteNodes`, batch stamping, recycle-bin grouping) is correct and is reused unchanged once real ids reach it.
- User-visible symptom: a photo deleted in another app stays in the library grid forever, and tapping it opens a viewer that shows the broken-image placeholder.
- Low blast radius: `uriExists` has exactly one caller.

## Recommendation

1. Fix the predicate:

   ```kotlin
   ?.use { it.count > 0 } ?: false
   ```

   or, if the intent is "resolvable at all", be explicit with `it.moveToFirst()`. `count > 0` is the smaller change and matches the KDoc ("still resolvable").

2. Delete the unused `val now` on line 198.

3. Consider a regression test: import a MediaStore PNG, delete it through `ContentResolver`, call `LibraryCore.refresh()`, and assert the node carries a non-null `timeDeleted` and disappears from `rootChildren()`. `PngEndToEndFlowTest` already builds the MediaStore fixture this test would need (see also `ACTION-KOTLIN-000004`, duplication group C14).

## Change History

- 2026-09-16 — Recorded from the full-source Kotlin audit (`ACTION-KOTLIN-000001`). Identified as the only P0: `LibraryCore.uriExists` uses `cursor.count >= 0`, which is always true, so `LibraryCore.refresh()` never collects a missing-file id and the R10 reconciliation has never taken effect. Also recorded the unused `val now` in the same function. No code changed.
