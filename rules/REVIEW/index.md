# REVIEW Index

Master index of architecture review issues.

- Entry point and write rules: [`rules/REVIEW.md`](rules/REVIEW.md)
- Detail files: [`rules/REVIEW/detail/`](rules/REVIEW/detail/)

This file contains **only** the item table. No statistics, no changelog — git tracks history.

| ID | Title | Category | Status | Priority | Detail |
| --- | --- | --- | --- | --- | --- |
| `ACTION-PREPIN-000001` | Preflight toolchain caching audit — SDK/NDK/Gradle cached; Rust NDK & python-for-android pending | `PREPIN` | Observation | P3 | [detail](rules/REVIEW/detail/ACTION-PREPIN-000001.md) |
| `ACTION-LIBRND-000001` | Library one-level rendering audit — current view renders only direct children; Recycle must mirror one-level queries and reset navigation on switch so the views never mix | `LIBRND` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-LIBRND-000001.md) |
| `DNGLAB-RAWLER-000001` | rawler_fotlab PNG preview is an unprocessed full-sensor dump — no black level, white balance, demosaic, colour mapping or gamma | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/DNGLAB-RAWLER-000001.md) |
| `DNGLAB-RAWLER-000002` | Rewriting external/dnglab (rawler) in Kotlin — cost / benefit assessment, triggered by a Rust-library-invocation crash | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/DNGLAB-RAWLER-000002.md) |
| `ACTION-KOTLIN-000001` | First-party Kotlin / Compose code audit — scope, method and the complete finding index (30 findings, 6 nesting sites, 14 duplication groups) | `KOTLIN` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000001.md) |
| `ACTION-KOTLIN-000002` | Library refresh is a no-op — `uriExists` tests `count >= 0`, which is always true | `KOTLIN` | Observation | P0 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000002.md) |
| `ACTION-KOTLIN-000003` | Compose state and lifecycle deviations — navigation state not observable, startup blocking, no state holder | `KOTLIN` | Observation | P1 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000003.md) |
| `ACTION-KOTLIN-000004` | Duplicated Compose and data blocks — ten extraction groups across the two library screens and the data layer | `KOTLIN` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000004.md) |
| `ACTION-KOTLIN-000005` | Control flow — six nesting sites, three non-exhaustive `when`, and one concept modelled three ways | `KOTLIN` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000005.md) |
| `ACTION-KOTLIN-000006` | Localization and formatting — English hard-coded in the viewer detail panel, unsafe and locale-implicit date formatting | `KOTLIN` | Observation | P1 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000006.md) |
| `ACTION-KOTLIN-000007` | Platform API and data layer — Media3, N+1 queries, nullable primary key, duplicated plumbing | `KOTLIN` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000007.md) |
| `FOTLAB-RAWLER-000001` | External C/Rust library internal-call detail differences — lesson from the rawler_fotlab binding | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000001.md) |
| `FOTLAB-RAWLER-000002` | RawImage already carries resolved calibration (color_matrix/cfa/wb); data↔camera match done inside rawler for all formats incl. CR2 via camera-DB lookup | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000002.md) |
| `FOTLAB-RAWLER-000003` | Extending rawler_fotlab with selectable demosaic algorithm, optional superpixel 1/4, and external color matrix — design | `RAWLER` | Proposal | P2 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000003.md) |
| `FOTLAB-RAWLER-000004` | Decode-once / develop-reuse across the Kotlin↔Rust FFI — hold the decoded RAW as a UniFFI auto-handle (`RawlerImageLoaded`), reusing it for preview + repeated develop without re-decode or re-crossing the pixel buffer | `RAWLER` | Approved | P1 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000004.md) |
| `DNGLAB-RAWLER-000005` | RAW decode cost is set by container and encoding, not by vendor — CR3 is parallel, CR2 and lossless NEF are not | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/DNGLAB-RAWLER-000005.md) |
| `FOTLAB-RAWLER-000005` | Working space is locked to sRGB and irreversibly gamut-clipped in `calibrate` — switch to a wide-gamut (ProPhoto D50) hub | `RAWLER` | Proposal | P1 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000005.md) |
| `FOTLAB-RAWLER-000006` | Handoff rawler ProPhoto-D50 linear → RawAlchemyCpp: add `rawalchemy_fotlab` cxx bridge (no submodule patch); Kotlin is the hub, consumes graded output as-is | `RAWLER` | Approved | P1 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000006.md) |
<!-- Next sequence per category: PREPIN 000002, LIBRND 000002, RAWLER 000007, KOTLIN 000008, FOTLAB-RAWLER 000007. Append one row per new item; never reuse or renumber IDs. -->
