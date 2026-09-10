# REVIEW Index

Master index of architecture review issues.

- Entry point and write rules: [`rules/REVIEW.md`](rules/REVIEW.md)
- Detail files: [`rules/REVIEW/detail/`](rules/REVIEW/detail/)

This file contains **only** the item table. No statistics, no changelog — git tracks history.

| ID | Title | Category | Status | Priority | Detail |
| --- | --- | --- | --- | --- | --- |
| `ACTION-PREPIN-000001` | Preflight toolchain caching audit — SDK/NDK/Gradle cached; Rust NDK & python-for-android pending | `PREPIN` | Observation | P3 | [detail](rules/REVIEW/detail/ACTION-PREPIN-000001.md) |
| `ACTION-LIBRND-000001` | Library one-level rendering audit — current view renders only direct children; Recycle must mirror one-level queries and reset navigation on switch so the views never mix | `LIBRND` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-LIBRND-000001.md) |
<!-- Next sequence per category: PREPIN 000002, LIBRND 000002. Append one row per new item; never reuse or renumber IDs. -->
