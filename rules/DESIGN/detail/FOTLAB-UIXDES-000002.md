# Per-Module Fun Bar and Drawer Behaviour

- ID: FOTLAB-UIXDES-000002
- Status: Draft
- Priority: P1
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-UIXDES-000001` (overall UI shell — the fun bar belongs to the module, not to the shell)

## Background & Goal

`FOTLAB-UIXDES-000001` establishes that the content region of the shell is owned by the active module,
and that nothing except the nav bar (pinned to the top) is persistent at application level. That makes
the module's action bar a module-level element — and therefore a place where modules can easily drift
apart.

This item defines the mandatory structure and behaviour of every module's **fun bar**, so that all
modules stay consistent while remaining autonomous. The bar is named by function, not by its edge;
it is currently pinned to the **bottom** of the module's content region (phone operation concentrates
at the bottom edge):

- G1 — Every module draws its own fun bar at the bottom of its content region.
- G2 — The leftmost element is a three-line ("hamburger") icon that opens a drawer; the drawer occupies 80% of the available width when expanded.
- G3 — The rightmost element is a vertical three-dot overflow menu (anchored at the bottom edge, its popup opens upward).
- G4 — The behaviour of these two affordances is identical across modules, even though their content is module-owned.

## Requirement

### R1 — Every module owns and renders its fun bar

- Each top-level module renders its own fun bar as the last (bottom) element of its content region.
- The fun bar is **not** part of the shell and **not** persistent: it is created and destroyed with the module, and it disappears when the user switches destination.
- Each module writes and owns its own fun bar implementation; no shared fun bar component exists (see C1). Consistency comes from the behaviour contract R2–R5, not from shared code.
- The title slot is **module-owned and optional**. A module may render its name (single line,
  truncated), or leave the slot empty — both current modules render no title text
  (`FOTLAB-UIXDES-000004` R6). The skeleton requires the slot to exist, not that it show text.
  Whether every screen must show a title is open as Q7.

### R2 — Leftmost element: drawer icon

- The leftmost element of the fun bar is an icon button with the three-line menu icon (`Icons.Default.Menu`, Material "menu"); with the bar at the bottom edge this icon sits at the bottom-left.
- It carries a non-null `contentDescription` for accessibility.
- Activating it opens the navigation drawer of the current module.
- No other element may be placed to its left, and the icon is always present — it is never conditionally hidden.
- **Exception — selection mode.** While the module is in selection mode, the leftmost element is the close (X) icon that leaves that mode, and the three-line icon is not shown (`FOTLAB-UIXDES-000006` R3). The slot itself is never empty: this is the only state in which the icon is replaced, and the three-line icon returns the moment selection mode ends.

### R3 — Drawer width and placement

- When expanded, the drawer content occupies **80% of the width of its parent container**, expressed as `Modifier.fillMaxWidth(0.8f)`.
- The drawer is the **native Material3 modal drawer** (`ModalNavigationDrawer`) wrapped around the module's whole region — the fun bar included — so it slides over the fun bar with the platform's motion and scrim, exactly as the platform does. The module region is the content region below the shell's top nav bar (`FOTLAB-UIXDES-000001` R2); the drawer never leaves it.
- Consequence: the shell's nav bar region is **not** covered by the drawer and remains visible and interactive while the drawer is open, preserving the rule that it is the only persistent element in the app. The module fun bar, being part of the drawer's content subtree, **is** dimmed by the scrim and covered by the sheet — which is what lets the drawer's own close button replace the menu in place (R6).
- Inside the module region the **fun bar and the content region are siblings** — one above the other, neither overlapping the other. A module may place them either by hand (for example in a `Column`) **or by nesting its own Material3 `Scaffold` inside the shell's root `Scaffold`**, hosting the bar in a slot (`topBar` or `bottomBar`; the current modules use `bottomBar`). A nested module `Scaffold` is permitted only when all three conditions hold:
  - **(a) Drawer coverage preserved.** The module `Scaffold` sits **inside the `ModalNavigationDrawer` content subtree** (the drawer wraps the Scaffold, not the reverse), so the drawer's scrim and sheet still slide over the module bar exactly as R3 requires; placing the bar in the shell root `Scaffold` instead would put it outside that subtree and is still forbidden.
  - **(b) Insets consumed exactly once.** The inner `Scaffold` pins `contentWindowInsets` to the insets its own bar does not already consume; the shell root `Scaffold` zeroes its `contentWindowInsets`, so the system status/navigation insets must never be padded by both layers.
  - **(c) No persistence change.** The bar stays module-owned and non-persistent (C2): nesting a `Scaffold` is a layout mechanism only and never lifts the bar into the shell, which still owns exactly one persistent element per `FOTLAB-UIXDES-000001`.

  The earlier blanket prohibition ("a module does not stack a second `Scaffold` on top of the shell's", R3 until 2026-09-19) is withdrawn; the sibling-region and drawer-coverage requirements it protected are retained verbatim.
- Implementation note: Material3's `ModalDrawerSheet` applies its own width constraints (default maximum 360dp). If those constraints conflict with the 80% requirement, the sheet is replaced by a custom `Surface` carrying the 80% modifier. The Library currently uses the native `ModalDrawerSheet` with `fillMaxWidth(0.8f)`; on phones (≈400dp wide) 80% ≈ 320dp sits under the 360dp cap, so the custom `Surface` is only needed on wider screens — that value is open as Q3.

### R4 — Rightmost element: overflow menu

- The rightmost element of the fun bar is an icon button with the vertical three-dot icon (`Icons.Default.MoreVert`, Material "more_vert"), opening a Material3 `DropdownMenu`.
- It carries a non-null `contentDescription` for accessibility.
- The menu holds secondary and destructive actions only. Primary actions belong in the content region, and navigation actions belong in the drawer or the nav bar.
- Destructive entries are visually distinguished and require confirmation before execution.
- Nothing may be placed to its right.
- Anchored at the bottom edge, the overflow (and every other bar-anchored popup) opens **upward** (drop-up); this follows automatically from the anchor's position and requires no custom popup placement.

### R5 — Drawer behaviour

- The drawer is owned by the module: its content, its state and its lifetime follow the module.
- **The drawer content is module-private.** No application-level entries (settings, about, licence or attribution) are placed in a module drawer; each module decides what its own drawer holds.
- Because it is module-owned, switching destination destroys it. A destination never inherits an expanded drawer from another module.
- While the drawer is expanded, the system back action closes it first; only a subsequent back action performs navigation. Whether Material3 already handles this internally must be verified during implementation, and a `BackHandler` is registered if it does not.
- **The shell nav bar stays interactive while the drawer is expanded.** Tapping a nav bar item switches to that destination and discards the drawer: no confirmation dialog, no preserved drawer state, and the outgoing module's drawer is destroyed together with the module. (The nav bar is outside the drawer's content subtree, so the scrim never dims it.)
- **Swipe gestures are disabled** (`ModalNavigationDrawer(gesturesEnabled = false)`): the drawer neither opens from an edge swipe nor closes by a drag. It opens only via the fun bar's menu icon and closes via its own X button (R6), system back, or a scrim tap. The platform's open/close **slide animation is retained** — only the drag gesture is removed, so the drawer can never be confused with horizontal content gestures (viewer paging, image pan).

### R6 — Close affordance in the drawer

- The expanded drawer carries a **close button in its own bottom-left corner**: an icon button with the Material "close" glyph (`Icons.Default.Close`), at the corner where the fun bar's three-line icon sits while the drawer is closed.
- Its padding matches the fun bar's leading slot, and its close row shares the fun bar's height and the same navigation-bar inset, so opening the drawer replaces the three-line icon **in place** with its counterpart instead of moving the affordance to a new position.
- Activating it closes the drawer, and nothing else: the destination, the current directory and the selection are unchanged.
- It carries a non-null `contentDescription` from resources.
- The fun bar's three-line icon keeps its glyph and is never swapped for a close icon: while the drawer is expanded the fun bar is covered by the drawer, and the close affordance belongs to the drawer.

## Constraints

- C1 — Each feature implements its own fun bar. Neither the shell nor the `ui/theme` package ships a shared fun bar (or drawer) component, and a feature must not depend on one to achieve consistency: uniformity is guaranteed by the behaviour contract R2–R5 and verified by AC1–AC10, not by shared code. Features may still share primitives (icons, dimensions, content descriptions).
- C2 — The fun bar is never lifted into the shell. Making it persistent would violate `FOTLAB-UIXDES-000001` R2.
- C3 — The drawer is never a second nav bar, and never overlaps the shell's nav bar region. It does cover the module's own fun bar — that is required for the in-place close affordance (R6) and does not violate this constraint, because the fun bar is module-private.
- C4 — Both end icons are always present; conditional hiding is not allowed (see Q2 for the empty-menu case). The one exception is selection mode, where the left end carries the close (X) that leaves the mode instead of the three-line icon (`FOTLAB-UIXDES-000006` R3/C2).
- C5 — First-party APIs only, per `FOTLAB-UIXDES-000001` R1.
- C6 — A module drawer carries module content only; shared application entries are not distributed across module drawers.
- C7 — With the drawer expanded, the shell nav bar remains visible **and** interactive. Tapping it always wins over the drawer: the destination switches and the drawer is discarded without confirmation.

## Acceptance Criteria

- AC1 — On every top-level destination, the content region ends with a fun bar whose leftmost element is the three-line icon (bottom-left) and whose rightmost element is the vertical three-dot icon (bottom-right).
- AC2 — Activating the three-line icon expands a drawer whose measured width equals 80% of the parent container width, within 1dp of rounding.
- AC3 — With the drawer expanded, the shell nav bar is still fully visible and interactive, not covered by the drawer or its scrim; the module fun bar is covered by the scrim/sheet.
- AC4 — Switching destination while the drawer is expanded results in the new destination showing a collapsed drawer.
- AC5 — With the drawer expanded, one system back press closes the drawer and does not change the destination; a second back press performs the normal back behaviour.
- AC6 — Activating the three-dot icon opens a drop-up menu anchored above the icon; every entry is reachable and destructive entries ask for confirmation.
- AC7 — Both end icons expose a non-null content description when queried by accessibility services.
- AC8 — Rotating the device re-measures the drawer to 80% of the new parent width.
- AC9 — With the drawer expanded, tapping a different nav bar item switches the destination immediately, and the newly shown module starts with a collapsed drawer. No drawer state of the outgoing module survives the switch.
- AC10 — With the drawer expanded, the drawer's bottom-left corner shows a close (X) button at the position the fun bar's three-line icon occupies while the drawer is closed; activating it collapses the drawer and changes nothing else.
- AC11 — Neither an edge swipe nor a drag opens or closes the drawer; opening via the menu icon and closing via the X button both play the standard slide animation.

## Impacted Modules

- `ui/theme` — theme and shared primitives only; ships no fun bar and no drawer component
- Every top-level feature package (`feature/<name>/`) — owns its fun bar and drawer outright: the implementation, the title, the drawer content and the menu entries
- `FOTLAB-UIXDES-000001` — the shell contract these rules build on

## Open Questions

- Q2 — If a module has no overflow actions, is the three-dot icon hidden or shown with an empty/disabled state? **TBD.** C4 currently requires it to be always present.
- Q3 — On tablets, foldables and landscape screens, is 80% still the right value, or should an absolute maximum (for example 400dp) apply? **TBD.**
- Q5 — Is the fun bar scroll behaviour unified (pinned vs. `enterAlways`), or chosen per module? **TBD.**
- Q6 — Is RTL supported at launch? In RTL the drawer expands from the opposite edge and the icon order mirrors.
- Q7 — Is the title slot mandatory (every screen shows its name) or optional (a screen may render an empty title, as both modules do today)? **TBD.**

Resolved and retired on 2026-09-07: Q1 (drawer content is module-private — now R5 and C6) and Q4 (nav bar stays interactive while the drawer is expanded — now R5, C7 and AC9). The retired numbers are intentionally not reused.

## Change History

- 2026-09-07 — Initial draft. Defined the mandatory top app bar structure per module (three-line drawer icon at the left end, vertical three-dot overflow menu at the right end), the 80%-of-parent drawer width, module ownership of the top bar and drawer, and the requirement that the drawer never covers the bottom navigation region. Left drawer content scope, empty-menu handling and large-screen behaviour open as Q1–Q6.
- 2026-09-07 — C1 amended: modules implement their **own** top app bar; the previously mandated shared top app bar component is withdrawn, because it contradicted module autonomy (a shared component turns the top bar into an app-level element in practice). R1 updated to state that no shared component exists. Consequence for the codebase: `:core:ui` is limited to theme and shared primitives and ships no top app bar or drawer component; each feature module owns its implementation, its state and its lifetime. R2–R5 remain the binding behaviour contract and AC1–AC9 remain the verification, so no acceptance criterion had to change.
- 2026-09-07 — Decisions recorded: the drawer content is **module-private** (no app-level entries such as settings, about or licence — added to R5 as a bold clause, plus new constraint C6), and the bottom navigation region stays **interactive** while the drawer is expanded, with a tap switching destination and discarding the drawer (new clause in R5, new constraint C7, new acceptance criterion AC9). Q1 and Q4 retired from Open Questions.
- 2026-09-07 — `:core:ui` no longer exists after the single-module move (`FOTLAB-STRUCT-000001`): C1 and the Impacted Modules list now name the `ui/theme` package, which ships theme and shared primitives only — no top app bar, no drawer component. Behaviour contract R2–R5 and verification AC1–AC9 are unchanged.
- 2026-09-10 — R3 rewritten: the drawer is the **native Material3 `ModalNavigationDrawer`** wrapped around the module's whole region, so it slides over the top bar the way the platform does; it still never leaves the module region, so the bottom navigation region stays uncovered and interactive. R3 now also states that the top bar and the content region are sibling regions and that a module does not stack its own `Scaffold` on the shell's. Added R6 and AC10: the expanded drawer owns the close affordance — an X button in its own top-left corner, aligned with the top bar's three-line icon, so the icon the user pressed is replaced in place. AC1–AC9 needed no change: the bottom bar's visibility and interactivity were never at stake, and the 80% width (AC2/AC8) is kept by the custom `Surface` already permitted by R3.
- 2026-09-10 — R2 and C4 gained their single exception: while a module is in **selection mode** (`FOTLAB-UIXDES-000006` R3), the leftmost element is the close (X) that leaves the mode, not the three-line drawer icon. The slot is never empty and the three-line icon returns as soon as the mode ends, so the "always present, never conditionally hidden" rule is narrowed to one documented state instead of being silently broken.
- 2026-09-11 — R1 amended: the title slot is module-owned and optional — a module may render its name or leave it empty (the Library renders an empty title composable, `FOTLAB-UIXDES-000004` R6); the skeleton requires the slot to exist, not that it shows text. R3 implementation note corrected: the Library uses the native `ModalDrawerSheet` with `fillMaxWidth(0.8f)`, so the custom `Surface` is only needed where the 80% width exceeds the 360dp cap (wide screens, Q3). Q7 added for the mandatory-vs-optional title.
- 2026-09-19 — R3 amended to **permit a nested module-level `Scaffold`** inside the shell's root `Scaffold`: a module may host its bar in a Scaffold slot (`topBar`/`bottomBar`) instead of laying the bar/content siblings out by hand in a `Column`. The blanket ban on a second `Scaffold` is withdrawn under three explicit conditions — (a) the inner Scaffold stays inside the `ModalNavigationDrawer` content subtree so the drawer scrim/sheet still cover the module bar (moving the bar to the shell root Scaffold remains forbidden), (b) window insets are consumed exactly once because the shell zeroes its `contentWindowInsets` and the inner Scaffold must pin its own, and (c) the bar remains module-owned and non-persistent per C2. This is a layout-mechanism allowance only: the sibling-region rule, R1–R2, R4–R6, C1–C7 and AC1–AC10 are unchanged.
- 2026-09-20 — **Layout flip and terminology refresh.** The module bar, formerly the top app bar, is renamed the position-independent **fun bar** and pinned to the BOTTOM edge of the content region (code: `LibraryScreenFunBar` / `StudioScreenFunBar` / `RecycleScreenFunBar`); the shell's persistent bar is now the top **nav bar** (`MainWindowNavBar`, see `FOTLAB-UIXDES-000001`). Consequential edits: title and G1–G4, R1, R2 (bottom-left), R3 (drawer now covers the module fun bar but never the top nav bar; modules nest a module `Scaffold` with the bar in `bottomBar`), R4 (drop-up popups), R5 (nav bar — not a bottom bar — stays interactive; swipe gestures explicitly DISABLED via `gesturesEnabled = false`, slide animation retained), R6 (close X moves to the drawer's bottom-LEFT, sharing the fun bar's height/inset), C1–C3/C7, AC1/AC3/AC6/AC9/AC10 reworded, new AC11 for gesture disabling, Impacted Modules, Q5/Q7. R2's selection-mode exception, the 80% drawer width and module-drawer ownership are unchanged. Historical entries above retain the "top app bar / bottom navigation" wording of their dates.
