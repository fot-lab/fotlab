# String Resources — Multi-Language (i18n) Structure and Naming Rules

- ID: FOTLAB-UIXDES-000003
- Status: Draft
- Priority: P1
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-UIXDES-000001` (module autonomy — modules own their content region and their copy), `FOTLAB-UIXDES-000002` (module-owned top bar, drawer and overflow menu — all of it is copy that needs resources)

## Background & Goal

FotLab must be usable in more than one language. That is only cheap if copy is treated as data
from the first line of code: no literal text in composables, one place to look for a string, and a
structure that a translator, a reviewer and an agent can all navigate without guessing.

The UI is module-owned (`FOTLAB-UIXDES-000001` R4): each module renders inside its own content
region and owns what it shows. Copy therefore has to follow the same boundary — otherwise every
shared module starts leaking text into its neighbours, or every module duplicates the same "OK" and
"Cancel" in its own dialect.

Goals:

- G1 — One i18n mechanism for the whole app: the Android resource system. No parallel solution.
- G2 — One canonical order for every strings document / source set: **`common` first, then modules
  in a fixed, published order.** The same order everywhere, so a string is found by position, not by
  search.
- G3 — Module ownership of copy, with an explicit promotion path when two modules need the same text.
- G4 — Stable, semantic, language-independent keys, so translations can be added or corrected without
  touching code.

## Requirement

### R1 — Android string resources are the only i18n mechanism

- All user-visible text lives in `res/values*/strings.xml` resource files, read through
  `stringResource(R.string.x)` in Compose (or `resources.getString(...)` where a `Context` is required).
- Default (unqualified) `values/strings.xml` holds the **base language** — see Q1; every other locale
  is a qualifier directory such as `values-zh-rCN`, `values-ja`, `values-de`.
- Plurals use `<plurals>` with the correct quantity for each language; they are never emulated with
  `if (count == 1)` in Kotlin.
- Variable text uses numbered placeholders (`%1$s`, `%2$d`), never string concatenation in code.
  A placeholder keeps the same index and the same type in every translation.
- Fixed, ordered lists use `<string-array>` rather than a set of unrelated keys.
- No third-party i18n framework, no runtime string packs, no string tables loaded from assets, no
  machine-translation shim baked into the app. Translation is a build-time artefact, not a runtime
  feature.
- HTML/styling inside strings is limited to what `stringResource` can render; rich text is built in
  code from separate spans only when formatting genuinely differs per locale.

### R2 — Document architecture: `common` first, then modules in a fixed order

Every strings document (a `strings.xml`, and any aggregated strings listing kept in the repo) is laid
out in the following order. No exceptions, no alphabetical wandering, no "append at the end":

```
┌──────────────────────────────────────────────┐
│ 1. common                                    │  ← app-wide copy, shared by every module
│      generic actions, generic states/errors, │
│      shared accessibility descriptions       │
├──────────────────────────────────────────────┤
│ 2. app (shell)                               │  ← bottom navigation labels, shell-level dialogs
├──────────────────────────────────────────────┤
│ 3. <module> …                                │  ← one block per feature module,
│ 4. <module> …                                │     in canonical module order
│ 5. <module> …                                │
├──────────────────────────────────────────────┤
│ n. other / non-destination modules           │  ← alphabetically, only if they own copy
└──────────────────────────────────────────────┘
```

- **Block 1 — `common`.** Copy that is, or may become, used by more than one module: generic
  affirmative/dismissive actions (OK, Cancel, Save, Delete, Retry, Close, Back, Done, More), generic
  states (Loading, Empty, No results), generic errors (Something went wrong, Network unavailable),
  confirm-dialog scaffolding, and accessibility descriptions for shared components.
- **Block 2 — `app`.** The shell: bottom navigation item labels and their content descriptions,
  shell-level dialogs, app name.
- **Blocks 3..n — modules.** One contiguous block per module. The order of these blocks is the
  **canonical module order**: the left-to-right order of the bottom navigation destinations defined
  by `FOTLAB-UIXDES-000001` (see Q2). Modules that are not top-level destinations are appended after
  them, sorted alphabetically by module id.
- Each block is introduced by a visible separator comment so the order is machine-checkable:

  ```xml
  <!-- ===== common ===== -->
  <string name="common_action_ok">OK</string>

  <!-- ===== module: render ===== -->
  <string name="render_preview_title">Preview</string>
  ```

- Inside a block, strings are grouped by screen/feature and, within a screen, ordered by role in the
  order: `title` → `label` → `action` → `hint` → `message` → `error` → `empty` → `cd`
  (content description). Keys of the same screen stay together; a new key is inserted in its group,
  never appended to the end of the file.
- The same order applies to locale files: a translated `strings.xml` mirrors the base file block for
  block and key for key, so a diff between locales is readable.

### R3 — Ownership: strings follow module boundaries

- Each module keeps its own `src/main/res/values*/strings.xml`; the shared module keeps the `common`
  block. A module's block exists in exactly one place.
- A module must not reference another module's string resource. If two modules need the same text,
  the string is **promoted into `common`** (with a `common_` key) and the module copies are removed.
  This promotion is recorded in this item's Change History when it affects an existing key.
- The shell must not reference module strings; a module must not reference `app` strings. Shared text
  belongs in `common`, which everyone may reference.
- Non-translatable content — brand names, file paths, format samples, debug identifiers — stays in
  `common` and is marked `translatable="false"`; it is not duplicated into locale files.

### R4 — Naming: semantic, prefixed, stable

- Key format (snake_case): `<block>_<screen or feature>_<what>_<role>`
  - `<block>` — `common`, `app`, or the module id (e.g. `render`, `imgmgr`, `metada`).
  - `<screen or feature>` — the screen or component the text belongs to (`preview`, `style_picker`).
  - `<what>` — what it names (`title`, `apply`, `empty`, `failed`).
  - `<role>` — suffix describing the kind of text: `_title`, `_label`, `_action`, `_hint`, `_message`,
    `_error`, `_empty`, `_cd` (content description), `_plural` is not used (`<plurals>` carries it).
- Examples: `common_action_ok`, `common_error_generic`, `app_nav_render_label`,
  `render_preview_style_title`, `render_preview_style_apply_action`, `render_preview_empty_message`,
  `render_preview_thumbnail_cd`.
- Keys are **semantic, never derived from the English text**, and never renamed just because the
  wording changed. Changing the meaning of a key requires a new key; the old one is removed only when
  no locale and no code references it.
- Keys are ASCII snake_case and identical in every locale file; a locale file never introduces a key
  of its own.

### R5 — Completeness and parity between locales

- Every locale file contains exactly the same translatable key set as the base file. A missing key in
  a locale is a build/lint error, not a silent fallback at runtime.
- A fallback to the base language at runtime is acceptable only as a safety net; it is never used as
  a substitute for completing a translation.
- Adding a key means adding it to the base file first, then to every shipped locale — in the same
  block position (R2).
- `translatable="false"` is used deliberately and sparingly; no `tools:ignore` is added to silence a
  missing translation.

### R6 — No hardcoded user-visible text

- No literal string is passed to a composable that renders text (`Text`, `contentDescription`,
  `Snackbar`, `Toast`, `Dialog` titles, menu entries). Preview-only composables must not introduce
  literals into production code paths either — they use the same resources.
- Accessibility descriptions (`contentDescription`) come from resources as well; a hardcoded English
  description is a translation defect, not a shortcut.
- Developer-facing output (log tags, log messages, exceptions, debug overlays) may stay literal
  English and is **not** part of the strings documents.

### R7 — Locale-aware formatting

- Dates, times, numbers, file sizes and units are formatted with platform formatters
  (`java.text` / `android.icu` / `Formatter`), never by concatenating pieces of text.
- A sentence is built as a whole string with placeholders, not assembled from fragments, because word
  order differs per language.
- Capitalisation and punctuation belong to the translation, not to the code; code must not uppercase
  or append punctuation to a translated string.

### R8 — Locale selection and layout direction

- The locale set shipped by the app is explicit and listed in the Change History when it changes;
  the resource qualifier alone decides which file is used.
- Layout direction is not hardcoded: RTL locales rely on the resource system and Compose's
  `LayoutDirection`; no `start`/`end` assumption may be baked into a string or into padding.
- Text expansion is assumed: layouts must tolerate a translation that is longer than the base text
  (no fixed widths around text, no single-line truncation of critical copy).
- Pseudo-locales are enabled in debug builds to surface hardcoded text and untranslated keys.

## Constraints

- C1 — Android resource files only; no third-party i18n library, no runtime string loading (R1).
- C2 — Every strings document starts with `common`, then `app`, then modules in canonical order (R2).
  Appending a new module anywhere else is a violation.
- C3 — A module references only `common` and its own block; cross-module string references are
  forbidden (R3).
- C4 — Keys are semantic, ASCII snake_case, prefixed by their block, and stable across wording
  changes (R4).
- C5 — Locale parity is mandatory; missing keys fail the build rather than falling back silently (R5).
- C6 — No literal user-visible text in composables, including `contentDescription` (R6).
- C7 — No manual date/number/unit formatting and no sentence assembly by concatenation (R7).
- C8 — Non-translatable strings are marked `translatable="false"` and kept out of locale files (R3).

## Acceptance Criteria

- AC1 — Searching the UI source for a string literal passed to `Text`, `contentDescription`, a menu
  entry, a snackbar or a dialog returns no production hits (preview and test code excluded).
- AC2 — Opening any `strings.xml` shows `common` as the first block, `app` as the second, and one
  contiguous block per module in canonical order, each preceded by its separator comment.
- AC3 — A module dependency check shows no module importing another module's `R.string` (only
  `common`/own `R` are referenced).
- AC4 — Changing a locale qualifier (e.g. forcing `zh-rCN`) renders the whole UI in that locale with
  no untranslated base-language text visible in a normal walkthrough of every destination.
- AC5 — A build with one key deliberately missing from a locale fails (or is rejected by the lint
  gate) instead of falling back at runtime.
- AC6 — Every `contentDescription` in the app resolves through a resource entry ending in `_cd`.
- AC7 — Enabling RTL pseudo-locale (or `ldrtl`) mirrors the layout without any string-specific code
  change, and no text is clipped in a locale whose strings are visibly longer than the base language.
- AC8 — Diffing two locale files shows identical block order and identical key order.

## Impacted Modules

- Shared/common UI module — owns the `common` block and, if needed, shared formatting helpers
- `app` (shell) — bottom navigation labels, shell-level dialogs
- Every feature module — owns its own block inside its own `res/values*/`
- `FOTLAB-UIXDES-000001` — supplies the destination order that fixes the canonical module order
- `FOTLAB-UIXDES-000002` — top bar, drawer and overflow menu copy that must come from resources

## Open Questions

- Q1 — What is the base language of the unqualified `values/` directory: English (current default in
  this item) or Chinese? **TBD.** Everything else — key semantics, translation workflow — is unaffected,
  but the choice must be written down before the first locale is added.
- Q2 — Canonical module order depends on the definitive destination set and its order, which is Q1 of
  `FOTLAB-UIXDES-000001`. **TBD.** Until it is fixed, module blocks follow the order in which
  destinations appear in the shell's route table.
- Q3 — Which locales ship in the first release? **TBD.**
- Q4 — Is translation done in-repo (translators edit `values-xx/strings.xml` directly) or through an
  export/import step with an external tool? The export format must preserve the block order of R2.
- Q5 — Is RTL in scope at launch? The resource and layout rules of R8 hold either way, but testing
  effort differs.
- Q6 — Does any module need locale-dependent plural rules beyond the standard CLDR quantities, and is
  `<plurals>` sufficient for Chinese (which has a single quantity)? **TBD.**

## Change History

- 2026-09-07 — Initial draft. Established Android string resources as the only i18n mechanism; the
  `common` → `app` → modules (canonical order) document architecture with per-block separator comments
  and a fixed intra-block role order; module ownership of copy with promotion of shared text into
  `common`; semantic prefixed snake_case keys; mandatory locale parity; the ban on hardcoded
  user-visible text (including content descriptions); platform-based date/number formatting; and
  locale/RTL layout rules. Base language, canonical module order, shipped locales, translation
  workflow, RTL scope and plural handling left open as Q1–Q6.
