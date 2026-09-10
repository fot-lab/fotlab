# Icon Vocabulary — Code, Chinese Name and English Name

- ID: FOTLAB-UIXDES-000005
- Status: Draft
- Priority: P2
- Created: 2026-09-10
- Owner: —
- Related: `FOTLAB-UIXDES-000002` (top bar and drawer behaviour these icons belong to), `FOTLAB-UIXDES-000004` (the library top bar that uses them), `FOTLAB-UIXDES-000003` (every content description comes from resources), `FOTLAB-UIXDES-000001` (first-party APIs only)

## Background & Goal

An icon carries no text, so a deviation is invisible in review: the same function can end up drawn
as a download arrow on one screen and as a plus sign on another, and nothing in the code flags it
as a mistake. The drift only surfaces when a user hesitates in front of a button.

This item fixes the vocabulary. For every icon the app uses it records three things side by side:
the **code** (the Compose constant used in the source), the **Chinese name** used in discussion and
in copy, and the **English name** (the Material icon the code is derived from). One row per icon,
one icon per function.

Goals:

- G1 — Every icon in use has exactly one recorded code, Chinese name and English name; no function is drawn with two different icons.
- G2 — Icons come from the first-party Material icon set only, in one style: no third-party icon pack, no project-specific vector drawable.
- G3 — Import and export are settled: 导入 is `SaveAlt`, the arrow **into** the tray (入盘), 导出 is `IosShare`, the arrow **out of** the box (出盘).

## Requirement

### R1 — Source and style are fixed

- Icons come from `androidx.compose.material.icons`, i.e. `androidx.compose.material:material-icons-core` plus `androidx.compose.material:material-icons-extended`, with versions managed by `gradle/libs.versions.toml`. No icon dependency is added beyond these two.
- Style is **filled** everywhere: `Icons.Filled.*`, written `Icons.Default.*` where a file already does so — the two are the same object. `Outlined`, `Rounded`, `Sharp` and `TwoTone` are not used.
- No third-party icon library, no downloaded icon pack and no project-specific vector drawable is introduced for a function an existing Material icon already expresses (`FOTLAB-UIXDES-000001` R1).

### R2 — Import is the into-tray arrow, export is the out-of-box arrow

**Standing rule — no re-deciding.** In this project, **导入 / Import is always `Icons.Filled.SaveAlt` (`save_alt`)**, and **导出 / Export is always `Icons.Filled.IosShare` (`ios_share`)**. Anywhere the words 导入 / import or 导出 / export appear — in this item, in another requirement, in a screen, in a menu, in a review comment — the icon is the one above, with no exception and no second judgement call. A screen that needs one of the two functions copies the constant of the table below; it never picks a glyph.

| 功能 | 代码 (Kotlin) | 中文名称 | 英文名称 (Material) | 语义 |
| --- | --- | --- | --- | --- |
| 导出 Export | `Icons.Default.IosShare` / `Icons.Filled.IosShare` | 出盘（箭头自盒中向上离开） | IosShare (`ios_share`) | 数据从 App 出去 |
| 导入 Import | `Icons.Default.SaveAlt` / `Icons.Filled.SaveAlt` | 入盘（箭头自外向内进入托盘） | SaveAlt (`save_alt`) | 数据进入 App |

- The pair is decided by the **shape and the direction**, not by the English word: 导入 is the arrow coming from outside **into** the tray (入盘), 导出 is the arrow rising **out of** the box (出盘). A glyph that shows only an arrow above or below a plain horizontal line is **not** either of them — the tray or box has to read as a container the arrow goes into or comes out of.
- These two were verified by eye on a device: `Upload` / `Download` (`upload` / `download`) render as an arrow next to a plain line in the icon set this project uses, which is why they are **not** the import/export icons here — see R4.
- Content descriptions stay in `strings.xml` (`FOTLAB-UIXDES-000003` R6); the two actions are promoted copy, so the library uses `common_action_export` and `common_action_import`.

### R3 — Library top bar, drawer and overflow vocabulary

| 功能 | 代码 (Kotlin) | 中文名称 | 英文名称 (Material) | 位置 |
| --- | --- | --- | --- | --- |
| 打开抽屉 | `Icons.Filled.Menu` | 三线菜单（汉堡） | Menu (`menu`) | 顶栏最左，`FOTLAB-UIXDES-000002` R2 |
| 关闭抽屉 | `Icons.Filled.Close` | 关闭 | Close (`close`) | 抽屉左上角，`FOTLAB-UIXDES-000002` R6 |
| 布局切换（网格态） | `Icons.Filled.GridView` | 网格（田字） | GridView (`grid_view`) | 前导区，`FOTLAB-UIXDES-000004` R9 |
| 布局切换（列表态） | `Icons.Filled.ViewList` | 列表 | ViewList (`view_list`) | 前导区，`FOTLAB-UIXDES-000004` R9 |
| 刷新 | `Icons.Filled.Refresh` | 刷新 | Refresh (`refresh`) | 前导区，`FOTLAB-UIXDES-000004` R10 |
| 导入文件 | `Icons.Filled.SaveAlt` | 入盘（箭头自外向内进入托盘） | SaveAlt (`save_alt`) | 槽位 A，空选择态，`FOTLAB-UIXDES-000004` R4 |
| 导出选中 | `Icons.Filled.IosShare` | 出盘（箭头自盒中向上离开） | IosShare (`ios_share`) | 槽位 A，有选择态，`FOTLAB-UIXDES-000004` R4 |
| 新建集合 | `Icons.Filled.Add` | 新建（加号） | Add (`add`) | 槽位 B，空选择态，`FOTLAB-UIXDES-000004` R4 |
| 删除选中 | `Icons.Filled.Delete` | 删除 | Delete (`delete`) | 槽位 B，有选择态，`FOTLAB-UIXDES-000004` R4 |
| 溢出菜单 | `Icons.Filled.MoreVert` | 更多（竖三点） | MoreVert (`more_vert`) | 顶栏最右，`FOTLAB-UIXDES-000002` R4 |
| 全选 | `Icons.Filled.SelectAll` | 全选 | SelectAll (`select_all`) | 溢出菜单第一位，`FOTLAB-UIXDES-000004` R5 |
| 反选 | `Icons.Filled.FlipToBack` | 反选（翻转） | FlipToBack (`flip_to_back`) | 溢出菜单第二位，`FOTLAB-UIXDES-000004` R5 |
| 全不选 | `Icons.Filled.Deselect` | 全不选（取消选择） | Deselect (`deselect`) | 溢出菜单第三位，`FOTLAB-UIXDES-000004` R5 |

- The three selection entries keep the order **全选 → 反选 → 全不选**; the order is part of the row, not a detail of one screen.
- `Deselect` is the dashed-square glyph of the Material set, not the cross: the cross belongs to `close` (`Icons.Filled.Close`, the drawer's close button) and to Material's legacy `clear` name for the same glyph.
- Every row is icon-only in the bar: the meaning is carried by the `contentDescription`, never by a text label next to the icon (`FOTLAB-UIXDES-000004` C4).

### R4 — Boundaries: what these icons are not

- Export is `IosShare` and import is `SaveAlt`, and nothing else. Export is **not** drawn as `Share` (the share glyph), `Upload` or `FileUpload`; import is **not** drawn as `Download`, `FileDownload`, `Add` or `FolderOpen`. Each of those has its own meaning and this table is what keeps them apart.
- `Upload` / `Download` keep the network meanings — sending data to a service and fetching data from one (for example downloading a model). They never stand for import or export.
- 全选 / 反选 / 全不选 are not drawn as `CheckCircle` / `CheckCircleOutline`: those two mean a single item's checked state and "finished / downloaded", never a bulk selection operation.
- When selection entries and import/export share one menu, the three selection entries come first, then the imports — the two groups are never interleaved.
- `Download` read as "download a model" and `Download` read as "import" are the same glyph. The two cases are separated by copy and by placement, never by swapping the glyph.
- The three-line icon and the three-dot icon never change glyph and are never hidden (`FOTLAB-UIXDES-000002` R2/R4/C4).

## Constraints

- C1 — `material-icons-core` + `material-icons-extended` only, filled style (`Icons.Filled.*` / `Icons.Default.*`); no third-party icon pack, no project vector drawable (R1).
- C2 — 导入 / Import is `SaveAlt` and 导出 / Export is `IosShare`, always and without exception (R2 standing rule); the pair is never replaced by `Download`/`Upload`, `FileDownload`/`FileUpload`, `Input`/`Output`, `Share`, `Add` or `FolderOpen` (R2/R4).
- C3 — A function already listed in R3 does not get a second icon; changing the meaning of a row requires a new row and a Change History entry.
- C4 — Every icon carries a `contentDescription` resolved from `strings.xml` (`FOTLAB-UIXDES-000003` R6).

## Acceptance Criteria

- AC1 — The library top bar reads left to right with the glyphs of R3, and each of them matches the code recorded for its row.
- AC2 — Import shows `SaveAlt` (arrow from outside into the tray) and export shows `IosShare` (arrow rising out of the box); no other glyph appears in slots A/B for these two functions.
- AC3 — A grep of the app sources for `Icons.` resolves every occurrence to a row of R3 (or to a later row appended to this table), i.e. no icon is used that this item does not record.
- AC4 — A dependency report shows no icon library beyond `material-icons-core` and `material-icons-extended`, and `res/drawable` holds no project-specific icon vector for a function of R3.
- AC5 — Every icon in the top bar, the drawer and the overflow menu exposes a content description resolved from a resource.
- AC6 — On the device, the export glyph reads as an arrow rising **out of** a box and the import glyph as an arrow coming from outside **into** a tray. A glyph that reads as an arrow next to a plain horizontal line fails this item, even when the code constant is the one listed in R2.

## Impacted Modules

- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreen.kt` — the top bar, the drawer and the overflow menu of R3
- `app/src/main/res/values/strings.xml` — the copy behind every icon
- `gradle/libs.versions.toml` — the two icon artifacts and their version
- Every future feature screen — adds its own icons by appending rows to R3, never by inventing a parallel set

## Open Questions

- Q2 — Does a second screen ever need an outlined variant of a row (for example an unselected state), or does the filled style hold everywhere? **TBD.**

Resolved and retired on 2026-09-10: Q1 — the selection trio is fixed: 全选 `SelectAll`, 反选
`FlipToBack`, 全不选 `Deselect`, in that order; the rows carry the decision and no longer a
question. The retired number is intentionally not reused.

## Change History

- 2026-09-10 — Initial draft. Fixed the icon vocabulary as code + Chinese name + English name in one table per screen: the source is `material-icons-core` + `material-icons-extended` in the filled style only (R1); import and export are settled as the tray arrows — 导入 `Download` (arrow down into the tray), 导出 `Upload` (arrow up out of it) — with an explicit boundary clause forbidding `Share`, `Save`, `Add` and `FolderOpen` substitutes (R2/R4); the library top bar, drawer and overflow rows are recorded in R3. The selection trio is recorded as-is and left open as Q1; the outlined-style question is Q2.
- 2026-09-10 — Q1 retired: the selection trio is fixed — 全选 `Icons.Filled.SelectAll`, 反选 `Icons.Filled.FlipToBack` (replacing `SwapHoriz`), 全不选 `Icons.Filled.Deselect` (replacing `Clear`) — in that order, and the copy key followed as `library_menu_deselect_all` (replacing `library_menu_clear`). R4 gained the `CheckCircle` / `CheckCircleOutline` boundary and the menu-ordering clause.
- 2026-09-10 — R2 corrected after checking the glyphs **on a device**: import and export are **not** `Download` / `Upload`, which render as an arrow next to a plain line in the icon set this project uses. 导入 is `Icons.Filled.SaveAlt` (入盘 — the arrow comes from outside into the tray) and 导出 is `Icons.Filled.IosShare` (出盘 — the arrow rises out of the box). R3, C2, AC2 and AC6 follow; R4 now reserves `Upload` / `Download` for the network upload/download meanings.
- 2026-09-10 — R2 opens with an explicit **standing rule**: 导入 / Import is always `SaveAlt` and 导出 / Export is always `IosShare`, wherever those words appear in the project — no exception, no second judgement per screen. C2 restates it as a constraint and `FOTLAB-UIXDES-000004` R4 references it instead of repeating the icons on its own.
- 2026-09-10 — AC5 wording follows the withdrawal of the `_cd`-suffix rule (`FOTLAB-UIXDES-000003`): a content description must resolve from a resource, with no required suffix.
