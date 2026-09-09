# REVIEW — Architecture Review Issues

> **Redirect**: this file is the entry point only. The master item table lives in [`rules/REVIEW/index.md`](REVIEW/index.md); full analyses live in [`rules/REVIEW/detail/`](rules/REVIEW/detail/).

## Directory Structure

```
rules/
├── REVIEW.md                        ← Layer 1 (this file)
│   Redirect + write spec + encoding/category tables + general rules
│
├── REVIEW/
│   └── index.md                     ← Layer 2
│       Master item table with ID links; no stats, no changelog
│
└── REVIEW/detail/
    └── ACTION-XXXXXX-NNNNNN.md      ← Layer 3
        Full analysis per item + human-readable Change History
```

## Review Principles

1. **One item, one file** — a detail file covers exactly one review issue, audit finding, or architecture decision.
2. **IDs are permanent** — never reuse, never renumber, never delete. Abandoned items stay in place with `Status: Deprecated`.
3. **Index stays thin** — `rules/REVIEW/index.md` holds the item table only. No statistics, no changelog; git already tracks history.
4. **Records decisions/observations, not discussions** — debate happens in issues and PRs; the detail file records the outcome and its constraints.
5. **Upstream is out of scope** — `external/` modules (dnglab, exiftool) are treated as fixed constraints. Record how to work with them, never specify changes to their source.
6. **Version numbers are not here** — versioning follows [`rules/VERSION.md`](rules/VERSION.md). A review item never carries a version number; use status and change history instead.

## Agent Usage Guide

**Read when**

- An architecture concern, audit finding, or review issue arises — check `rules/REVIEW/index.md` first for an existing item before proposing anything new.
- Before recording a review observation or architecture finding.

**Write when**

- The user explicitly asks to record a review issue, audit, or architecture finding.
- Do **not** create items proactively, and do **not** create placeholder entries.

**Write procedure**

1. Pick the category code from the table below.
2. Take the next sequence number **for that category** from `rules/REVIEW/index.md` — 6 digits, zero-padded, counting independently inside the category.
3. Create `REVIEW/detail/ACTION-{CATEGORY}-{NNNNNN}.md` from the template below.
4. Append exactly one row to the table in `rules/REVIEW/index.md`, linking the new detail file.
5. Never modify existing rows except the `Status` and `Title` fields.

## Detail File Template

```markdown
# {Title}

- ID: ACTION-PREPIN-000001
- Status: Observation
- Priority: P3
- Created: YYYY-MM-DD
- Owner: —
- Related: —

## Background & Goal

## Finding

## Impact / Conflict

## Recommendation

## Change History
```

## Encoding Rules

ID format: `XXXXXX-XXXXXX-NNNNNN` — fixed length, 18 characters excluding hyphens, grep-friendly.

| Segment | Value | Rule |
| --- | --- | --- |
| 1 | `XXXXXX` | Area code, always `ACTION` for review items |
| 2 | `XXXXXX` | Category code, exactly 6 characters (see table below) |
| 3 | `NNNNNN` | 6-digit zero-padded sequence, **counting independently inside each category** — the first item of a new category starts at `000001` regardless of other categories |

Example: `ACTION-PREPIN-000001` — the first `PREPIN` (preflight / toolchain-cache) review item.

The detail file name **must** equal its ID plus `.md`.

## Category Quick Reference

| Code | Category | Scope |
| --- | --- | --- |
| `PREPIN` | Preflight / Toolchain Cache | Runner toolchain bootstrap and caching of SDK, NDK, Gradle, Rust NDK, python-for-android, and other build tools |

## Status & Priority

- **Status**: `Observation` (initial finding) → `Review` → `Approved` → `Implemented`; terminal alternatives: `Rejected`, `Deprecated`
- **Priority**: `P0` (blocker) · `P1` (high) · `P2` (normal) · `P3` (nice to have)

## General Rules

- Write detail files in English to stay consistent with `rules/VERSION.md`; the UI-facing copy inside a requirement may be quoted in any language.
- Every detail file **must** end with a `Change History` section whose entries are directly human-readable — no bare commit hashes.
- Findings must be verifiable: observable behaviour, measurable threshold, or explicit exclusion.
- If a finding conflicts with an existing rule, link both IDs in `Related` instead of silently overriding.
- Changing a review item means appending to its `Change History`, not rewriting earlier entries.
