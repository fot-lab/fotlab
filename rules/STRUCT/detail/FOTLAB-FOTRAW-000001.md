# Canonical RAW intermediate representation — `FotRaw`

- ID: FOTLAB-FOTRAW-000001
- Status: Draft
- Priority: P1
- Created: 2026-09-17
- Owner: —
- Related: `DNGLAB-SURVEY-000002` (rawler `RawImage`/`RawImageData` contract), `DNGLAB-SURVEY-000003` (camera metadata propagation into encode), `DNGLAB-SURVEY-000004` (convert re-containerizes; LJPEG-92 is the serialization boundary, not the in-memory container), `DNGLAB-RAWDEV-000001` (develop pipeline), `RAWTRP-PIPELN-000001` (RawTherapee data model)

> **Terminology note**: this IR was referred to as `RawNegative` in the `DNGLAB-SURVEY` studies, then fixed as `RawPixel`, and has since been renamed to **`FotRaw`** — `RawPixel`, `RawNegative` and `RawFrame` are all deprecated for first-party use. The in-memory IR (`FotRaw`) and the on-disk DNG are the same object in two states (memory vs disk); the same `FotRaw`, in its serialized (wire) form, is what crosses the FFI (see R8). The earlier name `RawFrame` for this wire form is deprecated and unified into `FotRaw`.

## Background & Goal

We hold a decoded-but-undeveloped RAW model in Kotlin and hand it across the FFI to a native layer (rawler/dnglab), and onward to external engines (dnglab, RawTherapee, darktable). To do that without per-call negotiation, the intermediate representation must be **fixed once** as a project-structure contract: every source tree and the FFI boundary reference the same shape, the same field ownership, and the same nesting rules.

Goal:

- G1 — Define one canonical IR, `FotRaw`, with an unambiguous split between the pixel buffer and its metadata.
- G2 — Make `FotRaw` portable: it must survive crossing the FFI and being referenced from multiple first-party and external source trees without semantic drift.
- G3 — Separate the three metadata concerns cleanly: what is DNG-standard (portable), what tracks dnglab upstream (round-trips with rawler), and what is FotLab-private (our extension).

## Requirement

### R1 — `FotRaw` is the canonical IR

```
FotRaw
├── data : FotRawData
└── meta : FotRawMeta
    ├── isodng : TagsIsoDng     # DNG-ISO conformant, FLAT (no nested tree)
    ├── dnglab  : TagsDngLab     # rawler/dnglab RawImage non-data tags, nested allowed
    └── fotlab  : TagsFotLab     # FotLab-defined tags, nested allowed
```

- `FotRaw` is the in-memory, engine-neutral IR. It is **not** a file format and **not** a codec container.
- It is the serialized form's source: a DNG (via rawler `DngWriter`) and the `FotRaw` crossing the FFI in wire form are both projections of `FotRaw`.

### R2 — `FotRawData` is the pixel buffer only

- `FotRawData` is **nearly pure pixel data**: it carries only the uncompressed sample buffer. It holds **no shape, no format, and no semantic metadata of any kind** — not width/height, not components-per-pixel, not photometric interpretation, not element type.
- Fields:
  - `buffer` — one contiguous block of **uncompressed** samples, in row-major order, exactly as the LJPEG-92 source data looks before DNG compression (per `DNGLAB-SURVEY-000004` §5: keep uncompressed in memory and across the FFI; LJPEG-92 is applied only at the DNG serialization boundary).
- Every other property of the RAW — geometry (`ImageWidth`/`ImageLength`), component count (`SamplesPerPixel`), photometric interpretation, element byte width (`BitsPerSample`), crop/active area, orientation, CFA, black/white level, colour matrices, make/model, EXIF, and **all other DNG tags** — lives in `FotRawMeta`'s three namespaces, never in `FotRawData`. This is a hard separation: **`FotRawData` is meaningless without `FotRawMeta`.**
- Because `FotRawData` carries no shape, a reader MUST obtain geometry/format from the tags (see R2b); the buffer alone cannot be interpreted, and no FFI fast-path header carries shape either.

### R2b — Reading geometry/format: conservative tag fallback

- A reader MUST NOT assume shape is present in any single namespace, and MUST NOT default to a fixed shape. Geometry/format needed to interpret `FotRawData` is resolved from the three namespaces in this order:
  1. `isodng` — DNG-ISO shape tags preferred: `ImageWidth` (256), `ImageLength` (257), `SamplesPerPixel` (277), `PhotometricInterpretation` (262), `BitsPerSample` (258), plus DNG `PixelAspectRatio` (0xC617), `DefaultCropSize` (0xC61E), `ActiveArea` (0xC68D), `Orientation` (0x0112).
  2. `fotlab` — FotLab-defined shape/format hints (e.g. a shape the engine previously recorded).
  3. `dnglab` — rawler/dnglab upstream shape/format hints.
- The first namespace in this order that yields a complete, consistent geometry wins. If none does, the RAW cannot be developed and the reader MUST error rather than guess.
- `isodng` is first because it is the authoritative source when present (per R4 it is written whenever a `FotRaw` is emitted).

### R3 — `FotRawMeta` owns all interpretation metadata

- `FotRawMeta` is composed of exactly the three tag namespaces below. There is no other metadata location on `FotRaw`.
- Develop-critical fields that rawler attaches to `RawImage` (CFA pattern, `BlackLevel`, `WhiteLevel`, `ColorMatrix1/2/3` + illuminants, `AsShotNeutral`/`AsShotWhiteXY`, `ActiveArea`, `DefaultCropOrigin`/`DefaultCropSize`, `Orientation`, `Make`/`Model`/`UniqueCameraModel`, EXIF) are **not** on `FotRawData`; they are expressed as tags inside the namespaces of R4–R6. This holds for **every** DNG tag and every other property of the RAW, not only the develop-critical ones — nothing beyond the sample bytes belongs in `FotRawData`.

### R4 — `TagsIsoDng` follows the DNG-ISO standard, flat

- `TagsIsoDng` holds every tag that has a defined meaning in the **DNG-ISO specification** (the Adobe DNG 1.x / ISO 12234-2 "DNG" tag set): CFA, black/white level, colour matrices and calibration illuminants, white balance, crop/active area, orientation, make/model, the full EXIF block, etc.
- **Flat — no nested tree.** A `TagsIsoDng` entry is `tagId → value` where `tagId` is the numeric DNG tag identifier and `value` is typed per the DNG spec (SHORT/LONG/RATIONAL/ASCII/…, including lists/arrays of those types). Values may be lists, but a value is never a sub-tree.
- New entries must be valid DNG-ISO tags; if a needed field has no DNG tag, it goes to R5 or R6, not here.
- `TagsIsoDng` is the **portable subset**: it can be written 1:1 into a DNG IFD by rawler's `DngWriter` and is understood by every DNG consumer (Lightroom, darktable, RawTherapee).

### R5 — `TagsDngLab` tracks dnglab/rawler upstream, nested allowed

- `TagsDngLab` holds the non-pixel, non-DNG-ISO fields that rawler's `RawImage` / `RawMetadata` carries and that we want to preserve across the round-trip with dnglab: e.g. camera-DB hints (`camera.find_hint(...)`), decoder virtual tags (`WellKnownIFD::VirtualDngRawTags` / `VirtualDngRootTags`), Fuji-rotation markers, rawler-internal decode flags.
- **Follows dnglab upstream** as the source of truth: when rawler adds or renames such a field, `TagsDngLab` tracks it. These tags are dnglab-specific and are **not** guaranteed to survive into a DNG consumed by third parties.
- **Namespace is chosen by source of authority**: any tag whose origin and authority is the dnglab/rawler upstream belongs in `TagsDngLab`, even if a field with a similar name also exists in DNG-ISO. A field is placed in `TagsIsoDng` only when it is itself a DNG-ISO-standard tag (R4). The two namespaces are partitioned by *where the definition comes from*, not by superficial similarity.
- **Nested trees are allowed** (unlike R4), because upstream structures are occasionally hierarchical (e.g. virtual IFD subtrees).

### R6 — `TagsFotLab` is the FotLab-private namespace, nested allowed

- `TagsFotLab` holds project-defined extensions with no external-standard meaning: provenance (source file path/digest, producing decoder id, decode timestamp), pipeline/state markers, engine-routing hints (which engine should develop this), and any UI/asset metadata.
- **Nested trees are allowed**; FotLab owns the schema and may evolve it freely.
- `TagsFotLab` is never required to develop or to emit a standard DNG; it is carried opaquely across the FFI and persisted at FotLab's discretion (e.g. a private IFD or XMP).

### R7 — Nesting invariants

- `TagsIsoDng` MUST remain flat (R4). `TagsDngLab` and `TagsFotLab` MAY nest.
- An FFI reader that does not understand `TagsDngLab`/`TagsFotLab` MUST skip them without error; it MUST still be able to reconstruct a developable RAW from `FotRawData` + `TagsIsoDng` alone.

### R8 — FFI serialization of `FotRaw`

- The serialized (wire) form of `FotRaw` that crosses the FFI carries the fields below. No separate transport type name is used; `DNGLAB-SURVEY-000004` §5.3 described this same wire form as `RawFrame`, now deprecated.
- Encoding:
  - `FotRawData` → one contiguous block of uncompressed sample bytes (row-major). No shape/format header travels with the buffer; geometry and element type are recovered from the tag stream per R2b.
  - `FotRawMeta` → a tag stream: `TagsIsoDng` encoded as `(tagId:u16, type, value)` per DNG typing; `TagsDngLab`/`TagsFotLab` encoded as nested key→value (string path + typed value).
- The `FotRaw` on-wire (serialized) layout is **frozen by this document**; changing it is a breaking change requiring a new version recorded in Change History.

### R9 — Versioning and evolution

- `TagsIsoDng` evolves only by adopting DNG-ISO tags (R4).
- `TagsDngLab` evolves by tracking rawler/dnglab upstream (R5).
- `TagsFotLab` evolves at FotLab's discretion (R6).
- No version number is attached to a requirement (per STRUCT principle 6); evolution is tracked in Change History.

## Constraints

- C1 — `TagsIsoDng` content and typing MUST conform to the DNG-ISO specification; it is flat by definition.
- C2 — `TagsDngLab` MUST follow dnglab/rawler upstream as the source of truth for field names and semantics, and **every dnglab/rawler-upstream tag MUST be placed in `TagsDngLab`** (never in `TagsIsoDng` or `TagsFotLab`).
- C3 — `FotRawData` stays uncompressed in memory and across the FFI; any compression (LJPEG-92, LZ4, Zstd) is applied only at a serialization boundary that produces a DNG or an IPC payload, never inside `FotRaw`.
- C4 — `FotRawData` carries only the sample buffer; it holds no shape, no format, and no metadata of any kind — all of that is in `FotRawMeta` (R2/R3).
- C5 — The `FotRaw` serialized wire layout (R8) is stable; this document is its contract.

## Acceptance Criteria

- AC1 — A `FotRaw` is representable in every first-party source tree and across the FFI using exactly the shape of R1 (one `data`, one `meta` with `isodng`/`dnglab`/`fotlab`).
- AC2 — `FotRawData` contains only the sample buffer; grepping its definition shows no `width`/`height`/`cpp`/`photometric`/`layout`/CFA/black/white/colour-matrix/Orientation/Make/Model field (R2).
- AC3 — `TagsIsoDng` is encodable into a DNG IFD by rawler's `DngWriter` with no transformation beyond tag-ID lookup; a consumer reading `FotRawData` + `TagsIsoDng` alone can develop a valid RAW.
- AC4 — A reader ignoring `TagsDngLab` and `TagsFotLab` still produces a developable RAW (R7).
- AC5 — `TagsIsoDng` has no nested-tree value in any conforming instance (R4/R7).
- AC6 — The serialized `FotRaw` produced from a `FotRaw` round-trips back to an equivalent `FotRaw` (buffer + all three tag namespaces preserved).

## Impacted Modules

- `DNGLAB-SURVEY-000002` — the rawler `RawImage`/`RawImageData` contract; `FotRawData` is the uncompressed projection of `RawImageData`, and `FotRawMeta` redistributes `RawImage`'s semantic fields into the R4–R6 namespaces.
- `DNGLAB-SURVEY-000004` — re-containerization and the FFI wire form; this item fixes `FotRaw` as the IR that crosses the FFI in its serialized form.
- First-party native-integration layer — the FFI boundary that encodes/decodes the serialized `FotRaw` per R8.
- Any Kotlin-side holder of the decoded RAW model — now typed as `FotRaw`.

## Bindings

The Rust implementation of this spec is the first-party binding crate
`app/src/binding/rust/rawler_fotlab`. Each element below maps to a concrete item
there, and `intermediate.rs` points back at this document as the contract.

| Spec element | Rust item | File |
| --- | --- | --- |
| `FotRaw` | `FotRaw` | `src/intermediate.rs` |
| `FotRawData` (buffer only) | `FotRawData { buffer: FotRawBuffer }` | `src/intermediate.rs` |
| `TagsIsoDng` (flat) | `TagsIsoDng(BTreeMap<u16, rawler::formats::tiff::Value>)` | `src/intermediate.rs` |
| `TagsDngLab` | `TagsDngLab(BTreeMap<String, TagValue>)` | `src/intermediate.rs` |
| `TagsFotLab` | `TagsFotLab(BTreeMap<String, TagValue>)` | `src/intermediate.rs` |
| `rawImage → FotRaw` (R2/R3) | `rawimage_to_fotraw(&RawImage) -> FotRaw` | `src/intermediate.rs` |
| shape read-back `isodng`→`fotlab`→`dnglab` (R2b) | `read_shape(&FotRaw) -> Option<Shape>` | `src/intermediate.rs` |

The old monolithic `decode_to_png` is decomposed so each stage matches one
concept of this document:

| Stage | Function | File |
| --- | --- | --- |
| decode the RAW → rawler `RawImage` | `decode_to_rawimage` | `src/decode.rs` |
| project `RawImage` → `FotRaw` | `rawimage_to_fotraw` | `src/intermediate.rs` |
| encode `FotRaw` → PNG (bit-shift preview) | `fotraw_to_png` | `src/bound.rs` |
| UniFFI export wiring the three stages | `decode_to_png` | `src/lib.rs` |

`FotRaw` still crosses no FFI boundary: it is an in-Rust intermediate for now,
and only PNG bytes are returned to Kotlin (R8 untouched). `FotRawData` is copied
into the IR **without re-encoding** (no LJPEG; C3).

## Open Questions

- Q1 — (Resolved) DNG-ISO (TIFF-based) mandates shape tags — `ImageWidth` (256), `ImageLength` (257), `BitsPerSample` (258), `PhotometricInterpretation` (262), `SamplesPerPixel` (277), plus DNG `PixelAspectRatio` (0xC617), `DefaultCropSize` (0xC61E), `ActiveArea` (0xC68D), `Orientation` (0x0112). Shape is therefore canonically a DNG-ISO concern and lives **only** in the tags (`TagsIsoDng`, written whenever a `FotRaw` is emitted per R4). `FotRawData` holds **no** shape — it is a pure pixel buffer (R2); geometry/format is read back from the tags using the conservative fallback `isodng` → `fotlab` → `dnglab` (R2b). No further decision required.
- Q2 — Exact `FotRaw` wire encoding (endianness, type-tag scheme for `TagsDngLab`/`TagsFotLab` nested values) is specified by the FFI item, not here; this document freezes the *logical* contract only. **TBD** link to the FFI detail item once written.
- Q3 — When a dnglab upstream field has no stable name yet (e.g. a new virtual IFD), is it parked in `TagsDngLab` under a provisional key or held in `TagsFotLab` until upstream settles? **TBD.**

## Change History

- 2026-09-17 — Initial draft. Fixed `FotRaw` as the canonical decoded-but-undeveloped RAW intermediate, superseding the `RawNegative` name used in the `DNGLAB-SURVEY` studies. Defined `FotRaw = FotRawData + FotRawMeta`, with `FotRawData` as the uncompressed pixel buffer plus shape only (no semantic tags) and `FotRawMeta` split into three namespaces: `TagsIsoDng` (DNG-ISO conformant, flat), `TagsDngLab` (rawler/dnglab RawImage non-data tags, nested allowed, tracks upstream), `TagsFotLab` (FotLab-private, nested allowed). Recorded the FFI projection `FotRaw` → `RawFrame` as the frozen wire contract and the nesting/flatness invariants.
- 2026-09-17 — Revision. (a) Deprecated the `RawFrame` name used in earlier discussion/survey docs; the FFI wire form is now simply the serialized `FotRaw` (R8, C5, AC6, Impacted Modules, terminology note updated). (b) Resolved Q1: DNG-ISO (TIFF-based) mandates shape tags (`ImageWidth` 256, `ImageLength` 257, `BitsPerSample` 258, `PhotometricInterpretation` 262, `SamplesPerPixel` 277, plus DNG `PixelAspectRatio` 0xC617, `DefaultCropSize` 0xC61E, `ActiveArea` 0xC68D, `Orientation` 0x0112), so authoritative shape lives in `TagsIsoDng` (R4); `FotRawData` keeps a mirrored copy as a zero-copy FFI fast path (R2). (c) Strengthened `TagsDngLab` ownership: namespace is chosen by source of authority — any dnglab/rawler-upstream tag MUST live in `TagsDngLab` (R5, C2).
- 2026-09-17 — Revision (d). Corrected the data/meta split: `FotRawData` is now a **pure pixel buffer** — no shape, no format, no metadata (R2 rewritten; removed the `width`/`height`/`cpp`/`photometric`/`layout`/`datum` fields and the zero-copy shape mirror). All geometry/format and **every** DNG tag now live only in the three tag namespaces (R3 broadened, C4 tightened, AC2 updated). Reading geometry/format back uses a conservative fallback — `isodng` first, then `fotlab`, then `dnglab` (new R2b). The FFI wire carries the sample bytes only, with no shape header (R8 encoding updated). Q1's resolution and revision (b)'s "mirrored copy" statement are superseded.
- 2026-09-17 — Revision (e). Added the **Bindings** section pointing this spec at its Rust implementation in `app/src/binding/rust/rawler_fotlab`: the IR types (`FotRaw`/`FotRawData`/three tag namespaces), the `rawimage_to_fotraw` projection, the `read_shape` fallback (R2b), and the pipeline split (`decode_to_rawimage` → `rawimage_to_fotraw` → `fotraw_to_png`, wired by the `decode_to_png` UniFFI export). `intermediate.rs` carries the reciprocal comment pointing back at this document. No requirement changed; `FotRaw` still crosses no FFI boundary.
- 2026-09-18 — Revision (f). Renamed the in-memory IR `RawPixel` → `FotRaw` across this spec and its Rust implementation in `app/src/binding/rust/rawler_fotlab`: the type `RawPixel` and its components `RawPixelData`/`RawPixelBuffer`/`RawPixelMeta` are now `FotRaw`/`FotRawData`/`FotRawBuffer`/`FotRawMeta`; the projection `rawimage_to_rawpixel` → `rawimage_to_fotraw`; the grayscale preview encoder `rawpixel_to_png` → `fotraw_to_png`; and the Rust source file `src/rawpixel.rs` is renamed to `src/intermediate.rs` (module `rawpixel` → `intermediate`). No requirement changed (R1–R9, C1–C5, AC1–AC6) — purely a naming and source-file rename; the on-disk/wire form (R8) is unaffected.
