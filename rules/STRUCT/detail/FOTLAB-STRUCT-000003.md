# Naming — Avoid Product-Specific Tokens in Code Identifiers

- ID: FOTLAB-STRUCT-000003
- Status: Draft
- Priority: P1
- Created: 2026-09-08
- Owner: —
- Related: `FOTLAB-STRUCT-000001` (single-module source layout; file and package naming), `FOTLAB-STRUCT-000002` (source hygiene — no build artifacts, no circular dependencies)

## Background & Goal

The codebase carried the project/brand name "FotLab" in several code identifiers and file names
(`FotLabApplication`, `FotLabApp`, `FotLabBottomBar`). Embedding the product or brand name in code is a
liability: it couples identifiers to branding, forces broad renames on a rebrand, and adds noise. It
also let two non-compliant names (`FotLabApp`, `FotLabBottomBar`) exist as **unused duplicates** of
`MainWindowFrame` / `MainNavigationBar` — the same redundancy class as the `FotLabNavHost` /
`RootNavHost` pair removed earlier.

Goal: one naming rule — code identifiers and file names must not carry product/brand-specific tokens —
plus the companion rule that two components must not implement the same role under different names.

## Requirement

### R1 — No product/brand token in identifiers or file names

- Class, object, function, type and `.kt` file names must not contain the app/brand name or any other
  product-specific token. Use **role-based, generic names**.
- Established names (do not introduce a parallel `FotLab*` name): the `Application` subclass is
  `MainApplication`; the single activity is `MainActivity`; the shell frame is `MainWindowFrame`; the
  persistent bottom bar is `MainNavigationBar`.
- **Exempt** (these are not code structure, so the brand may appear):
  - the package namespace (`io.github.fotlab.fotlab`), which is the application identity tied to
    `applicationId` and set once;
  - user-facing resources and branding (`string/app_name` = "FotLab", `style/Theme.FotLab`), which are
    product presentation, not code structure.

### R2 — No duplicate component under a different name

- Do not keep two components that implement the same role under different names (two root nav hosts, two
  shell composables, two bottom bars). Keep the compliant one; remove the rest. This is the companion to
  `FOTLAB-STRUCT-000002`'s hygiene rules.

### R3 — New components follow established names

- A new top-level shell component uses the role-based name from R1; it never introduces a `FotLab*` (or
  other brand-prefixed) identifier.

## Constraints

- C1 — No class/function/type name or `.kt` file name embeds the product/brand name.
- C2 — No two components implement the same shell role under different names.
- C3 — The package namespace and user-facing resource names are exempt from C1.

## Acceptance Criteria

- AC1 — No `*.kt` file name or top-level declaration name contains the product/brand token ("FotLab").
- AC2 — The `Application` subclass is `MainApplication` and is the only one; `AndroidManifest.xml`
  `android:name` matches it.
- AC3 — For each shell role (application entry, activity, root nav host, shell frame, bottom bar) exactly
  one component exists.

## Impacted Modules

- Root package (`MainApplication`), `ui/` (shell composables), `AndroidManifest.xml`, and any new shell
  component.

## Open Questions

- Q1 — Should the package namespace also drop the `fotlab` segment (e.g. a neutral domain), so the brand
  never appears even in the package? **TBD** — high-churn, changes `applicationId` and every import.

## Change History

- 2026-09-08 — Initial draft. Forbids product/brand tokens in code identifiers and file names (use
  role-based names), exempting the package namespace and user-facing resources; forbids duplicate
  components under different names. Established in code by renaming `FotLabApplication` →
  `MainApplication` and removing the redundant `FotLabApp` / `FotLabBottomBar` (unused duplicates of
  `MainWindowFrame` / `MainNavigationBar`).
