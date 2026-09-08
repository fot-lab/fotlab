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

## Acceptance Criteria

- AC1 — Creating the schema produces exactly the two tables of R2/R3 with the stated columns,
  nullability, indexes, composite primary key and cascading foreign keys.
- AC2 — Inserting the same physical file reference twice fails on the UNIQUE `uri_storage` index;
  the file is recorded as one `fs_node_object` row regardless of how many relations reference it.
- AC3 — A node with a `NULL`-parent relation row appears as a top-level node, and no separate root
  row exists in `fs_node_object`.
- AC4 — Deleting a node row removes all `fs_node_relation` rows that reference it as child or
  parent, and leaves other nodes' relations untouched.
- AC5 — After a row is deleted, a later insert may reuse its freed ID (no `AUTOINCREMENT`).
- AC6 — The same physical file can appear under two collections as two relation rows against one
  file node; no file bytes are moved or copied.
- AC7 — Every migration is exercised by opening the database; Room's runtime validation confirms the
  post-migration schema matches the current entities, and a mismatch fails at open time rather than
  corrupting data (`FOTLAB-DATABS-000001` R5/R8).

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

## Change History

- 2026-09-08 — Initial draft. Settled the concrete persistence schema for the Image Library's
  two-table model: `fs_node_object` (identity, display name, MIME-based kind, storage URI,
  timestamps; UNIQUE `uri_storage`, index `type_mime`) and `fs_node_relation` (composite primary
  key, cascading foreign keys both ways, indexes on both columns). Decided the root is implicit
  (`NULL` parent = root-level, no stored root row), physical files deduplicate through the UNIQUE
  storage-URI index, and `INTEGER PRIMARY KEY` reuse freed IDs by design. Linked to and resolving
  parts of `FOTLAB-IMGMGR-000001`; left MIME kinds, tree-vs-DAG invariants, cycle prevention,
  ordering, timestamp semantics and ownership naming open as Q1–Q6.
