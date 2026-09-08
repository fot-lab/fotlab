# Single-Module Source Layout with a feature Package

- ID: FOTLAB-STRUCT-000001
- Status: Draft
- Priority: P0
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-UIXDES-000001` (shell/destination contract), `FOTLAB-UIXDES-000002` (per-destination top bar and drawer — the screen owns both), `FOTLAB-UIXDES-000003` (strings blocks), `FOTLAB-DATABS-000001` (persistence ownership), `FOTLAB-NATIVE-000001` (third-party source location), `FOTLAB-IMGMGR-000001` (the gallery module this layout hosts), `FOTLAB-DATABS-000002` (the gallery's fs_node schema, owned by the gallery's lower layer)

## Background & Goal

The codebase was first laid out as a multi-module Gradle build — `:app` (shell), `:core:ui`,
`:core:data`, `:feature:gallery`. That layout is the one Google recommends for large apps
(`developer.android.com/topic/modularization`, and the shape of `android/nowinandroid`), and it buys
hard dependency isolation: modules that must not depend on each other *cannot*, because the build
system rejects it.

For FotLab at its current size the cost outweighed the benefit: one `build.gradle.kts`, one manifest
and one resource set per module, every new destination starting with a new Gradle module, and every
shared symbol having to travel through `:core:*`. The maintenance overhead was disproportionate.

Decision: **the project is a single Gradle module `:app`.** Layers are expressed as packages, not as
modules. Each independent screen additionally gets its own `feature/<name>/` package that holds both
its UI and its lower layer, so a screen and the logic behind it travel together.

Goals:

- G1 — One module, one build script, one manifest, one resource set.
- G2 — Keep the layering that mattered (`ui` / `navigation` / `data` / `feature`) as packages under
  one root package, so structure is still visible in the tree.
- G3 — Keep destinations self-contained: adding one touches a small, predictable set of places.
- G4 — Record honestly what is lost: dependency isolation is no longer enforced by the build system,
  it is now a review convention (see C3).
- G5 — Each independent screen is a `feature/<name>/` package containing both its UI
  (`<Name>Screen.kt`, owning its top app bar and drawer) and its lower layer (`<Name>Core.kt`,
  repository/data access), so a screen and its backing logic stay in one place.
- G6 — `ui/` is reserved for the shell (frame, bottom bar) and theme; it does not hold per-screen UI.

## Requirement

### R1 — `:app` is the only first-party module

- `settings.gradle.kts` contains exactly one `include(":app")`.
- No new top-level source directory is created: there is no `core/`, no `feature/` at the repository
  root. All first-party Kotlin and resources live under `app/src/`.
- Third-party source is unaffected by this rule and stays under `external/`
  (`FOTLAB-NATIVE-000001`).
- If the project ever splits again into modules, that decision is recorded in this item's Change
  History *before* the first module is added (see Q1).

### R2 — Package layout under the single root package

```
app/src/main/
├── AndroidManifest.xml
├── kotlin/io/github/fotlab/fotlab/
│   ├── FotLabApplication.kt
│   ├── MainActivity.kt
│   ├── ui/                       ← shell composables and theme only
│   │   ├── theme/                ← Material3 theme and shared primitives
│   │   ├── MainWindowFrame.kt    ← shell: the two-region frame
│   │   └── MainNavigationBar.kt  ← shell: the only persistent UI
│   ├── navigation/               ← routes, destination set, graph assembly
│   │   ├── TopLevelDestination.kt
│   │   ├── RootNavHost.kt
│   │   └── <feature>/            ← one package per destination: its graph and route
│   ├── data/                     ← shared Room infrastructure only
│   │   └── (converters, migration helpers, in-memory test rule)
│   └── feature/                  ← one package per independent screen
│       └── gallery/
│           ├── GalleryScreen.kt  ← UI: owns its TopAppBar + drawer (UIXDES-000001/000002)
│           └── GalleryCore.kt    ← lower layer: repository / data access for the gallery tree
```

- The root package is `io.github.fotlab.fotlab`; the layer packages `ui`, `navigation`, `data` and
  `feature` are its direct children. `feature` is a sibling of `ui`, `navigation` and `data`.
- `ui/` — the shell only: the frame, the persistent bottom bar, the theme, and shared primitives.
  No per-screen UI lives here.
- `navigation/` — the destination set (routes, labels, icons), the root `NavHost`, and one
  `navigation/<feature>/` package per destination holding its route constant and its
  `NavGraphBuilder.<feature>Graph()` entry point.
- `data/` — Room infrastructure shared by every feature: converters, migration helpers and the
  in-memory test rule. Feature-owned entities, DAOs and repositories live in their own feature
  package, never here (`FOTLAB-DATABS-000001` R3).
- `feature/` — one `feature/<name>/` package per independent screen. Each package contains the
  screen UI (`<Name>Screen.kt`) and its lower layer (`<Name>Core.kt`). Feature packages are named
  after the destination, lower case, one word where possible (`gallery`, `render`, `import`).
- `GalleryScreen` is the gallery screen (UI); it owns its top app bar and drawer per
  `FOTLAB-UIXDES-000001` / `FOTLAB-UIXDES-000002`. `GalleryCore` is the gallery's lower layer and is
  the natural owner of the gallery's persistence — the `fs_node` schema of `FOTLAB-DATABS-000002`.

### R3 — Dependency direction between packages

- `data` depends on neither `ui`, `navigation` nor `feature`.
- `navigation` may reference screens in `feature/<feature>/` (a graph composes screens).
- `ui` (shell level) may reference `navigation` (routes, destination set, graph entry points).
- `feature/<name>/` UI (`<Name>Screen`) must not reference `navigation`: a screen receives what it
  needs through its parameters and callbacks, so it stays reusable and testable without a
  `NavController`.
- Inside `feature/<name>/`: `<Name>Screen` depends on `<Name>Core`; `<Name>Core` must not depend on
  `<Name>Screen`. The core may depend on `data` (shared infrastructure) and, when needed, on the
  first-party native-integration layer, but never on `ui` or `navigation`.
- No package-level dependency cycle among `ui`, `navigation`, `data` and `feature`.

### R4 — Adding a destination

Adding a top-level destination touches exactly these places, in this order:

1. `feature/<name>/` — `GalleryScreen.kt` (UI) and `GalleryCore.kt` (lower layer).
2. `navigation/<feature>/` — the route constant and `fun NavGraphBuilder.<feature>Graph()`.
3. `navigation/RootNavHost.kt` — one `<feature>Graph()` call.
4. `navigation/TopLevelDestination.kt` — one enum entry (route, label, icon).
5. `res/values/strings.xml` — one new block, inserted after the existing feature blocks
   (`FOTLAB-UIXDES-000003` R2).

No Gradle file and no manifest is touched.

### R5 — Resources are single too

- One `res/` set for the whole app. No per-feature resource directories.
- `strings.xml` is one file, ordered in blocks: `common` → `app` → one block per feature
  (`FOTLAB-UIXDES-000003`).

## Constraints

- C1 — `settings.gradle.kts` lists `:app` and nothing else.
- C2 — All first-party Kotlin lives under `io.github.fotlab.fotlab`, inside `ui`, `navigation`,
  `data` or `feature` (or a later layer package recorded here). No code at the root package level
  other than `FotLabApplication` and `MainActivity`.
- C3 — Dependency direction follows R3. Since Gradle no longer enforces it, violation is caught in
  review; no third-party architecture-test library is introduced to re-create the guarantee
  (`FOTLAB-UIXDES-000001` R1 — first-party APIs only).
- C4 — Adding a destination never adds a Gradle module, a manifest or a resource set.
- C5 — Third-party source never enters `app/`; it stays under `external/`
  (`FOTLAB-NATIVE-000001` R1).

## Acceptance Criteria

- AC1 — `settings.gradle.kts` contains exactly one `include()` call, `:app`.
- AC2 — Listing the repository root shows no first-party source directory other than `app/`.
- AC3 — Every Kotlin file under `app/src/main/kotlin` declares a package starting with
  `io.github.fotlab.fotlab`, and its second segment is `ui`, `navigation`, `data` or `feature` (or a
  recorded later layer); the only exceptions are `FotLabApplication.kt` and `MainActivity.kt`.
- AC4 — No file under `data/` imports anything from `ui`, `navigation` or `feature`.
- AC5 — No file under `feature/<name>/` imports anything from `navigation`; and `GalleryCore` (or any
  `<Name>Core`) does not import from `ui`.
- AC6 — Adding a destination following R4 requires no change to any `build.gradle.kts`, to
  `settings.gradle.kts`, or to `AndroidManifest.xml`.
- AC7 — `res/values/strings.xml` is the only strings file in the project.
- AC8 — A screen package (`feature/<name>/`) contains both `<Name>Screen` (UI, owning its top app bar
  and drawer) and `<Name>Core` (lower layer); the screen depends on the core and the core never
  depends on the screen.

## Impacted Modules

- `settings.gradle.kts`, `build.gradle.kts` (root), `app/build.gradle.kts` — single-module build
- `app/src/main/kotlin/io/github/fotlab/fotlab/{ui,navigation,data,feature}` — the layer packages
- `FOTLAB-UIXDES-000001` — the shell/destination contract, now expressed in packages
- `FOTLAB-UIXDES-000003` — strings blocks now live in one file
- `FOTLAB-DATABS-000001` — database ownership now expressed by naming, not by module
- `FOTLAB-NATIVE-000001` — `app/` is now the only first-party location
- `FOTLAB-IMGMGR-000001` / `FOTLAB-DATABS-000002` — the gallery module and its fs_node schema are
  hosted by `feature/gallery` (GalleryScreen + GalleryCore)

## Open Questions

- Q1 — At what size does the project split back into Gradle modules (build time, module count,
  team size)? **TBD.** The trigger should be a measurable threshold, not a feeling.
- Q2 — Is a native-integration layer (the `NATIVE` items) a package inside `app/` or the first
  reason to re-introduce a second module? **TBD.**
- Q3 — Is the package-dependency rule of R3 ever checked automatically, for example by a custom
  lint check (first-party) instead of a third-party architecture-test library?
- Q4 — `data/` stays flat and shared (converters, migration, test rule) while each feature owns its
  core in `feature/<name>/` (current default). Is that split stable, or does shared infrastructure
  grow per-feature sub-packages later? **TBD.**
- Q5 — May a `feature/<name>/` package ever hold more than the two files `GalleryScreen.kt` and
  `GalleryCore.kt` (for example nested sub-screens)? **TBD.**

## Change History

- 2026-09-07 — Initial draft. Decided that FotLab is a single Gradle module `:app`: `core/ui`,
  `core/data` and `feature/gallery` were merged into it, their code re-homed under
  `io.github.fotlab.fotlab.{ui,navigation,data}`, the gallery screen moved to `ui/gallery/` and its
  graph to `navigation/gallery/`, and all string resources merged into the single
  `app/src/main/res/values/strings.xml`. Defined the package layout, the dependency direction
  between `ui`, `navigation` and `data`, the five-step recipe for adding a destination, and recorded
  that dependency isolation is now a review convention rather than a build-enforced guarantee.
  Split-back triggers, the place of native code, and automated checking left open as Q1–Q4.
- 2026-09-08 — Extracted STRUCT out of the DESIGN rule domain into its own peer rule
  (`rules/STRUCT.md` + `rules/STRUCT/detail/`), moving this item with it. Added a `feature` package
  as a sibling of `ui`, `navigation` and `data`: each independent screen is now a
  `feature/<name>/` package holding both its UI (`<Name>Screen`, owning its top app bar and drawer per
  `FOTLAB-UIXDES-000001`/`-000002`) and its lower layer (`<Name>Core`, repository/data access), with
  the gallery example `GalleryScreen` + `GalleryCore`; `ui/` is now shell plus theme only. Updated
  R2/R3/R4, constraints C2/C3, acceptance criteria (added AC8) and Impacted Modules, linked
  `FOTLAB-IMGMGR-000001` and `FOTLAB-DATABS-000002`, and replaced Q4 with the data-vs-feature-core
  split question plus a new Q5 on package size.
