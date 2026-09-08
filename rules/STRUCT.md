# STRUCT — Project Structure Rules

> **Redirect**: this file is the entry point only. The master item table lives in [`rules/STRUCT/index.md`](STRUCT/index.md); full specifications live in [`rules/STRUCT/detail/`](rules/STRUCT/detail/).

## Directory Structure

```
rules/
├── STRUCT.md                        ← Layer 1 (this file)
│   Redirect + write spec + encoding/category tables + general rules
│
├── STRUCT/
│   └── index.md                     ← Layer 2
│       Master item table with ID links; no stats, no changelog
│
└── STRUCT/detail/
    └── FOTLAB-STRUCT-NNNNNN.md      ← Layer 3
        Full spec per item + human-readable Change History
```

## Design Principles

1. **One item, one file** — a detail file covers exactly one structure or layout decision.
2. **IDs are permanent** — never reuse, never renumber, never delete. Abandoned items stay in place with `Status: Deprecated`.
3. **Index stays thin** — `rules/STRUCT/index.md` holds the item table only. No statistics, no changelog; git already tracks history.
4. **Specs record decisions, not discussions** — debate happens in issues and PRs; the detail file records the outcome and its constraints.
5. **Upstream is out of scope** — `external/` modules (dnglab, exiftool) are treated as fixed constraints. Record how to work with them, never specify changes to their source.
6. **Version numbers are not here** — versioning follows [`rules/VERSION.md`](rules/VERSION.md). A requirement never carries a version number; use status and change history instead.

## Agent Usage Guide

**Read when**

- A task touches source layout: packages, Gradle modules, layer and package naming, or where a new screen/feature lives.
- Before adding a destination or a new first-party package — check `rules/STRUCT/index.md` first for an existing item.

**Write when**

- The user explicitly asks to record a structure or layout rule, or to move code between packages/modules.
- Do **not** create items proactively, and do **not** create placeholder entries.

**Write procedure**

1. Take the next sequence number from `rules/STRUCT/index.md` — 6 digits, zero-padded.
2. Create `STRUCT/detail/FOTLAB-STRUCT-{NNNNNN}.md` from the template below.
3. Append exactly one row to the table in `rules/STRUCT/index.md`, linking the new detail file.
4. Never modify existing rows except the `Status` and `Title` fields.

## Detail File Template

```markdown
# {Title}

- ID: FOTLAB-STRUCT-000001
- Status: Draft
- Priority: P2
- Created: YYYY-MM-DD
- Owner: —
- Related: —

## Background & Goal

## Requirement

## Constraints

## Acceptance Criteria

## Impacted Modules

## Open Questions

## Change History
```

## Encoding Rules

ID format: `XXXXXX-XXXXXX-NNNNNN` — fixed length, 18 characters excluding hyphens, grep-friendly.

| Segment | Value | Rule |
| --- | --- | --- |
| 1 | `XXXXXX` | Project code, always `FOTLAB` |
| 2 | `XXXXXX` | Category code, exactly 6 characters (see table below) |
| 3 | `NNNNNN` | 6-digit zero-padded sequence, counting independently inside the category |

Example: `FOTLAB-STRUCT-000002` — the second `STRUCT` item.

The detail file name **must** equal its ID plus `.md`.

## Category Quick Reference

| Code | Category | Scope |
| --- | --- | --- |
| `STRUCT` | Project Structure | Source layout, Gradle modules vs. packages, layer and package naming, where each screen/feature lives |

## Status & Priority

- **Status**: `Draft` → `Review` → `Approved` → `Implemented`; terminal alternatives: `Rejected`, `Deprecated`
- **Priority**: `P0` (blocker) · `P1` (high) · `P2` (normal) · `P3` (nice to have)

## General Rules

- Write detail files in English to stay consistent with `rules/VERSION.md`; the UI-facing copy inside a requirement may be quoted in any language.
- Every detail file **must** end with a `Change History` section whose entries are directly human-readable — no bare commit hashes.
- Acceptance criteria must be verifiable: observable behaviour, measurable threshold, or explicit exclusion.
- If a requirement conflicts with an existing one, link both IDs in `Related` instead of silently overriding.
- Changing a requirement means appending to its `Change History`, not rewriting earlier entries.
