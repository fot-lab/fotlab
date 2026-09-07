# DESIGN — Product Requirement Documents

> **Redirect**: this file is the entry point only. The master item table lives in [`rules/DESIGN/index.md`](DESIGN/index.md); full specifications live in [`rules/DESIGN/detail/`](rules/DESIGN/detail/).

## Directory Structure

```
rules/
├── DESIGN.md                        ← Layer 1 (this file)
│   Redirect + write spec + encoding/category tables + general rules
│
├── DESIGN/
│   └── index.md                     ← Layer 2
│       Master item table with ID links; no stats, no changelog
│
└── DESIGN/detail/
    └── FOTLAB-XXXXXX-NNNNNN.md      ← Layer 3
        Full spec per item + human-readable Change History
```

## Design Principles

1. **One item, one file** — a detail file covers exactly one requirement or design decision.
2. **IDs are permanent** — never reuse, never renumber, never delete. Abandoned items stay in place with `Status: Deprecated`.
3. **Index stays thin** — `rules/DESIGN/index.md` holds the item table only. No statistics, no changelog; git already tracks history.
4. **Specs record decisions, not discussions** — debate happens in issues and PRs; the detail file records the outcome and its constraints.
5. **Upstream is out of scope** — `external/` modules (dnglab, exiftool) are treated as fixed constraints. Record how to work with them, never specify changes to their source.
6. **Version numbers are not here** — versioning follows [`rules/VERSION.md`](rules/VERSION.md). A requirement never carries a version number; use status and change history instead.

## Agent Usage Guide

**Read when**

- A feature or requirement task arrives — check `rules/DESIGN/index.md` first for an existing item before proposing anything new.
- Before writing a PRD — read this file for encoding, category and template rules.

**Write when**

- The user explicitly asks to record a requirement, write a PRD, or archive a design decision.
- Do **not** create items proactively, and do **not** create placeholder entries.

**Write procedure**

1. Pick the category code from the table below.
2. Take the next sequence number **for that category** from `rules/DESIGN/index.md` — 6 digits, zero-padded, counting independently inside the category.
3. Create `DESIGN/detail/FOTLAB-{CATEGORY}-{NNNNNN}.md` from the template below.
4. Append exactly one row to the table in `rules/DESIGN/index.md`, linking the new detail file.
5. Never modify existing rows except the `Status` and `Title` fields.

## Detail File Template

```markdown
# {Title}

- ID: FOTLAB-FEATUR-000001
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
| 3 | `NNNNNN` | 6-digit zero-padded sequence, **counting independently inside each category** — the first item of a new category starts at `000001` regardless of other categories |

Example: `FOTLAB-RENDER-000003` — the third `RENDER` item, whatever the `UIXDES` counter is at

The detail file name **must** equal its ID plus `.md`.

## Category Quick Reference

| Code | Category | Scope |
| --- | --- | --- |
| `FEATUR` | Feature | User-visible capabilities and end-to-end flows |
| `RENDER` | Rendering & Style | Colour pipeline, style/LUT application, preview and export |
| `IMGMGR` | Image & File | Import, browse, storage, album, file lifecycle |
| `DATABS` | Data & Persistence | Structured local storage: Room entities, DAOs, migrations, repositories |
| `METADA` | Metadata | EXIF/XMP read & write, exiftool-backed behaviour |
| `NATIVE` | Native Integration | JNI/FFI native integration, dnglab integration |
| `UIXDES` | UI & UX | Layout, navigation, accessibility, copy |
| `PERFOR` | Performance | Memory, latency, large-image and batch processing |
| `COMPAT` | Compatibility | Android versions, vendor devices, ABI coverage |
| `PRIVCY` | Privacy & Security | Permissions, data retention, third-party dependencies |
| `RELEAS` | Release & Build | Signing, packaging, CI behaviour, distribution |

## Status & Priority

- **Status**: `Draft` → `Review` → `Approved` → `Implemented`; terminal alternatives: `Rejected`, `Deprecated`
- **Priority**: `P0` (blocker) · `P1` (high) · `P2` (normal) · `P3` (nice to have)

## General Rules

- Write detail files in English to stay consistent with `rules/VERSION.md`; the UI-facing copy inside a requirement may be quoted in any language.
- Every detail file **must** end with a `Change History` section whose entries are directly human-readable — no bare commit hashes.
- Acceptance criteria must be verifiable: observable behaviour, measurable threshold, or explicit exclusion.
- If a requirement conflicts with an existing one, link both IDs in `Related` instead of silently overriding.
- Changing a requirement means appending to its `Change History`, not rewriting earlier entries.
