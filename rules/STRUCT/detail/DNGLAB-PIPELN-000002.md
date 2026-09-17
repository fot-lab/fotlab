# External module study — demosaic algorithms: dnglab vs RawTherapee

- ID: DNGLAB-PIPELN-000002
- Status: Draft
- Priority: P2
- Created: 2026-09-17
- Owner: —
- Related: `rules/STRUCT/detail/DNGLAB-PIPELN-000001.md` (dnglab develop pipeline for JPEG output — §2 documents the runtime Demosaic stage), `rules/STRUCT/detail/DNGLAB-RAWDEV-000001.md` (dnglab `rawler::imgop::develop` cooperation — §2 lists the crate's demosaic implementations), `rules/STRUCT/detail/RAWTRP-PIPELN-000001.md` (RawTherapee develop pipeline — §3 Stage A documents the demosaic dispatch), `rules/STRUCT/detail/DNGLAB-RAWLER-000001.md` (our current `rawler_fotlab` decode emits an unprocessed dump)

> **Note on naming**: per the user's request this study uses the `DNGLAB-` project code (dnglab) and the six-character `PIPELN` category (develop pipeline). It lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified.

> **Scope**: this is a **focused cross-comparison of the demosaicing (去马赛克) stage** between dnglab's `rawler::imgop::develop` and RawTherapee's `RawImageSource::demosaic`. It isolates one pipeline stage that the two engines differ on sharply: dnglab hardcodes a single per-CFA algorithm with no user selector, whereas RawTherapee exposes a large, user-selectable algorithm set. The value is a completeness/fidelity checklist for whatever develop we wire into `rawler_fotlab` (today it does none — `DNGLAB-RAWLER-000001`). This is a **reference comparison, not a migration proposal** (RawTherapee is GPL-3.0 C++ read-only per `FOTLAB-NATIVE-000001`).

## 1. Background & Goal

Both engines demosaic *after* black/white rescale and *before* white balance + colour (confirmed in `DNGLAB-PIPELN-000001` §2 and `RAWTRP-PIPELN-000001` §3). The question this study answers is narrower: **how many demosaic algorithms does each engine make available, and can the user choose among them?**

The answer reveals a structural gap in dnglab: it *implements* several demosaicers in `rawler/src/imgop/sensor/` but its `develop` pipeline calls only **one per CFA type with no selection mechanism**.

## 2. dnglab — what is actually used vs what is implemented

### 2.1 What `develop_intermediate` actually calls (runtime, hardcoded)
The demosaic block in `rawler/src/imgop/develop.rs:188-228` branches on `rawimage.photometric` and instantly constructs a fixed algorithm — there is **no enum, no `dmethod` equivalent, no user option**:

| CFA configuration | Algorithm constructed | Location |
| --- | --- | --- |
| Bayer RGB (`is_rgb() && SensorType::Bayer`) | `PPGDemosaic` | `develop.rs:201-202` |
| 4-colour CFA (`unique_colors() == 4 && Bayer`) | `Bilinear4Channel` | `develop.rs:214-215` |
| X-Trans (`SensorType::Xtrans`) | `XTransBilinearDemosaic` | `develop.rs:217-218` |
| anything else | `todo!()` (panics across FFI) | `develop.rs:220` |

Conclusion: in the dnglab develop path, the **selectable count is 0**. Bayer is always PPG, X-Trans is always Bilinear; the algorithm is chosen by sensor type, not by the caller.

### 2.2 What the crate *implements but does not wire into develop*
`rawler/src/imgop/sensor/` contains more demosaic implementations than `develop.rs` uses — the surplus ones are currently **dead code from the develop path**:

- **Bayer** (`sensor/bayer/`):
  - `ppg.rs` — `PPGDemosaic` *(used)*
  - `bilinear.rs` — `BilinearDemosaic` *(implemented, not called by `develop.rs`)*
  - `superpixel.rs` — `Superpixel3Channel` / `Superpixel4Channel` *(implemented, not called)*
- **X-Trans** (`sensor/xtrans/`):
  - `bilinear.rs` — `XTransBilinearDemosaic` *(used)*
  - `markesteijn.rs` — `XTransMarkesteijnDemosaic` (1-pass / 3-pass) *(implemented, not called)*
  - `lab.rs` — `XTransLabDemosaic` *(implemented, not called)*

So the crate defines **3 Bayer + 3 X-Trans = 6 demosaic families**, but only **2 of them (PPG, XTransBilinear) are reachable** from `develop_intermediate`. The quality-tiered ones (Markesteijn, Lab, Bayer Bilinear/Superpixel) exist as code but are unreachable without first-party changes to `develop.rs`.

### 2.3 Quality tier
All algorithms actually wired in are the *simple* tier: PPG (pattern-pixel-group, gradient-based but not directional) and Bilinear. There is **no high-quality directional/adaptive demosaic** (e.g. AHD/AMAzE/RCD/LMMSE class) anywhere in the reachable path.

## 3. RawTherapee — user-selectable algorithm set

RawTherapee dispatches demosaic in `RawImageSource::demosaic` (`rawimagesource.cc:1796`) via `params->raw.dmethod` (Bayer) / `params->raw.xtransmethod` (X-Trans). The available, fully-wired algorithms (from `RAWTRP-PIPELN-000001` §3 Stage A) are:

- **Bayer (user-selectable, 11)**: AHD, AMAzE, VNG4, LMMSE, RCD, EAHD, HPHD, IGV, bilinear, dual, fast — each implemented in its own `rtengine/*_demosaic*.cc` file (`ahd_demosaic_RT.cc`, `amaze_demosaic_RT.cc`, `rcd_demosaic.cc`, `lmmse_demosaic.cc`, `bayer_bilinear_demosaic.cc`, `dual_demosaic_RT.cc`, `fast_demo.cc`, …).
- **X-Trans (user-selectable)**: Markesteijn (1-pass / 3-pass), bilinear, plus X-Trans variants.
- Surrounding passes: `green_equil_RT.cc` (green-channel equalisation) and `cfa_linedn_RT.cc` (linenoise / chromatic aberration) run around the demosaic.

RawTherapee therefore exposes a **large, user-driven algorithm menu spanning simple → high-quality adaptive** demosaicing, with a real selection mechanism (`dmethod`).

## 4. Side-by-side comparison

| Dimension | dnglab | RawTherapee |
| --- | --- | --- |
| Algorithms **selectable by the user** in the develop path | **0** (hardcoded by CFA type) | **11 (Bayer) + X-Trans** via `dmethod`/`xtransmethod` |
| Algorithms **implemented in the codebase** | 6 (3 Bayer + 3 X-Trans; 4 of them unreachable from `develop`) | 11+ (all reachable) |
| Selection mechanism | none (no enum; `if sensor == Xtrans` branch) | `params->raw.dmethod` / `xtransmethod` dispatch |
| Algorithms **actually wired into develop** | PPG (Bayer), XTransBilinear (X-Trans) | all 11+ via dispatch |
| Quality tier available | simple only (PPG, Bilinear) | simple → high-quality (AHD/AMAzE/RCD/LMMSE …) |
| Unknown sensor/CFA handling | `todo!()` panic (`develop.rs:220`) | per-algorithm fallback variants |

**Count summary**:
- *Implemented* families — dnglab **6** vs RawTherapee **11+**.
- *Usable/selectable* in develop — dnglab **2** (and non-selectable) vs RawTherapee **11+** (user-selectable).

## 5. Implications for our `rawler_fotlab` develop work

Facts only; the implementation decision belongs to a later DESIGN/STRUCT item (linked to `DNGLAB-RAWLER-000001`, `DNGLAB-PIPELN-000001`, `FOTLAB-STUDIO-000001`).

- dnglab's develop demosaic is **correct but minimal**: PPG is a reasonable default, but there is no way for our Studio (R4: "true developed image") to upgrade to a higher-quality demosaic without first-party work.
- To reach RawTherapee-class quality we would have to (a) add a demosaic **selector** (an enum on `RawDevelop`) and (b) wire the already-existing-but-unused `XTransMarkesteijnDemosaic` / `XTransLabDemosaic` / Bayer `Bilinear`/`Superpixel` into `develop.rs`'s branch logic. This contradicts `DNGLAB-PIPELN-000001` §2's note that develop order is hardcoded and order/selection cannot be changed without editing the code sequence.
- The `todo!()` at `develop.rs:220` for unknown CFA/sensor is an FFI hazard (panics across the UniFFI boundary); any demosaic expansion must route that into `RawlerFotlabError` rather than abort.
- Licensing/feasibility: adopting RawTherapee's demosaicers is not on the table (GPL-3.0 C++, read-only `external/`). Deepening the dnglab binding is the first-party path.

## 6. Pre-demosaic data structures: dnglab/rawler vs RawTherapee

This section isolates the buffer that exists **after the initial RAW load + black/white rescale, but before demosaic** — the data structure each engine feeds into its demosaicers — and what that implies for swapping/plugging demosaic algorithms.

### 6.1 dnglab/rawler — `RawImage` (decode output) → `Intermediate::Monochrome(PixF32)` (post-rescale) + separate CFA metadata

**Stage 0 — `RawImage`: the initial-load structure (decode output, pre-demosaic, pre-rescale).**
- The decoder emits a `RawImage` (`rawimage.rs:202-252`) that carries the pixel buffer **and all develop metadata together**:
  - `data: RawImageData` (`rawimage.rs:243, 256-261`): the mosaic itself, as `RawImageData::Integer(Vec<u16>)` for almost all formats or `RawImageData::Float(Vec<f32>)` for some DNGs; length `width*height*cpp`, **one scalar per photosite** (still the Bayer/X-Trans CFA, not yet colour).
  - `width` / `height` / `cpp` (`rawimage.rs:214-218`): `cpp == 1` for Bayer (mosaic), `3` for RGB images.
  - `blacklevel: BlackLevel`, `whitelevel: WhiteLevel` (`rawimage.rs:224-226`) — per-channel, consumed by Rescale.
  - `wb_coeffs: [f32;4]` (`rawimage.rs:222`), `color_matrix: HashMap<Illuminant, FlatColorMatrix>` (`rawimage.rs:245`) — consumed later by Calibrate, **not** by demosaic.
  - `photometric: RawPhotometricInterpretation` (`rawimage.rs:230`) — the CFA config (`CFA` + `PlaneColor` + `SensorType`).
  - `active_area` / `crop_area` / `fuji_rotation_width` / `blackareas` / `orientation`.
- Key point: at Stage 0 the pixels are **still in native integer/raw range**; black/white normalization has **not** happened yet. The CFA pattern is **not stored inside the pixel buffer** — it lives alongside the data in `photometric`.

**Stage 1 — `Intermediate::Monochrome(PixF32)`: the pre-demosaic buffer actually fed to demosaic.**
- `develop_intermediate` clones the `RawImage`, runs `apply_scaling` (Rescale, `develop.rs:169-171`) which normalizes `data` in place to f32 in **[0,1]**, then wraps `rawimage.data.as_f32()` into `Intermediate::Monochrome(PixF32)` (`develop.rs:173-186`): `cpp == 1` → `Intermediate::Monochrome(PixF32)`.
- `PixF32 = Pix2D<f32>` (`pixarray.rs:42-50`): a **single packed `Vec<f32>`** of length `width*height`, row-major, **one scalar per photosite**.
- The CFA pattern remains **outside the pixel buffer**, in `RawImage.photometric`.
- The demosaic **trait** boundary (`sensor/mod.rs:55`): `fn demosaic(&self, pixels: &PixF32, cfa: &CFA, colors: &PlaneColor, roi: Rect) -> Color2D<f32, N>`. Input = mosaic buffer **plus the CFA/colors passed explicitly**; output = `Color2D<f32, N>` (`pixarray.rs:354-362` — a single `Vec<[f32; N]>`, i.e. `N` interleaved planes). Demosaic transitions `Monochrome → ThreeColor`/`FourColor`.

### 6.2 RawTherapee — `array2D<float> rawData` + class-member CFA
- After `preprocess`, `RawImageSource` holds `array2D<float> rawData;` — *"holds preprocessed pixel values, rowData[i][j] corresponds to the ith row and jth column"* (`rawimagesource.h:86`). This is a **single 2D float array**, one value per photosite — the same conceptual mosaic as dnglab's `PixF32`. Multi-frame / pixel-shift variants: `rawDataFrames` / `rawDataBuffer` (`rawimagesource.h:87-88`).
- Value range: `float` in **[0, 65535]** scale (RT's `Imagefloat` convention, `imagefloat.h:35`), **not** normalized to [0,1] like dnglab.
- CFA metadata is carried as a **class member** of the image source (`cfa` / `sensorOffset`) and is read *implicitly* by the demosaic methods. The demosaic signatures take only `(const array2D<float> &rawData, array2D<float> &red, array2D<float> &green, array2D<float> &blue, …)` (`rawimagesource.h:278-288, 303-308`) — **no CFA parameter**; each method is CFA-specific (`ahd_demosaic` vs `xtrans_interpolate`).
- Output: **three separate `array2D<float>` planes** (`red`/`green`/`blue`) passed by reference — split-planar, unlike dnglab's single interleaved `Color2D`.

### 6.3 Structural differences summary

| Aspect | dnglab/rawler | RawTherapee |
| --- | --- | --- |
| Pre-demosaic buffer | `PixF32` (single `Vec<f32>`, one value/photosite) | `array2D<float> rawData` (single 2D float, one value/photosite) |
| Value range | f32 in **[0,1]** (after Rescale) | float in **[0,65535]** |
| CFA pattern storage | explicit `CFA`+`PlaneColor`+`SensorType` in `RawImage.photometric` | class member `cfa`/`sensorOffset` on the image source |
| CFA passed to demosaic | **explicit argument** (`cfa`, `colors`) in the `Demosaic` trait | **implicit** (method reads `this->cfa`); no CFA param |
| Demosaic output | single `Color2D<f32,N>` (`N` interleaved planes) | three separate `array2D<float>` planes (red/green/blue) |
| Algorithm interface | uniform `Demosaic<T,N>` trait (all 6 impls share one signature) | distinct member methods per algorithm (no common trait) |
| Bayer vs X-Trans | same trait, branched on `SensorType` | distinct method sets (`ahd_demosaic` vs `xtrans_interpolate`) |

### 6.4 Feasibility of cross-code / pluggable demosaic invocation

**(a) Within dnglab — swap among its own 6 implementations: feasible, low cost.**
- **Architectural observation (the good part):** every dnglab demosaicer — `PPGDemosaic`, `BilinearDemosaic`, `Superpixel3Channel`/`Superpixel4Channel`, `XTransBilinearDemosaic`, `XTransMarkesteijnDemosaic`, `XTransLabDemosaic` — is a **standalone `pub` struct implementing the single `Demosaic<T,N>` trait** (`sensor/mod.rs:55`), independently reachable as `rawler::imgop::sensor::bayer::*` / `xtrans::*` (the module chain is `pub`, `lib.rs:85`). `RawDevelop::develop_intermediate` does **not** own or embed any algorithm; it merely *constructs one* (`PPGDemosaic::new()` at `develop.rs:201`) and calls `.demosaic(...)`. The orchestrator is therefore **decoupled from algorithm code** — it "picks one" (PPG by default), but the algorithms are composable units that can also be invoked directly without `RawDevelop` at all (e.g. `Superpixel3Channel::new().demosaic(&pix, &cfa, &colors, roi)`). This is exactly why swapping algorithms is a one-line change, not an interface rewrite.
- All six demosaicers implement the same `Demosaic<T,N>` trait (`sensor/mod.rs:55`) with an identical `demosaic(pixels, cfa, colors, roi) -> Color2D` signature, and the CFA is passed explicitly — so the *same* mosaic buffer works for any algorithm (Bayer or X-Trans). Swapping is a one-line `let algo = …; algo.demosaic(…)` change.
- Blocker today: `develop.rs:188-228` hardcodes `if Bayer → PPG; if Xtrans → XTransBilinear` with **no selector enum**, and `todo!()` at `develop.rs:220` panics on unknown CFA. Fix = add a `Demosaic` selector to `RawDevelop`, branch on it, and route the `todo!()` arm into `RawlerFotlabError`.
- Caveats when wiring the 4 currently-unused impls: `Superpixel*Channel` emits a **1/4-size** (downscaled) image, not 1:1; `XTransMarkesteijnDemosaic` takes a `passes` param (1 vs 3); Bayer `Bilinear` / `XTransLabDemosaic` must have their in/out buffer contract re-checked against the `Intermediate::Monochrome → ThreeColor` transition.

**(b) Cross-codebase (dnglab ⇄ RawTherapee): not feasible.**
- RT's demosaics are **C++ member functions of `RawImageSource`**, tightly coupled to class state (`cfa`, `sensorOffset`, dimensions, `params`) and to the `array2D<float>` convention; they are not standalone, reusable units with a clean buffer ABI. Calling them from Rust/dnglab would need either a C-ABI shim over GPL-3.0 C++ (license + `STRUCT.md` principle 5 read-only constraint violated) or a full reimplementation.
- Buffer mismatch compounds this: RT is `[0,65535]` float split into 3 planes; dnglab is `[0,1]` interleaved `Color2D` — conversion on both sides would be required.
- RawTherapee is GPL-3.0 and `external/` is read-only (`FOTLAB-NATIVE-000001`), so adopting its algorithms is out of scope. The realistic path to higher-quality demosaic is first-party Rust work on dnglab's own trait (option a), or implementing AHD/AMAzE-class algorithms anew.

**(c) Takeaway.** dnglab's trait-based, CFA-explicit design is *structurally* the easier one to make pluggable — the work is a selector plus a branch, not an interface rewrite. RT's richness (11+ selectable vs dnglab's 2 reachable) is a *content* gap, not a *structural* one for dnglab; the algorithms themselves are locked behind C++ class coupling and licensing, so cross-code invocation is not a viable route.

## 7. Demosaic→Calibrate boundary, the `colors` guardrail, and provenance of core parameters

This section records three follow-on findings: (a) how the demosaic and calibrate steps relate, (b) what the `colors` (`PlaneColor`) parameter is and how it guards the demosaic, and (c) where every core parameter passed through the pipeline actually comes from and what it does. All line refs are in `rawler/src/`.

### 7.1 Demosaic and Calibrate — relationship (three layers)

- **Structural independence.** They are two separate `if self.steps.contains(&ProcessingStep::…)` blocks inside `develop_intermediate` — Demosaic (`develop.rs:188`) and Calibrate (`develop.rs:230`) — each gated by an independent `ProcessingStep` flag and reading different `rawimage` fields (demosaic: `cfa`/`colors`/`active_area`/`fuji_rotation_width`; calibrate: `color_matrix`/`wb_coeffs`). They can be toggled independently.
- **Strict data-flow dependency (not interchangeable).** Demosaic produces `Intermediate::ThreeColor`/`FourColor` (`develop.rs:212/215/218`); Calibrate consumes exactly that (`develop.rs:281-285`). Calibrate is a **no-op on `Monochrome`** (`develop.rs:282`) — i.e. without demosaic (or on a natively monochrome image) Calibrate does nothing. White balance + colour matrix are per-pixel RGB operations that require the full demosaiced image, so Calibrate must run *after* demosaic; the order cannot be swapped.
- **Algorithmic decoupling (the pluggability hinge).** Calibrate does **not** care *which* demosaic algorithm produced the pixels — it only consumes the `Intermediate` data structure. Swapping PPG↔AMAzE↔RCD changes only the `Intermediate` contents, not Calibrate. **This `Intermediate` handoff is the clean interface that makes cross-algorithm (and, modulo licensing, cross-code) demosaic plugging feasible** (cf. §6.4(a)).
- **Caveat — the "Demosaic" step name is a misnomer.** The Demosaic `if` block also folds in `CropActiveArea` (computed into the demosaic ROI, `develop.rs:192-199`) and `FujiRotate` (applied immediately after the demosaic call, same block, `develop.rs:205-211`). Geometry (active-area crop, Fuji rotation) is therefore resolved *before* the pixels reach Calibrate; Calibrate itself only sees already-rotated/cropped RGB.
- **Whole-pipeline note.** `develop_intermediate` (`develop.rs:167-327`) actually contains the *entire* pipeline — Rescale → Demosaic(+FujiRotate+CropActiveArea) → Calibrate(WB+colour fused) → CropDefault (`develop.rs:288-316`) → SRgb (`develop.rs:318-324`). `develop()` (`develop.rs:332-336`) only serializes the resulting `Intermediate` to TIFF; **nothing colour-related happens after `develop_intermediate` returns.**
- **WB + colour are fused, not two passes.** In Calibrate, `wb_coeffs` and `xyz2cam` are both fed to a single `map_3ch_to_rgb`/`map_4ch_to_rgb` call (`develop.rs:283-284`); there is no separate "WB then colour" sequence. If the `WhiteBalance` step is off, `wb` is neutralized to `[1,1,1,1]` but the colour matrix still applies (`develop.rs:275-277`).

### 7.2 The `colors` parameter — what it is and how it guards

- **Type.** `colors: PlaneColor` (`rawimage.rs:177`), defined in `cfa.rs:305-308` as `{ colors: Vec<CFAColor> }` — a per-plane → `CFAColor` assignment table (R,G,B for 3-plane; R,G,B,Emerald for 4-colour Bayer).
- **Provenance.** `CFAConfig.colors` ← `cam.plane_color`, parsed from the camera-DB `"plane_color"` string (e.g. `"RGGB"`/`"RGBE"`, `camera.rs:187-189`; default `PlaneColor::default()`, `camera.rs:269`). For DNG it is derived from the file's own plane-colour metadata during decode. In `develop.rs` it arrives as `config.colors` from `RawPhotometricInterpretation::Cfa(config)` and is passed as the 3rd argument to every demosaic call (`develop.rs:202/215/218`).
- **What it is used for.**
  - *Plane-count validation / 3-vs-4 dispatch*: `Superpixel3Channel` asserts `colors.plane_count() == 3` (`superpixel.rs:29-31`); `Superpixel4Channel` and `Bilinear4Channel` assert `== 4` (`superpixel.rs:91-93`, `bilinear.rs:32-34`).
  - *Plane→CFA position mapping* (only Superpixel actually consumes it): `superpixel.rs:102` `let colormap = colors.plane_colors::<4>().map(|c| PlaneColor::cfa_index(&cfa, c));` — tells the algorithm which raw sub-pixel in the 2×2 superpixel is R/G/B/Emerald.
  - *Ignored* in `PPGDemosaic` (`_colors`, `ppg.rs:37`), `XTransBilinearDemosaic` (`_colors`, `bilinear.rs:49`), `XTransMarkesteijnDemosaic` (`_colors`, `markesteijn.rs:76`) — those consult only `cfa`.
- **Key clarification — `colors` vs `cfa`.** `cfa: CFA` is the *per-pixel* spatial filter pattern (RGGB 2×2, X-Trans 6×6…) and is what actually drives interpolation. `colors: PlaneColor` is the *per-plane* channel descriptor and is essentially a metadata guardrail. Among the three algorithms **actually wired into `develop_intermediate`**, `colors` is **ignored** by PPG and XTransBilinear and only `plane_count`-checked by Bilinear4Channel; its only real algorithmic use is in Superpixel — which is *not* called by `develop` (dead code, §2.2).

### 7.3 Provenance & role of every core parameter

| Parameter | Type / location | Provenance (where read from) | Role in the pipeline |
| --- | --- | --- | --- |
| `cfa` | `CFA` (`rawimage.rs:176`, in `CFAConfig`) | Camera-DB `"cfa"` string → `cam.cfa` (`camera.rs:185`) / DNG `get_cfa` (`dng.rs:262`); default empty | **Drives demosaic interpolation** — the per-pixel pattern telling which neighbours share a colour. Passed explicitly to the `Demosaic` trait (`sensor/mod.rs:66`). |
| `colors` | `PlaneColor` (`rawimage.rs:177`) | Camera-DB `"plane_color"` string → `cam.plane_color` (`camera.rs:187-189`); DNG file metadata; default `PlaneColor::default()` | **Channel-count guardrail + superpixel colormap** (§7.2). Mostly ignored by wired algos; only Superpixel consumes it. |
| `wb_coeffs` | `[f32;4]` (`rawimage.rs:222`, "RGBE order") | Per-format decode — DNG `AsShotNeutral`/`AsShotWhiteXY` (`dng.rs:291-293`); TFR `AsShotNeutral` (`tfr.rs:165-168`); MRW `wb_vals` (`mrw.rs:219-222`); ARW maker data (`arw.rs:353`), debayer→identity `[1,1,1,NaN]` | **White-balance multipliers**, consumed in Calibrate and folded into `map_*ch_to_rgb` (`develop.rs:270-284`). Neutralized to `[1,1,1,1]` if `WhiteBalance` step off (`develop.rs:275-277`). Distinct from the colour matrix. |
| `color_matrix` | `HashMap<Illuminant, FlatColorMatrix>` (`rawimage.rs:245`) | **DNG**: `ColorMatrix1/2` + `CalibrationIlluminant1/2` tags (`dng.rs:408-433`). **Other RAW**: camera-DB `"color_matrix"` table (`camera.rs:151-167`). | **Colour mapping (camera XYZ→camRGB calibration)**, the static profile in Calibrate (`develop.rs:230-286`). `color_matrix_find_first` picks by illuminant priority (D65 first, `rawimage.rs:724-731`); Bradford-adapted to D65 if needed (`develop.rs:249-258`); identity fallback if missing. |
| `Intermediate` | enum `Monochrome`/`ThreeColor`/`FourColor` | Handoff structure: produced by Demosaic (`develop.rs:212/215/218`), consumed by Calibrate (`develop.rs:281-285`) | **The decoupling interface** between demosaic and calibrate (§7.1). Its shape is the only hard coupling between the two steps. |

Note on the two white-balance-related fields: `wb_coeffs` (multipliers, from the file's WB metadata) and `color_matrix` (the XYZ→camRGB calibration, from DNG tags / camera DB) are **two independent data sources** that Calibrate fuses into one `map_*ch_to_rgb(pixels, &wb, xyz2cam)` call (`develop.rs:283-284`). Neither is computed from image content; both are static calibration/profile data shipped with the file or the camera database.

## Constraints (STRUCT.md principle 5)

`external/dnglab` and `external/RawTherapee` remain fixed constraints: this study records where each engine's demosaic lives, how many algorithms each exposes, and whether they are selectable. No change to either upstream source is specified or permitted.

## Change History

- 2026-09-17 — dnglab vs RawTherapee demosaic comparison. Established that dnglab's `develop_intermediate` (`rawler/src/imgop/develop.rs:188-228`) hardcodes exactly one algorithm per CFA type with **no user selector** — Bayer → `PPGDemosaic` (`develop.rs:201`), 4-colour → `Bilinear4Channel` (`develop.rs:214`), X-Trans → `XTransBilinearDemosaic` (`develop.rs:217`), unknown → `todo!()` (`develop.rs:220`). Recorded that the crate *implements* 6 demosaic families (`sensor/bayer/{ppg,bilinear,superpixel}.rs`, `sensor/xtrans/{bilinear,markesteijn,lab}.rs`) but only 2 (PPG, XTransBilinear) are reachable from `develop`. Recorded RawTherapee's user-selectable set (`RawImageSource::demosaic`, `rawimagesource.cc:1796`, dispatched by `params->raw.dmethod`/`xtransmethod`): 11 Bayer (AHD/AMAzE/VNG4/LMMSE/RCD/EAHD/HPHD/IGV/bilinear/dual/fast) + X-Trans Markesteijn/bilinear. Filed as `DNGLAB-PIPELN-000002`; row appended to `rules/STRUCT/index.md`.
- 2026-09-17 — Added §6 comparing the **pre-demosaic data structures**: dnglab uses `Intermediate::Monochrome(PixF32)` (single `Vec<f32>` mosaic, range [0,1]) with CFA kept explicitly in `RawImage.photometric` and passed into the uniform `Demosaic<T,N>` trait (`sensor/mod.rs:55`, `pixarray.rs:42-50,354-362`); RawTherapee uses `array2D<float> rawData` (single 2D float mosaic, range [0,65535]) with CFA as a class member read implicitly by per-algorithm member methods that output three separate `red`/`green`/`blue` planes (`rawimagesource.h:86,278-308`, `imagefloat.h:35`). Concluded dnglab→dnglab algorithm swapping is feasible/low-cost (add a selector to `RawDevelop`; all 6 trait impls share one signature), whereas cross-codebase (dnglab⇄RawTherapee) invocation is infeasible (C++ class coupling, `[0,65535]`-vs-[0,1] + split-planar-vs-interleaved buffer mismatch, GPL-3.0 read-only constraint).
- 2026-09-17 — Supplemented §6.1 with the **`RawImage` decode-output (Stage 0) structure** preceding the post-rescale `Intermediate::Monochrome`: `RawImage` (`rawimage.rs:202-252`) carries the mosaic `data: RawImageData` (Integer `Vec<u16>` or Float `Vec<f32>`, `rawimage.rs:243,256-261`), `width/height/cpp` (`cpp==1` Bayer), `blacklevel`/`whitelevel`, `wb_coeffs`, `color_matrix`, and `photometric` (CFA config), with pixels still in native range (rescale not yet applied). Also recorded the architectural observation in §6.4(a): every dnglab demosaicer is a standalone `pub` struct behind the single `Demosaic<T,N>` trait, reachable as `rawler::imgop::sensor::bayer::*`/`xtrans::*` (`lib.rs:85` `pub mod imgop`); `RawDevelop::develop_intermediate` merely constructs one (`PPGDemosaic::new()`, `develop.rs:201`) and is **decoupled from algorithm code** — it "picks one" (PPG), but algorithms are composable units also invocable directly without `RawDevelop`.
- 2026-09-17 — Added **§7** documenting the **demosaic↔calibrate boundary**, the **`colors` (`PlaneColor`) guardrail**, and **provenance/role of every core parameter**. Three relationship layers: structural independence (separate `ProcessingStep`-gated `if` blocks at `develop.rs:188`/`230`), strict data-flow dependency (Calibrate consumes `Intermediate::ThreeColor/FourColor` from Demosaic; no-op on `Monochrome` at `develop.rs:282`), and algorithmic decoupling (Calibrate only depends on the `Intermediate` shape — the clean interface enabling pluggable demosaic per §6.4(a)). Noted the "Demosaic" step name is a misnomer (folds in `CropActiveArea` ROI + `FujiRotate`, `develop.rs:192-211`) and that `develop_intermediate` contains the *whole* pipeline (Rescale→Demosaic→Calibrate(WB+colour fused)→CropDefault→SRgb), with `develop()` only serializing TIFF. Documented `colors: PlaneColor` (`cfa.rs:305-308`) as a per-plane `CFAColor` descriptor from camera-DB `"plane_color"` (`camera.rs:187-189`) / DNG metadata, used only for `plane_count` validation + Superpixel colormap (`superpixel.rs:29-102`); ignored by PPG/XTransBilinear/Markesteijn (`_colors`). Added §7.3 parameter table for `cfa` (`camera.rs:185`/`dng.rs:262`), `colors`, `wb_coeffs` (file WB metadata — DNG `AsShotNeutral` `dng.rs:291`, TFR `tfr.rs:165`, MRW `mrw.rs:219`, ARW `arw.rs:353`; fused in Calibrate `develop.rs:270-284`), `color_matrix` (DNG `ColorMatrix1/2` `dng.rs:408-433` or camera-DB `camera.rs:151-167`; Bradford-adapted to D65 `develop.rs:249-258`), and `Intermediate` as the decoupling handoff.
