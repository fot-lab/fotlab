# Control flow — six nesting sites, three non-exhaustive `when`, and one concept modelled three ways

- ID: ACTION-KOTLIN-000005
- Status: Observation
- Priority: P2
- Created: 2026-09-16
- Owner: —
- Related: `ACTION-KOTLIN-000001` (master audit), `rules/DESIGN/detail/FOTLAB-UIXDES-000004.md` (R9 layout modes, selection action mode), `app/src/main/kotlin/io/github/fotlab/fotlab/ui/ZoomableImage.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryScreen.kt`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryThumbnail.kt`

## Background & Goal

The audit question was whether any `if` tree is deep enough to warrant flattening into a `when` / sealed dispatch. The answer is that the code base has no `if`/`else` ladders — most dispatch is already `when` (`route()`, `exifOrientationText()`, `mimeIcon()`), and several sealed interfaces make it exhaustive.

What it does have is six sites where nesting reaches three or more levels, three `when` expressions that swallow cases behind `else`, and one domain concept ("is this media, and which kind") that is expressed three different ways. This item records those.

## Finding

### 1. `ZoomableImage.kt:232-272` — five levels, the deepest in the code base

`do { … } while` → `if (!cancelled)` → `if (transforms)` → `if (!pastSlop)` → `if (zoomMotion > touchSlop || …)`, with a parallel `if (pastSlop)` sibling. The gesture logic itself is correct and the touch-slop gate is deliberately modelled on the official detector, but a reader has to hold five conditions in their head at the innermost point.

### 2. `LibraryScreen.LibraryTopBar` — the same predicate decided twice

`navigationIcon` branches on `if (!selectionModeActive)`, and `actions` branches on `if (!selectionModeActive)` again; the `else` in `navigationIcon` then branches on `if (selectionSize == 1)`. Two independent readings of one mode means the two halves can drift, and the `selectionSize == 1` special case is invisible from the actions slot.

### 3. `LibraryScreen.NodeCell:784-875` — `if (isGrid)` over two ~45-line bodies

The grid `Card` and the detail `ListItem` share nothing but their inputs. Keeping both in one function makes each harder to read and guarantees the function grows whenever either layout changes.

### 4. `LibraryThumbnail.NodeThumbnail:47-74` — three levels

`when` → `isMedia` branch → `if (uri != null)`. The innermost decision is really a third kind of thumbnail, not a sub-case of media.

### 5. Non-exhaustive `when`

- `LibraryScreen.kt:757` — `when (layoutMode) { DetailList -> …; else -> … }`. The `else` absorbs `Grid1` / `Grid2` / `Grid3`, so adding a mode compiles silently. It also duplicates `LibraryLayoutMode.isGrid`, which is defined as `this != DetailList` — two expressions of one fact.
- `LibraryViewerDialog.kt:122-148` — four branches of which the first (`uri == null`) and the last (`else`) differ only in which placeholder icon they show.
- `Theme.kt:78-85` — `when` whose first branch contains `if (darkTheme) … else …`; flattening to four top-level branches removes one level.
- `LibraryScreenRecycle.kt:123-130` — `when` whose `is Inside` branch contains `if (loc.nodeId != null) … else …`.
- `StudioEngine.kt:84-91` — two sequential early returns; harmless, but could be one `when`.

### 6. One concept, three expressions (K-28)

"Is this node media, and image or video?" is written as:

- `isMedia(mime)` in `LibraryThumbnail.kt:92`
- `node.typeMime.startsWith("image/")` / `("video/")` in `LibraryViewerDialog.kt:130,132,313,315`
- `isMedia(it.typeMime)` in `LibraryScreen.kt:247,250` and `LibraryScreenRecycle.kt:463`

### 7. Style defects found in the same pass (P3)

- `LibraryScreen.kt:87-90` and `LibraryScreenRecycle.kt:19` — import order violates `kotlin.code.style=official`.
- `LibraryScreen.kt:194-233` — block body under-indented by four spaces (ktlint would flag it).
- `LibraryScreen.kt:672-683` — `onTextLayout` measures text and writes state; it is guarded by `if (fitted != displayText)` so it terminates today, but it is a layout callback writing state and deserves a comment or a `derivedStateOf`.
- `StudioEngine.kt:129` — `Constants` nested object, inconsistent with every other top-level `private const` (`DEFAULT_SNIFF_TIMEOUT_MS`, `SCALE_EPSILON`).

## Impact / Conflict

- None of these change behaviour when fixed; the value is readability and, for item 5, compile-time safety when the layout-mode set grows.
- Item 3 and item 6 touch `LibraryScreen`, which is also the file `ACTION-KOTLIN-000004` wants to restructure — do that extraction first, then flatten, to avoid two people editing the same 878-line file at once.
- Item 5's `when (layoutMode)` and `isGrid` duplication is a real drift hazard: adding a mode that should behave like a list would silently render as a grid.

## Recommendation

1. `ZoomableImage`: extract the loop body into `private suspend fun AwaitPointerEventScope.handleEvent(...): Boolean` (returns whether to keep looping) and use early returns; split out `shouldStartTransform()` and `accumulateSlop()`. Five levels become two.
2. `LibraryTopBar`: introduce a small sealed input — `Idle` vs `Selecting(count, onRename)` — and derive leading and action slots from one `when`. This is the same helper `ACTION-KOTLIN-000004` group C2/C3 asks for; do it once, there.
3. `NodeCell`: split into `GridNodeCell` and `DetailListNodeCell`; `NodeCell` becomes a two-branch `when (layoutMode)`. Keep the inner `if (selected) a else b` — two branches is fine.
4. `NodeThumbnail`: add

   ```kotlin
   internal sealed interface ThumbKind {
       data object Folder : ThumbKind
       data class Media(val uri: Uri) : ThumbKind
       data class Glyph(val mime: String) : ThumbKind
   }
   ```

   with a pure `thumbKindOf(node)`, then a flat `when (kind)` in the composable. This also resolves item 6 for the thumbnail path.
5. Make the layout `when` exhaustive (`Grid1, Grid2, Grid3 ->`) and let `isGrid` be the single definition, or drop `isGrid` and dispatch only on the enum — pick one.
6. Collapse the two placeholder branches in `LibraryViewerDialog` into one `ViewerPlaceholder(icon)` call; flatten `Theme.kt` and the `RecycleLocation` back handler; move `Constants.HEADER_BYTES` to a top-level `private const`.
7. Replace the three media tests with `MediaKind.of(mime)` once item 4's sealed modelling is in place.

## Change History

- 2026-09-16 — Recorded from the full-source Kotlin audit (`ACTION-KOTLIN-000001`). Six nesting sites (deepest: the five-level gesture loop in `ZoomableImage.kt`), five non-exhaustive or nested `when` expressions, one domain concept expressed three ways, and four style defects. No code changed.
