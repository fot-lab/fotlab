# External module study — dnglab main body vs rawler: division of labor (dnglab = orchestration shell, rawler = capability layer)

- ID: DNGLAB-SURVEY-000004
- Status: Draft
- Priority: P2
- Created: 2026-09-17
- Owner: —
- Related: `DNGLAB-SURVEY-000001` (what dnglab is: workspace / CLI / `dnglab_lib` seam), `DNGLAB-SURVEY-000002` (decode pipeline & `RawImage`/`RawImageData` contract), `DNGLAB-SURVEY-000003` (camera metadata propagation into encode)

> **Note on naming**: per `DNGLAB-SURVEY-000001`, this study uses the `DNGLAB-` prefix and lives under
> `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules (dnglab, exiftool)
> as fixed constraints to be documented, not modified.

> This document is a **fourth deep-dive companion** to `DNGLAB-SURVEY-000001`. That file records *what
> dnglab is* (workspace layout, CLI surface, licensing/trust) but stops short of the precise boundary
> between the `dnglab` crate and the `rawler` crate it wraps. **This file establishes that boundary**,
> which 000001 left implicit and which matters for the first-party native-integration module: it
> determines how much of the pipeline we get "for free" from the rawler binding vs. what we would have
> to re-implement or orchestrate ourselves.

## Background & Goal

The working assumption coming out of 000002/000003 was that `rawler` is "the decoder" and `dnglab`
is "the RAW→DNG converter". Reading the source shows this mental model is **incomplete**: the DNG
encoding logic (`convert_raw_file`, `DngWriter`) already lives **inside the `rawler` crate**, not in
`dnglab`. The `dnglab` binary/library is, in effect, an **application/orchestration shell** that drives
rawler and adds CLI, jobs, file mapping, a manual DNG assembler, and a reverse extractor.

Understanding this distinction matters because:

- The first-party module only needs to **bind `rawler`** to get decode + DNG write + even develop
  (demosaic/gamma) — dnglab is not strictly required for the core pipeline.
- `dnglab`'s added value is the **orchestration pattern** (jobs, multi-frame, embed-raw, preview/
  thumbnail, compression options) and the `makedng` **per-tag override** model — both are useful
  reference designs for our own `RawNegative` (intermediate-state) serialization, not functionality
  we must reimplement.
- `convert` (raw→dng) is a **re-containerization, not a develop**: it preserves the CFA mosaic
  (`DngPhotometricConversion::Original`) and leaves demosaic/color/gamma to the DNG *consumer*
  (Lightroom / darktable / RawTherapee). The emitted DNG is therefore exactly the serialized form of
  the `RawNegative` intermediate discussed in the `FOTLAB-RAWLER` design notes.

## 1. rawler is a full capability layer (not just a decoder)

`rawler/src/lib.rs` exposes modules far beyond decoding (`lib.rs:73-93`):

| Capability | Module / symbol | Notes |
| --- | --- | --- |
| Decode → `RawImage` | `rawler::decode_file` / `decode` (`lib.rs:204-220`) | The `RawImage`/`RawImageData` contract from 000002 |
| **DNG write** | `rawler::dng::convert::{ConvertParams, convert_raw_file, convert_raw_source}` (`rawler/src/dng/convert.rs:31,155,173`) + `rawler::dng::writer::DngWriter` | **The actual raw→DNG encoder. `DngWriter` is what `dnglab` calls.** |
| **Develop (demosaic+gamma)** | `rawler::imgop::develop` (`RawProcessingParams`) + `rawler::imgop::{gamma,srgb,xyz}` | Produces an RGB image (used by `process-raw` → TIFF) |
| Analyze / source / lens / exif | `rawler::analyze`, `rawler::rawsource`, `rawler::lens`, `rawler::exif` | Raw-source abstraction, camera+lens DB, EXIF |
| Tags / CFA | `rawler::tags`, `rawler::cfa` | DNG/TIFF tag + CFA definitions |

> **Key finding**: `convert_raw_file` (the raw→dng entry point) is in **rawler**, not dnglab. dnglab's
> `jobs/raw2dng.rs:12,100` literally calls `rawler::dng::convert::convert_raw_file(...)`. No DNG bit-
> writing happens in the `dnglab` crate.

## 2. dnglab main body: what is actually added on top of rawler

`dnglab-lib/src/lib.rs:11-22` declares the modules that constitute the dnglab application layer:

```
analyze, app, cameras, convert, extract, filemap, ftpconv, gui, jobs, lenses, makedng, process_raw
```

None of these perform pixel/color math of their own — even `makedng`'s linearization/gamma tables
delegate to `rawler::imgop::gamma` / `srgb` / `xyz` (`makedng.rs:15,18,19`). The dnglab-added value
is summarized below.

### 2.1 CLI application layer (`bin/dnglab` + `dnglab-lib/src`)

clap subcommands wrapping rawler: `convert`, `process-raw`, `extract`, `makedng`, `analyze`,
`cameras`, `lenses`, `ftpserver`, `gui`.

### 2.2 Job orchestration & concurrency (`jobs/`)

- `Raw2DngJob` (`jobs/raw2dng.rs:24-30`): holds `input`/`output`/`replace`/`params: ConvertParams`;
  `internal_exec` (`:70-138`) opens the output with `O_EXCL`, calls `convert_raw_file`, handles
  file locking, mtime preservation, and cleanup-on-error. Execution is dispatched onto **rayon** (not
  tokio's blocking pool) to avoid double-dispatch with the LJPEG tile-parallelism inside rawler
  (`:150-163`).
- `Raw2Image` (`jobs/process_raw.rs`): the `process-raw` counterpart; outputs `.tif` (`:135`).
- Plus: multi-frame splitting, output-filename disambiguation (`FOO.dng` / `FOO_1.dng`), `keep_mtime`.

### 2.3 File mapping (`filemap.rs`)

`MapMode::{File, Dir}` resolution, supported-extension filtering (`supported_extensions()` from
rawler), recursive directory walk — the glue that turns CLI args into job lists (`convert.rs:13,42-60`).

### 2.4 Manual DNG assembler — `makedng` (dnglab-unique, not exposed by rawler CLI)

`makedng.rs` is the one feature **rawler does not expose as a CLI**. It builds a DNG from arbitrary
loose inputs (`--map` to assign each input file a role: `raw`/`preview`/`thumbnail`/`exif`/`xmp`,
`makedng.rs:312-333`) and even accepts plain RGB images (`open_without_limits`, `:46-50`,
`:128-170`). It then lets the user **override every DNG tag**:

- `ColorMatrix1/2/3` + `CalibrationIlluminant1/2/3` (`:243-267`, behind `--matrix*`, `--illuminant*`)
- `LinearizationTable` (`:136-162`, with built-in sRGB/gamma tables from rawler `imgop`)
- `AsShotNeutral` / `AsShotWhiteXY` (`:269-277`)
- `Make` / `Model` / `UniqueCameraModel` / `ColorimetricReference` / DNG version 1.0–1.6 (`:100-118, 683-705`)

Mechanically it is a thin "manual driving" wrapper over `rawler::dng::writer::DngWriter`
(`makedng.rs:10-11,98,124-126`).

### 2.5 Reverse operation — `extract`

Pulls the **embedded original raw** back out of a DNG — the inverse of the `embed-raw` option in
`convert` (`convert.rs:123` `embedded: options.get_flag("embedded")`).

### 2.6 Multi-format output — `process-raw`

raw → **TIFF** (a real develop, not a re-containerization): uses
`rawler::imgop::develop::RawProcessingParams` (`process_raw.rs:7,116`) and writes `.tif`
(`process_raw.rs:135`).

### 2.7 Introspection — `analyze`

Dumps structure / metadata / pixel checksums as JSON or YAML (jq-friendly). rawler also has
`rawler::analyze`; dnglab wires it to a CLI with `--json`/`--yaml`/`--structure` etc.

### 2.8 Deployment integration — `ftpconv` / `embedftp`, `gui`

An FTP server that converts uploads on the fly (`ftpconv.rs` + `embedftp/`), plus a (WIP) GUI
(`gui.rs`). These are runtime/deployment conveniences, not pipeline primitives.

## 3. The decisive insight: `convert` re-containerizes, it does not develop

`convert` (raw→dng) writes the **CFA mosaic** into the DNG losslessly (LJPEG-92) and **keeps it raw**:

- `makedng.rs:125` calls `rawframe.raw_image(&rawimage, CropMode::Best, DngCompression::Lossless,
  DngPhotometricConversion::Original, 1)` — i.e. the photometric interpretation stays `Original`
  (mosaic), not developed RGB.
- `convert`'s `ConvertParams` is built with `photometric_conversion: Default::default()` and
  `apply_scaling: false` (`convert.rs:209,221`) — consistent with "preserve raw, leave develop to the
  consumer".
- The actual demosaic/color/gamma therefore happens **later**, in whatever engine consumes the DNG
  (Lightroom / darktable / RawTherapee).

Consequence for our design: **the DNG that dnglab emits is precisely the serialized form of the
`RawNegative` intermediate we discussed** (data + metadata, still mosaic). This closes the loop with
the `FOTLAB-RAWLER` naming/IR research — `RawNegative` ↔ DNG is the same object, in-memory vs on-disk.

## 4. Implications for the first-party native-integration module

1. **Bind `rawler`, not the `dnglab` binary.** Decode, DNG write, and even develop all come from the
   rawler crate (`rawler::decode_file` → `RawImage` → `rawler::dng::writer::DngWriter`, or
   `rawler::imgop::develop` for RGB). We do not need to shell out to the `dnglab` CLI.
2. **`RawNegative` serialization is "free".** Our Kotlin-held `RawImage` (rawler's `RawImage` is
   already the standard data+metadata intermediate) can be written to DNG by reusing
   `rawler::dng::writer::DngWriter`. DNG then feeds dnglab / RT / darktable unchanged.
3. **Borrow dnglab's orchestration pattern, not its code.** `jobs/` (atomic output, file lock,
   mtime, multi-frame disambiguation), `filemap` (File↔Dir, extension filter), and `makedng`'s
   **per-tag override** model are the useful reference designs — especially `makedng`'s tag list,
   which doubles as a "minimum required metadata field set" for handing a `RawNegative` to an
   external engine (CFA, ColorMatrix1/2 + illuminants, LinearizationTable, AsShotNeutral,
   Black/WhiteLevel, Crop/ActiveArea).

## 5. LJPEG-92 as the serialization boundary, not the in-memory container

A recurring design question is whether the DNG lossless codec (LJPEG-92) is also a good **data
container for the intermediate** (`RawNegative`) that we hand between components. It is not — its
sweet spot is the on-disk DNG file, not the in-memory / cross-process intermediate.

### 5.1 Performance profile of LJPEG-92 (from `rawler` source)

LJPEG-92 is a predictive + Huffman **CPU codec** with no widespread hardware (GPU/ISP) acceleration:

- **Mandatory tiling** — `dng_put_raw_ljpeg` (`rawler/src/dng/writer.rs:567-638`) splits the image into
  **256×256 tiles** (`writer.rs:571-572`) and compresses each independently. DNG requires LJPEG raw to be
  tiled, so a pixel cannot be read without locating + decoding its tile — there is **no random access**.
- **16-bit integer only** — `writer.rs:148`: "Lossless (LJPEG92) can only be used for 16 bit integer
  data".
- **CPU-bound, parallelized to cope** — tile compression runs on rayon (`writer.rs:606-615`,
  `into_par_iter`), confirming it is a real cost, not free.
- **Modest compression on Bayer mosaic** — the CFA is high-frequency; the default predictor is 1
  (`convert.rs:65`), predictors 4–7 help CFA but it is still prediction + Huffman. Raw typically
  compresses only **~1.5–2×**. Throughput is tens-to-low-hundreds of MB/s per core for 16-bit; encode
  and decode are comparable. Fine for one-shot export, a recurring tax if done repeatedly in a hot path.

### 5.2 Suitability as the intermediate data container

| Scenario | LJPEG fit | Reason |
| --- | --- | --- |
| DNG file on disk, consumed by dnglab / RT / darktable | ✅ Yes | DNG-standard, lossless, natively readable by every engine — exactly what `DngWriter` does |
| `RawNegative` held in memory, awaiting demosaic/develop | ❌ No | Every pixel touch requires decode; tiling breaks row-major / planar assumptions engines expect |
| Same-process FFI handoff (Kotlin ↔ native) | ❌ No | Bandwidth saved ≪ decode tax; rawler itself holds `RawImage` **uncompressed** and only compresses on write |
| Cross-process IPC (bandwidth only, no DNG compatibility needed) | ⚠️ Better options | LZ4/Zstd decode far faster, no tiling, random-readable after decompress — beats LJPEG |

### 5.3 Conclusion

LJPEG-92 belongs at the **serialization boundary** (writing the DNG file / crossing out of process when
DNG compatibility is wanted), not at the **intermediate-state holding layer**. This matches both the
existing first-party design and rawler's own architecture:

- In-memory `RawNegative`: keep **uncompressed `u16` mosaic** (or the decoded buffer) so engines
  consume it with zero decode tax. This is consistent with `FOTLAB-RAWLER-000002`, which defines
  `RawFrame` as a **flat, uncompressed** FFI transport, and with rawler, which keeps `RawImage`
  uncompressed and only compresses inside `DngWriter`.
- At the export boundary: when DNG interoperability is required, call `DngWriter` to LJPEG-compress to
  disk. If the goal is IPC bandwidth without DNG compatibility, prefer LZ4/Zstd.
- Side note: DNG also supports `DngCompression::Uncompressed` tiles — emitting an *uncompressed DNG*
  (larger file, zero codec tax) is a valid middle ground when transfer cost matters but the DNG shell
  is still wanted.

**One-line rule**: *LJPEG is the compression layer of the DNG file, not the container of the
intermediate; keep it at the serialization boundary, and use uncompressed data in memory and FFI.*

## Constraints (STRUCT.md principle 5)

`external/dnglab` is a fixed constraint. This document records the **division of labor** between the
`rawler` and `dnglab` crates only; no change to either crate's source is specified or permitted here.
The FFI boundary, threading model and lifecycle belong in the first-party native-integration module.

## Relationship to DNGLAB-SURVEY-000001 / 000002 / 000003

- 000001 establishes *what dnglab is* (workspace, CLI, `dnglab_lib` seam, licensing, trust) — but
  implied dnglab "is the converter". This file corrects that: the converter (DNG encoder) is **rawler**.
- 000002 establishes *what rawler outputs* (the `RawImage`/`RawImageData` contract).
- 000003 establishes *where the per-camera parameters inside `RawImage.camera` come from* and how they
  reach `DngWriter`.
- 000004 establishes *who does the work*: rawler = capability layer (decode + DNG write + develop +
  analyze); dnglab = orchestration shell (CLI, jobs, filemap, `makedng`, `extract`, `process-raw`,
  `analyze` CLI, ftp/gui).

## Change History

- 2026-09-17 — Division-of-labor study. Documented that the DNG encoder (`convert_raw_file`,
  `DngWriter`) lives in **rawler** (`rawler/src/dng/convert.rs:155`, `rawler/src/dng/writer`),
  called by `dnglab jobs/raw2dng.rs:100`; enumerated dnglab's added modules (`lib.rs:11-22`): CLI,
  `jobs/` orchestration (rayon dispatch, file lock, multi-frame, mtime), `filemap`, the `makedng`
  manual DNG assembler with per-tag overrides (`makedng.rs:243-277`), `extract` (reverse of
  embed-raw), `process-raw` → TIFF via `rawler::imgop::develop` (`process_raw.rs:7,135`), `analyze`
  CLI, ftp/gui. Flagged that `convert` re-containerizes (keeps mosaic, `DngPhotometricConversion::
  Original` per `makedng.rs:125`) rather than developing, so the emitted DNG == the serialized
  `RawNegative` intermediate; and that the first-party module only needs to bind rawler (not the
  dnglab binary) to get decode + DNG write + develop.
- 2026-09-17 — Added §5 "LJPEG-92 as the serialization boundary, not the in-memory container".
  Documented LJPEG-92 performance profile from source (`dng_put_raw_ljpeg`, `rawler/src/dng/writer.rs:
  567-638`; 256×256 mandatory tiling `:571-572`; 16-bit only `:148`; rayon-parallel tile compression
  `:606-615`; default predictor 1 in `convert.rs:65`; ~1.5–2× compression on Bayer mosaic). Added a
  suitability matrix (DNG file ✅ / in-memory intermediate ❌ / same-process FFI ❌ / IPC ⚠️) and the
  one-line rule: LJPEG = compression layer of the DNG file, not the container of the intermediate; keep
  uncompressed in memory + FFI, compress only at the DNG serialization boundary (consistent with
  `FOTLAB-RAWLER-000002` `RawFrame` and rawler holding `RawImage` uncompressed).
