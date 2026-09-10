# Icon Vocabulary — Code, Chinese Name and English Name

- ID: FOTLAB-UIXDES-000005
- Status: Draft
- Priority: P2
- Created: 2026-09-10
- Owner: —
- Related: `FOTLAB-UIXDES-000002` (top bar and drawer behaviour these icons belong to), `FOTLAB-UIXDES-000004` (the gallery top bar that uses them), `FOTLAB-UIXDES-000003` (every content description comes from resources), `FOTLAB-UIXDES-000001` (first-party APIs only)

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
- G3 — Import and export are settled: 导出 is the arrow **out of** the tray (up), 导入 is the arrow **into** the tray (down).

## Requirement

### R1 — Source and style are fixed

- Icons come from `androidx.compose.material.icons`, i.e. `androidx.compose.material:material-icons-core` plus `androidx.compose.material:material-icons-extended`, with versions managed by `gradle/libs.versions.toml`. No icon dependency is added beyond these two.
- Style is **filled** everywhere: `Icons.Filled.*`, written `Icons.Default.*` where a file already does so — the two are the same object. `Outlined`, `Rounded`, `Sharp` and `TwoTone` are not used.
- No third-party icon library, no downloaded icon pack and no project-specific vector drawable is introduced for a function an existing Material icon already expresses (`FOTLAB-UIXDES-000001` R1).

### R2 — Import and export are the tray arrows

| 功能 | 代码 (Kotlin) | 中文名称 | 英文名称 (Material) | 语义 |
| --- | --- | --- | --- | --- |
| 导出 Export | `Icons.Default.Upload` / `Icons.Filled.Upload` | 上传（箭头向上出盘 / 出盘） | Upload (`upload`) | 数据从 App 出去 |
| 导入 Import | `Icons.Default.Download` / `Icons.Filled.Download` | 下载（箭头向下入盘 / 入盘） | Download (`download`) | 数据进入 App |

- The pair is decided by the **shape and the direction**, not by the English word: the glyph is a tray (盘), and the arrow either **leaves** it (export) or **enters** it from outside (import). A rendering that shows only an arrow above or below a plain horizontal line is **not** this icon: the tray has to read as a container the arrow goes into or comes out of.
- The icon is the tray arrow (`upload` / `download`), never a file, share, save or plus glyph — see R4.
- Content descriptions stay in `strings.xml` (`FOTLAB-UIXDES-000003` R6); the gallery uses `gallery_cd_export` and `gallery_cd_import`.

### R3 — Gallery top bar, drawer and overflow vocabulary

| 功能 | 代码 (Kotlin) | 中文名称 | 英文名称 (Material) | 位置 |
| --- | --- | --- | --- | --- |
| 打开抽屉 | `Icons.Filled.Menu` | 三线菜单（汉堡） | Menu (`menu`) | 顶栏最左，`FOTLAB-UIXDES-000002` R2 |
| 关闭抽屉 | `Icons.Filled.Close` | 关闭 | Close (`close`) | 抽屉左上角，`FOTLAB-UIXDES-000002` R6 |
| 布局切换（网格态） | `Icons.Filled.GridView` | 网格（田字） | GridView (`grid_view`) | 前导区，`FOTLAB-UIXDES-000004` R9 |
| 布局切换（列表态） | `Icons.Filled.ViewList` | 列表 | ViewList (`view_list`) | 前导区，`FOTLAB-UIXDES-000004` R9 |
| 刷新 | `Icons.Filled.Refresh` | 刷新 | Refresh (`refresh`) | 前导区，`FOTLAB-UIXDES-000004` R10 |
| 导入文件 | `Icons.Filled.Download` | 下载（箭头向下入盘） | Download (`download`) | 槽位 A，空选择态，`FOTLAB-UIXDES-000004` R4 |
| 导出选中 | `Icons.Filled.Upload` | 上传（箭头向上出盘） | Upload (`upload`) | 槽位 A，有选择态，`FOTLAB-UIXDES-000004` R4 |
| 新建集合 | `Icons.Filled.Add` | 新建（加号） | Add (`add`) | 槽位 B，空选择态，`FOTLAB-UIXDES-000004` R4 |
| 删除选中 | `Icons.Filled.Delete` | 删除 | Delete (`delete`) | 槽位 B，有选择态，`FOTLAB-UIXDES-000004` R4 |
| 溢出菜单 | `Icons.Filled.MoreVert` | 更多（竖三点） | MoreVert (`more_vert`) | 顶栏最右，`FOTLAB-UIXDES-000002` R4 |
| 全选 | `Icons.Filled.SelectAll` | 全选 | SelectAll (`select_all`) | 溢出菜单，`FOTLAB-UIXDES-000004` R5 — 见 Q1 |
| 反选 | `Icons.Filled.SwapHoriz` | 反选（交换） | SwapHoriz (`swap_horiz`) | 溢出菜单，`FOTLAB-UIXDES-000004` R5 — 见 Q1 |
| 不选 | `Icons.Filled.Clear` | 清除（叉号） | Clear (`clear`) | 溢出菜单，`FOTLAB-UIXDES-000004` R5 — 见 Q1 |

- `Clear` is Material's name for the cross glyph; Material Symbols renamed the same glyph to `close`, and both `Icons.Filled.Clear` and `Icons.Filled.Close` exist in the set.
- Every row is icon-only in the bar: the meaning is carried by the `contentDescription`, never by a text label next to the icon (`FOTLAB-UIXDES-000004` C4).

### R4 — Boundaries: what these icons are not

- Export is not drawn as `Share` / `IosShare`, and import is not drawn as `Add` / `FolderOpen`. Each of those has its own meaning and this table is what keeps them apart.
- `Download` read as "download a model" and `Download` read as "import" are the same glyph. The two cases are separated by copy and by placement, never by swapping the glyph.
- The three-line icon and the three-dot icon never change glyph and are never hidden (`FOTLAB-UIXDES-000002` R2/R4/C4).

## Constraints

- C1 — `material-icons-core` + `material-icons-extended` only, filled style (`Icons.Filled.*` / `Icons.Default.*`); no third-party icon pack, no project vector drawable (R1).
- C2 — Import is `Download` and export is `Upload`; the pair is never replaced by `FileDownload`/`FileUpload`, `Input`/`Output`, `Share`, `Save`, `Add` or `FolderOpen` (R2/R4).
- C3 — A function already listed in R3 does not get a second icon; changing the meaning of a row requires a new row and a Change History entry.
- C4 — Every icon carries a `contentDescription` resolved from `strings.xml` (`FOTLAB-UIXDES-000003` R6).

## Acceptance Criteria

- AC1 — The gallery top bar reads left to right with the glyphs of R3, and each of them matches the code recorded for its row.
- AC2 — Import shows the arrow pointing down into the tray and export the arrow pointing up out of it; no other glyph appears in slots A/B for these two functions.
- AC3 — A grep of the app sources for `Icons.` resolves every occurrence to a row of R3 (or to a later row appended to this table), i.e. no icon is used that this item does not record.
- AC4 — A dependency report shows no icon library beyond `material-icons-core` and `material-icons-extended`, and `res/drawable` holds no project-specific icon vector for a function of R3.
- AC5 — Every icon in the top bar, the drawer and the overflow menu exposes a content description resolved from a `*_cd` resource.
- AC6 — On the device, the export glyph reads as an arrow rising **out of** a tray and the import glyph as an arrow coming from outside **into** a tray. A glyph that reads as an arrow next to a plain horizontal line fails this item, even when the code constant is the one listed in R2.

## Impacted Modules

- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/gallery/GalleryScreen.kt` — the top bar, the drawer and the overflow menu of R3
- `app/src/main/res/values/strings.xml` — the `*_cd` copy behind every icon
- `gradle/libs.versions.toml` — the two icon artifacts and their version
- Every future feature screen — adds its own icons by appending rows to R3, never by inventing a parallel set

## Open Questions

- Q1 — The selection trio (全选 / 反选 / 不选) is recorded in R3 **as it is today**; Material ships no `invert_selection`, so the invert glyph is still under discussion and the three rows carry no final decision yet. **TBD.**
- Q2 — Does a second screen ever need an outlined variant of a row (for example an unselected state), or does the filled style hold everywhere? **TBD.**

## Change History

- 2026-09-10 — Initial draft. Fixed the icon vocabulary as code + Chinese name + English name in one table per screen: the source is `material-icons-core` + `material-icons-extended` in the filled style only (R1); import and export are settled as the tray arrows — 导入 `Download` (arrow down into the tray), 导出 `Upload` (arrow up out of it) — with an explicit boundary clause forbidding `Share`, `Save`, `Add` and `FolderOpen` substitutes (R2/R4); the gallery top bar, drawer and overflow rows are recorded in R3. The selection trio is recorded as-is and left open as Q1; the outlined-style question is Q2.
