# External module study — dnglab develop pipeline for JPEG output (white balance, demosaic, colour mapping & execution order)

- ID: DNGLAB-PIPELN-000001
- Status: Draft
- Priority: P2
- Created: 2026-09-17
- Owner: —
- Related: `DNGLAB-RAWDEV-000001` (broader cooperation study: where `rawler::imgop::develop` lives and the public seam), `DNGLAB-RAWLER-000001` (our binding currently skips every develop step), `FOTLAB-STUDIO-000001` (native media pipeline, R4 demands a true develop), `RAWTRP-PIPELN-000001` (RawTherapee develop pipeline — cross-reference on the RGB-working→Lab vs linear-RGB model)

> **Note on naming**: per the user's request this study uses the `DNGLAB-` prefix and the six-character `PIPELN` category (develop pipeline). It lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified.

> **Scope**: this is the **focused** companion to `DNGLAB-RAWDEV-000001`. That study establishes *where* the develop pipeline lives and the public seam; this one documents the **exact execution order** of the default pipeline and the three stages the user called out — **white balance, demosaic, colour mapping/conversion** — in the context of producing a **JPEG** (dnglab itself emits TIFF; the JPEG path is a downstream encoder over the same intermediate, see §1). All paths are inside `external/dnglab/rawler/src/imgop/` unless noted.

## 1. JPEG output seam — dnglab emits TIFF, the JPEG encoder is downstream

dnglab has **no native JPEG writer**. Two output sinks exist in `rawler`:

- `RawDevelop::develop()` (`develop.rs:332-408`) writes a **16-bit LZW-compressed TIFF** via rawler's own `TiffWriter` (`develop.rs:354-405`). This is what the dnglab `process-raw` CLI uses.
- `RawDevelop::develop_intermediate()` (`develop.rs:167`) returns an in-memory `Intermediate` (f32, `develop.rs:82-86`). `Intermediate::to_dynamic_image()` (`develop.rs:105-120`) converts that to an `image::DynamicImage` (`ImageLuma16`/`ImageRgb16`/`ImageRgba16`).

**A JPEG is therefore produced by a consumer of `Intermediate`/`to_dynamic_image`, not by dnglab.** Our first-party binding `rawler_fotlab` already depends on the `image` crate (`lib.rs:28` `PngEncoder`); emitting JPEG means calling `image::codecs::jpeg::JpegEncoder` on the same `DynamicImage` instead of `PngEncoder` (`lib.rs:139`). The develop math (WB, demosaic, colour) is **100% rawler**; only the container differs. This is why the "JPEG develop pipeline" is really "the rawler develop pipeline, then an `image`-crate JPEG encode."

## 2. The ordered pipeline — order is hardcoded, not Vec-driven

The default step set is declared as a `Vec<ProcessingStep>` in `RawDevelop::default()` (`develop.rs:128-143`):

```rust
steps: vec![
    Rescale, Demosaic, FujiRotate, CropActiveArea,
    WhiteBalance, Calibrate, CropDefault, SRgb,
]
```

**Critical ordering fact**: the order is **not** produced by iterating that `Vec` at runtime. `develop_intermediate` runs a *fixed* sequence of `if self.steps.contains(&ProcessingStep::X)` guards (`develop.rs:169, 188, 230, 288, 318`), and `FujiRotate`/`CropActiveArea` are checked *inside* the Demosaic block (`develop.rs:192, 205`) while `WhiteBalance` is checked *inside* Calibrate (`develop.rs:275`). The default `Vec` merely mirrors that fixed code order; `RawDevelop::new_with(&[...])` can *enable/disable* steps but **cannot reorder** them, because the code sequence is the single source of truth. (Note also that `FujiRotate`/`CropActiveArea`/`WhiteBalance` never appear as standalone passes in the code — they are folded into Demosaic/Calibrate.)

Effective linear order for a **Bayer CFA colour image** with default steps:

| # | Stage (fixed code order) | Executed at | Runs when | What it does |
| --- | --- | --- | --- | --- |
| 1 | **Rescale** | `develop.rs:169` | always (if enabled) | black/white normalisation → `RawImage::apply_scaling` → `correct_blacklevel_cfa` (`raw.rs:165-190`); buffer becomes f32 in **[0,1]** |
| 2 | **Demosaic** | `develop.rs:188-228` | `photometric == Cfa` | Bayer RGB → **PPG** (`PPGDemosaic`); 4-colour CFA → `Bilinear4Channel`; X-Trans → `XTransBilinearDemosaic`. Runs on **linear** data, *before* any WB/colour |
| 2a | FujiRotate | `develop.rs:205` | inside Demosaic, X-Trans + `fuji_rotation_width` | orientation normalisation |
| 2b | CropActiveArea | `develop.rs:192` | inside Demosaic, if enabled | ROI folded into demosaic |
| 3 | **WhiteBalance** (gate, *not a pass*) | `develop.rs:270-277` | consumed in Calibrate | if the `WhiteBalance` step is absent, `wb` is forced to `[1,1,1,1]`; missing/NaN `wb_coeffs` default to 1.0 |
| 4 | **Calibrate** (colour mapping) | `develop.rs:230-286` | not `Monochrome` | D65-first matrix + Bradford CAT, then per-pixel `wb × cam2rgb` (`raw.rs:193/220`) |
| 5 | CropDefault | `develop.rs:288-316` | if `crop_area`/`active_area` set | final crop (adapts to active-area; 0.5× for superpixel) |
| 6 | **SRgb** (gamma) | `develop.rs:318-324` | always (if enabled) | sRGB transfer function — the **only** non-linear stage; runs **last** |

**Ordering rules that matter for the three named stages**:
- **Demosaic (step 2) precedes WB + colour.** The demosaic itself uses no WB; it operates on linear CFA data.
- **WB and colour mapping share one stage (Calibrate, step 4).** WB is applied as a per-channel multiply *immediately before* the colour matrix, inside `map_3ch_to_rgb`/`map_4ch_to_rgb`.
- **sRGB gamma (step 6) is the final, non-linear stage.** Everything before it (Rescale, Demosaic, Calibrate colour) is linear-light.
- **Monochrome** images skip Calibrate entirely (`develop.rs:282` `Intermediate::Monochrome(_) => intermediate`) — i.e. no WB and no colour mapping; they still get Rescale + SRgb.

## 3. White balance — a per-channel multiplier applied inside Calibrate

- Source value: `RawImage::wb_coeffs` (as-shot multipliers), read in `develop.rs:270-274`. If `NaN` → `[1,1,1,1]`; if the `WhiteBalance` step is not in the set → forced to `[1,1,1,1]` (`develop.rs:275-277`).
- Application: per-pixel, **before** the colour matrix, in `raw.rs`:
  - 3-channel: `r = pix[0]*wb[0]; g = pix[1]*wb[1]; b = pix[2]*wb[2];` (`raw.rs:204-206`)
  - 4-channel: a 4th term `pix[3]*wb[3]` (`raw.rs:231-234`)
- **No chromatic adaptation / CAT is applied for WB itself** — it is a plain scalar channel gain (as-shot). Any illuminant adaptation happens in the colour-matrix selection (§4), not here.
- This is a key difference from RawTherapee (`RAWTRP-PIPELN-000001`): RT's WB is also a multiplier but feeds a full colour-management chain (camera ICC/DCP → working space → output), whereas rawler's WB is a single gain into a fixed camera→sRGB matrix.

## 4. Colour mapping / conversion — the body of Calibrate

`develop.rs:230-286` selects the conversion matrix, then `raw.rs:193/220` apply it. The algorithm:

1. **Matrix selection** (`develop.rs:233-268`): `color_matrix_find_first([D65, A, B, C, D50, D55, D75, Daylight, Flash])`. If the chosen illuminant is **not D65**, it is Bradford-adapted to D65 via `adapt_bradford` (`chromatic_adaption.rs`); a total miss falls back to identity + warning. The selected `xyz2cam` is a 3×4 (or 4×4) camera→XYZ matrix.
2. **Camera→sRGB matrix** (`raw.rs:194-195`):
   ```rust
   let rgb2cam = normalize(multiply(&xyz2cam, &SRGB_TO_XYZ_D65));
   let cam2rgb = pseudo_inverse(rgb2cam);
   ```
   i.e. `cam2rgb = pinv( normalize(xyz2cam · SRGB_TO_XYZ_D65) )` — maps camera-linear RGB to sRGB-linear RGB.
3. **Per-pixel** (`raw.rs:207-212` / `235-240`): `srgb = cam2rgb · [r,g,b]` (where r,g,b already include the WB gain from §3), then `clip_euclidean_norm_avg` (`raw.rs:72-85`) — clips negatives to 0 and, if any channel exceeds 1.0, averages the colour-normalised pixel with its euclidean norm to retain hue while preventing overflow.
4. 4-channel input is **collapsed to 3** here (`develop.rs:284` `map_4ch_to_rgb → ThreeColor`).

Net effect: one 3×3 (or 3×4) matrix multiply per pixel, preceded by the WB gain. Hot loops are `#[multiversion(targets("x86_64+avx+avx2","x86+sse","aarch64+neon"))]` (e.g. `map_3ch_to_rgb`, `raw.rs:192`) with `rayon` parallel iterators — the `arm64-v8a` Android build takes the NEON path.

## 5. Data model — linear f32 all the way to the gamma

- `Intermediate` (`develop.rs:82-86`): `Monochrome(PixF32)` | `ThreeColor(Color2D<f32,3>)` | `FourColor(Color2D<f32,4>)` — **f32, linear, normalised [0,1]** (until SRgb).
- `map_3ch_to_rgb` returns `RgbF32` (`raw.rs:216`). The intermediate stays f32 through Calibrate and CropDefault; only `SRgb` (develop.rs:318) and the final `to_dynamic_image`/`develop` f32→u16 scale (develop.rs:108-117, 356+) move it to integer.
- Contrast with RawTherapee (`RAWTRP-PIPELN-000001` §4): RT keeps an `Imagefloat` (RGB in a working space) and a `LabImage` (CIELAB) with explicit RGB↔Lab round-trips; rawler stays in **linear RGB f32** the entire develop and applies a single sRGB gamma at the end. No working-profile, no Lab, no CIECAM, no tone curve — a deliberately minimal "render to sRGB" path.

## 6. Implications for our JPEG output (first-party `rawler_fotlab`)

Facts only; the implementation decision belongs to a future DESIGN/STRUCT item (linked to `DNGLAB-RAWLER-000001`, `FOTLAB-STUDIO-000001`).

- The develop is rawler's and **order-correct by construction** — calling `RawDevelop::default().develop_intermediate(&img)` yields a fully WB'd, demosaiced, camera→sRGB, gamma-correct raster (the exact minimum order our `DNGLAB-RAWLER-000001` recommendation asked for: black/white → demosaic → WB → colour → gamma). Our current `decode_to_png` (`lib.rs:88`) deliberately skips all of this (`lib.rs:111-143` `encode_png` does a bare `v>>8`).
- To produce **JPEG**, stop at `develop_intermediate`, call `Intermediate::to_dynamic_image()` (`develop.rs:105`), and hand the `DynamicImage` to `image::codecs::jpeg::JpegEncoder` instead of `PngEncoder` (`lib.rs:139`). No develop change needed — only the encoder and bit-depth choice (8-bit JPEG takes the existing `shrink_f32` path in `lib.rs:151`).
- Upstream gaps to route into `RawlerFotlabError` rather than abort (see `DNGLAB-RAWDEV-000001` §7): `todo!()` in `develop_intermediate` for `cpp != 1/3/4` (`develop.rs:185`); `unimplemented!()` for non-3×3 Bradford matrices (`develop.rs:257`); `todo!()` demosaic fallthrough for unknown CFA/sensor (`develop.rs:220`). These panic across FFI, so the existing `catch_unwind` hardening (`lib.rs:92`) must wrap the develop call too.
- **Ordering guarantee when customising**: because order is hardcoded in `develop_intermediate` (§2), any first-party trimming (e.g. disabling SRgb to keep linear, or disabling WhiteBalance for a "no-WB" preview) is done by *omitting steps*, never by reordering. WB/colour always stay bound together in Calibrate.

## Constraints (STRUCT.md principle 5)

`external/dnglab` remains a fixed constraint: this study records the develop execution order and the WB/demosaic/colour stages for JPEG output, plus the `to_dynamic_image` → `image`-crate JPEG seam. No change to `rawler` or `dnglab_lib` is specified or permitted. Whether/when `rawler_fotlab` switches `decode_to_png` to a develop-backed JPEG is a first-party decision for a later item.

## Change History

- 2026-09-17 — dnglab develop-pipeline study focused on JPEG output. Established that dnglab has **no native JPEG encoder**: `develop()` writes 16-bit LZW TIFF (`develop.rs:332-408`) and the JPEG path is `develop_intermediate` (`develop.rs:167`) → `Intermediate::to_dynamic_image` (`develop.rs:105`) → `image`-crate JPEG encoder (our `rawler_fotlab` already uses `image` at `lib.rs:28,139`). Documented the **fixed execution order** (not Vec-driven): `develop_intermediate` runs hardcoded `if self.steps.contains(...)` guards in the sequence Rescale (`develop.rs:169`) → Demosaic (`188`, with nested FujiRotate `205` / CropActiveArea `192`) → Calibrate (`230`) → CropDefault (`288`) → SRgb (`318`); `WhiteBalance` is a gate consumed inside Calibrate (`develop.rs:275`), `FujiRotate`/`CropActiveArea` folded into Demosaic. Detailed the three named stages: **demosaic** runs first on linear CFA data (PPG / Bilinear4Channel / XTransBilinear); **white balance** is a per-channel gain applied inside `map_3ch_to_rgb`/`map_4ch_to_rgb` (`raw.rs:204-206, 231-234`) immediately before the colour matrix; **colour mapping** = D65-first matrix (+ Bradford CAT on non-D65, `develop.rs:233-268`) then `cam2rgb = pinv(normalize(xyz2cam · SRGB_TO_XYZ_D65))` per pixel with `clip_euclidean_norm_avg` (`raw.rs:194-195, 212`). Noted sRGB gamma is the sole non-linear, last stage (`srgb.rs:31-37`), the f32-linear data model, and the contrast with RawTherapee's RGB-working→Lab model. Filed as `DNGLAB-PIPELN-000001`; row appended to `rules/STRUCT/index.md`.
