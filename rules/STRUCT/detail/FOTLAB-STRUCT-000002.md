# Source Hygiene — No Build Artifacts in Source, No Circular Dependencies

- ID: FOTLAB-STRUCT-000002
- Status: Draft
- Priority: P1
- Created: 2026-09-08
- Owner: —
- Related: `FOTLAB-STRUCT-000001` (single-module layout; dependency isolation is now a review convention, not build-enforced), `FOTLAB-DATABS-000001` (Room persistence discipline; `exportSchema = false` — schema JSON is a build artifact and is not committed), `FOTLAB-DATABS-000002` (the library's `fs_node` schema, realised without committed schema files), `FOTLAB-STRUCT-000003` (naming — avoid product-specific tokens; no duplicate components)

## Background & Goal

`FOTLAB-STRUCT-000001` made the project a **single Gradle module** and expressed layering as
packages (`ui` / `navigation` / `navigation.<feature>` / `feature.<name>` / `data`) instead of Gradle
modules. The benefit is simpler builds; the cost is that the **compiler no longer rejects a bad
dependency** — a package can import another that imports it back, and a generated file can be edited or
committed without error.

Two hygiene hazards follow, and both must be held by convention because the build will not stop them:

1. **Build artifacts in source** — compiled output, generated source, or exported schemas slipped into
   the tree and committed. They are non-source, drift silently from their generator, and create noisy
   diffs and false conflicts. (Precedent: `FOTLAB-DATABS-000001` R5 already set `exportSchema = false`
   so the Room schema JSON — a pure build artifact — is never committed.)
2. **Circular package dependencies (回环)** — A depends on B which depends back on A, directly or
   transitively, so neither can be understood or changed alone.

Goal: state both rules once, with the allowed dependency direction and the checks that keep them true.

## Requirement

### R1 — No build artifact is committed or edited in source

- Never commit files produced by the build. This covers, at minimum: anything under `build/`,
  `.gradle/`, `.kotlin/`, `bin/`, `gen/`, `out/`, `captures/`, `.externalNativeBuild/`, `.cxx/`;
  compiled outputs `*.class`, `*.dex`; packaged outputs `*.apk`, `*.aab`; IDE files `.idea/`, `*.iml`,
  `*.ipr`, `*.iws`; `local.properties`; and any generated *source* (Room `*_Impl`, Hilt/Dagger
  components, KSP/KAPT output, view bindings, `BuildConfig`).
- Generated source is consumed **only** from the build's generated-source directories. It is never
  hand-edited and never copied into `src/`.
- Room databases set `exportSchema = false` (`FOTLAB-DATABS-000001` R5): the schema JSON is a build
  artifact and must not be committed. Migration safety rests on Room's runtime schema validation, which
  derives the expected schema from the entities — no committed file required.
- `.gitignore` already excludes the standard outputs. Do not relax it to allow a generated file in.

### R2 — No circular package dependencies

The intended dependency direction is a directed acyclic graph (downward only):

```
root (MainApplication, MainActivity)
  ├─▶ ui            (shell: MainWindowFrame, MainNavigationBar, theme)
  │     └─▶ navigation                 (RootNavHost, TopLevelDestination)
  │           └─▶ navigation.<feature> (library: LibraryGraph, LibraryDestination)
  │                 └─▶ feature.<name> (LibraryScreen → LibraryCore → LibraryRepository → LibraryDatabase → entities/DAOs)
  └─▶ feature.<name>  (root may prepare/own a feature's lower layer, e.g. MainApplication → LibraryCore)

data  (shared converters, in-memory test rule) — depended on by features, depends on nothing first-party.
```

- A package that is depended on must not depend back, directly or transitively.
- `feature.<name>` must **never** import `ui` or `navigation`. It may expose a screen composable and a
  `Core` entry; the shell reaches it through `navigation.<feature>`, never the other way.
- `navigation.<feature>` may import the feature's screen as its single assembly boundary; `navigation`
  (shell level) reaches features only through `navigation.<feature>`, never importing a feature type
  directly.
- Same-package references carry direction too, even though Kotlin needs no `import` for them: within
  `feature.<name>`, the order is `Screen → Core → Repository → Database → entities`. `Core` must never
  depend on `Screen`.
- `data` is a leaf: features may use it, but `data` must not import `ui`, `navigation`, or `feature.*`.

### R3 — Cycle analysis exempts generated/resource classes

- References to `R`, `BuildConfig`, and other generated/resource classes are **not** counted as
  dependency edges. Every package legitimately references `R`; treating it as an edge would report a
  false cycle with the root package. Only first-party Kotlin *source* dependencies count.
- Only `import` statements of first-party packages (`io.github.fotlab.fotlab.*`, excluding `...R` and
  `...BuildConfig`) are edges.

### R4 — Enforcement is by review and check, not the build

- Because the project is one module (GOTLAB-STRUCT-000001), the compiler does not reject a cycle or a
  stray artifact. These rules are kept by code review and the acceptance checks below; a future CI job
  may automate them (see Open Questions Q1).

## Constraints

- C1 — No build artifact is committed or edited under `src/`; generated code lives only in build output.
- C2 — `exportSchema = false` for every Room database; no schema JSON is committed.
- C3 — No package import cycle (direct or transitive). In particular `feature.*` does not import `ui`
  or `navigation`, and `navigation` does not depend back on a feature's UI beyond the single
  graph-assembly entry.
- C4 — `.gitignore` keeps build output out of the tree and is not loosened to admit generated files.

## Acceptance Criteria

- AC1 — A clean checkout tracks no `build/`, `*.class`, `*.dex`, `*.apk`, schema JSON, or generated
  `*_Impl` file. (Currently true; this AC guards regressions.)
- AC2 — A package-dependency review (manual or tool) finds no cycle among `ui`, `navigation`,
  `navigation.<feature>`, `feature.<name>`, and `data`.
- AC3 — No `feature.*` source file imports `io.github.fotlab.fotlab.ui` or
  `io.github.fotlab.fotlab.navigation` (the `R` class and generated classes are exempt). Currently
  satisfied: `feature.library` imports only `R`.
- AC4 — `.gitignore` excludes build output and has not been relaxed.

## Impacted Modules

- Every package under the app root: `ui`, `navigation`, `navigation.<feature>`, `feature.<name>`,
  `data`, and the root `MainApplication` / `MainActivity`.
- `app/build.gradle.kts` and `.gitignore` (artifact exclusion).

## Open Questions

- Q1 — Should cycle detection and artifact-checking be automated in CI (parse first-party `import`s
  into a graph; fail on a cycle or on a tracked generated file)? **TBD.** Until then the checks are
  review-based.
- Q2 — Should `app/schemas/` be added to `.gitignore` as a belt-and-suspenders, even though
  `exportSchema = false` means it is never generated? (Yes — added; keeps the rule robust if a database
  later flips the flag.)

## Change History

- 2026-09-08 — Initial draft. Prohibits committing build artifacts (incl. Room schema JSON — consistent
  with `FOTLAB-DATABS-000001` R5's `exportSchema = false` stance) and prohibits circular package
  dependencies. Sets the allowed DAG, carves generated/resource classes (`R`, `BuildConfig`) out of
  cycle analysis, and states that enforcement is review/check-based because the project is a single
  module. `app/schemas/` added to `.gitignore` as a safeguard.
- 2026-09-08 — Applied `FOTLAB-STRUCT-000003`: renamed `FotLabApplication` → `MainApplication`
  (class, file and manifest `android:name`), and removed the redundant non-compliant duplicates
  `FotLabApp` and `FotLabBottomBar` (unused shells duplicating `MainWindowFrame` / `MainNavigationBar`).
  Updated the DAG and every `FotLabApplication` reference accordingly.
