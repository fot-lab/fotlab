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
| `RAPIDR-SURVEY-000001` | External module study — RapidRAW (Tauri+Rust RAW editor): Android build & image render pipeline | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/RAPIDR-SURVEY-000001.md) |
| `RAPIDR-SURVEY-000002` | External module study — RapidRAW on Android: library import (SAF/content:// + copy-into-`.library`), storage model, non-destructive develop metadata | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/RAPIDR-SURVEY-000002.md) |
| `RAPIDR-SURVEY-000003` | External module study — RapidRAW Android: minimal Kotlin (WebView shell), why interaction is poor, optimization tiers & Kotlin-rewrite feasibility | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/RAPIDR-SURVEY-000003.md) |
| `RAPIDR-SURVEY-000004` | External module study — RapidRAW processing pipeline: RAW decode (rawler/imgop), two-stage CPU-geometry + GPU-color flow, fs_main stage order, preview vs export, non-destructive caching | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/RAPIDR-SURVEY-000004.md) |
| `RAPIDR-SURVEY-000005` | External module study — RapidRAW LUT subsystem: WebView UI entry (LUTControl/Effects), edit-JSON fields, parse (cube/3dl/hald), f16 3D-texture upload, tetrahedral sampling, scene- vs display-referred application | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/RAPIDR-SURVEY-000005.md) |
| `RAPIDR-SURVEY-000006` | External module study — RapidRAW in-memory representation after decode: rawler `Intermediate` (Img<f32>, linear scene-referred) → `DynamicImage::ImageRgba32F` → `Arc<DynamicImage>` caches → `Rgba16Float` GPU texture | `STRUCT` | Draft | P2 | [detail](rules/STRUCT/detail/RAPIDR-SURVEY-000006.md) |
| `FOTLAB-STUDIO-000001` | Studio frontend rendering — Coil-only raster decode; RAW converted upstream | `STUDIO` | Draft | P0 | [detail](rules/STRUCT/detail/FOTLAB-STUDIO-000001.md) |
| `RAWTRP-PIPELN-000001` | External module study — RawTherapee develop (processing) pipeline: ordered stages, colour management, data model | `PIPELN` | Draft | P2 | [detail](rules/STRUCT/detail/RAWTRP-PIPELN-000001.md) |
| `RAWTRP-SURVEY-000001` | External module study — RawTherapee working colour space: 12 built-in profiles, custom `workingspaces.json`, TRC, illuminant & primaries | `SURVEY` | Draft | P2 | [detail](rules/STRUCT/detail/RAWTRP-SURVEY-000001.md) |
| `RAWTRP-DECODE-000001` | External module study — RawTherapee demosaicing algorithms: full inventory (Bayer + X-Trans enumerators, dispatch, kernels, maintenance) | `DECODE` | Draft | P2 | [detail](rules/STRUCT/detail/RAWTRP-DECODE-000001.md) |
| `RAWTRP-DECODE-000002` | External module study — RawTherapee format sniffing (ext-based), standard-image jpg/png input (StdImageSource+ImageIO), and CR2/LJPEG decode parallelism (2-section pipeline) | `DECODE` | Draft | P2 | [detail](rules/STRUCT/detail/RAWTRP-DECODE-000002.md) |
| `RAWTRP-DECODE-000003` | External module study — RawTherapee demosaic kernel I/O contract (array+CFA) and the rawler→RT data-structure bridge | `DECODE` | Draft | P2 | [detail](rules/STRUCT/detail/RAWTRP-DECODE-000003.md) |
| `RAWTRP-DECODE-000004` | External module study — develop-pipeline integer→f32 conversion boundary (rawler `apply_scaling`, lossless cast + f32 black-subtract) and why minus-EV keeps shadow detail | `DECODE` | Draft | P2 | [detail](rules/STRUCT/detail/RAWTRP-DECODE-000004.md) |
| `DNGLAB-PIPELN-000001` | External module study — dnglab develop pipeline for JPEG output: execution order, white balance, demosaic, colour mapping | `PIPELN` | Draft | P2 | [detail](rules/STRUCT/detail/DNGLAB-PIPELN-000001.md) |
| `DNGLAB-PIPELN-000002` | External module study — demosaic algorithms: dnglab vs RawTherapee (implemented vs wired, selectable count, quality tier) | `PIPELN` | Draft | P2 | [detail](rules/STRUCT/detail/DNGLAB-PIPELN-000002.md) |
| `FOTLAB-FOTRAW-000001` | Canonical RAW intermediate representation — `FotRaw` (`FotRawData` + `FotRawMeta`: `TagsIsoDng` / `TagsDngLab` / `TagsFotLab`) | `FOTRAW` | Draft | P1 | [detail](rules/STRUCT/detail/FOTLAB-FOTRAW-000001.md) |
| `FOTLAB-UNIFFI-000001` | UniFFI 0.28 Kotlin naming conventions — enum variants (SCREAMING_SNAKE_CASE) and record fields (camelCase) | `UNIFFI` | Approved | P1 | [detail](rules/STRUCT/detail/FOTLAB-UNIFFI-000001.md) |
| `RAWTRP-SURVEY-000002` | External module study — the Oklab family in our `external/` dependencies: RawTherapee's matrix-parameterised `rgb2oklab`/`oklab2rgb` (pass ProPhoto matrices for ProPhoto↔Oklab; Ottosson math behind a D50↔D65 Bradford sandwich; white-at-Y=1 contract; unbound); Oklch only in colour-science; no Okhsl/Okhsv anywhere (gamut-normalised, would need a target-gamut cusp solve) | `SURVEY` | Draft | P2 | [detail](rules/STRUCT/detail/RAWTRP-SURVEY-000002.md) |
<!-- Next sequence per category: STRUCT 000004, RAWDEV 000002, PIPELN 000003, DNGLAB-PIPELN 000003, FOTRAW 000002, UNIFFI 000002, DNGLAB-SURVEY 000005, RAPIDR-SURVEY 000012, EXIFTL-SURVEY 000002, RAWTRP-SURVEY 000003, RAWTRP-DECODE 000005. Append one row per new item; never reuse or renumber IDs. -->
