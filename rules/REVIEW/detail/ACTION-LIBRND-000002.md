# Recycle bin batch-algorithm audit + virtual batch-folder selection implementation

- ID: ACTION-LIBRND-000002
- Status: Implemented
- Priority: P2
- Created: 2026-11-28
- Owner: —
- Related: `rules/REVIEW/detail/ACTION-LIBRND-000001.md` (one-level rendering invariant), `rules/DESIGN/detail/FOTLAB-DATABS-000002.md` (soft-delete batch schema), `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryRepository.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/FsNodeObjectDao.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/FsNodeRelationDao.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreenRecycle.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreen.kt`

## Background & Goal

The recycle bin is built on a two-table soft-delete schema (`fs_node_object` + `fs_node_relation`), where every delete operation stamps both tables with one shared `time_deleted` timestamp — a "batch". This review (a) audits that the two tables are indeed maintained **in lockstep** across every data path (delete, restore, delete-forever, UI rebuild), and (b) records the design and implementation of **virtual batch-folder selection** at the recycle-bin root, where a non-`fs_node` placeholder folder can be selected and acts as an atomic "select all nodes in this batch" shortcut.

No data-layer mutation is changed by this review; it confirms the existing batch-maintenance algorithm is self-consistent, and adds a UI-layer feature on top.

## Finding

### 1. Two-table batch maintenance is self-consistent (verified)

Every data path touches both tables inside one transaction:

| Path | `fs_node_object` | `fs_node_relation` | Transaction |
| --- | --- | --- | --- |
| **Soft-delete** (`markDeleted`) | `markDeleted(nodeId, now)` stamps row | `relationsWithChild` + `relationsWithParent` → both stamped `time_deleted = now` | Single `withTransaction` |
| **Restore from bin** (`restoreFromBin`) | `restoreNodesByBatch(time)` clears stamp on every node of the batch | `restoreRelationsByBatch(time)` clears stamp on every edge of the batch | Single `withTransaction` |
| **Delete forever** (`deleteForever`) | `deleteNodesForever(toDelete)` hard-drops rows | `deleteRelationsForever(toDelete)` hard-drops all edges touching the nodes | Single `withTransaction` |

Idempotency is preserved across all three:

- **Soft-delete** skips nodes whose `timeDeleted != null` (reached through two parent paths); relation stamps are idempotent (UPDATE with the same timestamp is a no-op).
- **Restore** is batch-granular by design — it derives batch times from selected ids, then restores *every* node and edge stamped with that time, regardless of which subset the user picked. This is intentional (atomic undo) and explicitly confirmed by a dialog added in commit `84abc9d`.
- **Delete forever** walks only edges whose `time_deleted` matches the batch time of the starting node (`batchChildIdsOf(parentId, batchTime)`), so a live child that merely shared membership with a deleted parent is never pulled into the hard delete — the batch boundary is respected.

### 2. Virtual batch-folder selection — design and implementation

#### 2.1 Root cause of the original bug

The recycle-bin root renders one `FsNodeObject` per distinct `time_deleted` value, but these are **presentation-only** rows: `fsNodeId = null`, `typeMime = MimeFolderDeleted` (`"application/folder_deleted"`). The pre-existing selection mechanism was gated on `fsNodeId != null` at three layers:

| Layer | Gate | Location |
| --- | --- | --- |
| UI interaction | `onToggleSelect = { node -> node.fsNodeId?.let { toggleSelect(it) } }` | `LibraryScreenRecycle.kt` |
| UI interaction | `onLongPress = { node -> node.fsNodeId?.let { enterSelection(it) } }` | `LibraryScreenRecycle.kt` |
| Visual render | `selected = node.fsNodeId != null && node.fsNodeId in selectedIds` | `LibraryScreen.kt` |
| Overflow trio | `LaunchedEffect(Unit) { onVisibleIds(emptyList()) }` — candidate ids always empty | `LibraryScreenRecycle.kt` |

Result: batch folders were visually identical to selectable folders but never actually selected. The user had to enter a batch before selecting anything.

#### 2.2 Design choice — behavior-layer expansion, no data-layer change

The selection state stays `selectedIds: Set<Long>` — a flat set of real `fsNodeId` values. No new "virtual id" type, no change to the repository or DAO layer. A virtual batch-folder "selected" simply means "every fsNodeId belonging to that batch is in `selectedIds`".

This choice aligns perfectly with the existing semantics of `restoreFromBin` and `deleteForever`, both of which operate at batch granularity. The UI shortcut ("select this batch = select all its nodes") is therefore a pure UX layer — it does not alter data-layer invariants.

#### 2.3 Implementation outline

**New query.** `FsNodeObjectDao.deletedNodeIdsAt(time: Long): List<Long>` — returns every node id belonging to a delete batch. Used only to expand a virtual batch-folder into concrete ids at selection time; it does **not** need to touch the relation table because the batch identification already comes from the object table's `time_deleted` column (and any node that appears in a batch necessarily has its batch boundary preserved by the relation-table stamps).

**NodeList gains a virtual-key branch.** `NodeList` adds `virtualSelectedKeys: Set<Long> = emptySet()` as an optional parameter. The `selected` calculation in `NodeCell` becomes:

```kotlin
selected = when {
    node.fsNodeId != null -> node.fsNodeId in selectedIds
    node.typeMime == MimeFolderDeleted -> node.timeCreated in virtualSelectedKeys
    else -> false
}
```

`LibraryScreen.kt` (the main non-recycle Library view) is unaffected because it never creates nodes with `MimeFolderDeleted`, and the new parameter defaults to empty.

**RecycleRoot logic.**

1. Loads `batches = deletedBatchTimes()` (unchanged).
2. Runs `LaunchedEffect(batches)` to populate a `batchNodeIdsCache: Map<Long, List<Long>>` by calling `LibraryCore.recycleBatchNodeIds(time)` for each batch. This runs once per batch-list change; no per-click DB access.
3. Calculates `virtualSelectedKeys` as every batch time whose ids are all present in `selectedIds`.
4. `onVisibleIds(allNodeIds)` — the overflow trio (select-all / invert / deselect) now operates over every real node across every batch at the root, instead of an always-empty list.
5. `onToggleSelectBatch(batchTime, batchIds)` — if every id is already selected → deselect the whole batch; otherwise → select every id. Atomic toggle.
6. `onLongPressBatch(batchTime, batchIds)` — enters selection mode and selects every id of that batch.

**Selection-confirm dialog added as pre-change.** Commit `84abc9d` adds an `AlertDialog` that fires before any restore-from-bin operation, informing the user that restoring selected items will also restore every other item deleted together in the same batch. This closes a prior UX gap where the batch-granular restore was silent and users could reasonably expect to restore only a partial selection.

#### 2.4 What is deliberately NOT in scope

- **No batch-level selection type** (`RecycleSelectionKey = Node | Batch`). The flat `Set<Long>` is sufficient because every operation layer already operates at batch granularity.
- **No schema change**. Virtual batch folders remain presentation-only; no rows are inserted into `fs_node_object`.
- **No relation-table queries for batch expansion**. The node-table `deletedNodeIdsAt(time)` is the only intermediate query needed. The relation table is only touched by the final data-layer operations (restore / delete forever), which already query both tables in lockstep.

## Impact / Conflict

- No conflict with `ACTION-LIBRND-000001` (one-level rendering). The virtual batch-folder is an **additional** item type at the recycle root, not a recursive rendering. Inside a batch, `RecycleBatch` still renders exactly one level.
- No conflict with `FOTLAB-DATABS-000002` (soft-delete schema). The existing batch-maintenance invariants are preserved and explicitly audited here.
- The `restoreFromBin` confirm dialog (commit `84abc9d`) is a necessary pre-change: before virtual batch-folder selection, the UX was already misleading for partial-selection restore; after virtual selection, the dialog also prevents "I selected one batch folder but expected only these three files to come back" confusion.
- A subtle boundary case: multi-parent nodes — if node B sits under collections A and C, only A is deleted. After restoreFromBin(A), B reappears under A *and* remains under C. This is correct behavior and not changed by this review; restore restores the entire batch, not the exact subgraph the user picked.

## Recommendation

- Keep the current two-table batch-maintenance algorithm as-is (verified self-consistent).
- Keep the "behavior-layer expansion" pattern for virtual items — do not introduce a parallel selection type or schema row unless a future feature truly requires it.
- The restore-confirm dialog should remain until such time as the full product UX is redesigned around batch-granular recycle-bin semantics; it is a defense-in-depth, not a permanent requirement.

## Change History

- 2026-11-28 — Review recorded. Audited both-table batch-maintenance across all data paths (delete, restore, delete forever, UI rebuild) and confirmed self-consistency. Designed and implemented virtual batch-folder selection at recycle-bin root via behavior-layer expansion — keeps `selectedIds: Set<Long>` flat, adds `virtualSelectedKeys` to `NodeList`, populates `batchNodeIdsCache` in `RecycleRoot`. Added restore-confirm dialog as UX pre-change. Filed as `ACTION-LIBRND-000002`; row appended to `rules/REVIEW/index.md`. Implementation commits: `84abc9d` (restore confirm), `44fc722` (batch-folder selection).
