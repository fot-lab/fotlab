# DESIGN Index

Master index of product requirement documents.

- Entry point and write rules: [`rules/DESIGN.md`](rules/DESIGN.md)
- Detail files: [`rules/DESIGN/detail/`](rules/DESIGN/detail/)

This file contains **only** the item table. No statistics, no changelog — git tracks history.

| ID | Title | Category | Status | Priority | Detail |
| --- | --- | --- | --- | --- | --- |
| `FOTLAB-UIXDES-000001` | Overall UI shell architecture | `UIXDES` | Draft | P1 | [detail](rules/DESIGN/detail/FOTLAB-UIXDES-000001.md) |
| `FOTLAB-UIXDES-000002` | Per-module top app bar and drawer behaviour | `UIXDES` | Draft | P1 | [detail](rules/DESIGN/detail/FOTLAB-UIXDES-000002.md) |
| `FOTLAB-UIXDES-000003` | String resources — multi-language (i18n) structure and naming rules | `UIXDES` | Draft | P1 | [detail](rules/DESIGN/detail/FOTLAB-UIXDES-000003.md) |
| `FOTLAB-DATABS-000001` | Local structured persistence — Room DAO rules | `DATABS` | Draft | P1 | [detail](rules/DESIGN/detail/FOTLAB-DATABS-000001.md) |
| `FOTLAB-DATABS-000002` | Image Library — fs_node schema: node object and node relation tables | `DATABS` | Draft | P1 | [detail](rules/DESIGN/detail/FOTLAB-DATABS-000002.md) |
| `FOTLAB-NATIVE-000001` | Third-party modules — single location under `external/` | `NATIVE` | Draft | P1 | [detail](rules/DESIGN/detail/FOTLAB-NATIVE-000001.md) |
| `FOTLAB-NATIVE-000002` | Running Python on Android — open-source, royalty-free | `NATIVE` | Draft | P2 | [detail](rules/DESIGN/detail/FOTLAB-NATIVE-000002.md) |
| `FOTLAB-NATIVE-000003` | Embedding Python in the host app via p4a-built CPython & packages | `NATIVE` | Draft | P2 | [detail](rules/DESIGN/detail/FOTLAB-NATIVE-000003.md) |
| `FOTLAB-IMGMGR-000001` | Image Library — two-table virtual tree for in-place file management | `IMGMGR` | Draft | P1 | [detail](rules/DESIGN/detail/FOTLAB-IMGMGR-000001.md) |
<!-- Next sequence per category: UIXDES 000004, DATABS 000003, NATIVE 000004, IMGMGR 000002. Append one row per new item; never reuse or renumber IDs. -->
