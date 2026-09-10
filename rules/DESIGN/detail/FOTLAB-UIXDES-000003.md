# String Resources — Multi-Language (i18n) Structure and Naming Rules

- ID: FOTLAB-UIXDES-000003
- Status: Draft
- Priority: P1
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-STRUCT-000001` (single module — one `res/` set, features are packages), `FOTLAB-UIXDES-000001` (destinations own their content region and their copy), `FOTLAB-UIXDES-000002` (top bar, drawer and overflow menu — all of it is copy that needs resources)

## Background & Goal

FotLab must be usable in more than one language. That is only cheap if copy is treated as data
from the first line of code: no literal text in composables, one place to look for a string, and a
structure that a translator, a reviewer and an agent can all navigate without guessing.

The project is a single Gradle module (`FOTLAB-STRUCT-000001`), so there is exactly **one**
`strings.xml`. That removes the old per-module question but makes order the only navigational aid
left: without a fixed order, a single file of a few hundred keys becomes unreadable.

Goals:

- G1 — One i18n mechanism for the whole app: the Android resource system. No parallel solution.
- G2 — One canonical order for the single strings file: **`common` first, then `app`, then one block
  per feature in a fixed order.** A string is found by position, not by search.
- G3 — Feature ownership of copy, with an explicit promotion path when two features need the same
  text.
- G4 — Stable, semantic, language-independent keys, so translations can be added or corrected without
  touching code.

## Requirement

### R1 — Android string resources are the only i18n mechanism

- All user-visible text lives in `app/src/main/res/values*/strings.xml`, read through
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

### R2 — Document architecture: `common` first, then features in a fixed order

The strings file is laid out in the following order. No exceptions, no alphabetical wandering, no
"append at the end":

```
┌──────────────────────────────────────────────┐
│ 1. common                                    │  ← app-wide copy, shared by every feature
│      generic actions, generic states/errors, │
│      shared accessibility descriptions       │
├──────────────────────────────────────────────┤
│ 2. app (shell)                               │  ← bottom navigation labels, shell-level dialogs
├──────────────────────────────────────────────┤
│ 3. <feature> …                               │  ← one block per feature package,
│ 4. <feature> …                               │     in canonical feature order
│ 5. <feature> …                               │
├──────────────────────────────────────────────┤
│ n. other / non-destination features          │  ← alphabetically, only if they own copy
└──────────────────────────────────────────────┘
```

- **Block 1 — `common`.** Copy that is, or may become, used by more than one feature: generic
  affirmative/dismissive actions (OK, Cancel, Save, Delete, Retry, Close, Back, Done, More), generic
  states (Loading, Empty, No results), generic errors (Something went wrong, Network unavailable),
  confirm-dialog scaffolding, and accessibility descriptions for shared components.
- **Block 2 — `app`.** The shell: bottom navigation item labels and their content descriptions,
  shell-level dialogs, app name.
- **Blocks 3..n — features.** One contiguous block per feature package. The order of these blocks is
  the **canonical feature order**: the left-to-right order of the bottom navigation destinations
  defined by `FOTLAB-UIXDES-000001` (see Q2). Features that are not top-level destinations are
  appended after them, sorted alphabetically by feature id.
- Each block is introduced by a visible separator comment so the order is machine-checkable:

  ```xml
  <!-- ===== common ===== -->
  <string name="common_action_ok">OK</string>

  <!-- ===== app ===== -->
  <string name="app_name">FotLab</string>

  <!-- ===== feature: library ===== -->
  <string name="library_title">Library</string>
  ```

- Inside a block, strings are grouped by screen and, within a screen, ordered by role in the order:
  `title` → `label` → `action` → `hint` → `message` → `error` → `empty` → `cd`
  (content description). Keys of the same screen stay together; a new key is inserted in its group,
  never appended to the end of the file.
- The same order applies to locale files: a translated `strings.xml` mirrors the base file block for
  block and key for key, so a diff between locales is readable.

### R3 — Ownership: strings follow feature boundaries

There is one strings file per locale; ownership is expressed by the block and by the key prefix, not
by a file per feature (`FOTLAB-STRUCT-000001` R5).

- Each feature owns exactly one contiguous block; a block exists in exactly one place.
- A feature must not use another feature's keys. If two features need the same text, the string is
  **promoted into `common`** (with a `common_` key) and the feature copies are removed. This
  promotion is recorded in this item's Change History when it affects an existing key.
- The shell must not use feature keys; a feature must not use `app` keys. Shared text belongs in
  `common`, which everyone may use.
- Non-translatable content — brand names, file paths, format samples, debug identifiers — stays in
  `common` and is marked `translatable="false"`; it is not duplicated into locale files.

### R4 — Naming: semantic, prefixed, stable

- Key format (snake_case): `<block>_<screen or feature>_<what>_<role>`
  - `<block>` — `common`, `app`, or the feature id (e.g. `library`, `render`, `import`).
  - `<screen or feature>` — the screen or component the text belongs to (`preview`, `style_picker`).
  - `<what>` — what it names (`title`, `apply`, `empty`, `failed`).
  - `<role>` — suffix describing the kind of text: `_title`, `_label`, `_action`, `_hint`, `_message`,
    `_error`, `_empty`, `_cd` (content description); plurals use `<plurals>`, not a `_plural` suffix.
- Examples: `common_action_ok`, `common_error_generic`, `app_nav_library_label`,
  `library_title`, `library_cd_open_drawer`, `library_drawer_empty`.
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
  English and is **not** part of the strings file.

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
- C2 — The strings file starts with `common`, then `app`, then features in canonical order (R2).
  Appending a new feature anywhere else is a violation.
- C3 — A feature uses only `common` and its own block; cross-feature key use is forbidden (R3).
- C4 — Keys are semantic, ASCII snake_case, prefixed by their block, and stable across wording
  changes (R4).
- C5 — Locale parity is mandatory; missing keys fail the build rather than falling back silently (R5).
- C6 — No literal user-visible text in composables, including `contentDescription` (R6).
- C7 — No manual date/number/unit formatting and no sentence assembly by concatenation (R7).
- C8 — Non-translatable strings are marked `translatable="false"` and kept out of locale files (R3).

## Acceptance Criteria

- AC1 — Searching the UI source for a string literal passed to `Text`, `contentDescription`, a menu
  entry, a snackbar or a dialog returns no production hits (preview and test code excluded).
- AC2 — Opening `app/src/main/res/values/strings.xml` shows `common` as the first block, `app` as the
  second, and one contiguous block per feature in canonical order, each preceded by its separator
  comment.
- AC3 — A grep of the `ui/<feature>/` and `navigation/<feature>/` sources shows no feature using
  another feature's `R.string` — only `common_*` and its own prefixed keys (R3).
- AC4 — Changing a locale qualifier (e.g. forcing `zh-rCN`) renders the whole UI in that locale with
  no untranslated base-language text visible in a normal walkthrough of every destination.
- AC5 — A build with one key deliberately missing from a locale fails (or is rejected by the lint
  gate) instead of falling back at runtime.
- AC7 — Enabling RTL pseudo-locale (or `ldrtl`) mirrors the layout without any string-specific code
  change, and no text is clipped in a locale whose strings are visibly longer than the base language.
- AC8 — Diffing two locale files shows identical block order and identical key order.

## Impacted Modules

- `app/src/main/res/values*/strings.xml` — the single copy source per locale
- `ui/theme` and shared helpers — own the `common` block and, if needed, shared formatting helpers
- `navigation/TopLevelDestination.kt` — bottom navigation labels (`app_*`)
- Every feature package (`ui/<feature>/`) — owns one block in the strings file
- `FOTLAB-UIXDES-000001` — supplies the destination order that fixes the canonical feature order
- `FOTLAB-STRUCT-000001` — decides that there is exactly one resource set

## Open Questions

- Q1 — What is the base language of the unqualified `values/` directory: English (current default in
  this item) or Chinese? **TBD.** Everything else — key semantics, translation workflow — is unaffected,
  but the choice must be written down before the first locale is added.
- Q2 — Canonical feature order depends on the definitive destination set and its order, which is Q1 of
  `FOTLAB-UIXDES-000001`. **TBD.** Until it is fixed, feature blocks follow the order in which
  destinations appear in the shell's route table.
- Q3 — Which locales ship in the first release? **TBD.**
- Q4 — Is translation done in-repo (translators edit `values-xx/strings.xml` directly) or through an
  export/import step with an external tool? The export format must preserve the block order of R2.
- Q5 — Is RTL in scope at launch? The resource and layout rules of R8 hold either way, but testing
  effort differs.
- Q6 — Does any feature need locale-dependent plural rules beyond the standard CLDR quantities, and is
  `<plurals>` sufficient for Chinese (which has a single quantity)? **TBD.**

## Change History

- 2026-09-07 — Initial draft. Established Android string resources as the only i18n mechanism; the
  `common` → `app` → features (canonical order) document architecture with per-block separator comments
  and a fixed intra-block role order; feature ownership of copy with promotion of shared text into
  `common`; semantic prefixed snake_case keys; mandatory locale parity; the ban on hardcoded
  user-visible text (including content descriptions); platform-based date/number formatting; and
  locale/RTL layout rules. Base language, canonical feature order, shipped locales, translation
  workflow, RTL scope and plural handling left open as Q1–Q6.
- 2026-09-07 — Updated for the single-module layout (`FOTLAB-STRUCT-000001`):   "module" became
  "feature" throughout, because there is now **one** strings file instead of one per module. Ownership
  (R3) is expressed by block and key prefix rather than by file; AC3 became a grep over
  `ui/<feature>/` and `navigation/<feature>/` instead of a module dependency check; the Impacted
  Modules section now names `app/src/main/res/values*/strings.xml` and the feature packages. The block
  order itself — `common` first, then `app`, then features — is unchanged.
- 2026-09-10 — Promoted shared copy from `feature: library` into `common` (R3 promotion path), per the
  owner's decision that these are app-wide, not library-private: the selection trio and count
  (`common_selection_select_all`, `common_selection_invert`, `common_selection_deselect_all`,
  `common_selection_count` — replacing `library_menu_select_all`, `library_menu_invert`,
  `library_menu_deselect_all`, the `library_selection_count` plural), the two slot actions
  (`common_action_import`, `common_action_export` — replacing `library_cd_import`,
  `library_cd_export`) and the drawer copy (`common_drawer_empty`, `common_drawer_open`,
  `common_drawer_close` — replacing `library_drawer_empty`, `library_cd_open_drawer`,
  `library_cd_close_drawer`). The old keys are removed; no code references them.
- 2026-09-10 — AC6 removed: the requirement that every `contentDescription` resolve through a key
  ending in `_cd` was never an owner decision and is withdrawn. Content descriptions still must come
  from resources (R6); the `_cd` suffix remains an available naming suffix in R4, nothing more. AC
  numbering keeps the gap so history stays greppable.
