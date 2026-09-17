# STRUCT Index

Master index of project-structure documents.

- Entry point and write rules: [`rules/STRUCT.md`](rules/STRUCT.md)
- Detail files: [`rules/STRUCT/detail/`](rules/STRUCT/detail/)

This file contains **only** the item table. No statistics, no changelog — git tracks history.

| ID | Title | Category | Status | Priority | Detail |
| --- | --- | --- | --- | --- | --- |
| `FOTLAB-STRUCT-000001` | Single-module source layout with a feature package | `STRUCT` | Draft | P0 | [detail](rules/STRUCT/detail/FOTLAB-STRUCT-000001.md) |
| `FOTLAB-STRUCT-000002` | Source hygiene — no build artifacts in source, no circular dependencies | `STRUCT` | Draft | P1 | [detail](rules/STRUCT/detail/FOTLAB-STRUCT-000002.md) |
| `FOTLAB-STRUCT-000003` | Naming — avoid product-specific tokens in code identifiers; no duplicate components | `STRUCT` | Draft | P1 | [detail](rules/STRUCT/detail/FOTLAB-STRUCT-000003.md) |
| `DNGLAB-SURVEY-000001` | External module study — dnglab (RAW→DNG converter) structure & capabilities | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/DNGLAB-SURVEY-000001.md) |
| `DNGLAB-SURVEY-000002` | External module study — dnglab rawler: RAW decode pipeline & unified intermediate data model | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/DNGLAB-SURVEY-000002.md) |
| `DNGLAB-SURVEY-000003` | External module study — dnglab rawler: camera metadata (`data/cameras`) propagation through decode → encode | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/DNGLAB-SURVEY-000003.md) |
| `DNGLAB-SURVEY-000004` | External module study — dnglab main body vs rawler: division of labor (dnglab = orchestration shell, rawler = capability layer) | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/DNGLAB-SURVEY-000004.md) |
| `DNGLAB-RAWDEV-000001` | External module study — how dnglab's develop pipeline cooperates with rawler (`rawler::imgop::develop`) | `RAWDEV` | Draft | P2 | [detail](rules/STRUCT/detail/DNGLAB-RAWDEV-000001.md) |
| `EXIFTL-SURVEY-000001` | Research — external/exiftool module: structure, architecture and capabilities | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/EXIFTL-SURVEY-000001.md) |
| `FOTLAB-STUDIO-000001` | Studio frontend rendering — Coil-only raster decode; RAW converted upstream | `STUDIO` | Draft | P0 | [detail](rules/STRUCT/detail/FOTLAB-STUDIO-000001.md) |
| `RAWTRP-PIPELN-000001` | External module study — RawTherapee develop (processing) pipeline: ordered stages, colour management, data model | `PIPELN` | Draft | P2 | [detail](rules/STRUCT/detail/RAWTRP-PIPELN-000001.md) |
| `DNGLAB-PIPELN-000001` | External module study — dnglab develop pipeline for JPEG output: execution order, white balance, demosaic, colour mapping | `PIPELN` | Draft | P2 | [detail](rules/STRUCT/detail/DNGLAB-PIPELN-000001.md) |
| `DNGLAB-PIPELN-000002` | External module study — demosaic algorithms: dnglab vs RawTherapee (implemented vs wired, selectable count, quality tier) | `PIPELN` | Draft | P2 | [detail](rules/STRUCT/detail/DNGLAB-PIPELN-000002.md) |
| `FOTLAB-IPIXEL-000001` | Canonical RAW intermediate representation — `RawPixel` (`RawPixelData` + `RawPixelMeta`: `TagsIsoDng` / `TagsDngLab` / `TagsFotLab`) | `IPIXEL` | Draft | P1 | [detail](rules/STRUCT/detail/FOTLAB-IPIXEL-000001.md) |
<!-- Next sequence per category: STRUCT 000004, RAWDEV 000002, PIPELN 000003, DNGLAB-PIPELN 000003, IPIXEL 000002. Append one row per new item; never reuse or renumber IDs. -->
