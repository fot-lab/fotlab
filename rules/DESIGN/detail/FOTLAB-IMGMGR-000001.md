# Image Library — Two-Table Virtual Tree for In-Place File Management

- ID: FOTLAB-IMGMGR-000001
- Status: Draft
- Priority: P1
- Created: 2026-09-08
- Owner: —
- Related: `FOTLAB-STRUCT-000001` (feature package `library` owns the destination), `FOTLAB-DATABS-000001` (Room persistence, data ownership, large objects stay on disk), `FOTLAB-UIXDES-000001` / `FOTLAB-UIXDES-000002` (library owns its screen, top bar and drawer)

## Background & Goal

The Library (图库 / library) destination is the app's first feature module and its current start
destination. FotLab deals with file input and output — original media brought in for editing and
files produced or exported from it. The naive approach would copy every file into an app-owned
store so the app can organise it. That is expensive, duplicates storage and breaks the user's
mental model of "my files live where I put them".

FotLab instead follows the model professional media managers use (Lightroom's Catalog): **files
stay in their original location and the app maintains a purely virtual organisation over them.**

Goals:

- G1 — Files are never moved, copied or duplicated by the app for the sake of organisation. The app
  stores a reference to the physical location plus the structure the user has built on top.
- G2 — Provide one virtual organisation model that is **unified**: the same structure serves both
  input files (source media imported for editing) and output files (results the app produces).
- G3 — Support nested, user-facing grouping ("collection folders") over the file references, in a
  design small enough to remain understandable yet able to express that **one file can appear in
  more than one group**.
- G4 — Keep the conceptual data model at exactly **two logical tables** and fix that shape now, so
  every feature that manages files builds on the same foundation rather than inventing its own.

This document records the **design** of that model only — no code, no schema names. Concrete table
and field names are deliberately left open (see Open Questions).

## Requirement

### R1 — Physical and logical layers are separated; files are never moved for organisation

- A file's bytes stay at their original location (its physical path or content URI) at all times.
- The app persists, per file, only a **reference** to that location plus descriptive metadata
  (display name, type, size, modification date, and so on).
- Organising, grouping, renaming-in-the-view or re-parenting a virtual group never rewrites, copies
  or relocates the underlying file.
- Large binary content (images, RAW/DNG sources, generated results) is never stored inside the
  persisted data store; only references and small metadata are (`FOTLAB-DATABS-000001` R6).

### R2 — Two-table logical model

The whole virtual organisation is described by exactly two logical tables.

- **Table A — nodes.** One row per "thing that can be shown in the tree": a **collection** (a
  virtual, user-visible group/folder) or a **file entry** (a reference to one physical file). A row
  records what the node is (its type) and, for a file entry, the metadata described in R1. A row
  carries **no** notion of "who is above it" — structure is not stored here.
- **Table B — relations.** One row per "X is directly under Y" edge. Each edge names a child node
  and a parent node. Nothing about a node's own properties lives here; the table only answers
  "who sits under whom".

Decoupling "what a node is" from "where a node sits" (Table A vs. Table B) is the core of the
design: it is what lets the same structure be shared by input and output management, and what
makes many-to-many membership trivial.

### R3 — One root collection anchors every tree

- There is exactly one distinguished top-level **root collection** (the "root folder").
- Any collection that the user would otherwise place at the top of the virtual view is a child of
  this root collection, so the view always has a single stable root and never a forest of orphans.
- The root is a regular row of Table A; whether it is created eagerly at first launch, hidden from
  the UI, and whether its parent field is stored or implied by "has no parent" are Open Questions.

### R4 — Membership is many-to-many by adding relation rows

- A file entry may appear under **more than one** collection, and a collection may appear under
  more than one parent.
- Multi-membership costs nothing structurally: the same child appears under several parents by
  adding one relation row per parent (child → parent A, child → parent B). No copies of the file or
  of its node row are created.
- A relation row is unique for a given (child, parent) pair — the same edge cannot be inserted
  twice.
- This is the Lightroom-style "one photo in many collections" behaviour, achieved with only the two
  logical tables of R2.

### R5 — The model is the unified foundation for input and output file management

- Input management (bringing original media into a working set) and output management (referencing
  results the app produces) both build on this same two-table structure and the same node types.
- A node of either kind can be grouped, re-parented and referenced by multiple collections through
  the identical mechanics of R2–R4.
- Features that manage files expose the physical file through the reference stored in Table A and
  never reach for storage abstractions of their own.

### R6 — Structural operations are single small changes

Because structure lives only in relation rows, common operations stay local and predictable:

- Creating a collection: one node row plus one relation row under its parent.
- Moving a collection/file in the virtual view: updating its relation to the new parent (one edge),
  or adding/removing an edge for multi-membership.
- Removing a file entry from a collection: deleting one relation row; the file and its other
  memberships are untouched.

### R7 — Lifecycle and consistency are the feature's responsibility

- Removing a node (collection or file entry) also removes every relation row that references it, so
  no dangling edges survive (`FOTLAB-DATABS-000001` R6 "orphans are not left silently").
- When a file entry is removed, the responsible code must decide whether the referenced physical
  file is also removed, and that removal must be explicit, not a side effect of deleting a row.
- Operations that change structure are atomic as a unit so an interrupted change cannot leave the
  tree half-applied (`FOTLAB-DATABS-000001` R4 transaction discipline).

### R8 — Querying is by relation, composition over recursion

- Children of a node: the children reachable through that node's outgoing relation rows.
- Parents of a node (which collections contain this file): the parents reachable through that node's
  incoming relation rows.
- The UI renders the tree by resolving these relations; no structural field needs to be
  re-derived from string paths. Whether an additional denormalised convenience (for example a
  cached breadcrumb or path) is added later is an Open Question and must not be introduced at
  design time.

## Constraints

- C1 — First-party persistence only: the model is realised with Room (`FOTLAB-DATABS-000001` R1);
  no third-party database or ORM.
- C2 — Large objects stay out of the data store: Table A stores references and metadata only
  (R1).
- C3 — The model never relocates, copies or renames physical files for organisational purposes (R1).
- C4 — Data ownership follows the feature boundary: entities, DAOs and the database for this model
  are owned by one place and reached by other features only through its exported repository
  interface (`FOTLAB-DATABS-000001` R2/R3, `FOTLAB-STRUCT-000001` C3). Where that owner lives — in
  the `library` feature or in the shared `data` package, given the model is reused for input and
  output — is recorded in Open Questions and settled before implementation.
- C5 — No code is written from this document: it records the design and leaves schema names,
  entities, DAOs and migrations to the subsequent implementation items.

## Acceptance Criteria

- AC1 — A node of the model is persisted with its own properties and type but carries no knowledge
  of its position in the structure.
- AC2 — Adding a file entry under two different collections persists two relation rows and results
  in exactly one file-entry row — no file reference is duplicated.
- AC3 — Moving a collection in the virtual view changes only the structure of the relation rows;
  no file reference and no file byte is rewritten.
- AC4 — Querying the children of the root collection returns the top-level collections, and
  querying the parents of a file entry returns every collection that contains it.
- AC5 — Removing a node leaves no relation row referencing it, and no other node's membership is
  altered as a side effect.
- AC6 — Removing a file entry performs no implicit physical-file deletion unless the owning flow
  explicitly requests it.
- AC7 — An interrupted structural change (process death mid-operation) leaves the tree consistent,
  never half-applied.
- AC8 — Both an input media node and an output/result node can be organised, re-parented and
  multi-membership grouped through the same code path.

## Impacted Modules

- `ui/library/` — the Library screen renders the virtual tree (`FOTLAB-UIXDES-000001`/`000002`)
- The feature (or shared) package that owns this model's persistence and repository — location TBD
  (C4, Open Questions)
- `data/` — shared Room infrastructure, converters and migration helpers (`FOTLAB-DATABS-000001`)
- Future file-management features (input import, output/export) that consume the exported
  repository — this model is their shared foundation
- `FOTLAB-DATABS-000001` — the persistence discipline this model is realised with

## Open Questions

- Q1 — Concrete table, entity, DAO and field names are intentionally not fixed here. The two tables
  of R2 need agreed names, and their columns (identity, type, reference, the child/parent edges)
  need agreed field names. **TBD before implementation.**
- Q2 — Is the root collection an explicit, stored row (eagerly created), or an implicit convention
  (rows with no parent are top-level)? Where does it appear — hidden from the UI, or shown as a
  label for the top-level group? **TBD.**
- Q3 — Is the structure a strict tree for collections (each collection has exactly one parent) with
  many-to-many reserved for file entries, or a full directed acyclic graph where any node may have
  many parents? R4 permits both; the invariant is undecided. **TBD.**
- Q4 — How are cycles prevented (a collection must not become its own ancestor), and is this
  enforced at the repository, database or UI layer? **TBD.**
- Q5 — Is child ordering (sort order) stored, and if so where — as a column on a relation row or on
  the node — given that a file under several collections may need a different position in each?
  **TBD.**
- Q6 — Are file entries deduplicated by their physical reference (one node per unique file,
  unique-constrained) so the same physical file maps to one row shared by many collections? **TBD.**
- Q7 — Is a denormalised convenience (cached path or breadcrumb) added later for faster ancestry
  reads? Deferred by R8; must not be introduced at design time. **TBD.**
- Q8 — Where does this model's persistence and repository owner live: inside the `library` feature
  package, or in the shared `data` package given input and output features will reuse it (C4)?
  **TBD.**
- Q9 — Do collections map onto any platform media notion (for example a MediaStore album) at
  import time, or are they purely app-defined virtual groups? **TBD.**
- Q10 — Deletion policy: is there a recycle-bin / soft-delete step before a node (and its files)
  are irreversibly removed, and how does the physical-file deletion decision of R7/AC6 surface to
  the user? **TBD.**

## Change History

- 2026-09-08 — Initial draft. Decided the Library (图库/library) module's foundation: files remain
  in place and are referenced, never moved or copied, for organisation; a two-table logical model
  (a node table for what nodes are, a relation table for who sits under whom) delivers nested
  collections and many-to-many membership by adding relation rows; one root collection anchors the
  view; the model is the unified basis for both input and output file management. Left concrete
  schema names, tree-vs-DAG invariants, cycle and ordering rules, deduplication, ownership location
  and deletion policy as Open Questions Q1–Q10.
