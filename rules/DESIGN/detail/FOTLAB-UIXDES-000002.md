# Per-Module Top App Bar and Drawer Behaviour

- ID: FOTLAB-UIXDES-000002
- Status: Draft
- Priority: P1
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-UIXDES-000001` (overall UI shell — the top bar belongs to the module, not to the shell)

## Background & Goal

`FOTLAB-UIXDES-000001` establishes that the top region of the shell is owned by the active module,
and that nothing except the bottom navigation bar is persistent at application level. That makes the
top app bar a module-level element — and therefore a place where modules can easily drift apart.

This item defines the mandatory structure and behaviour of every module's top app bar, so that all
modules stay consistent while remaining autonomous:

- G1 — Every module draws its own top app bar at the top of its content region.
- G2 — The leftmost element is a three-line ("hamburger") icon that opens a drawer; the drawer occupies 80% of the available width when expanded.
- G3 — The rightmost element is a vertical three-dot overflow menu.
- G4 — The behaviour of these two affordances is identical across modules, even though their content is module-owned.

## Requirement

### R1 — Every module owns and renders its top app bar

- Each top-level module renders its own Material3 top app bar as the first element of its content region.
- The top app bar is **not** part of the shell and **not** persistent: it is created and destroyed with the module, and it disappears when the user switches destination.
- Each module writes and owns its own top app bar implementation; no shared top app bar component exists (see C1). Consistency comes from the behaviour contract R2–R5, not from shared code.
- The title is the module name, single line, truncated on overflow.

### R2 — Leftmost element: drawer icon

- The leftmost element of the top app bar is an icon button with the three-line menu icon (`Icons.Default.Menu`, Material "menu").
- It carries a non-null `contentDescription` for accessibility.
- Activating it opens the navigation drawer of the current module.
- No other element may be placed to its left, and the icon is always present — it is never conditionally hidden.

### R3 — Drawer width and placement

- When expanded, the drawer content occupies **80% of the width of its parent container**, expressed as `Modifier.fillMaxWidth(0.8f)`.
- The drawer is the **native Material3 modal drawer** (`ModalNavigationDrawer`) wrapped around the module's whole region — the top bar included — so it slides over the top bar with the platform's motion and scrim, exactly as the platform does. The module region is the region above the bottom navigation bar (`FOTLAB-UIXDES-000001` R2); the drawer never leaves it.
- Consequence: the bottom navigation region is **not** covered by the drawer and remains visible while the drawer is open, preserving the rule that it is the only persistent element in the app.
- Inside the module region the **top bar and the content region are siblings** — one above the other, neither overlapping the other. A module places them side by side itself (for example in a `Column`); it does not stack a second `Scaffold` on top of the shell's to do it.
- Implementation note: Material3's `ModalDrawerSheet` applies its own width constraints (default maximum 360dp). If those constraints conflict with the 80% requirement, the sheet is replaced by a custom `Surface` carrying the 80% modifier. The gallery uses that resolution: a plain `Surface` with `fillMaxWidth(0.8f)` and `fillMaxHeight()`.

### R4 — Rightmost element: overflow menu

- The rightmost element of the top app bar is an icon button with the vertical three-dot icon (`Icons.Default.MoreVert`, Material "more_vert"), opening a Material3 `DropdownMenu`.
- It carries a non-null `contentDescription` for accessibility.
- The menu holds secondary and destructive actions only. Primary actions belong in the content region, and navigation actions belong in the drawer or the bottom bar.
- Destructive entries are visually distinguished and require confirmation before execution.
- Nothing may be placed to its right.

### R5 — Drawer behaviour

- The drawer is owned by the module: its content, its state and its lifetime follow the module.
- **The drawer content is module-private.** No application-level entries (settings, about, licence or attribution) are placed in a module drawer; each module decides what its own drawer holds.
- Because it is module-owned, switching destination destroys it. A destination never inherits an expanded drawer from another module.
- While the drawer is expanded, the system back action closes it first; only a subsequent back action performs navigation. Whether Material3 already handles this internally must be verified during implementation, and a `BackHandler` is registered if it does not.
- **The bottom navigation region stays interactive while the drawer is expanded.** Tapping a bottom navigation item switches to that destination and discards the drawer: no confirmation dialog, no preserved drawer state, and the outgoing module's drawer is destroyed together with the module.
- Gesture opening (edge swipe) is enabled when the platform gesture system allows it.

### R6 — Close affordance in the drawer

- The expanded drawer carries a **close button in its own top-left corner**: an icon button with the Material "close" glyph (`Icons.Default.Close`), at the corner where the top bar's three-line icon sits while the drawer is closed.
- Its padding matches the top bar's leading slot, so opening the drawer replaces the three-line icon **in place** with its counterpart instead of moving the affordance to a new position.
- Activating it closes the drawer, and nothing else: the destination, the current directory and the selection are unchanged.
- It carries a non-null `contentDescription` from resources.
- The top bar's three-line icon keeps its glyph and is never swapped for a close icon: while the drawer is expanded the top bar is covered by the drawer, and the close affordance belongs to the drawer.

## Constraints

- C1 — Each feature implements its own top app bar. Neither the shell nor the `ui/theme` package ships a shared top app bar (or drawer) component, and a feature must not depend on one to achieve consistency: uniformity is guaranteed by the behaviour contract R2–R5 and verified by AC1–AC9, not by shared code. Features may still share primitives (icons, dimensions, content descriptions).
- C2 — The top app bar is never lifted into the shell. Making it persistent would violate `FOTLAB-UIXDES-000001` R2.
- C3 — The drawer is never a second bottom bar, and never overlaps the bottom navigation region.
- C4 — Both end icons are always present; conditional hiding is not allowed (see Q2 for the empty-menu case).
- C5 — First-party APIs only, per `FOTLAB-UIXDES-000001` R1.
- C6 — A module drawer carries module content only; shared application entries are not distributed across module drawers.
- C7 — With the drawer expanded, the bottom navigation region remains visible **and** interactive. Tapping it always wins over the drawer: the destination switches and the drawer is discarded without confirmation.

## Acceptance Criteria

- AC1 — On every top-level destination, the content region starts with a top app bar whose leftmost element is the three-line icon and whose rightmost element is the vertical three-dot icon.
- AC2 — Activating the three-line icon expands a drawer whose measured width equals 80% of the parent container width, within 1dp of rounding.
- AC3 — With the drawer expanded, the bottom navigation region is still fully visible on screen and not covered by the drawer or its scrim.
- AC4 — Switching destination while the drawer is expanded results in the new destination showing a collapsed drawer.
- AC5 — With the drawer expanded, one system back press closes the drawer and does not change the destination; a second back press performs the normal back behaviour.
- AC6 — Activating the three-dot icon opens a dropdown menu; every entry is reachable and destructive entries ask for confirmation.
- AC7 — Both end icons expose a non-null content description when queried by accessibility services.
- AC8 — Rotating the device re-measures the drawer to 80% of the new parent width.
- AC9 — With the drawer expanded, tapping a different bottom navigation item switches the destination immediately, and the newly shown module starts with a collapsed drawer. No drawer state of the outgoing module survives the switch.
- AC10 — With the drawer expanded, the drawer's top-left corner shows a close (X) button at the position the top bar's three-line icon occupies while the drawer is closed; activating it collapses the drawer and changes nothing else.

## Impacted Modules

- `ui/theme` — theme and shared primitives only; ships no top app bar and no drawer component
- Every top-level feature package (`ui/<feature>/`) — owns its top app bar and drawer outright: the implementation, the title, the drawer content and the menu entries
- `FOTLAB-UIXDES-000001` — the shell contract these rules build on

## Open Questions

- Q2 — If a module has no overflow actions, is the three-dot icon hidden or shown with an empty/disabled state? **TBD.** C4 currently requires it to be always present.
- Q3 — On tablets, foldables and landscape screens, is 80% still the right value, or should an absolute maximum (for example 400dp) apply? **TBD.**
- Q5 — Is the top app bar scroll behaviour unified (pinned vs. `enterAlways`), or chosen per module? **TBD.**
- Q6 — Is RTL supported at launch? In RTL the drawer expands from the opposite edge and the icon order mirrors.

Resolved and retired on 2026-09-07: Q1 (drawer content is module-private — now R5 and C6) and Q4 (bottom navigation stays interactive while the drawer is expanded — now R5, C7 and AC9). The retired numbers are intentionally not reused.

## Change History

- 2026-09-07 — Initial draft. Defined the mandatory top app bar structure per module (three-line drawer icon at the left end, vertical three-dot overflow menu at the right end), the 80%-of-parent drawer width, module ownership of the top bar and drawer, and the requirement that the drawer never covers the bottom navigation region. Left drawer content scope, empty-menu handling and large-screen behaviour open as Q1–Q6.
- 2026-09-07 — C1 amended: modules implement their **own** top app bar; the previously mandated shared top app bar component is withdrawn, because it contradicted module autonomy (a shared component turns the top bar into an app-level element in practice). R1 updated to state that no shared component exists. Consequence for the codebase: `:core:ui` is limited to theme and shared primitives and ships no top app bar or drawer component; each feature module owns its implementation, its state and its lifetime. R2–R5 remain the binding behaviour contract and AC1–AC9 remain the verification, so no acceptance criterion had to change.
- 2026-09-07 — Decisions recorded: the drawer content is **module-private** (no app-level entries such as settings, about or licence — added to R5 as a bold clause, plus new constraint C6), and the bottom navigation region stays **interactive** while the drawer is expanded, with a tap switching destination and discarding the drawer (new clause in R5, new constraint C7, new acceptance criterion AC9). Q1 and Q4 retired from Open Questions.
- 2026-09-07 — `:core:ui` no longer exists after the single-module move (`FOTLAB-STRUCT-000001`): C1 and the Impacted Modules list now name the `ui/theme` package, which ships theme and shared primitives only — no top app bar, no drawer component. Behaviour contract R2–R5 and verification AC1–AC9 are unchanged.
- 2026-09-10 — R3 rewritten: the drawer is the **native Material3 `ModalNavigationDrawer`** wrapped around the module's whole region, so it slides over the top bar the way the platform does; it still never leaves the module region, so the bottom navigation region stays uncovered and interactive. R3 now also states that the top bar and the content region are sibling regions and that a module does not stack its own `Scaffold` on the shell's. Added R6 and AC10: the expanded drawer owns the close affordance — an X button in its own top-left corner, aligned with the top bar's three-line icon, so the icon the user pressed is replaced in place. AC1–AC9 needed no change: the bottom bar's visibility and interactivity were never at stake, and the 80% width (AC2/AC8) is kept by the custom `Surface` already permitted by R3.
