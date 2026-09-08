# Gallery Screen — Top Bar Layout and Selection Model

- ID: FOTLAB-UIXDES-000004
- Status: Draft
- Priority: P1
- Created: 2026-09-08
- Owner: —
- Related: `FOTLAB-UIXDES-000002` (the fixed top bar skeleton this screen implements), `FOTLAB-UIXDES-000001` (the gallery owns its content region), `FOTLAB-UIXDES-000003` (copy and key naming for the new strings), `FOTLAB-IMGMGR-000001` (virtual tree: collections and file entries are nodes), `FOTLAB-DATABS-000002` (`fs_node_object` / `fs_node_relation` — what a node is), `FOTLAB-STRUCT-000001` (`feature/gallery` holds `GalleryScreen` + `GalleryCore`), `FOTLAB-STRUCT-000003` (role-based naming, no duplicate components)

## Background & Goal

`FOTLAB-UIXDES-000002` fixes the skeleton every screen repeats: a three-line icon at the far left
opening an 80%-wide drawer, a vertical three-dot menu at the far right, and a middle region the
screen fills in itself. It deliberately says nothing about what a screen puts in that middle region
or directly left of the overflow menu — that is the screen's own business, and that is where
screens would otherwise drift apart.

The Gallery is the first destination to fill that space. Its content is a virtual tree of nodes
(`FOTLAB-IMGMGR-000001`, `FOTLAB-DATABS-000002`), and what the user can do with that tree depends
entirely on **what is selected right now**. So the whole top bar is driven by one piece of state:
the current selection.

Goals:

- G1 — Define what the Gallery puts in the middle region and in the two action slots left of the
  overflow menu, and how those change with the selection.
- G2 — Define `ListSelectionOfGallery`: what it holds, and precisely how long it lives.
- G3 — Keep the screen inside the skeleton of `FOTLAB-UIXDES-000002` — the two end icons never
  move, never disappear, and the drawer stays at 80%.
- G4 — Express every action in terms of virtual nodes, so the UI never reasons about physical
  files directly.

## Requirement

### R1 — The skeleton is inherited, not reinvented

The Gallery top bar is laid out as follows, and this order never changes:

```
┌──────────────────────────────────────────────────────────────────┐
│ ☰   middle region (custom)                  [A]  [B]   ⋮         │
└──────────────────────────────────────────────────────────────────┘
  │         │                                   │    │     │
  │         │                                   │    │     └─ three-dot overflow (always last)
  │         │                                   │    └─────── slot B: create / delete
  │         │                                   └──────────── slot A: import / export
  │         └───────────────────────────────────────────────── screen-defined
  └─────────────────────────────────────────────────────────── three-line drawer icon
```

- The three-line icon (`Icons.Default.Menu`) is the leftmost element and opens the drawer at 80%
  of the content region (`FOTLAB-UIXDES-000002` R2/R3).
- The three-dot icon (`Icons.Default.MoreVert`) is the rightmost element and opens the dropdown of
  R5 (`FOTLAB-UIXDES-000002` R4). Nothing is placed to its right and it is never hidden.
- Slots A and B sit between the middle region and the three-dot icon, in that order: **A, then B,
  then the three dots**.
- Everything in the top bar is **icon-only** — no text labels in the bar itself. Text appears only
  in the dropdown (`FOTLAB-UIXDES-000002` R4) and, as copy, in the middle region.
- Drawer behaviour, back handling and the rule that the drawer never covers the bottom navigation
  region are inherited unchanged from `FOTLAB-UIXDES-000002` R5.

### R2 — `ListSelectionOfGallery` is the single driver

- `ListSelectionOfGallery` is the ordered collection of currently selected **nodes** of the virtual
  tree. A node is a row of `fs_node_object` (`FOTLAB-DATABS-000002` R1): either a collection
  (`type_mime = application/folder`) or a file entry.
- It holds **node identities** (`fs_node_id`), not node snapshots, so a node whose properties change
  while selected stays the same selection.
- **The type keeps its full name, `ListSelectionOfGallery`.** Several features drive their top bar
  from their own selection list, so the destination name stays in the identifier: a short, generic
  name would collide as soon as a second feature introduces one. This does not conflict with
  `FOTLAB-STRUCT-000003`, which forbids **brand** tokens (`FotLab`) — `gallery` is a destination
  name, and here global uniqueness outranks brevity inside the package. A future second list is
  named the same way (for example `ListSelectionOfRender`), never by dropping the suffix.
- It drives exactly one thing: the state of the top bar (slots A/B and the middle region). It is not
  a navigation state and not a filter.
- The empty list and a non-empty list are the two states of the screen; every visual difference
  between them is defined in R4.

### R3 — Lifetime: process-scoped, starts empty

- **Cold start** — the list is empty every time the application process starts.
- **Switching destination** — does **not** change the list. Leaving Gallery through the bottom
  navigation bar and coming back restores the same selection.
- **Process end** — the list is gone. It is cleared when the application process terminates, i.e.
  when the user fully exits and shuts the app down.
- The list is **never persisted**. Because its lifetime is the process, there is nothing to store:
  a new process starts empty by definition. Consequence, stated honestly: if the system kills the
  process in the background, the selection is lost on the next start — the same outcome as a user
  exit, and acceptable for selection state.
- **Holder: a process-scoped object owned by `GalleryCore`, not by the composition.** The gallery's
  lower layer (`feature/gallery/GalleryCore`, `FOTLAB-STRUCT-000001` R2/R3) owns the single
  `ListSelectionOfGallery` instance and exposes it as an observable state (a `StateFlow`, read as
  Compose `State`). Selection changes are made through that holder, never by the screen mutating a
  local copy.
- **The composition only reads it.** `GalleryScreen` obtains the holder with `remember` (plain, not
  `rememberSaveable`) and subscribes to the exposed state. The screen owns no selection state of
  its own.
- **`rememberSaveable` must not carry the selection.** Two reasons: (1) leaving the Gallery
  destination removes the composable from the tree, so a composition-held value is lost on
  destination switch, which contradicts "switching destination does not change the list";
  (2) `rememberSaveable` writes into saved instance state, which the system can restore after it
  has killed the process in the background — that would resurrect a selection the user's process
  had already lost, contradicting "process end clears the list". The same reasoning rules out
  `SavedStateHandle` and any persistence.
- Consequences that follow from the holder choice: rotation and other configuration changes keep
  the selection (the holder is not recreated), switching destination keeps it, a new process starts
  empty, and nothing is ever restored behind the app's back.

### R4 — Slot A and slot B swap with the selection

| State | Slot A (left) | Slot B (right, next to the dots) |
| --- | --- | --- |
| **Empty selection** | Import — bring files into the current virtual directory | New collection — create a folder in the current virtual directory |
| **Non-empty selection** | Export — write the selected nodes out | Delete — remove the selected nodes |

- The two slots are **mutually exclusive per state**: import and export never appear together, and
  neither do new-collection and delete. One position, one meaning per state.
- When the selection becomes empty again — the last node deselected, or the overflow menu's
  "clear selection" used — the bar returns to import + new collection.
- Every icon carries a non-null `contentDescription` from resources (`FOTLAB-UIXDES-000003`).
- Icons (Material, first-party; `material-icons-core`, `material-icons-extended` when needed):

  | Slot | Empty selection | Non-empty selection |
  | --- | --- | --- |
  | A | `Icons.Default.Download` (import) | `Icons.Default.Upload` (export) |
  | B | `Icons.Default.Add` (new collection) | `Icons.Default.Delete` (delete) |

### R5 — The three-dot dropdown: selection operations only

Activating the three-dot icon opens a Material3 `DropdownMenu` with exactly three entries. Each
entry is **icon + text** (no brackets, no decorative punctuation in the copy):

| Entry | Icon | Effect |
| --- | --- | --- |
| Select all | `Icons.Default.SelectAll` | Every node of the currently shown virtual directory becomes selected |
| Invert selection | `Icons.Default.SwapHoriz` | Selected nodes become unselected and vice versa, within the currently shown directory |
| Clear selection | `Icons.Default.Deselect` | The list becomes empty, returning the bar to its empty state |

- The three entries are always present, in this order, whatever the current selection is; entries
  are not hidden, reordered or disabled based on state.
- They operate on the **currently shown virtual directory** — the directory whose children the
  content region is displaying. Nodes outside it are untouched (see Q5 for recursion).
- "Clear selection" is the only way, other than deselecting every node, to return to the empty
  state.
- Because the dropdown holds selection operations and the destructive action lives in slot B, the
  dropdown itself contains no destructive entry (`FOTLAB-UIXDES-000002` R4).

### R6 — Middle region

- **Empty selection** — the name of the currently shown virtual directory; at the virtual root the
  screen title is shown. Single line, truncated on overflow (`FOTLAB-UIXDES-000002` R1).
- **Non-empty selection** — the number of selected nodes, as a plural resource
  (`gallery_selection_count`), for example "3 selected". Single line, truncated on overflow.
- The middle region never holds actions; it is display only.

### R7 — Actions operate on virtual nodes, never on physical files directly

- **New collection** — creates one collection node under the currently shown directory
  (`FOTLAB-IMGMGR-000001` R6: one node row plus one relation row). Its display name is the
  localised default (`gallery_new_collection_name`, "New Folder" / "新文件夹"). No file is created.
- **Import** — opens the platform file picker, and each picked file becomes one file-entry node
  referenced by `uri_storage`, related to the currently shown directory. The physical file is
  **never moved or copied** (`FOTLAB-IMGMGR-000001` R1/R3).
- **Export** — writes the selected nodes out to a location chosen through the platform. A selected
  collection means its contents (see Q6).
- **Delete** — removes the selected nodes from the virtual tree. Relations are removed with the
  node, and deleting a node never deletes the physical file implicitly — that remains an explicit,
  confirmed action (`FOTLAB-IMGMGR-000001` R7, `FOTLAB-UIXDES-000002` R4).
- All four go through the gallery's lower layer (`GalleryCore`); the screen does not touch the
  database.

### R8 — Copy lives in resources

Every label, content description and the default collection name comes from the `feature: gallery`
block of the single strings file (`FOTLAB-UIXDES-000003` R2/R4). Keys used by this screen:
`gallery_title`, `gallery_cd_open_drawer`, `gallery_cd_more_options`, `gallery_action_import`,
`gallery_action_export`, `gallery_action_new_collection`, `gallery_action_delete`,
`gallery_menu_select_all`, `gallery_menu_invert`, `gallery_menu_clear`,
`gallery_new_collection_name`, `gallery_selection_count` (plural).

## Constraints

- C1 — The skeleton of `FOTLAB-UIXDES-000002` is binding: three-line icon leftmost, three-dot icon
  rightmost, both always present, drawer at 80% of the content region, drawer never covering the
  bottom navigation region.
- C2 — Slots A and B each carry exactly one action per state; import/export and create/delete never
  coexist in the bar.
- C3 — The three-dot dropdown holds exactly the three selection entries of R5, always in that
  order, each icon + text.
- C4 — The top bar is icon-only; only the dropdown and the middle region carry text.
- C5 — `ListSelectionOfGallery` is process-scoped, starts empty, survives destination switching and
  is never persisted.
- C6 — The single instance is owned by `GalleryCore` and exposed as observable state; the screen
  reads it through `remember`. `rememberSaveable`, `SavedStateHandle` and any persistence are
  forbidden for this state (R3).
- C7 — Selection holds node identities, not node copies.
- C8 — No action moves, copies or implicitly deletes a physical file; import and export work
  through platform pickers and the virtual tree (`FOTLAB-IMGMGR-000001` R1/R3/R7).
- C9 — First-party APIs only, per `FOTLAB-UIXDES-000001` R1.

## Acceptance Criteria

- AC1 — On a cold start of the app, the Gallery top bar reads left to right: three-line icon,
  directory name, import icon, add icon, three-dot icon.
- AC2 — Selecting one node changes the bar to: three-line icon, "1 selected", export icon, delete
  icon, three-dot icon; neither import nor add is present.
- AC3 — Deselecting back to zero nodes restores the bar of AC1.
- AC4 — Activating the three-dot icon shows exactly three entries — Select all, Invert selection,
  Clear selection — each with an icon and a text label and no other decoration.
- AC5 — "Select all" selects every node of the currently shown directory; "Clear selection" empties
  the list and restores the empty-state bar; "Invert selection" exchanges the selected and
  unselected nodes of that directory.
- AC6 — Switching to another bottom-navigation destination and returning to Gallery shows the same
  selection as before the switch.
- AC7 — Fully exiting the app and starting it again shows an empty selection and the empty-state
  bar.
- AC13 — Rotating the device (or any other configuration change) keeps the selection and the state
  of the top bar unchanged.
- AC14 — After the system kills the process in the background and the user returns, the selection is
  **not** restored: the app shows an empty selection and the empty-state bar. No saved-state
  mechanism carries the selection across process death.
- AC8 — Creating a collection adds one node under the currently shown directory whose display name
  equals the localised default name, and no physical file or directory is created.
- AC9 — Importing a picked file creates one file-entry node related to the currently shown
  directory while the file itself stays at its original location.
- AC10 — Deleting a selection asks for confirmation before it runs, removes the nodes and their
  relations, and leaves the referenced physical files in place.
- AC11 — Every icon in the top bar and every dropdown entry exposes a non-null content description
  or text resolved from resources.
- AC12 — With the drawer expanded, the bottom navigation region stays visible and interactive, and
  the drawer measures 80% of the content region (`FOTLAB-UIXDES-000002` AC2/AC3/AC9).

## Impacted Modules

- `feature/gallery/GalleryScreen.kt` — renders the top bar, the two slots, the dropdown and the
  drawer; holds no data logic
- `feature/gallery/GalleryCore.kt` — the lower layer implementing new-collection, import, export
  and delete over the virtual tree
- `feature/gallery/GalleryCore` — owns the single process-scoped `ListSelectionOfGallery` instance
  and exposes it as observable state; also implements the four node operations
- `navigation/gallery/` — unchanged; the graph still only composes the screen
- `res/values/strings.xml` — new keys in the `feature: gallery` block
- `FOTLAB-UIXDES-000002` — the skeleton this screen implements
- `FOTLAB-IMGMGR-000001` / `FOTLAB-DATABS-000002` — what a node is and how structure is stored

## Open Questions

- Q3 — "Invert selection" has no dedicated Material icon. `SwapHoriz` is proposed as the closest
  first-party expression of "swap the two sets"; is that acceptable, or should invert get no icon
  in the dropdown? **TBD.**
- Q4 — Selectable scope: can a collection node be selected and deleted together with file entries,
  or is selection restricted to file entries at first? **TBD.**
- Q5 — Do "Select all" and "Invert selection" apply only to the children of the currently shown
  directory, or recursively to the whole subtree beneath it? R5 currently says direct children
  only. **TBD.**
- Q6 — Exporting a selected collection: export its contents flattened, recreate the folder
  structure, or refuse? **TBD.**
- Q7 — Does importing take a persistable URI permission for each picked file, and what happens when
  a permission is later revoked or the file disappears (dangling `uri_storage`)? **TBD.**
- Q8 — New collection naming: if a collection with the default name already exists, is a numeric
  suffix appended, and is the user offered immediate renaming? **TBD.**
- Q9 — Does the middle region show a breadcrumb for nested directories, or only the current
  directory name? R6 currently says the current directory name. **TBD.**

Resolved and retired on 2026-09-08: Q1 (the state holder — a process-scoped object owned by
`GalleryCore`, read by the screen with plain `remember`; now R3 and C6) and Q2 (the type keeps its
full `ListSelectionOfGallery` name — now a clause in R2). The retired numbers are intentionally not
reused.

## Change History

- 2026-09-08 — Initial draft. Fixed the Gallery top bar as the `FOTLAB-UIXDES-000002` skeleton plus
  two action slots that swap with the selection: import + new collection when nothing is selected,
  export + delete when something is. Defined `ListSelectionOfGallery` as a process-scoped list of
  node identities that starts empty on every cold start, survives destination switching untouched
  and dies with the process without ever being persisted. Defined the three-entry overflow menu
  (select all, invert selection, clear selection) with icon + text, the icon-only rule for the bar
  itself, the middle region (directory name vs. selection count), and the rule that all four
  actions work on virtual nodes and never move, copy or implicitly delete a physical file. Left the
  state holder's placement, the identifier name, the invert icon, selection scope, export shape,
  URI permission handling, duplicate naming and breadcrumbs open as Q1–Q9.
- 2026-09-08 — Q2 retired: the type keeps its full name `ListSelectionOfGallery`. More than one
  feature drives its top bar from its own selection list, so the destination name stays in the
  identifier to prevent collisions; `FOTLAB-STRUCT-000003` forbids brand tokens, not destination
  names, and a future second list (for example `ListSelectionOfRender`) follows the same pattern
  instead of shortening the name. Recorded as a clause in R2.
- 2026-09-08 — Q1 retired: the selection is held by a **process-scoped object owned by
  `GalleryCore`** — not by the composition. `GalleryScreen` obtains that holder with plain
  `remember` and subscribes to the state it exposes (`StateFlow` read as Compose `State`); the
  screen holds no selection of its own. `rememberSaveable` is explicitly forbidden for this state:
  a composition-held value dies on destination switch, and saved instance state would resurrect the
  selection after the system killed the process — both contradict R3. The same reasoning rules out
  `SavedStateHandle` and any persistence. Added as clauses in R3 and constraint C6 (later
  constraints renumbered to C7–C9), plus acceptance criteria AC13 (rotation keeps the selection)
  and AC14 (no restoration across process death).
