# Library one-level rendering audit — current view is correct; Recycle must mirror and stay isolated

- ID: ACTION-LIBRND-000001
- Status: Observation
- Priority: P2
- Created: 2026-09-10
- Owner: —
- Related: `rules/DESIGN/detail/FOTLAB-UIXDES-000004.md` (Library UI spec — left cluster, selection, actions), `rules/DESIGN/detail/FOTLAB-DATABS-000002.md` (soft-delete via `time_deleted`), `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreen.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/FsNodeRelationDao.kt`

## Background & Goal

The Library feature shows a virtual fs tree. For performance, the normal (non-deleted) Library view must render **only the direct children of the current node** — one level at a time. Tapping a folder navigates *into* it and renders that folder's children, but never its grandchildren in the same pass.

This review (a) confirms the current code already satisfies that one-level rule, and (b) records the discipline the **Recycle Bin** view must follow when it is built, so the two views never mix their data and never pull a whole subtree into the UI.

No code is changed by this review; it records a confirmed invariant and a forward constraint.

## Finding

### 1. Library view already renders exactly one level (verified)

The rendered list is driven entirely by `currentDirectory` through a single-level query:

```137:140:app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreen.kt
    val children by remember(currentDirectory) {
        val parentId = currentDirectory?.fsNodeId
        if (parentId == null) LibraryCore.rootChildren() else LibraryCore.childrenOf(parentId)
    }.collectAsState(initial = emptyList())
```

- Entering a folder only sets `currentDirectory = node` (`LibraryScreen.kt:209`); it does **not** also load grandchildren.
- The data queries are single-level by construction — `childrenOf` joins on `fs_node_id_parent = :parentId`, `rootChildren` on `fs_node_id_parent IS NULL` (`FsNodeRelationDao.kt:26-43`). Neither walks the subtree.
- `NodeList` is non-recursive: it only iterates the `nodes` it is handed and emits one `NodeCell` per node (`LibraryScreen.kt:567-595`); `NodeCell` renders a single node (thumbnail + name) and never fetches children.
- The media viewer's paging list is also scoped to the current level: `children.filter { isMedia(it.typeMime) }` (`LibraryScreen.kt:213`), so the full-screen viewer only pages within the opened folder.

Conclusion: the normal Library view meets the one-level rendering requirement with no change needed.

### 2. Discipline required when the Recycle view is built

The Recycle view is currently a placeholder (`LibraryScreen.kt:240-245`). To keep it performant and isolated from Library, the following must hold:

- **One level at a time, mirrored.** Add `recycleRootChildren()` (top-level deleted nodes) and `recycleChildrenOf(parentId)` (a deleted folder's direct deleted children). Both must JOIN only one edge — never expand the whole deleted subtree in the UI.
- **Recycle root must exclude nested deleted children.** When a folder `F` containing `X` is deleted, `F` and `X` and their relation are all soft-stamped (`time_deleted`). A naive "all deleted nodes" query would show `F` and `X` both at the top of Recycle (duplicated / mixed). Rule: a deleted node `N` is a Recycle root iff `N` has **no** relation `r` (parent `p` → `N`) where `r.time_deleted IS NOT NULL` **and** `p.time_deleted IS NOT NULL`. Equivalently, `N` is top-level in Recycle only when its deleted parent chain leads to a live node (or to no node), so nested `X` appears under `F`, not at the root.
- **Reset navigation on mode switch.** `currentDirectory` must be set to `null` whenever `viewMode` changes (Library ↔ Recycle). Both views start from their own top level; a folder opened in Library must never leak into the Recycle query, and vice versa.

## Impact / Conflict

- No conflict with `FOTLAB-DATABS-000002` (soft-delete). The one-level rule is a *rendering* constraint; the data layer's `delete` correctly walks the whole subtree to stamp `time_deleted` — that is mutation, not UI expansion, and is unaffected.
- The only risk is at implementation time: reusing `currentDirectory` across both modes without the reset, or writing a Recycle query that selects all deleted rows flat, would mix the two views and/or pull a full deleted subtree. This review pre-empts that.

## Recommendation

- Keep the current Library rendering as-is (already correct).
- When building Recycle, implement the two single-level recycle queries above, apply the nested-child exclusion rule for the recycle root, and reset `currentDirectory` on every `viewMode` change.
- Treat "one level at a time + reset on switch" as the invariant for both views; add a regression check that the rendered list equals the direct-children query result, not a subtree.

## Change History

- 2026-09-10 — Review recorded. Confirmed the normal Library view renders exactly one level (single-level `childrenOf` / `rootChildren` queries + non-recursive `NodeList` / `NodeCell`). Recorded the discipline the Recycle view must follow: mirror one-level queries, exclude nested deleted children from the recycle root, and reset `currentDirectory` on `viewMode` switch so the two views never mix. Filed as `ACTION-LIBRND-000001`; category `LIBRND` added to `rules/REVIEW.md`; row appended to `rules/REVIEW/index.md`.
