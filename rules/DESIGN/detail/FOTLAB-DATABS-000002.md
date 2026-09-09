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

Indexes:

- `uri_storage` — **UNIQUE**, prevents the same physical file being recorded twice (see R7).
- `type_mime` — plain index, for filtering nodes by type.

### R3 — `fs_node_relation` columns, keys and indexes

| Column | Type | Required | Meaning |
| --- | --- | --- | --- |
| `fs_node_id_child` | INTEGER (FK → `fs_node_object.fs_node_id`) | yes | Child node ID |
| `fs_node_id_parent` | INTEGER (FK → `fs_node_object.fs_node_id`) | no | Parent node ID; `NULL` means a root-level node (see R5) |

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

### R10 — Removal is a move into two recycle tables

Nothing of the virtual tree is ever dropped silently. Removing a node or a relation
**archives** it:

- `fs_node_object_recycle` — archived copy of a removed `fs_node_object` row.
- `fs_node_relation_recycle` — archived copy of a removed relation row.
- Both tables carry `id_recycle`, the **batch id**: every row written by one delete
  operation shares the same value, so a single operation can be identified, inspected
  and — if a restore is ever built — undone as one unit.
- The recycle tables carry **no foreign keys** and take part in no cascade. They are
  append-only archives and must stay readable after the live rows they mirror are gone.
- The live `fs_node_object` / `fs_node_relation` tables keep the cascading foreign keys
  of R3/R8; recycling is an explicit move performed by the delete routine, not a
  side effect of a cascade.

### R11 — Recycle table columns and keys

`fs_node_object_recycle`:

| Column | Type | Required | Meaning |
| --- | --- | --- | --- |
| `id_recycle` | INTEGER | yes | Batch id of the delete operation |
| `fs_node_id` | INTEGER | yes | The archived node's id |
| `name_display` | TEXT | yes | Archived display name |
| `type_mime` | TEXT | yes | Archived MIME kind |
| `uri_storage` | TEXT | no | Archived file reference |
| `time_modified` | INTEGER | no | Archived modification timestamp |
| `time_created` | INTEGER | yes | Archived creation timestamp |

- Primary key: the composite `(id_recycle, fs_node_id)` — one operation archives a node once.
- Index: `id_recycle` (reading one batch).

`fs_node_relation_recycle`:

| Column | Type | Required | Meaning |
| --- | --- | --- | --- |
| `id_recycle` | INTEGER | yes | Batch id of the delete operation |
| `fs_node_id_child` | INTEGER | yes | Archived child node id |
| `fs_node_id_parent` | INTEGER | no | Archived parent node id; `NULL` = root-level |

- Primary key: the composite `(id_recycle, fs_node_id_child, fs_node_id_parent)`.
- Indexes: `id_recycle`, and `fs_node_id_parent` (reconstructing what sat under an
  archived collection).

### R12 — The delete algorithm

One delete operation takes a set of node ids and one batch id, and runs as follows.

1. **Archive the node itself.** Archive every relation row where the node is the
   **child** (its link to its own parent), then archive the node row into
   `fs_node_object_recycle`.
2. **If it is a collection, walk its children.** For every relation row where the node
   is the **parent**:
   1. archive that relation row into `fs_node_relation_recycle`;
   2. count how many parent relations the child still has;
   3. **if the child still has a parent** — stop here for that child. It stays exactly
      where it is: its other memberships are untouched, its own subtree is untouched,
      and it is neither archived nor recursed into (many-to-many membership, R4/R7);
   4. **if the child has no parent left** — it is an orphan. Archive its node row into
      `fs_node_object_recycle`, then apply step 2 to it (recursion), so a whole
      orphaned subtree is collected.
3. **Never archive a node that still has a parent.** A node reachable from a surviving
   collection is not removable as a side effect of deleting one of its parents.

Consequences: deleting a collection removes that collection and, transitively, only
those descendants that became unreachable; a file that also lives in another collection
simply loses one membership and remains in the other.

### R13 — Atomicity, and what is not part of the transaction

- The whole delete — all archived rows and all removals from the live tables — runs in
  **one transaction** (`FOTLAB-DATABS-000001` R4). An interrupted delete leaves the live
  tree and the recycle batch consistent: the batch is complete, or nothing moved.
- No file-system operation belongs to that transaction; the delete never opens, writes
  or deletes a physical file (R9).
- The batch id is assigned once per operation, before the transaction opens, so every
  row of the batch can be recognised afterwards.

### R14 — Vacuum after archiving

- After a delete or a refresh archives rows into the recycle tables, an explicit `VACUUM` is run on
  the Room database so the page space freed by the removed live rows is reclaimed. Auto-vacuum is
  not enabled on this database, so space is only returned by an explicit `VACUUM`.
- Vacuum runs **after** the archive transaction has committed (it cannot run inside a write
  transaction), once per operation — after a delete, and after a refresh (`FOTLAB-UIXDES-000004`
  R10). It is a maintenance step, not part of the archive logic, and touches no node or relation row.

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
- C6 — Removal archives instead of dropping. Every archived row carries the `id_recycle` of
  the operation that produced it, and the recycle tables carry no foreign keys and no
  cascade (R10).
- C7 — A child that still has a surviving parent is never archived and never recursed into;
  only a node left without any parent is archived as an orphan (R12).
- C8 — Space freed by archiving is reclaimed by an explicit `VACUUM` after the archive transaction
  commits, on both delete and refresh (R14). Vacuum is a maintenance step and must not touch live
  rows.

## Acceptance Criteria

- AC1 — Creating the schema produces exactly the two tables of R2/R3 with the stated columns,
  nullability, indexes, composite primary key and cascading foreign keys.
- AC2 — Inserting the same physical file reference twice fails on the UNIQUE `uri_storage` index;
  the file is recorded as one `fs_node_object` row regardless of how many relations reference it.
- AC3 — A node with a `NULL`-parent relation row appears as a top-level node, and no separate root
  row exists in `fs_node_object`.
- AC4 — Removing a node archives it and every live relation row that references it as child
  (R9–R12); nothing is dropped silently and no other node's relations are altered.
- AC5 — After a row is deleted, a later insert may reuse its freed ID (no `AUTOINCREMENT`).
- AC6 — The same physical file can appear under two collections as two relation rows against one
  file node; no file bytes are moved or copied.
- AC7 — Every migration is exercised by opening the database; Room's runtime validation confirms the
  post-migration schema matches the current entities, and a mismatch fails at open time rather than
  corrupting data (`FOTLAB-DATABS-000001` R5/R8).
- AC8 — Deleting a file entry archives its node row and its child-side relations under one
  `id_recycle`, removes them from the live tables, and leaves the file at its original
  location — the bytes are unchanged.
- AC9 — Deleting a collection archives every relation row where it is the parent. A child
  that still has another parent keeps that relation, is not archived, and its own subtree is
  untouched.
- AC10 — A child left with no parent at all is archived as an object and the rule is applied
  to it recursively, so an orphaned subtree is collected in the same batch.
- AC11 — All rows written by one delete share one `id_recycle`; two separate deletes carry
  different values, so a batch can be listed on its own.
- AC12 — An interrupted delete leaves no half-applied batch: either every row of the batch is
  archived and removed from the live tables, or none is.
- AC13 — After a delete or a refresh archives rows, an explicit `VACUUM` reclaims the freed space;
  the operation leaves the database internally consistent (R14).

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
- Q7 — Is there a restore path out of the recycle tables — user-visible undo or a
  recycle-bin screen — or are they an audit trail only? **TBD.**
- Q8 — Retention: are recycle rows ever purged (by age, size cap or app upgrade), or kept
  indefinitely? **TBD.**
- Q9 — How is `id_recycle` produced — clock, in-memory counter or a persisted sequence — and
  must it stay unique across process restarts? **TBD.** The current implementation uses a
  clock value, which makes a collision possible if two deletes land in the same millisecond.

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
  nullable column in a `@PrimaryKey`, so the composite key `(fs_node_id_child, fs_node_id_parent)`
  of R3/R5 cannot be expressed directly — a `NULL` parent (root node) is a first-class value. Both
  relation tables (`fs_node_relation`, `fs_node_relation_recycle`) therefore use a surrogate
  auto-generated `id` as the primary key and guard the original key columns with a `UNIQUE` index
  instead: `(fs_node_id_child, fs_node_id_parent)` for the live table and
  `(id_recycle, fs_node_id_child, fs_node_id_parent)` for the recycle table, preserving "an edge is
  recorded once" (R3/R11). The `NULL`-parent semantics, both cascading foreign keys (R3/R8) and all
  DAO queries are unchanged. Caveat: SQLite treats `NULL`s as distinct under a `UNIQUE` index, so two
  `(child, NULL)` rows are not rejected by the index; the `OnConflictStrategy.IGNORE` insert plus the
  app's single-link-per-child usage make this unreachable in practice. Also: `FsNodeObject.fsNodeId`
  must carry `@ColumnInfo(name = "fs_node_id")` so the column is `fs_node_id`, matching every FK and
  query (it previously defaulted to the camelCase field name, which broke KSP).
