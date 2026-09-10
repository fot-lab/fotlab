# Image Library — fs_node Schema: Node Object and Node Relation Tables

- ID: FOTLAB-DATABS-000002
- Status: Draft
- Priority: P1
- Created: 2026-09-08
- Owner: —
- Related: `FOTLAB-IMGMGR-000001` (the two-table logical model this schema realises), `FOTLAB-DATABS-000001` (Room persistence discipline, runtime schema validation, migrations, no large objects)

## Background & Goal

`FOTLAB-IMGMGR-000001` fixes the shape of the Library's virtual tree at two logical tables — a node
table ("what nodes are") and a relation table ("who sits under whom") — and leaves the concrete
schema, column names, deduplication and root semantics open. This item settles those decisions into
a concrete persistence schema, so that entities, DAOs and migrations can be written against a fixed
contract.

It resolves from `FOTLAB-IMGMGR-000001`: Q1 (concrete names), Q2 (root semantics), Q6 (physical-file
deduplication) and the cascade-deletion part of the lifecycle rules (R7). Remaining decisions are
listed in Open Questions.

Goal: one agreed schema for the virtual file tree, realisable in Room under
`FOTLAB-DATABS-000001`.

## Requirement

### R1 — Two tables: node object and node relation

The schema has exactly two tables.

- `fs_node_object` — one row per node (collection or file entry), carrying only the node's own
  properties and **no** structural knowledge.
- `fs_node_relation` — one row per "X is directly under Y" edge, carrying only structure and **no**
  node properties.

### R2 — `fs_node_object` columns and indexes

| Column | Type | Required | Meaning |
| --- | --- | --- | --- |
| `fs_node_id` | INTEGER PRIMARY KEY | yes | Row identity; no `AUTOINCREMENT` (see R6) |
| `name_display` | TEXT | yes | Display name (collection name or file name) |
| `type_mime` | TEXT | yes | `application/folder` marks a collection; any other value is the file's MIME type |
| `uri_storage` | TEXT | no | File reference (`content://` URI); `NULL` for a collection |
| `time_modified` | INTEGER | no | Last modification timestamp |
| `time_created` | INTEGER | yes | Node creation timestamp |
| `time_deleted` | INTEGER | no | Soft-delete timestamp; `NULL` = live, non-null = removed (see R10) |

Indexes:

- `uri_storage` — **UNIQUE**, prevents the same physical file being recorded twice (see R7).
- `type_mime` — plain index, for filtering nodes by type.

### R3 — `fs_node_relation` columns, keys and indexes

| Column | Type | Required | Meaning |
| --- | --- | --- | --- |
| `fs_node_id_child` | INTEGER (FK → `fs_node_object.fs_node_id`) | yes | Child node ID |
| `fs_node_id_parent` | INTEGER (FK → `fs_node_object.fs_node_id`) | no | Parent node ID; `NULL` means a root-level node (see R5) |
| `time_deleted` | INTEGER | no | Soft-delete timestamp; `NULL` = live edge, non-null = removed (see R10) |

- Primary key: the composite `(fs_node_id_child, fs_node_id_parent)` — the same edge cannot be
  inserted twice.
- Indexes: `fs_node_id_parent` (loading the children of a directory) and `fs_node_id_child`
  (finding all parents of a node).
- Foreign keys: both reference `fs_node_object.fs_node_id` with `ON DELETE CASCADE`, so deleting a
  node automatically removes every relation row that references it.

### R4 — Node kinds distinguished by MIME convention

- A collection (directory / virtual folder) is a node whose `type_mime` is the fixed value
  `application/folder`.
- Any other `type_mime` value denotes a file entry and that value is the file's MIME type.
- Root-level convenience aside (R5), this one discriminator lets the UI and queries tell
  collections from files without a separate kind column.

### R5 — Root is implicit: a `NULL` parent means a root-level node

- There is **no** stored root row. A node whose relation row has `fs_node_id_parent = NULL` is a
  top-level node of the virtual tree.
- This supersedes the "explicit root collection" option of `FOTLAB-IMGMGR-000001` Q2: the virtual
  root is the set of rows with a `NULL` parent, giving the view a single conceptual top without an
  artificial node.
- Because the composite primary key includes the parent, a `NULL` parent participates in the key
  normally; only one "child under root" row per child can exist.

### R6 — `INTEGER PRIMARY KEY` reuse semantics (no `AUTOINCREMENT`)

- The primary key is `INTEGER PRIMARY KEY` without `AUTOINCREMENT`, so SQLite assigns the rowid
  and, after a row is deleted, reuses the freed ID on a later insert. This is the documented,
  intentional behaviour, not a bug.
- Row IDs are internal identity only: they are never presented to the user and never assumed to be
  stable across deletion/re-insertion.
- Under soft deletion (R10) a removed row is never dropped, so its id stays occupied and is not
  reused by the delete itself; normal inserts (after a hard delete outside this model) may still
  reuse a freed id.

### R7 — One physical file maps to one node

- The UNIQUE index on `uri_storage` means a physical file is recorded once. Adding the same file to
  several collections creates several relation rows against the same `fs_node_object` row, never a
  duplicate file node.
- A collection has `uri_storage = NULL`; because `NULL` values are distinct under a UNIQUE index,
  multiple collections can coexist without colliding.

### R8 — Lifecycle behaviour follows the schema

- Deleting a node row cascades to `fs_node_relation` rows referencing it as child or parent, so no
  dangling edge survives (`FOTLAB-IMGMGR-000001` R7).
- Deleting a file-entry row never deletes the physical file by itself; physical removal remains an
  explicit action of the owning flow (`FOTLAB-IMGMGR-000001` R7/AC6).
- Structural writes (creating/removing an edge, reparenting) are applied atomically
  (`FOTLAB-DATABS-000001` R4).

### R9 — Deleting never touches external storage

- No delete path removes, truncates, unlinks or otherwise modifies a physical file. The
  app organises **references**; deleting removes the reference, never the bytes
  (`FOTLAB-IMGMGR-000001` R1/R3, R7/AC6).
- This holds for every node kind: deleting a file entry and deleting a collection both
  leave the referenced storage untouched.

### R10 — Removal is a soft delete (`time_deleted`)

Nothing of the virtual tree is ever dropped silently, and **no separate recycle tables exist** — so
the node id and edge id spaces never collide with a removed row's copy (`FOTLAB-DATABS-000002` R6).
Removing a node or a relation **stamps** it instead:

- `fs_node_object.time_deleted` — `NULL` means the node is live; a non-null timestamp marks it
  removed.
- `fs_node_relation.time_deleted` — `NULL` means the edge is live; a non-null timestamp marks it
  removed.
- Every row one delete operation stamps shares the same timestamp, so the operation can be
  recognised, inspected and — if a restore is ever built — undone as one unit (the timestamp plays
  the role the old `id_recycle` batch id did, before this revision removed the recycle tables).
- The live `fs_node_object` / `fs_node_relation` tables keep the cascading foreign keys of R3/R8;
  soft deletion is an explicit stamp performed by the delete routine, not a side effect of a cascade.
  Because rows are never removed by deletion, a removed row keeps its id occupied.
- All reads that should see the live tree filter `time_deleted IS NULL` on both the node and the
  relation (every `childrenOf` / `rootChildren` / `parentsOf` / `observeCollections` /
  `fileEntryNodes` / `getByUri` query).

### R11 — `time_deleted` column and the delete timestamp

`fs_node_object.time_deleted` / `fs_node_relation.time_deleted`:

| Column | Type | Required | Meaning |
| --- | --- | --- | --- |
| `time_deleted` | INTEGER | no | Soft-delete timestamp; `NULL` = live, non-null = removed. Set by the delete routine, never by insert. |

- The delete timestamp is assigned **once per operation**, before the transaction opens, and stamped
  on every node and relation the operation removes, so the whole batch shares one value
  (`FOTLAB-DATABS-000002` R13). It replaces the old `id_recycle` batch id of the recycle tables,
  which are removed by this revision.
- A `NULL` parent (root node, R5) needs no sentinel in `fs_node_relation`: the column is an ordinary
  nullable `INTEGER`, so the `FsNodeParentRootId` sentinel once used by the recycle table is gone.

### R12 — The delete algorithm

One delete operation takes a set of node ids and one timestamp, and runs as follows.

1. **Soft-delete the node itself.** Stamp every relation row where the node is the **child** (its
   link up) with `time_deleted`, then stamp the node row's `time_deleted`.
2. **If it is a collection, walk its children.** For every relation row where the node is the
   **parent**:
   1. stamp that relation row's `time_deleted`;
   2. count how many **live** parent relations the child still has (relations with
      `time_deleted IS NULL`);
   3. **if the child still has a live parent** — stop here for that child. It stays exactly where it
      is: its other memberships are untouched, its own subtree is untouched, and it is neither
      stamped nor recursed into (many-to-many membership, R4/R7);
   4. **if the child has no live parent left** — it is an orphan. Stamp its node row's `time_deleted`,
      then apply step 2 to it (recursion), so a whole orphaned subtree is collected.
3. **Never stamp a node that still has a live parent.** A node reachable from a surviving collection
   is not removable as a side effect of deleting one of its parents.

Consequences: deleting a collection stamps that collection and, transitively, only those descendants
that became unreachable; a file that also lives in another collection simply loses one membership and
remains in the other. The node id and edge id of every removed row stay occupied, so live and removed
id spaces never collide (R10).

The routine is **idempotent**: a node already stamped (`time_deleted IS NOT NULL`) is skipped, so a
node reached by two paths in one operation is processed once.

### R13 — Atomicity, and what is not part of the transaction

- The whole delete — every `time_deleted` stamp on a node and on a relation — runs in **one
  transaction** (`FOTLAB-DATABS-000001` R4). An interrupted delete leaves the tree and the stamped
  batch consistent: either the whole batch is stamped, or nothing is.
- No file-system operation belongs to that transaction; the delete never opens, writes or deletes a
  physical file (R9).
- The delete timestamp is assigned once per operation, before the transaction opens, so every row of
  the batch can be recognised afterwards (R11).

### R14 — No `VACUUM` after soft deletion

- Soft deletion only stamps `time_deleted`; it never removes a row, so no database page is freed and
  no `VACUUM` is needed. Auto-vacuum stays off; the maintenance step that reclaimed page space after
  the old hard-delete is removed by this revision.
- Stale removed rows accumulate; retention/cleanup of `time_deleted`-stamped rows (by age, size cap or
  app upgrade) is a future decision (`FOTLAB-DATABS-000002` Q8, revised).

## Constraints

- C1 — Realised with Room only, under the persistence discipline of `FOTLAB-DATABS-000001`
  (KSP, `exportSchema = false` — the generated schema JSON is not committed; migration safety
  comes from Room's runtime validation, explicit migration per version, no destructive
  fallback in release).
- C2 — No large binaries: `fs_node_object` stores references and small metadata only.
- C3 — The UNIQUE index on `uri_storage` and the composite primary key of `fs_node_relation` are
  structural guarantees of R7 and R3 and must be preserved across migrations.
- C4 — The schema is the persistence contract of `FOTLAB-IMGMGR-000001`; entities, DAOs and
  migrations for it are owned in the place that item C4/Q8 settles (feature vs. shared `data`
  package), reached by consumers only through the exported repository.
- C5 — No delete path touches a physical file: removing a node removes only the app's
  reference to it (R9).
- C6 — Removal stamps `time_deleted` instead of dropping or archiving. Every removed row carries the
  same delete timestamp as its batch, and no separate recycle tables exist (R10/R11).
- C7 — A child that still has a surviving parent is never stamped and never recursed into; only a
  node left without any live parent is stamped as an orphan (R12).
- C8 — No `VACUUM` runs: soft deletion frees no page, so the maintenance step is removed (R14).

## Acceptance Criteria

- AC1 — Creating the schema produces exactly the two tables of R2/R3 with the stated columns,
  nullability, indexes, composite primary key and cascading foreign keys.
- AC2 — Inserting the same physical file reference twice fails on the UNIQUE `uri_storage` index;
  the file is recorded as one `fs_node_object` row regardless of how many relations reference it.
- AC3 — A node with a `NULL`-parent relation row appears as a top-level node, and no separate root
  row exists in `fs_node_object`.
- AC4 — Removing a node stamps it (`time_deleted`) and every live relation row that references it as
  child (R9–R12); nothing is dropped silently and no other node's relations are altered.
- AC5 — After a row is deleted by a hard path, a later insert may reuse its freed ID (no
  `AUTOINCREMENT`). Soft deletion does not free the id (R6/R10).
- AC6 — The same physical file can appear under two collections as two relation rows against one
  file node; no file bytes are moved or copied.
- AC7 — Every migration is exercised by opening the database; Room's runtime validation confirms the
  post-migration schema matches the current entities, and a mismatch fails at open time rather than
  corrupting data (`FOTLAB-DATABS-000001` R5/R8).
- AC8 — Deleting a file entry stamps its node row and its child-side relations with one delete
  timestamp; no row leaves the live tables, and the file stays at its original location — the bytes
  are unchanged.
- AC9 — Deleting a collection stamps every relation row where it is the parent. A child that still
  has another parent keeps that relation, is not stamped, and its own subtree is untouched.
- AC10 — A child left with no live parent at all is stamped as deleted, and the rule is applied to it
  recursively, so an orphaned subtree is collected in the same batch.
- AC11 — All rows stamped by one delete share one `time_deleted`; two separate deletes carry
  different values, so a batch can be listed on its own.
- AC12 — An interrupted delete leaves no half-applied batch: either every row of the batch is stamped,
  or none is.
- AC13 — After a delete or a refresh stamps rows, no `VACUUM` runs (R14, revised): soft deletion frees
  no page, so the operation leaves the database internally consistent without a maintenance step.

## Impacted Modules

- The package owning the model's persistence/repository (location per `FOTLAB-IMGMGR-000001` Q8)
- `data/` — shared Room infrastructure, migration helpers (`FOTLAB-DATABS-000001`)
- `ui/gallery/` — consumes the exported repository; reads collection/file nodes through DAO queries
  (`FOTLAB-IMGMGR-000001`)
- Future input/output file-management features that reuse the same file tree
- `FOTLAB-DATABS-000001` and `FOTLAB-IMGMGR-000001` — the rules and model this schema realises

## Open Questions

- Q1 — MIME `type_mime`: is `application/folder` final, and what other synthetic kinds (for
  example an output/result marker) are needed beyond real MIME types? **TBD.**
- Q2 — Does the design permit any node to have many parents (full DAG), or only file entries while
  collections stay single-parented? The schema allows many parents for any node; the invariant is
  still that of `FOTLAB-IMGMGR-000001` Q3. **TBD.**
- Q3 — How are cycles prevented (a node must not become its own ancestor), given the schema does
  not forbid them? **TBD.**
- Q4 — Is child ordering stored anywhere? No sort column exists in this schema; whether and how
  ordering is added (per relation row, given a file's position may differ per parent) is open.
  **TBD.**
- Q5 — `time_modified` is optional while `time_created` is required: is that asymmetry intended,
  and is `time_modified` maintained for nodes or left to the physical file? **TBD.**
- Q6 — Entity, DAO and database names and their owning package are not fixed here (C4 /
  `FOTLAB-IMGMGR-000001` Q8). **TBD.**
- Q7 — Is there a restore path out of the soft-deleted rows — user-visible undo that clears
  `time_deleted` — or are they an audit trail only? **TBD.**
- Q8 — Retention: are `time_deleted`-stamped rows ever purged (by age, size cap or app upgrade), or
  kept indefinitely? **TBD.**
- Q9 — How is the delete timestamp produced — clock, in-memory counter or a persisted sequence — and
  must it stay unique across process restarts? **TBD.** The current implementation uses a clock value
  (`System.currentTimeMillis()`), which makes a collision possible if two deletes land in the same
  millisecond.

## Change History

- 2026-09-08 — Initial draft. Settled the concrete persistence schema for the Image Library's
  two-table model: `fs_node_object` (identity, display name, MIME-based kind, storage URI,
  timestamps; UNIQUE `uri_storage`, index `type_mime`) and `fs_node_relation` (composite primary
  key, cascading foreign keys both ways, indexes on both columns). Decided the root is implicit
  (`NULL` parent = root-level, no stored root row), physical files deduplicate through the UNIQUE
  storage-URI index, and `INTEGER PRIMARY KEY` reuse freed IDs by design. Linked to and resolving
  parts of `FOTLAB-IMGMGR-000001`; left MIME kinds, tree-vs-DAG invariants, cycle prevention,
  ordering, timestamp semantics and ownership naming open as Q1–Q6.
- 2026-09-08 — Added the deletion design: nothing is ever dropped silently and no physical
  file is ever removed — deleting moves the node row into `fs_node_object_recycle` and the
  relation rows into `fs_node_relation_recycle`, both stamped with a batch id `id_recycle`
  that identifies one delete operation. Recycle tables mirror the live columns, carry no
  foreign keys and no cascade, and are keyed by `(id_recycle, …)`. Specified the delete
  algorithm (R12): archive the node's own child-side relations and the node itself; for a
  collection, archive every relation where it is the parent, then for each child keep it
  when another parent survives and archive it as an orphan — recursing into it — when none
  does. Added constraints C5–C7, reworded AC4, added AC8–AC12, and recorded restore,
  retention and batch-id generation as Q7–Q9.
- 2026-09-08 — Added R14, C8 and AC13: after a delete or a refresh archives rows into the recycle
  tables, an explicit `VACUUM` reclaims the freed page space (auto-vacuum is off), running once per
  operation after the archive transaction commits and touching no live row. Driven by the gallery
  refresh (`FOTLAB-UIXDES-000004` R10).
- 2026-09-09 — Room adaptation (implementation, not a schema change in intent). Room forbids a
  nullable column in a `@PrimaryKey`, so a `NULL` parent (root node, R5) cannot sit in a composite
  key. The two relation tables are handled differently:
  - `fs_node_relation` (live): a surrogate auto-generated `id` is the primary key; the
    `(fs_node_id_child, fs_node_id_parent)` pair is guarded by a `UNIQUE` index, so "an edge is
    recorded once" (R3) holds and `NULL`-parent, both cascading foreign keys (R3/R8) and all DAO
    queries stay unchanged. Caveat: SQLite treats `NULL`s as distinct under a `UNIQUE` index, so two
    `(child, NULL)` rows are not rejected by the index; the `OnConflictStrategy.IGNORE` insert plus the
    app's single-link-per-child usage make this unreachable in practice.
  - `fs_node_relation_recycle` (archive): keeps the design's composite primary key
    `(id_recycle, fs_node_id_child, fs_node_id_parent)` exactly as R11 specifies. Its `fs_node_id_parent`
    is `NOT NULL` (a composite key cannot hold `NULL`), so an archived root-level edge stores the
    sentinel `FsNodeParentRootId` (0, never a real node id) instead of `NULL`; the delete algorithm
    writes that sentinel in `GalleryRepository.archiveRelation`. No FK, no cascade there (R10).
  Also: `FsNodeObject.fsNodeId` must carry `@ColumnInfo(name = "fs_node_id")` so the column is
  `fs_node_id`, matching every FK and query (it previously defaulted to the camelCase field name,
  which broke KSP).
- 2026-09-10 — Soft-delete revision (replaces the recycle-table archive). Removed the two recycle
  tables (`fs_node_object_recycle`, `fs_node_relation_recycle`) and the `FsNodeParentRootId` sentinel;
  added a nullable `time_deleted` column to both live tables (`fs_node_object`, `fs_node_relation`).
  Deletion now stamps `time_deleted` on the node and on every relation that leaves with it, using one
  batch timestamp in place of the old `id_recycle`, and never drops or archives a row — so live and
  removed id spaces never collide. All live queries filter `time_deleted IS NULL`; the delete
  algorithm (R12) propagates the stamp across the subtree using the count of *live* parents and is
  idempotent. Database version raised 2→3 with `MIGRATION_2_3` (add the two columns, drop the recycle
  tables); the `VACUUM` after delete/refresh is removed (R14). Rules R10–R14, R2/R3 columns, C6, C8
  and open questions Q7–Q9 updated accordingly; AC4–AC13 reworded from "archives" to "stamps
  `time_deleted`".
