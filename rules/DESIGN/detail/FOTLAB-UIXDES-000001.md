# Overall UI Shell Architecture

- ID: FOTLAB-UIXDES-000001
- Status: Draft
- Priority: P1
- Created: 2026-09-07
- Owner: —
- Related: native code is reached only through a dedicated first-party native-integration layer (see `FOTLAB-NATIVE-000001`)

## Background & Goal

FotLab is a photography application whose feature set will grow module by module. The top-level
shell therefore has to be small, stable and boring: it defines *where* things live, not *what*
they do. Everything feature-specific belongs to a module that owns its own screen.

Goals:

- G1 — Define the one-level UI structure: a persistent bottom navigation region plus a content region that is fully delegated to the active module.
- G2 — Build the UI as far as possible on first-party APIs only: Android platform APIs and Material3 (`androidx.compose.material3`), with no third-party UI component library.
- G3 — Keep the shell independent of feature modules so that modules can be added, replaced or removed without touching the shell.

## Requirement

### R1 — Native-first UI stack

- The UI is written in Kotlin with Jetpack Compose.
- Allowed dependencies are first-party only:
  - `androidx.compose.material3` — `NavigationBar`, `NavigationBarItem`, `Scaffold`, theming, `MaterialTheme`, colour scheme, typography
  - `androidx.navigation.compose` — `NavHost`, `NavController`, `NavGraphBuilder`
  - `androidx.lifecycle` / `androidx.lifecycle.compose` — `ViewModel`, lifecycle-aware state collection
  - `androidx.activity` — `ComponentActivity`, `enableEdgeToEdge()`
  - `androidx.compose.material:material-icons-core` (and `-extended` only if a needed icon is missing)
- Third-party UI component libraries, design systems or widget packs are **not** introduced by default. If a concrete case cannot be covered by the above APIs, the exception and its justification are recorded in the Change History of this item before the dependency is added.
- Theming uses Material3 dynamic colour where the platform provides it, with a static fallback scheme.

### R2 — One-level shell split into two vertical regions

The top-level screen consists of exactly two regions:

```
┌───────────────────────────────────────┐
│                                       │
│   Content region                      │  ← owned by the active module
│   swaps when navigation changes       │
│                                       │
├───────────────────────────────────────┤
│   Bottom navigation region            │  ← the only persistent UI in the app
└───────────────────────────────────────┘
```

- **Bottom region** — reserved exclusively for the Material3 `NavigationBar`. It is the **only** persistent content in the entire application: it is not rebuilt, replaced or hidden as a result of navigation, and no other element (top app bar, FAB, drawer, snackbar host) is persistent at application level.
- **Top region** — hosts the currently selected destination. Each module owns everything inside it: its own top app bar, floating action button, dialogs, sheets, empty/error states and any nested navigation.
- The shell does not interpret, wrap or decorate module content. It provides the region; the module fills it.

### R3 — Bottom navigation behaviour

- Implemented with Material3 `NavigationBar` and `NavigationBarItem` (icon + label, `alwaysShowLabel = true`).
- Holds 3–5 top-level destinations, per Material3 guidance.
- Selection state is derived from the current back stack entry, not from local mutable state, so that system back and deep links keep the bar in sync.
- Navigating between destinations uses:

  ```
  navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) { saveState = true }
      launchSingleTop = true
      restoreState = true
  }
  ```

- The concrete destination set (routes, labels, icons, order, start destination) is **TBD** — see Open Questions.

### R4 — Module autonomy

- Each top-level module contributes exactly one navigation graph through a single assembly entry point, e.g. `fun NavGraphBuilder.featureXGraph(...)`.
- The shell assembles graphs; it does not know what is inside them.
- Feature packages must not depend on each other. Cross-feature navigation, when needed, goes through routes owned by the shell or a dedicated navigation contract. This is a review convention, not a build-enforced rule — see `FOTLAB-STRUCT-000001` C3.
- A module may only render inside its own content region. Rendering a second bottom bar (or any app-level persistent element) is forbidden.

### R5 — System UI integration

- The single activity calls `enableEdgeToEdge()`; the shell handles `WindowInsets` explicitly instead of relying on defaults.
- The bottom navigation region accounts for `WindowInsets.navigationBars` so it stays usable under gesture navigation.
- The shell survives configuration changes and process recreation without losing the selected destination (`rememberNavController` plus saved state handling as in R3).

## Constraints

- C1 — First-party APIs only for UI construction (R1); exceptions require an explicit decision recorded here.
- C2 — The bottom navigation region is unique and app-global; modules never draw their own.
- C3 — Modules do not hold a reference to the shell's `NavController` beyond their graph entry point.
- C4 — UI code never calls native code directly. Any work touching `external/dnglab` or `external/exiftool` goes through a dedicated first-party native-integration layer (never a direct FFI call from UI).
- C5 — Adding a destination changes the shell's route table only; the shell's structure stays as defined in R2.

## Acceptance Criteria

- AC1 — Launching the app shows a screen divided into exactly two vertical regions: a bottom navigation region and a content region above it.
- AC2 — Tapping every bottom navigation item swaps the content region, and the bottom region remains on screen and visually unchanged (identical position, items and selection feedback).
- AC3 — No element other than the bottom navigation bar persists across all destinations; each module's top bar (if any) belongs to that module and disappears with it.
- AC4 — With three or more destinations, pressing system back from any top-level destination returns to the start destination and the bottom bar selection matches the visible screen.
- AC5 — Rotating the device, or triggering activity recreation, keeps the current destination and bottom bar selection.
- AC6 — Under gesture navigation, the bottom bar is fully visible and tappable, not covered by the system navigation area.
- AC7 — A static analysis check (dependency report) shows no third-party UI component library in the UI modules, or lists it together with the approved exception recorded in this file.

## Impacted Modules

- `app` (shell) — activity, root `Scaffold`, bottom navigation composable, root `NavHost`
- Feature packages (TBD, one per destination) — each owns its graph and its content region
- `FOTLAB-NATIVE-000001` — defines how first-party code may reach into third-party modules

## Open Questions

- Q1 — What is the definitive set of top-level destinations, and what are their routes, labels and icons? **TBD.**
- Q2 — Which destination is the start destination? **TBD.**
- Q3 — Does any feature need a full-screen or immersive mode? That would contradict R2's "only persistent element" rule and requires an explicit amendment of this item.
- Q4 — Are cross-module deep links in scope for the first implementation, or only top-level destinations?
- Q5 — Is dynamic colour (Material You) the default, or a fixed brand colour scheme?
- Q6 — Which locales and RTL layouts must the shell support at launch?

## Change History

- 2026-09-07 — Initial draft. Defined the two-region shell (persistent bottom navigation region + module-owned content region), the native-first Material3 stack constraint, module autonomy rules and the edge-to-edge requirement. Destination set, start destination and immersive-mode exception left open as Q1–Q3.
- 2026-09-07 — With the move to a single Gradle module (`FOTLAB-STRUCT-000001`), "module" in this item now means a destination's feature package (`ui/<feature>/` plus `navigation/<feature>/`). R4 reworded accordingly, and the isolation note added: feature packages must not depend on each other, but that is a review convention rather than a build-enforced guarantee. The two-region structure, the navigation behaviour and the edge-to-edge requirement are unchanged.
