# External module study — dnglab rawler: RAW decode pipeline & unified intermediate data model

- ID: DNGLAB-SURVEY-000002
- Status: Draft
- Priority: P2
- Created: 2026-09-10
- Owner: —
- Related: `DNGLAB-SURVEY-000001` (precursor — workspace/CLI/integration surface & capabilities), `FOTLAB-NATIVE-000001` (single `external/` integration point), `FOTLAB-STRUCT-000002` (no build artifacts / no circular deps)

> **Note on naming**: per the user's request this study file uses the `DNGLAB-` prefix rather than the
> standard `FOTLAB-STRUCT-NNNNNN` ID. It lives under `rules/STRUCT/detail/` because `STRUCT.md`
> principle 5 treats `external/` modules (dnglab, exiftool) as fixed constraints to be documented, not modified.

> This document is a **deep-dive companion** to `DNGLAB-SURVEY-000001`. That file covers the Cargo
> workspace, CLI surface, licensing and the `dnglab_lib` integration seam. This file records the
> **internal decode algorithm** and the **unified intermediate data structures** the `rawler` crate
> produces, so the first-party native-integration module knows exactly what it receives across all
> supported camera formats.

## Background & Goal

`rawler` (under `external/dnglab/rawler`) is the library that turns a vendor RAW file into a single,
normalized in-memory representation. Understanding its pipeline and data model matters because:

- The first-party native-integration module will consume **`rawler`'s output**, not the raw bytes — so
  the `RawImage` / `RawImageData` contract is the real FFI boundary (the pixel buffer + metadata, not
  the compressed stream).
- All 30+ formats converge to **one** struct, which is what decouples fotlab's downstream processing
  from per-camera quirks.

This document records (a) the decode pipeline and core decompression algorithms, (b) the unified
intermediate data model, (c) the demosaic (de-Bayer) layer and its algorithm provenance, and
(d) the encode/output layer and supported formats. As with all `external/` studies, **no change to
dnglab source is proposed** (per `STRUCT.md` principle 5).

## 1. RAW Decode Core Algorithm

### 1.1 Pipeline overview (entry → dispatch → decompress → post-process)

1. **Entry** — `rawler::decode_file` / `rawler::decode` (`lib.rs:204` / `lib.rs:218`) route to the
   global `RawLoader` (`LOADER`, a `lazy_static`).
2. **Format dispatch** — `RawLoader::get_decoder` (`decoders/mod.rs:909`) sniffs the file by magic
   bytes / container type and returns a boxed `dyn Decoder`:
   - `mrw` / `raf` / `ari` / `crw` (CIFF) / `x3f` by signature;
   - BMFF with `crx ` compatible brand → `cr3::Cr3Decoder`;
   - otherwise TIFF, then `DngTag::DNGVersion` → `dng::DngDecoder`, else `Make` string match →
     `cr2` / `nef` / `arw` / `pef` / `orf` / `srw` / `rw2` / `iiq` / `tfr` / `mos` / `kdc` / `dcr` /
     `erf` / `nrw` / `nef` / `dcs` … (30+ decoders in `decoders/`).
3. **Decode** — each `Decoder::raw_image()` parses the container/IFD, locates the pixel offset
   (`StripOffsets` / `TileOffsets` / BMFF box), reads the compressed bytes, decompresses them, then
   assembles a `RawImage`.
4. **Panic safety** — `RawLoader::decode` (`decoders/mod.rs:1093`) wraps the call in
   `catch_unwind`; on panic it returns `RawlerError::DecoderFailed`. (Recall from 000001: upstream
   *prefers* panics on corrupt input, so this must run isolated.)

### 1.2 Decompression core: the `Decompressor` trait

`decompressors/mod.rs:96` defines the single abstraction every codec implements:

```rust
fn decompress(&self, src: &[u8], skip_rows: usize,
              lines: impl LineIteratorMut<'a, T>, line_width: usize) -> Result<(), String>;
```

It writes decoded pixels row-by-row into `LineIteratorMut`. A `can_skip_rows()` flag lets row-addressable
codecs (packed bits) start mid-stream, while entropy codecs (JPEG) must decode from the start.
Parallel dispatch helpers `decompress_lines_fn` / `decompress_strips_fn` (`mod.rs:143` / `:210`) drive
this over Rayon.

| Decompressor | Path | Algorithm | Used by |
| --- | --- | --- | --- |
| `PackedDecompressor` | `decompressors/packed.rs` | **Bit unpacking** — 12/14-bit samples packed into bytes (e.g. 2 px → 3 bytes, MSB/LSB first). This is the "core algorithm" behind almost all "uncompressed" RAWs. | NEF, ARW, RW2, DNG strips/tiles, most TIFF-based formats |
| `LJpegDecompressor` | `decompressors/ljpeg/` | LJPEG-92 lossless (Huffman + predictor) | Canon CR2/CR3, most DNG |
| `JpegDecompressor` | `decompressors/jpeg.rs` | Baseline lossy JPEG (8-bit YCbCr) | thumbnails / previews / YCbCr |
| `JpegXLDecompressor` | `decompressors/jpegxl.rs` | JPEG-XL | DNG 1.7.1, CR3 lossy |
| `DeflateDecompressor` | `decompressors/deflate.rs` | zlib/deflate + predictor | float (f32) DNG tiles |
| `crx` | `decompressors/crx/` | Canon CR3 CRX (a JPEG-XL variant) | CR3 |
| `arw6` | `decompressors/arw6/` | Sony ARW 2.0 delta | ARW |
| `radc` | `decompressors/radc.rs` | Redcode | RED |

The dispatch table itself lives in `plain_image_from_ifd` (`decoders/mod.rs:606`), which inspects
`SampleFormat` × `CompressionMethod` × strip/tile `DataMode` and selects the decompressor.

### 1.3 Common post-processing (after decompression)

All paths then run, in `decoders/mod.rs:606`:

- **crop** — `into_crop` removes codec row-alignment padding (`pixbuf.into_crop` at `mod.rs:663`).
- **linearization** — `apply_linearization` (`mod.rs:812`) applies the `Linearization` lookup table
  per `u16` pixel with dithering (used by e.g. EOS D2000).
- **deinterleave** — DNG 1.7.1 2×2 row/column interleave reordering via `deinterleave2x2`
  (`pixarray.rs:235`), guarded by `RowInterleaveFactor` / `ColumnInterleaveFactor`.

### 1.4 Vendor-specific post-processing (CR2 example)

`decoders/cr2.rs` shows the per-format work on top of the generic pipeline:

- **sRAW / mRAW**: LJPEG output is **YCbCr subsampled**; `convert_to_rgb` (`cr2.rs:691`) does chroma
  interpolation (`interpolate_yuv`, `cr2.rs:624`) then a dcraw-style YUV→RGB transform with
  white-balance coefficients and a model-specific hue correction.
- **Stripe reassembly**: multi vertical stripes (`Cr2StripeWidths`) are re-packed into a full frame.
- **Metadata extraction**: white balance, black level and white level are read from the MakerNote
  `ColorData` tag (`get_wb` / `get_blacklevel` / `get_whitelevel`, `cr2.rs:549`/`573`/`592`).

> **Key insight**: `rawler` emits **pre-demosaic** data — a single CFA channel (or `LinearRaw` RGB).
> True demosaic (Bayer → RGB) is **not** part of the decode layer; it belongs to the `imgop`
> development pipeline that consumes `RawImage`. This is why the unified struct below is
> mosaic-agnostic.

## 2. Unified Intermediate Data Model

Every format normalizes to the same in-memory shape. This is the real contract the integration layer
receives.

### 2.1 `RawImage` — the unified output (`rawimage.rs:201`)

| Field | Type | Meaning |
| --- | --- | --- |
| `data` | `RawImageData` | the pixel buffer (see 2.2) |
| `width` / `height` | `usize` | CPP=1 ⇒ CFA pixel count |
| `cpp` | `usize` | components/pixel: Bayer = 1, sRGB = 3 |
| `bps` | `usize` | bits per (sub)sample |
| `camera` / `make` / `model` / `clean_*` | `Camera` / `String` | camera identity, raw + cleaned |
| `wb_coeffs` | `[f32; 4]` | white balance (RGBE order) |
| `whitelevel` / `blacklevel` | `WhiteLevel` / `BlackLevel` | per-channel levels |
| `xyz_to_cam` / `color_matrix` | `[[f32;3];4]` / `HashMap<Illuminant, FlatColorMatrix>` | color matrices |
| `photometric` | `RawPhotometricInterpretation` | `Cfa` / `BlackIsZero` / `LinearRaw` |
| `active_area` / `crop_area` | `Option<Rect>` | usable / recommended crop |
| `blackareas` | `Vec<Rect>` | masked (light-shielded) sensor regions |
| `orientation` | `Orientation` | from metadata `Orientation` tag |
| `dng_tags` / `fuji_rotation_width` | `HashMap<u16,Value>` / `Option<usize>` | DNG overrides / Fuji sensor split |

### 2.2 `RawImageData` — the pixel buffer (`rawimage.rs:255`)

```rust
pub enum RawImageData {
  Integer(Vec<u16>),  // most formats (unpacked / LJPEG output)
  Float(Vec<f32>),    // float DNG
}
```

Flat layout `width * height * cpp`. `as_f32()` converts between the two; `force_integer()` quantizes
back to `u16`. During decompression the working type is `Pix2D<T>` (`pixarray.rs:42`) with aliases
`PixU16 = Pix2D<u16>` and `PixF32 = Pix2D<f32>` — a width/height/`data: Vec<T>` buffer whose
`into_inner()` is moved into `RawImageData`.

### 2.3 `CFA` — color filter array pattern (`cfa.rs:73`)

```rust
pub struct CFA { pub name: String, pub width: usize, pub height: usize, pattern: [[u8;48];48] }
```

`name` is e.g. `"RGGB"` (2×2) or the 6×6 X-Trans string; `color_at(row, col)` returns the per-pixel
color index (fast, for demosaic / deinterleave inner loops). Variants `CFAColor` cover R/G/B/C/M/Y/W
and Fuji-green. `PlaneColor` (`cfa.rs:305`) lists the active planes/channels.

### 2.4 `RawMetadata` — the metadata side-channel (`decoders/mod.rs:237`)

Decoupled from pixels: `exif: Exif`, `model`, `make`, optional `lens: LensDescription`,
`unique_image_id`, `rating`. `Exif` itself is `crate::exif::Exif`.

### 2.5 `DevelopParams` — the development-pipeline contract (`imgop/raw.rs:23`)

Bundles `RawImage`'s `photometric` / `wb_coeff` / `blacklevel` / `whitelevel` / `active_area` /
`crop_area` / `color_matrices` into one struct for the `imgop` stage. Operands it provides:
`correct_blacklevel_cfa` (normalize black/white to 0..1, `raw.rs:165`), and `map_3ch_to_rgb` /
`map_4ch_to_rgb` (camera colorspace → sRGB via `cam2rgb` pseudo-inverse, `raw.rs:192`/`219`).

## 3. Demosaic layer — algorithms & provenance (`imgop/sensor/`)

The `imgop` development pipeline (consumed by `develop.rs`) performs demosaic through a single
`Demosaic<T, N>` trait (`imgop/sensor/mod.rs:55`). Implementations are split by CFA type into
`imgop/sensor/bayer/` and `imgop/sensor/xtrans/`. **Unlike the decode/decompression layer (which is
heavily ported from dcraw.c / LibRaw, see §1.2), the demosaic algorithms are NOT derived from dcraw** —
they are academic or self-implemented, except the X-Trans path.

### 3.1 Algorithms & their provenance

| Algorithm | Path | Provenance | Default use |
| --- | --- | --- | --- |
| PPG (Patterned Pixel Grouping) | `imgop/sensor/bayer/ppg.rs` | **Chuan-kai Lin** (2004) — source cited in-file (`ppg.rs:25-27`) at `sites.google.com/site/chklin/demosaic`. **Not dcraw.** | ✅ Bayer RGB default (`develop.rs:201`) |
| Bilinear (3ch) | `imgop/sensor/bayer/bilinear.rs` | Standard bilinear; self-implemented, no external citation | — |
| Bilinear (4ch) | `imgop/sensor/bayer/bilinear.rs` | Standard bilinear; used for 4-color CFA (CMYG) | ✅ 4-color CFA default (`develop.rs:214`) |
| Superpixel (half-size) | `imgop/sensor/bayer/superpixel.rs` | Classic **dcraw `-h` half-size** shape: 2×2 input → 1 output, 1/4 size (`superpixel.rs:25-28`). Comment does not name dcraw, but the algorithm is exactly dcraw's superpixel mode | — |
| X-Trans Markesteijn | `imgop/sensor/xtrans/markesteijn.rs` | Port of **Roman Kuraev**'s Rust `naorunaoru/demosaic` (`markesteijn.rs:1-9`); hex-neighbor tables & padding "matching dcraw/LibRaw" (`markesteijn.rs:159`, `:862`) | ❌ implemented but not on the default path (`develop.rs`); must be constructed explicitly |
| X-Trans Bilinear | `imgop/sensor/xtrans/bilinear.rs` | Standard bilinear (5×5) for X-Trans previews | ✅ X-Trans default (`develop.rs:217`) |

> A repo-wide `dcraw`/LibRaw search only matches demosaic code inside the Markesteijn implementation
> (hex tables, padding). PPG / superpixel / bilinear comments carry **no** dcraw attribution — so the
> demosaic layer is largely independent of dcraw, whereas the **decompression** layer (§1.2) is
> explicitly dcraw/LibRaw-derived (`decompressors/radc.rs`, `qtk.rs`, `crx/*`, `rw2/*`, `iiq.rs`,
> `raf/*`, `kdc.rs`).

### 3.2 Default dispatch

`develop.rs:188-221` selects: Bayer RGB → PPG; Bayer 4-color → 4ch Bilinear; X-Trans → X-Trans
Bilinear. Markesteijn (highest quality; 3-pass ≈ DCB/AMaZE per `markesteijn.rs:47-49`) exists but is
not wired into the default convert path.

## 4. Encode / output layer & supported formats

dnglab is *"a camera RAW to DNG file format converter"* (README:1). The encode layer turns the
intermediate `RawImage` into a DNG; all other outputs are debug/analysis only.

### 4.1 `DngWriter` & TIFF container (`rawler/src/dng/writer.rs`)

`DngWriter<B>` (`writer.rs:40`) sits on top of the self-built TIFF writer (`formats/tiff/writer.rs`,
`TiffWriter`/`DirectoryWriter`). DNG is a TIFF 6.0 container with multiple IFDs:

- **Root IFD** — Make/Model, CFA, color matrices, black/white levels, ActiveArea, DefaultCrop,
  DNGVersion=1.6 + DNGBackwardVersion (`writer.rs:370-386`, `dng/mod.rs:16`).
- **EXIF IFD** — via `RawMetadata::write_exif_tags` (`writer.rs:409`).
- **Sub IFD** — the main Raw pixel data.
- **Preview / Thumbnail IFD** — optional (`writer.rs:318` / `:469`).
- **OriginalRaw** — optional embedded source (`writer.rs:450` + `original.rs`).

### 4.2 Compression

`write_rawimage` dispatches on `DngCompression` (`writer.rs:298-307`, enum at `dng/mod.rs:108`):

| Data | Mode | Method |
| --- | --- | --- |
| Main Raw | `Uncompressed` | TIFF `CompressionMethod::None` (raw u16) |
| Main Raw | `Lossless` (default) | TIFF `ModernJPEG` = **LJPEG-92** (DNG lossless JPEG), predictor 1–7 (`--ljpeg92-predictor`); float forced to u16 first (`writer.rs:147-153`) |
| Preview | — | lossy **JPEG** via `image::JpegEncoder`, quality 0.75, ≤1024×768, YCbCr (`writer.rs:318-347`) |
| Thumbnail | — | uncompressed RGB, 240×120 (`writer.rs:469-490`) |
| Embedded original | — | **zlib/deflate** via `libflate`, 64 KiB blocks (`dng/original.rs:5,22`) |

The TIFF writer also implements LZW (`write_strips_lzw`, `weezl`, `formats/tiff/writer.rs:55`) but it
is not on the main Raw path.

### 4.3 Supported output formats

| Format | How produced | Notes |
| --- | --- | --- |
| **DNG** | `convert` / `makedng` subcommands | Only target format. `convert <IN> <OUT>` (`README:98`). Opts: `--dng-compression lossless\|uncompressed`, `--ljpeg92-predictor 1-7`, `--embed-raw` (default on), `--dng-preview` / `--dng-thumbnail` (default on), `--crop best\|activearea\|none`. Supported DNG feature per README is **LJPEG-92 lossless only** (`README:85-88`). |
| **TIFF** | `analyze` subcommand → STDOUT | Debug only: `--srgb` writes sRGB **16-bit TIFF** (`README:167`); `--full-pixel` writes uncompressed pixel stream. |
| **JPEG** | inside DNG Preview IFD only | Not a standalone top-level output. |

**Conclusion**: the encode layer = self-built TIFF/DNG writer + LJPEG-92 (main Raw) / lossy JPEG
(preview) / zlib (embedded original). dnglab emits **DNG only** as a real output; TIFF & raw pixel
streams are `analyze`-only debugging aids. It does **not** emit standalone PNG, processed RGB JPEG, or
RGB TIFF image files.

## Constraints (STRUCT.md principle 5)

`external/dnglab` is a fixed constraint. This document records `rawler`'s decode behavior and its
in-memory contract only; no change to its source is specified or permitted here. The FFI boundary,
threading model and lifecycle all belong in the first-party native-integration module.

## Relationship to DNGLAB-SURVEY-000001

- 000001 established *what dnglab is* (workspace, CLI, `dnglab_lib` seam, licensing, trust).
- 000002 establishes *what rawler outputs* (the `RawImage`/`RawImageData` contract + CFA + metadata),
  which is the actual data the first-party module must marshal across the FFI/CLI boundary.

## Change History

- 2026-09-10 — Decode-pipeline & data-model deep-dive. Documented the entry→dispatch→decompress→
  post-process flow, the `Decompressor` trait and its codec implementations (packed bits / LJPEG-92 /
  JPEG-XL / deflate / CRX / ARW6 / Redcode), the common crop/linearize/deinterleave steps and the CR2
  YUV→RGB + stripe reassembly specifics. Recorded the unified `RawImage`, `RawImageData` (Integer/Float),
  `Pix2D`/`PixU16` working buffer, `CFA`, `RawMetadata` and `DevelopParams` structures with file:line
  references. Flagged that rawler output is pre-demosaic (CFA / LinearRaw), so demosaic lives in `imgop`.
- 2026-09-10 — Added §3 (demosaic layer & provenance) and §4 (encode/output layer & formats) to the
  SURVEY. §3 maps the `Demosaic` trait (`imgop/sensor/mod.rs:55`) to its implementations: PPG = Chuan-kai
  Lin (NOT dcraw, `bayer/ppg.rs:25-27`), bilinear (self-implemented), superpixel (dcraw `-h` half-size
  shape, `bayer/superpixel.rs:25-28`), X-Trans Markesteijn (port of `naorunaoru/demosaic` / Roman Kuraev;
  hex tables & padding "matching dcraw/LibRaw", `xtrans/markesteijn.rs:1,159,862`) and X-Trans bilinear.
  Noted default dispatch in `develop.rs:188-221` (PPG / 4ch-bilinear / X-Trans-bilinear; Markesteijn not
  on the default path). §4 records `DngWriter` (`dng/writer.rs:40`) on the self-built TIFF writer, the
  multi-IFD layout, `DngCompression` (`dng/mod.rs:108`) → LJPEG-92 (main Raw) / lossy JPEG (preview) /
  uncompressed RGB (thumbnail) / zlib (embedded original, `dng/original.rs:5,22`), and that dnglab emits
  **DNG only** as a real output (TIFF & raw pixel stream are `analyze`-only debugging aids).
