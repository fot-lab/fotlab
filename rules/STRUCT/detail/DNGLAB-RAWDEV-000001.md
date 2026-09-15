# External module study — how dnglab's develop pipeline cooperates with rawler (`rawler::imgop::develop`)

- ID: DNGLAB-RAWDEV-000001
- Status: Draft
- Priority: P2
- Created: 2026-09-14
- Owner: —
- Related: `DNGLAB-SURVEY-000001` (dnglab workspace / CLI / `dnglab_lib` seam), `DNGLAB-SURVEY-000002` (decode pipeline & `RawImage` contract), `DNGLAB-SURVEY-000003` (camera DB → `RawImage` metadata), `rules/REVIEW/detail/DNGLAB-RAWLER-000001.md` (our binding currently skips every develop step), `rules/STRUCT/detail/FOTLAB-STUDIO-000001.md` (native media pipeline)

> **Note on naming**: per the user's request this study file uses the `DNGLAB-` prefix and the
> six-character `RAWDEV` category (raw-develop) rather than the standard `FOTLAB-STRUCT-NNNNNN`
> ID. It lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/`
> modules as fixed constraints to be documented, not modified.

> **Key correction up front**: an earlier review note (`DNGLAB-RAWLER-000001` §4) described the
> dnglab develop pipeline as "outside our dependency graph". That is inaccurate and this study
> supersedes it on that point: **the develop pipeline is implemented inside the `rawler` crate**
> (`rawler/src/imgop/develop.rs`, exported via `pub mod imgop`). Our first-party crate already
> path-depends on `rawler`, so the full develop stack is reachable without any new dependency —
> `rawler_fotlab` simply never calls it.

## Background & Goal

`DNGLAB-SURVEY-000002` documents rawler's decode side (`RawLoader` → decoder → `RawImage`),
which is the only side `rawler_fotlab` uses today. This study documents the **other half**
shipped inside the same crate: `rawler::imgop::develop`, i.e. the raw-develop pipeline (black/white
normalisation, demosaic, white balance, camera→sRGB colour transform, crop, gamma) and exactly how
the dnglab CLI drives it. Goal: establish the real call graph and the public seam a first-party
binding can use when the develop work recorded in `DNGLAB-RAWLER-000001` is implemented.

All paths below are inside the pinned `external/dnglab` submodule. No upstream source is modified.

## 1. Crate topology — develop lives inside `rawler`, not in `dnglab_lib`

```
external/dnglab/
├── rawler/                         ← decode AND develop in ONE crate
│   └── src/
│       ├── decoders/…              ← decode side (RawLoader, format decoders)
│       ├── rawimage.rs             ← RawImage + apply_scaling() (black/white normalisation)
│       └── imgop/                  ← develop side (pub mod imgop; rawler/src/lib.rs:85)
│           ├── develop.rs          ← RawDevelop, ProcessingStep, process_raw_image()
│           ├── raw.rs              ← CFA blacklevel math, camera→sRGB matrices (SIMD)
│           ├── srgb.rs             ← sRGB gamma
│           ├── chromatic_adaption.rs (Bradford CAT)
│           ├── matrix.rs / xyz.rs / gamma.rs / spline.rs / …
│           └── sensor/
│               ├── bayer/{ppg,bilinear,superpixel}.rs   ← Bayer demosaicers
│               └── xtrans/{bilinear,markesteijn,lab}.rs ← Fuji X-Trans demosaicers
└── bin/dnglab/
    └── dnglab-lib/                 ← thin application layer (CLI, async jobs, file IO)
        └── src/
            ├── process_raw.rs      ← `process-raw` CLI (builds RawProcessingParams, jobs)
            ├── jobs/process_raw.rs ← Raw2Image job: spawn_blocking + writer
            ├── jobs/raw2dng.rs     ← Raw2Dng job (convert path, no develop)
            └── …
```

- `rawler/Cargo.toml` has **no feature gate** around `imgop` (features are only `clap`,
  `inspector`, `rawdb`); the pipeline is compiled into every consumer of the crate.
- `dnglab_lib/Cargo.toml:18` depends on `rawler = { path = "../../../rawler", features = ["clap"] }`.
  The application crate contains **no image-processing math of its own** — every algorithm is in
  `rawler`. Version drift between decoder and developer is structurally impossible: one crate.

## 2. The dnglab CLI has two distinct consumers of rawler

| CLI job | Entry call | Pixel processing | Output |
| --- | --- | --- | --- |
| `convert` / raw→DNG | `rawler::dng::convert::convert_raw_file` (`jobs/raw2dng.rs:12`) | None — sensor data passes through into DNG tags/tiles; develop coefficients are *written as metadata* only | DNG |
| `process-raw` | `rawler::imgop::develop::process_raw_image` (`jobs/process_raw.rs:12,86`) | Full develop pipeline (§4) | 16-bit LZW TIFF |

The `process-raw` CLI wrapper (`dnglab-lib/src/process_raw.rs`) only maps input/output paths and
builds `RawProcessingParams` (`crop`, `thumbnail`, `artist`, `software`, `index`, `keep_mtime`).
The async job (`jobs/process_raw.rs`) wraps the blocking call in `spawn_blocking`
(`jobs/process_raw.rs:134`) and owns file creation / mtime / error cleanup. All pixels are rawler.

## 3. Decode → develop call chain

`rawler::imgop::develop::process_raw_image` (`imgop/develop.rs:56-65`) is the whole story:

```rust
let rawfile  = RawSource::new(raw)?;                 // 1. open bytes
let decoder  = crate::get_decoder(&rawfile)?;        // 2. format dispatch
let rawimage = decoder.raw_image(&rawfile, &raw_params, false)?;   // 3. DECODE  → RawImage
let metadata = decoder.raw_metadata(&rawfile, &raw_params)?;       // 4. EXIF/metadata side channel
let develop  = RawDevelop::default();                // 5. ordered step list
develop.develop(&rawimage, &metadata, image_file)?;  // 6. DEVELOP → TIFF writer
```

- Steps 3–4 are exactly the API our `rawler_fotlab` already uses for `identify` / `decode_to_png`
  (`rawler::decode_dummy` / `rawler::decode`). Develop is therefore a **continuation of the same
  contract**, not a different framework.
- `develop_intermediate(&RawImage) -> Intermediate` (`develop.rs:167`) is the pure in-memory form
  (no file, no EXIF); `develop()` (`develop.rs:332`) calls it and then writes TIFF. A first-party
  caller that wants its own container (PNG/JPEG) should stop at `develop_intermediate`.
- `RawImage` is cloned at the top of `develop_intermediate` (`develop.rs:168`) because the Rescale
  step mutates data in place (u16 → f32).

### Data contract: which `RawImage` fields develop actually consumes

| Field | Used by step | Purpose |
| --- | --- | --- |
| `data`, `cpp`, `width`, `height` | all | pixel buffer; `cpp` selects Mono/3ch/4ch intermediate |
| `photometric` (CFA config: pattern, colors, sensor) | Demosaic | chooses PPG / 4-ch bilinear / X-Trans; non-CFA passes through |
| `blacklevel`, `whitelevel` | Rescale | per-CFA-channel subtraction/normalisation (`rawimage.rs:519`) |
| `wb_coeffs` | Calibrate (gated by WhiteBalance) | as-shot channel multipliers, applied inside the colour map |
| `color_matrix` | Calibrate | XYZ→camera matrix per illuminant; D65 preferred, Bradford otherwise |
| `active_area`, `crop_area` | CropActiveArea / CropDefault | two-stage crop rectangles |
| `fuji_rotation_width`, `camera.find_hint("fuji_rotate_90cw")` | FujiRotate | X-Trans orientation normalisation |
| `clean_make`, `clean_model`, `orientation` | develop() TIFF tags only | not needed for in-memory developing |

`RawMetadata` is used **only** by `develop()` to copy EXIF into the TIFF (`develop.rs:345`); pure
pixel developing (`develop_intermediate`) does not need it.

## 4. The default pipeline — `RawDevelop::default()` (`develop.rs:128-143`)

Eight ordered `ProcessingStep` values (`develop.rs:68-77`); `new_with(&[...])` allows a custom
subset, and several steps are conditional inside later stages:

1. **Rescale** — `RawImage::apply_scaling` (`rawimage.rs:519-541`). CFA path calls
   `correct_blacklevel_cfa` (`imgop/raw.rs:165-190`): per CFA quadrant
   `v' = max(v - black, 0) / (white - black)`, converting the buffer to f32 in **[0.0, 1.0]**;
   black/white levels are then reset to 0/1 to match. `LinearRaw` uses the non-CFA variant;
   `BlackIsZero` is currently `todo!()`.
2. **Demosaic** — only for `photometric = Cfa` and only on the monochrome intermediate
   (`develop.rs:188-228`): RGB Bayer → **PPG** (`PPGDemosaic`, pattern-pixel-group,
   `sensor/bayer/ppg.rs`) → `ThreeColor`; 4-colour CFA → `Bilinear4Channel` → `FourColor`;
   X-Trans → `XTransBilinearDemosaic`. The active-area ROI is passed into demosaic when
   CropActiveArea is enabled. Unknown sensor/CFA combinations hit `todo!()`.
3. **FujiRotate** — `fuji_normalize_rotation` for X-Trans files carrying `fuji_rotation_width`.
4. **CropActiveArea** — folded into the demosaic ROI (and panics if rotation was not normalised).
5. **WhiteBalance** — not a standalone pass: it is a **gate**. In the Calibrate stage the wb
   vector is `wb_coeffs` when this step is present, else forced to `[1.0; 4]`
   (`develop.rs:270-277`). Missing/NaN coefficients default to 1.0.
6. **Calibrate** (`develop.rs:230-286`) — the colour stage:
   - matrix selection: `color_matrix_find_first([D65, A, B, C, D50, D55, D75, Daylight, Flash])`;
     a non-D65 matrix is adapted to D65 with the Bradford CAT (`adapt_bradford`); total miss →
     identity + warning.
   - `cam2rgb = pseudo_inverse(normalize(xyz2cam × SRGB_TO_XYZ_D65))`
     (`imgop/raw.rs:193-217`).
   - per pixel: multiply channels by `wb_coeff` on the fly, apply `cam2rgb`, then
     `clip_euclidean_norm_avg`. `FourColor` collapses to `ThreeColor` here.
   - Monochrome images skip calibration entirely.
7. **CropDefault** — `crop_area` (fallback `active_area`) is applied, adapted to the earlier
   active-area crop, and scaled 0.5 for superpixel debayer (`develop.rs:288-316`).
8. **SRgb** — sRGB transfer function per channel: piecewise linear below the crossover,
   `v^(1/2.4)`-style above (`srgb::srgb_apply_gamma`, `imgop/srgb.rs:31-37`).

## 5. Intermediate representation and the output seam

- `Intermediate` (`develop.rs:82-121`): `Monochrome(PixF32)` | `ThreeColor(Color2D<f32, 3>)` |
  `FourColor(Color2D<f32, 4>)` — normalised **f32** pixels.
- `develop()` converts f32 → u16 (`convert_from_f32_scaled_u16(.., 0, u16::MAX)`) and writes a
  **16-bit LZW-compressed TIFF** with rawler's own `TiffWriter` (`develop.rs:354-405`): 1 sample
  for mono (PhotometricInt=1), 3 for RGB (=2), 4 for the extra-channel case. It does not use the
  `image` crate for output.
- `Intermediate::to_dynamic_image()` (`develop.rs:105-120`) maps the same f32 data to
  `image::DynamicImage` (`ImageLuma16` / `ImageRgb16` / `ImageRgba16`) — the ready-made bridge for
  callers that already use the `image` crate (as `rawler_fotlab` does for PNG).

## 6. Performance characteristics relevant to Android

- Hot loops are `#[multiversion(targets("x86_64+avx+avx2", "x86+sse", "aarch64+neon"))]`
  (e.g. `correct_blacklevel_cfa`, `map_3ch_to_rgb`, `map_4ch_to_rgb`) with `rayon` parallel
  iterators — the Android `arm64-v8a` build selects the **NEON** path; `armeabi-v7a`/x86 fall
  back to scalar code.
- Cost order on a full-sensor image: demosaic + the per-pixel 3×3 matrix dominate; both run over
  the full f32 buffer (12–24 MP × 3 × 4 bytes), i.e. tens–hundreds of MB of transient memory.
- Input/output blocking: `process_raw_image` takes a file `Path`, not bytes; the in-memory seam
  is `get_decoder` + `raw_image` + `develop_intermediate`, which is what a byte-oriented UniFFI
  binding must use.

## 7. Implications for the first-party `rawler_fotlab` binding

Facts only; the implementation decision belongs to a future DESIGN/STRUCT item.

- No new dependency is required: `imgop` is public and ungated in the `rawler` revision we already
  compile. Calling develop changes *which rawler functions we invoke*, not the crate graph.
- The natural port of the current `decode_to_png` (see `DNGLAB-RAWLER-000001`) is:
  `rawler::decode` (already used) → `RawDevelop::default().develop_intermediate(&img)` → take
  `Intermediate::ThreeColor` → encode PNG (8-bit, or 16-bit via `to_dynamic_image`) with the
  `image` crate we already depend on. `process_raw_image` itself is unsuitable: it is
  path-based, needs a `Write + Seek`, and emits TIFF.
- Default steps already implement the exact minimum order recommended in
  `DNGLAB-RAWLER-000001` (black/white → demosaic → WB → camera→sRGB → gamma) plus Fuji rotation
  and crops; reusing them means the camera DB (`DNGLAB-SURVEY-000003`) flows through unchanged.
- Known upstream gaps to handle: `todo!()` arms for `BlackIsZero` rescale and unknown
  sensor/CFA demosaic combinations (would panic; rawler wraps decoder panics but the develop call
  is outside that catch), and matrices that are missing D65 trigger a Bradford path or identity
  fallback. A binding must route these into its existing `RawlerFotlabError` instead of aborting.
- Memory/SIMD: the four-ABI release build grows little in source but the develop f32 buffers are a
  new runtime cost; the NEON multiversion path covers the shipping `arm64-v8a` ABI.

## Constraints (STRUCT.md principle 5)

`external/dnglab` remains a fixed constraint: this study records where the pipeline lives, its
ordered steps, its data contract and its public seam. No change to `rawler` or `dnglab_lib` is
specified or permitted. Whether/when `rawler_fotlab` starts calling `develop_intermediate`, and at
what bit depth it encodes the preview, are first-party decisions for a later item (linked to
`DNGLAB-RAWLER-000001` and `FOTLAB-STUDIO-000001`).

## Change History

- 2026-09-14 — Raw-develop cooperation study. Established that the develop pipeline ships **inside
  `rawler`** at `rawler/src/imgop/` (`pub mod imgop`, no feature gate) rather than in the dnglab
  application layer, correcting `DNGLAB-RAWLER-000001` §4's "outside the dependency graph" note.
  Traced the dnglab `process-raw` chain (`dnglab-lib` jobs/process_raw →
  `imgop::develop::process_raw_image`, `develop.rs:56`) and distinguished it from the raw→DNG
  convert path (`dng::convert::convert_raw_file`, no pixel processing). Documented the
  RawImage-field contract, the eight default steps (Rescale black/white `rawimage.rs:519`; PPG /
  4-ch / X-Trans demosaic; FujiRotate; crops; WB-as-gate; Calibrate D65-first + Bradford +
  cam2rgb pseudo-inverse `raw.rs:193`; sRGB gamma `srgb.rs:31`), the f32 `Intermediate` contract
  with 16-bit LZW TIFF output and the `to_dynamic_image`/`develop_intermediate` seam, NEON
  multiversion/rayon cost, and the first-party integration option that requires no new
  dependency. Filed as `DNGLAB-RAWDEV-000001`; row appended to `rules/STRUCT/index.md`.
