# Local Structured Persistence — Room DAO Rules

- ID: FOTLAB-DATABS-000001
- Status: Draft
- Priority: P1
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-UIXDES-000001` (module autonomy), `FOTLAB-UIXDES-000002` (module-owned UI), `FOTLAB-NATIVE-000001` (how native code reaches third-party modules)

## Background & Goal

FotLab stores structured data locally: module settings, processing history, style presets, album
indices, metadata caches. Left unconstrained, each module invents its own storage — shared
preferences for record-like data, raw SQL here, a JSON file there — and the result can be neither
queried nor migrated safely.

Goal: **all structured local persistence goes through Android's first-party DAO layer, Room**, with
one layering, one ownership model and one migration discipline across every module.

## Requirement

### R1 — Room is the only structured persistence layer

- Structured data is persisted with **Room**: `androidx.room:room-runtime`, `room-ktx`, and `room-compiler` applied through KSP.
- Room runs on the SQLite engine shipped with the Android platform; no database engine is bundled into the APK.
- Third-party persistence libraries are not used: no SQLDelight, ObjectBox, Realm, GreenDAO, no self-written ORM wrapper.
- Hand-written `SQLiteOpenHelper` code is not used; if a case appears that Room cannot express, it is registered here as an exception before the code is written.
- Non-structured key–value flags may use `androidx.datastore` or `SharedPreferences`. They are not a substitute for records that are queried, filtered or related to each other.

### R2 — Layering and visibility

```
UI (Compose) → ViewModel → Repository → DAO → Room Database
```

- UI code never references a DAO, an `Entity` or the `Database` type. It only observes state exposed by a `ViewModel`.
- DAOs are visible inside the data layer of the owning module only.
- A module that needs another module's data depends on that module's exported `Repository` interface. Cross-module DAO or entity references are forbidden.
- Native code never touches the database. Work involving `external/dnglab` or `external/exiftool` goes through a dedicated first-party native-integration layer; persistence of the results is done by Kotlin code.

### R3 — Data ownership follows module boundaries

- Each feature owns its entities, DAOs and database; naming is `<Feature>Entity`, `<Feature>Dao`, `<Feature>Database`.
- Default: **one Room database per feature**, so a feature can add, change or drop its schema without a shared `@Database` class that every feature has to edit.
- Shared infrastructure (common type converters, migration helpers, in-memory test rule) lives in the `data` package; it holds no entity of any feature.

### R4 — Threading and asynchronous access

- Main-thread database access is never allowed. `allowMainThreadQueries()` is not used, so Room itself enforces this.
- Write operations are `suspend` functions or run inside `withTransaction`.
- Read operations exposed to the UI return `Flow` (or `PagingSource` for long lists) so the UI reacts to changes instead of polling.
- Queries returning one-shot results are `suspend`.

### R5 — Schema and migration discipline

- `exportSchema = true` for every database; the generated JSON schemas are committed to the repository.
- Every version change ships an explicit `Migration` or a verified `AutoMigration`.
- `fallbackToDestructiveMigration` — and its variants — are forbidden in release builds.
- Database `version` is an internal schema counter; it is unrelated to `VERSION_NAME` / `VERSION_CODE`.

### R6 — Large objects stay out of the database

- Images, RAW files, LUTs, thumbnails and other binaries are stored on the file system. The database stores only the path/URI plus descriptive metadata.
- Entities do not carry large binary columns; a row stays small enough to be queried and paginated cheaply.
- When a record is deleted, the module is responsible for the referenced file; orphan files are not left behind silently.

### R7 — Build configuration

- Annotation processing uses **KSP** (`com.google.devtools.ksp`), not KAPT.
- Room's compile-time verification runs on every build; schema and query errors fail the build rather than the app start.

### R8 — Testing

- DAO tests run against `Room.inMemoryDatabaseBuilder`; they do not touch the on-device database file.
- Every migration has a migration test that upgrades from the previous version with data present.

## Constraints

- C1 — First-party Room only, per R1; any exception is recorded in this item's Change History before it is implemented.
- C2 — UI and feature code must not import DAO, entity or database types; the dependency direction is UI → ViewModel → Repository → DAO.
- C3 — A feature must not reference another feature's DAO or entity directly; only exported repositories cross feature boundaries (`FOTLAB-STRUCT-000001` C3).
- C4 — No main-thread access; no `allowMainThreadQueries()`.
- C5 — No destructive migration fallback in release builds; every schema version change is accompanied by a committed schema JSON.
- C6 — No large binaries in the database.
- C7 — Native code (dnglab, exiftool, or any other third-party module) never opens the database.

## Acceptance Criteria

- AC1 — A dependency report of the data layer lists only `androidx.room` artifacts (plus KSP as a build plugin); no third-party database library appears.
- AC2 — An architecture check (package review or lint rule) shows no UI/feature package importing a DAO, entity or database class.
- AC3 — Calling a DAO query on the main thread throws Room's main-thread exception in a test; the same holds for every DAO.
- AC4 — For each database, the schema directory contains one JSON per released version, and any version bump adds exactly one new JSON plus a migration entry.
- AC5 — A release build fails (or a review checklist rejects it) if `fallbackToDestructiveMigration` is configured.
- AC6 — Inspecting all entity definitions shows no binary/blob column; image-carrying records store a path or URI instead.
- AC7 — DAO tests execute against an in-memory database and pass; a migration test upgrades real data from the previous version without loss.

## Impacted Modules

- `data` package — shared converters, migration helpers, in-memory test rule
- Every feature package that persists structured data — owns its entities, DAOs and database
- `FOTLAB-NATIVE-000001` — governs how native code reaches third-party modules, keeping it out of the database

## Open Questions

- Q1 — One database per module (current default, R3) or a single application database listing all entities? **TBD.** A single database enables cross-module transactions at the cost of a shared, coupled `@Database` class.
- Q2 — Is a dependency-injection framework (Hilt) introduced to provide database and repository instances? **TBD.** The default here is manual construction from an application-level provider, to stay on first-party APIs.
- Q3 — Is any stored data sensitive enough to require encryption at rest? SQLCipher and similar libraries are third-party and would conflict with R1, so this needs an explicit decision.
- Q4 — Is Paging required for long lists, and is full-text search (FTS) needed anywhere?
- Q5 — Where does the database file live (internal storage only), and is it excluded from or included in platform backup/transfer?
- Q6 — How does the metadata cache kept by exiftool-backed flows stay consistent with the database? This will need a `METADA` item.

## Change History

- 2026-09-07 — Initial draft. Established Room as the only structured persistence layer, the UI → ViewModel → Repository → DAO → Database layering with module-owned data, the no-main-thread rule, the migration and schema-export discipline, the rule that large binaries stay on the file system, KSP-only build configuration, and in-memory/migration testing. Left single-vs-multi database, DI, encryption, paging/FTS, backup and metadata-cache consistency open as Q1–Q6.
- 2026-09-07 — Updated for the single-module layout (`FOTLAB-STRUCT-000001`): ownership is per feature package instead of per Gradle module, `<Module>*` naming became `<Feature>*`, shared infrastructure lives in the `data` package, and the architectural check of AC2 is a package review rather than a module dependency graph. Room as the only persistence layer, the layering, the threading and migration discipline are unchanged.
