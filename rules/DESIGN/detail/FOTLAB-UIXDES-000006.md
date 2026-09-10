# Selection Mode — Long Press, Top Bar State and Batch Actions

- ID: FOTLAB-UIXDES-000006
- Status: Draft
- Priority: P1
- Created: 2026-09-10
- Owner: —
- Related: `FOTLAB-UIXDES-000001` (the bottom navigation region is the only persistent element and is never replaced), `FOTLAB-UIXDES-000002` (the top bar skeleton — and the single exception this item introduces to its leftmost icon), `FOTLAB-UIXDES-000004` (the gallery selection model and its lifetime rules), `FOTLAB-UIXDES-000005` (the icon vocabulary: 全选 / 反选 / 全不选, 导入 / 导出)

## Background & Goal

The pattern this item adopts is the one a mature Material Design file manager (MaterialFiles) uses:

- **long press activates** — long pressing a list item switches the screen from browsing to selecting;
- **the top toolbar changes state** — it shows the selected count and a close (X) button that leaves the mode;
- **the mode has its own exit** — the close button or system back leaves it and clears the selection;
- **the batch actions appear** — a bar of operations that act on the whole selection.

FotLab takes that interaction over, with one structural difference that is not negotiable: the
bottom navigation region is the **only** persistent element of the application and lives outside the
module region (`FOTLAB-UIXDES-000001` R2). MaterialFiles replaces the bottom bar with an action bar
while selecting; FotLab cannot, so the batch action bar sits at the bottom of the module's **own
content region**, directly above the bottom navigation region.

Goals:

- G1 — One selection interaction for every list and grid in the app: long press enters, close or back leaves, tap toggles.
- G2 — The mode is always visible in the top bar: the leading icon becomes the exit affordance, and the title becomes the count.
- G3 — Batch actions have exactly one home, which no screen invents differently, and that home never touches the bottom navigation region.

## Requirement

### R1 — Two modes, one state machine

- A screen with a list or a grid has two modes: **browse mode** (default) and **selection mode**.
- The state is a pair: the current **mode**, and the **set of selected item identities**. Entering selection mode with an empty selection is allowed (for example right after 全不选); leaving selection mode always clears the selection.
- The state belongs to the feature's lower layer, not to the composition — the same place the gallery already keeps `ListSelectionOfGallery` (`FOTLAB-UIXDES-000004` R3). The screen reads it; it never mirrors or re-derives it.
- The selection is never persisted (`FOTLAB-UIXDES-000004` R3/C5/C6).

### R2 — Long press enters selection mode

- Long pressing an item enters selection mode **and** selects that item, in one gesture.
- A plain tap means one thing per mode: in browse mode it opens or navigates; in selection mode it toggles that item's selection. The same gesture never does both.
- Entering selection mode changes nothing else: not the current directory, not the display mode, not the scroll position.

### R3 — The top bar in selection mode

- The **leftmost** element becomes the close (X) icon that leaves selection mode — `Icons.Filled.Close` (`FOTLAB-UIXDES-000005` R3), with a `contentDescription` from resources. It takes the place of the three-line drawer icon for the duration of the mode; this is the one exception to `FOTLAB-UIXDES-000002` R2 and is recorded there. The slot itself is never empty.
- The title becomes the number of selected items, from the same plural resource the screen uses for its count (`FOTLAB-UIXDES-000004` R6).
- The action slots carry the actions of the mode: the gallery shows 导出 + 删除 (`FOTLAB-UIXDES-000004` R4).
- The three-dot overflow icon stays the rightmost element and is never hidden (`FOTLAB-UIXDES-000002` R4).
- Leaving the mode restores the three-line icon, the directory title and the browse-mode slots; no other element of the bar moves.

### R4 — Leaving selection mode

- The close (X) button and the system back action both leave selection mode, and leaving clears the selection.
- Back precedence is: **drawer → selection mode → the screen's own back step** (for the gallery: leaving the current directory). One back press never does two of these.
- Leaving selection mode never performs an action on the previously selected items.

### R5 — What selection mode does to the items

- Every item renders its checked state explicitly — a check mark, or a highlighted background, or both. An item's own checked state may use `CheckCircle` / `CheckCircleOutline`; those two are reserved for exactly that and never for the bulk entries of the overflow menu (`FOTLAB-UIXDES-000005` R4).
- 全选 / 反选 / 全不选 live in the three-dot overflow menu in **both** modes, in that order (`FOTLAB-UIXDES-000004` R5, `FOTLAB-UIXDES-000005` R3).
- Selecting is inert: it never moves, copies, exports or deletes anything by itself (`FOTLAB-UIXDES-000004` R7/C8).

### R6 — The batch action bar lives inside the content region

- In selection mode a screen may show a **batch action bar** holding the operations that apply to the selection — the gallery's 导出 / 删除, and for a file screen copy, move, rename, share and the like.
- It is the last row of the module's content region, sitting directly above the bottom navigation region. It never covers, replaces or slides over the bottom navigation region, and it is not persistent: it exists only while selection mode is active (`FOTLAB-UIXDES-000001` R2, `FOTLAB-UIXDES-000002` C3).
- Its icons come from `FOTLAB-UIXDES-000005`; every action carries a content description from resources.

### R7 — Destructive batch actions ask first

- A destructive operation in the batch action bar asks for confirmation before it runs, as any destructive entry does (`FOTLAB-UIXDES-000002` R4).

## Constraints

- C1 — No screen covers, replaces or hides the bottom navigation region; the batch action bar belongs to the content region (R6).
- C2 — The leading icon slot is never empty: three-line icon in browse mode, close (X) in selection mode (R3).
- C3 — The mode and the selection set are owned by the feature's lower layer, never by the composition, and are never persisted (R1).
- C4 — One gesture, one meaning per mode: a tap never both opens and selects (R2).
- C5 — First-party Material3 only (`FOTLAB-UIXDES-000001` R1).

## Acceptance Criteria

- AC1 — Long pressing an item switches the top bar to its selection state: close (X) at the left, the count in the middle, the mode's actions at the right; the pressed item is selected.
- AC2 — In selection mode, tapping an item toggles its selection and updates the count, and no item opens or navigates.
- AC3 — Tapping the close (X), or pressing back, leaves selection mode: the three-line icon, the directory title and the browse-mode slots return and the selection is empty.
- AC4 — With a selection active, the bottom navigation region is still fully visible and interactive; the batch action bar, if any, sits above it and never overlaps it.
- AC5 — The overflow menu offers 全选 / 反选 / 全不选, in that order, in both modes.
- AC6 — A destructive batch action asks for confirmation before it runs.
- AC7 — Rotating the device, or any other configuration change, keeps the mode and the selection: the state lives outside the composition.
- AC8 — Pressing back once with the drawer expanded closes the drawer and does not leave selection mode; a second press leaves selection mode.

## Impacted Modules

- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/gallery/GalleryScreen.kt` — the top bar states of R3 and the back precedence of R4
- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/gallery/GalleryCore.kt` — owns the mode and the selection set (R1)
- `app/src/main/res/values/strings.xml` — the count copy and a `*_cd` description for the close (X) that leaves selection mode
- Every future screen with a list or a grid — adopts R1–R7 instead of inventing its own selection handling
- `FOTLAB-UIXDES-000002` — carries the one exception to R2 (the leftmost icon in selection mode)
- `FOTLAB-UIXDES-000005` — owns the icons this mode uses

## Open Questions

- Q1 — Does the gallery keep deriving selection mode from "the selection is not empty", the way it does today, or does it adopt the explicit mode flag of R1? **TBD.**
- Q2 — Is the batch action bar pinned above the bottom navigation region, or does it scroll away with the content? **TBD.**
- Q3 — Does long press on a collection enter selection mode as well, given collections are selectable (`FOTLAB-UIXDES-000004` R7)? **TBD.**
- Q4 — Does entering selection mode keep the drawer reachable through the edge-swipe gesture, or is it disabled for the duration of the mode? **TBD.**

## Change History

- 2026-09-10 — Initial draft. Defines the selection interaction for every list and grid: long press enters selection mode and selects the pressed item (R2), the top bar switches to close (X) + count + the mode's own actions (R3) — the single documented exception to the always-present three-line icon of `FOTLAB-UIXDES-000002` R2 — close or back leaves the mode and clears the selection with the precedence drawer → selection → screen step (R4), items render an explicit checked state while the overflow menu keeps 全选 / 反选 / 全不选 in both modes (R5), and the batch action bar sits at the bottom of the module's content region instead of replacing the bottom navigation region, which stays visible and interactive (R6, C1). Left the gallery's migration to an explicit mode flag, the pinning of the action bar, long press on collections and the drawer gesture during selection mode open as Q1–Q4.
