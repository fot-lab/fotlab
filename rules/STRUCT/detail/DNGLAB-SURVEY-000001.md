# External module study — dnglab (camera RAW → DNG converter)

- ID: DNGLAB-SURVEY-000001
- Status: Draft
- Priority: P2
- Created: 2026-09-08
- Owner: —
- Related: `FOTLAB-NATIVE-000001` (single `external/` location; dnglab delivery form is open question Q6), `FOTLAB-DATABS-000001` (C7 — native code never touches the database), `FOTLAB-STRUCT-000002` (no build artifacts / no circular deps)

> **Note on naming**: per the user's request this study file uses the `DNGLAB-` prefix rather than the
> standard `FOTLAB-STRUCT-NNNNNN` ID. It lives under `rules/STRUCT/detail/` because `STRUCT.md`
> principle 5 treats `external/` modules (dnglab, exiftool) as fixed constraints to be documented, not modified.

## Background & Goal

`dnglab` is an external third-party module vendored/pinned under `external/dnglab/`. It is a
camera RAW → Adobe DNG converter written in Rust, currently in **alpha** state (its own README
warns it is not polished or bug-free, and that the `rawler` API is not SemVer-stable). This document
records its code structure and capabilities so the team can decide how to integrate it into fotlab
per `FOTLAB-NATIVE-000001` (single integration point under `external/`). It does **not** propose
changes to dnglab source — per `STRUCT.md` principle 5, upstream is out of scope.

## Workspace Structure

Root `Cargo.toml` defines a Cargo workspace (`resolver = "2"`) with three members:

```toml
members = ["bin/dnglab", "rawler", "embedftp"]
```

| Crate | Path | Kind | License | Version | Role |
| --- | --- | --- | --- | --- | --- |
| `rawler` | `rawler/` | library | LGPL-2.1 | 0.8.0 | Core: decode camera RAW + extract image/metadata |
| `dnglab` | `bin/dnglab/` | binary (CLI) | LGPL-2.1 | 0.8.0 | The `dnglab` command-line tool |
| `dnglab_lib` | `bin/dnglab/dnglab-lib/` | library | *(unset)* | 0.8.0 | Reusable library wrapping the CLI logic — the natural integration entry |
| `embedftp` | `embedftp/` | library | MIT | 0.0.1 | Embedded FTP server (used by `ftpconv`) |

- `rawler` targets `edition = "2024"`, `rust-version = "1.89"` (MSRV), and runs a build script
  `rawler/data/join.rs` that joins bundled camera data. It exposes developer features `clap`,
  `inspector` (deep algorithm-stage inspection), and `rawdb` (downloads sample DB over the network
  via `ureq`). **`rawdb` must be disabled in a fotlab build.**
- `dnglab_lib` depends on `rawler` (with `features = ["clap"]`), `embedftp`, `image`, `serde`/`serde_json`/`serde_yaml`,
  `tokio` (full), `futures`, `async-trait`, `hex`, `anyhow`. This is the crate fotlab would most
  likely consume as a static library / via FFI.
- `embedftp` is independent (MIT) and only pulled in by `dnglab_lib` for `ftpconv`.

## Key Dependencies (rawler)

`jxl-oxide` (JPEG XL), `image` (jpeg only), `rayon` (parallelism), `zerocopy`, `multiversion`
(SIMD multi-version dispatch), `num`/`num_enum`, `serde`/`toml`, `uuid`, `weezl`, `libflate`,
`bitstream-io`, `md5`, `memmap2`, `chrono`, `thiserror`, `log`, `backtrace`. Dev deps include
`criterion` benches (`perf`, `raw_decoder`, `convert`, `demosaic`).

## CLI Subcommands (`bin/dnglab`)

- `convert <INPUT> <OUTPUT>` — RAW → DNG. Options: `--compression`
  (`lossless` LJPEG-92 / `uncompressed`), `--crop` (`best`/`activearea`/`none`),
  `--embed-raw`, `--dng-preview`/`--dng-thumbnail`, `--ljpeg92-predictor` (1–7), `--artist`,
  `--image-index`, `-r` recursive, `-f` override.
- `analyze <FILE>` — dump internal structure / metadata. `--structure`, `--meta`, `--json`/`--yaml`,
  `--full-pixel`/`--raw-pixel`/`--preview-pixel`, `--*-checksum` (MD5), `--srgb` (16-bit TIFF), `--summary`.
- `extract <FILE> <INPUT> <OUTPUT>` — extract the original RAW embedded inside a DNG.
- `makedng` — low-level DNG assembly: merge multiple inputs mapped to `raw`/`preview`/`thumbnail`/`exif`/`xmp`,
  `--dng-backward-version` (1.0–1.6), color matrices, illuminants, linearization tables, white balance.

## rawler Core Architecture (`rawler/src/`)

| Subtree | Purpose |
| --- | --- |
| `formats/` (52 `.rs`) | Per-format container parsers (CR2/CR3/NEF/ARW/RW2/PEF/ORF/IIQ/…) |
| `decoders/` (38 `.rs`) | Camera-specific RAW decoders built on the format parsers |
| `decompressors/` (17 `.rs`) | Bitstream decompression (LJPEG-92, lossless JPEG, vendor schemes) |
| `dng/` | DNG file generation / writing |
| `imgop/` (22 `.rs`) | Image operations: demosaic, crop, resize, rotate |
| `devtools/` | Developer/debug tooling |
| `bin/` | `rawler` standalone CLI |
| top-level `*.rs` | `lib.rs`, `rawimage.rs`, `rawsource.rs`, `cfa.rs` (CFA layout), `exif.rs`, `tags.rs`, `lens.rs`, `buffer.rs`, `bits.rs`/`bitarray.rs`/`pumps.rs` (bitstream I/O), `ljpeg92.rs`, `pixarray.rs`, `tiles.rs`, `analyze.rs`, `envparams.rs` |

## dnglab-lib Integration Surface (`bin/dnglab/dnglab-lib/src/`)

`lib.rs` plus focused modules: `convert.rs`, `extract.rs`, `makedng.rs`, `process_raw.rs`,
`analyze.rs`, `cameras.rs`, `lenses.rs`, `filemap.rs`, `ftpconv.rs`, `gui.rs` (placeholder — the
README mentions a future GUI), `app.rs`, and a `jobs/` directory (job scheduling for batch/parallel
conversion). This crate is the intended seam for a first-party native-integration module to call
into, rather than shelling out to the `dnglab` binary.

## Facts Relevant to fotlab Integration

- **Delivery form** (open in `FOTLAB-NATIVE-000001` Q6): either ship the `dnglab` executable and
  drive it via CLI, or consume `dnglab_lib` as a Rust static library (`.a`) and bridge it through
  JNI/FFI from the first-party native-integration module. `dnglab_lib` already exists for the
  latter, which avoids process spawning and keeps a single integration point.
- **Licensing**: `rawler` and `dnglab` are LGPL-2.1; `embedftp` is MIT. **`dnglab_lib` declares no
  `license` field** — a gap to close before any distribution (LGPL obligations would otherwise be
  ambiguous). Static-linking LGPL code on Android requires either shipping the library as a
  replaceable `.so` or providing object files / relink capability; this needs a compliance decision.
- **Cross-compilation**: Rust → `aarch64-linux-android` (and `x86_64-linux-android` for emulator)
  via the NDK. The `rawdb` feature pulls samples from the network and must be off in a release build.
- **Trust/safety**: upstream explicitly states it does **not** guarantee panic-free behaviour on
  corrupt/untrusted input, and even prefers panics that surface algorithm misunderstandings. Since
  fotlab ingests user-provided RAW files, dnglab must run isolated (separate process or sandboxed
  worker), never inline in the UI/database path (see `DATABS-000001` C7).
- **State**: alpha, API unstable, no SemVer. Pin to a specific commit (per `NATIVE-000001` R4) and
  budget for re-pinning when upgrading.

## Constraints (STRUCT.md principle 5)

`external/dnglab` is a fixed constraint. This document records how to work with it; no change to its
source is specified or permitted here. All fotlab-specific logic (FFI boundary, threading, lifecycle)
belongs in the first-party native-integration module, not in `external/`.

## Open Questions

- Q1 — Delivery form: `dnglab_lib` static library (FFI/JNI) vs. `dnglab` executable (CLI driver)?
  → tracks `FOTLAB-NATIVE-000001` Q6.
- Q2 — LGPL-2.1 compliance path for Android static linking (separate `.so` vs. relinkable objects).
- Q3 — Is `embedftp` / `ftpconv` needed by fotlab at all (direct camera tethering)? If not, drop it.
- Q4 — How to isolate dnglab against malformed user files (separate process vs. sandbox) given its
  panic-on-corrupt stance.
- Q5 — Compiled size budget of `rawler` + its MIT/LGPL deps on Android (feeds `NATIVE-000002` R4).
- Q6 — Close the missing `license` field on `dnglab_lib` (upstream fix or a documented local patch).

## Change History

- 2026-09-08 — Initial study. Documented `external/dnglab` Cargo workspace (rawler / dnglab / dnglab_lib
  / embedftp), CLI subcommands, rawler source layout, the `dnglab_lib` integration surface, and the
  licensing / delivery-form / cross-compile / trust facts relevant to fotlab. Flagged the unset
  `license` field on `dnglab_lib` and linked open questions to `FOTLAB-NATIVE-000001` Q6.
