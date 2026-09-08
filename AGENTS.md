# Agent Rules

ALL project rules for AI agents reside in the **[rules/](rules/)** directory.

## Entry Points

| Entry | Purpose |
|-------|---------|
| [rules/VERSION.md](rules/VERSION.md) | Version management — bumping, tagging, release notes (`VERSION_NAME` / `VERSION_CODE`) |
| [rules/ACTION.md](rules/ACTION.md) | Build & CI/CD behavior — no local toolchain; cloud CI only |
| [rules/REVIEW.md](rules/REVIEW.md) | Architecture review issues — discovery, tracking, resolution |
| [rules/DESIGN.md](rules/DESIGN.md) | Product requirement documents — feature specs & constraints |
| [rules/STRUCT.md](rules/STRUCT.md) | Project structure rules — source layout, packages, where each screen lives |
| [rules/SKILLS.md](rules/SKILLS.md) |  |

## Top-Level Config Files

| File | Purpose |
|------|---------|
| [VERSION_NAME](VERSION_NAME) | Semantic version (`YYYY.MM.DD.HH.mm` format); drives CI release naming |
| [VERSION_CODE](VERSION_CODE) | Incrementing build number; drives Android versionCode |

## Rules

- **Read**: Before any task, scan `rules/` for applicable rules.
- **Write**: When asked to persist a rule, create/update the appropriate file under `rules/`.
- **Paths**: Every path written inside a rule document — links and inline code alike — starts at the **repository root**. Write `docs/architecture.md` or `rules/VERSION.md`, never `../docs/...`. This keeps a reference valid no matter how deep the file sits.

This file itself contains no rules — it is only a redirect to `rules/`.


## Design Hierarchy

The `rules/` directory uses a three-layer structure for rule documentation:

```
rules/
├── {ENTRY}.md             ← Layer 1: Entry file (REVIEW.md, DESIGN.md, ACTION.md, STRUCT.md)
│   Redirect + write spec + quick reference tables + encoding rules
│
├── {Folder}/
│   └── index.md           ← Layer 2: Master index (REVIEW/index.md, PrdReqDocs/index.md)
│       Complete item table with ID links; no stats, no changelog (git tracks)
│
└── {Folder}/detail/
    └── XXXX-XXXX-NNNN.md  ← Layer 3: Detail files (one per item)
        Full analysis/spec + must include a human-readable changelog section
```

### Layer 1 — Entry File (`REVIEW.md`, `DESIGN.md`, `ACTION.md`)

- `REVIEW.md` / `DESIGN.md`: Lightweight redirect to the index folder; contains directory structure diagram, design principles, agent usage guide, write specifications, encoding rules, category quick-reference table, and general rules. Does NOT contain the master index of items.

### Layer 2 — Index (`index.md`)

- Master item table with ID, metadata, and links to detail files
- Does NOT maintain statistics/counts
- Does NOT maintain changelogs (git manages version history)
- Only the item table is maintained here

### Layer 3 — Detail Files (`detail/XXXXXX-XXXXXX-NNNNNN.md`)

- One file per encoded item
- Must include a Change History section at the end — each change must be directly human-readable
- Encoded using `XXXXXX-XXXXXX-NNNNNN` 18-character fixed-length IDs for grep-ability

