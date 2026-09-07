# Single-Module Source Layout

- ID: FOTLAB-STRUCT-000001
- Status: Draft
- Priority: P0
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-UIXDES-000001` (shell/destination contract), `FOTLAB-UIXDES-000002` (per-destination top bar and drawer), `FOTLAB-UIXDES-000003` (strings blocks), `FOTLAB-DATABS-000001` (persistence ownership), `FOTLAB-NATIVE-000001` (third-party source location)

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
modules.

Goals:

- G1 — One module, one build script, one manifest, one resource set.
- G2 — Keep the layering that mattered (`ui` / `navigation` / `data`) as packages under one root
  package, so structure is still visible in the tree.
- G3 — Keep destinations self-contained: adding one touches a small, predictable set of places.
- G4 — Record honestly what is lost: dependency isolation is no longer enforced by the build system,
  it is now a review convention (see C3).

## Requirement

### R1 — `:app` is the only first-party module

- `settings.gradle.kts` contains exactly one `include(":app")`.
- No new top-level source directory is created: there is no `core/`, no `feature/`, no
  `common/` at the repository root. All first-party Kotlin and resources live under `app/src/`.
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
│   ├── ui/                       ← composables and theme
│   │   ├── theme/                ← Material3 theme and shared primitives
│   │   ├── MainWindowFrame.kt    ← shell: the two-region frame
│   │   ├── MainNavigationBar.kt  ← shell: the only persistent UI
│   │   └── <feature>/            ← one package per destination: its screens
│   ├── navigation/               ← routes, destination set, graph assembly
│   │   ├── TopLevelDestination.kt
│   │   ├── RootNavHost.kt
│   │   └── <feature>/            ← one package per destination: its graph and route
│   └── data/                     ← persistence and shared Room infrastructure
└── res/                          ← single resource set
```

- The root package is `io.github.fotlab.fotlab`; the layer packages `ui`, `navigation` and `data`
  are its direct children.
- `ui/` — everything composable: the shell frame, the persistent bottom bar, the theme, and one
  `ui/<feature>/` package per destination holding that destination's screens.
- `navigation/` — the destination set (routes, labels, icons), the root `NavHost`, and one
  `navigation/<feature>/` package per destination holding its route constant and its
  `NavGraphBuilder.<feature>Graph()` entry point.
- `data/` — Room infrastructure, converters, repositories and DAOs.
- Further layer packages (`domain/`, `imaging/`, …) may be added later; each addition is recorded in
  the Change History of this item.
- Feature packages are named after the destination, lower case, one word where possible
  (`gallery`, `render`, `import`).

### R3 — Dependency direction between packages

- `data` depends on neither `ui` nor `navigation`.
- `navigation` may reference screens in `ui/<feature>/` (a graph composes screens).
- `ui` (shell level) may reference `navigation` (routes, destination set, graph entry points).
- `ui/<feature>/` must not reference `navigation`: a screen receives what it needs through its
  parameters and callbacks, so a screen stays reusable and testable without a `NavController`.
- No package-level dependency cycle between `ui` and `navigation`.

### R4 — Adding a destination

Adding a top-level destination touches exactly these places, in this order:

1. `ui/<feature>/` — the screens of the destination.
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
  `data` (or a later layer package recorded here). No code at the root package level other than
  `FotLabApplication` and `MainActivity`.
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
  `io.github.fotlab.fotlab`, and its second segment is `ui`, `navigation` or `data` (or a recorded
  later layer); the only exceptions are `FotLabApplication.kt` and `MainActivity.kt`.
- AC4 — No file under `data/` imports anything from `ui` or `navigation`.
- AC5 — No file under `ui/<feature>/` imports anything from `navigation`.
- AC6 — Adding a destination following R4 requires no change to any `build.gradle.kts`, to
  `settings.gradle.kts`, or to `AndroidManifest.xml`.
- AC7 — `res/values/strings.xml` is the only strings file in the project.

## Impacted Modules

- `settings.gradle.kts`, `build.gradle.kts` (root), `app/build.gradle.kts` — single-module build
- `app/src/main/kotlin/io/github/fotlab/fotlab/{ui,navigation,data}` — the layer packages
- `FOTLAB-UIXDES-000001` — the shell/destination contract, now expressed in packages
- `FOTLAB-UIXDES-000003` — strings blocks now live in one file
- `FOTLAB-DATABS-000001` — database ownership now expressed by naming, not by module
- `FOTLAB-NATIVE-000001` — `app/` is now the only first-party location

## Open Questions

- Q1 — At what size does the project split back into Gradle modules (build time, module count,
  team size)? **TBD.** The trigger should be a measurable threshold, not a feeling.
- Q2 — Is a native-integration layer (the `NATIVE` items) a package inside `app/` or the first
  reason to re-introduce a second module? **TBD.**
- Q3 — Is the package-dependency rule of R3 ever checked automatically, for example by a custom
  lint check (first-party) instead of a third-party architecture-test library?
- Q4 — Does `data/` stay flat, or does it grow `data/<feature>/` packages once several features
  persist data?

## Change History

- 2026-09-07 — Initial draft. Decided that FotLab is a single Gradle module `:app`: `core/ui`,
  `core/data` and `feature/gallery` were merged into it, their code re-homed under
  `io.github.fotlab.fotlab.{ui,navigation,data}`, the gallery screen moved to `ui/gallery/` and its
  graph to `navigation/gallery/`, and all string resources merged into the single
  `app/src/main/res/values/strings.xml`. Defined the package layout, the dependency direction
  between `ui`, `navigation` and `data`, the five-step recipe for adding a destination, and recorded
  that dependency isolation is now a review convention rather than a build-enforced guarantee.
  Split-back triggers, the place of native code, and automated checking left open as Q1–Q4.
