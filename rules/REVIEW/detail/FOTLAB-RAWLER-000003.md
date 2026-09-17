# Extending rawler_fotlab with a selectable demosaic algorithm, optional superpixel 1/4, and an external color matrix — design

- ID: FOTLAB-RAWLER-000003
- Status: Proposal
- Priority: P2
- Created: 2026-09-17
- Owner: —
- Related: `rules/REVIEW/detail/FOTLAB-RAWLER-000001.md` (RawImage never crosses the FFI; preview is an unprocessed dump), `rules/REVIEW/detail/FOTLAB-RAWLER-000002.md` (RawImage transport shapes across FFI), `rules/REVIEW/detail/DNGLAB-RAWLER-000002.md` (deepen, don't rewrite), `rules/DESIGN/detail/FOTLAB-NATIVE-000001.md` (R4 — upstream read-only), `rules/DESIGN/detail/DNGLAB-RAWDEV-000001.md` (develop pipeline)

## Background & Goal

The `rawler_fotlab` binding currently does `rawler::decode` then a bit-shift PNG (`app/src/binding/rust/rawler_fotlab/src/lib.rs:88-143`) — an unprocessed preview. We want to extend it with three **optional** developer-facing knobs:

1. **Selectable demosaic algorithm** — today rawler picks PPG / Bilinear4 / XTrans automatically; we want to *override* that choice.
2. **Optional superpixel 1/4** — a quarter-resolution output mode.
3. **Optional external color matrix** — let the caller supply a 4×3 `FlatColorMatrix` that overrides `RawImage.color_matrix` in the calibrate (camera→sRGB) step.

Hard constraint: upstream `external/dnglab/rawler` is **read-only** (`FOTLAB-NATIVE-000001` R4) — we cannot edit `RawDevelop` in place; any change to it must be a **recorded patch** reapplied on upgrade. Goal: design the extension so rawler's color science stays authoritative and the upstream surface we touch is minimal.

## Finding — what rawler already provides

### 1. The pipeline is step-selectable, but the demosaic *algorithm* is not parameterized

- `RawDevelop { steps: Vec<ProcessingStep> }` (`rawler/src/imgop/develop.rs:124-126`); `RawDevelop::default()` runs `Rescale → Demosaic → FujiRotate → CropActiveArea → WhiteBalance → Calibrate → CropDefault → SRgb` (`develop.rs:128-143`). `RawDevelop::new_with(&[ProcessingStep])` (`develop.rs:146-148`) lets you drop/reorder steps, but there is **no parameter for *which* demosaic algorithm**.
- Inside `develop_intermediate`, the demosaic algorithm is **hardcoded by CFA/sensor type** (`develop.rs:188-228`):
  - `cfa.is_rgb() && sensor == Bayer` → `PPGDemosaic` (`develop.rs:200-202`)
  - `cfa.unique_colors() == 4 && sensor == Bayer` → `Bilinear4Channel` (`develop.rs:213-215`)
  - `cfa.is_rgb() && sensor == Xtrans` → `XTransBilinearDemosaic` (`develop.rs:216-218`)
- So "optional demosaic algorithm" cannot be satisfied through `RawDevelop` config alone — it requires either a patch to `RawDevelop`, or first-party orchestration in the binding.

### 2. Superpixel (1/4) exists but is wired into nothing

- `Superpixel3Channel` (`rawler/src/imgop/sensor/bayer/superpixel.rs:15-16`) and `Superpixel4Channel` (`:77-78`) implement `Demosaic<f32,3>` / `Demosaic<f32,4>`; both doc-comment "result image is 1/4 of size" (`:27`, `:89`) — they combine each 2×2 block into one RGB(E) pixel.
- **`develop_intermediate` never calls them.** The only Superpixel reference in `develop.rs` is at `:301-304`: a *detection* that, if the intermediate is exactly `active_area.w/2`, scales the crop by 0.5. So superpixel is a ready-made primitive we can invoke, but the pipeline ignores it.

### 3. Calibrate / color-matrix mechanics — and the one reachability gap

- The calibrate step (`develop.rs:230-286`) selects a matrix via `rawimage.color_matrix_find_first([D65, A, B, C, D50, …])` (`develop.rs:233-244`), falling back to identity (`:247`). For non-D65 it applies Bradford chromatic adaptation (`:254`). It then calls `map_3ch_to_rgb` / `map_4ch_to_rgb` (`:283-284`).
- `color_matrix_find_first` is **`pub`** on `RawImage` (`rawler/src/rawimage.rs:724`) → reachable from the binding. `RawImage.color_matrix` is `HashMap<Illuminant, FlatColorMatrix>` (`rawimage.rs:245`); `FlatColorMatrix = Vec<f32>` (`rawler/src/imgop/xyz.rs:31`), i.e. a flattened 4×3 = 12 floats.
- **Gap:** `map_3ch_to_rgb` / `map_4ch_to_rgb` are **`pub(crate)`** (`rawler/src/imgop/raw.rs:193`, `:220`) → **not reachable from the binding.** This is the single blocker for a fully first-party reimplementation of calibrate.
- The demosaic primitives, by contrast, are `pub` structs in `pub` modules: `pub mod imgop` (`rawler/src/lib.rs:85`), `PPGDemosaic` (`imgop/sensor/bayer/ppg.rs:15`), `Bilinear4Channel` (`imgop/sensor/bayer/bilinear.rs:14`), `XTransBilinearDemosaic` (`imgop/sensor/xtrans/bilinear.rs:29`), `Superpixel3Channel` / `Superpixel4Channel` (`superpixel.rs:16/78`). The binding can call `.demosaic(pixels, &cfa, &colors, roi)` on them directly.

## Design

### A. External-facing config (UniFFI-exported)

Mirror the earlier transport research (`FOTLAB-RAWLER-000002`): expose the knobs as UniFFI types so Kotlin can pass them in, and return either a PNG (preview) or a developed `RawFrame` (if Kotlin develops). Suggested shapes:

```rust
#[derive(uniffi::Enum)]
pub enum DemosaicAlgo { Default, Ppg, Bilinear4, Xtrans, Superpixel, None }

#[derive(uniffi::Enum)]
pub enum ScaleMode { Full, Quarter }   // Quarter = post-demosaic 2×2 bin

#[derive(uniffi::Record)]
pub struct DevelopConfig {
  pub demosaic: DemosaicAlgo,            // override rawler's auto-pick
  pub scale: ScaleMode,                  // optional 1/4 via post-bin
  pub external_matrix: Option<Vec<f32>>, // 12 floats (4×3), overrides color_matrix
  // future: white_balance: bool, srgb: bool, crop: bool …
}
```

- `DemosaicAlgo::Default` = rawler's current auto-dispatch (do nothing special). `None` = skip demosaic (pass through mosaic / for non-Bayer RGB). `Superpixel` selects the 1/4 superpixel demosaic.
- `ScaleMode::Quarter` is **orthogonal** to `DemosaicAlgo::Superpixel` (which is *already* 1/4). Applying both would quarter an already-quartered image — guard against double-downscale (see §C).

### B. Two-layer execution

Keep rawler authoritative for the standard path; only build first-party orchestration where rawler cannot be parameterized:

**Layer 1 — standard / external-matrix / no-algo-override (zero upstream change).**
Clone the `RawImage` (it is already a local value at `lib.rs:94`), and if `external_matrix` is set, replace its map:

```rust
let mut img = rawimage.clone();
if let Some(m) = &cfg.external_matrix {
  img.color_matrix = HashMap::from([(Illuminant::D65, m.clone())]);
}
let inter = RawDevelop::default().develop_intermediate(&img)?;  // uses our matrix in Calibrate
```

This reuses the *entire* rawler pipeline — WB, Bradford adaptation, crop, SRGB — with only the matrix swapped. No patch needed.

**Layer 2 — custom algorithm / superpixel / external-matrix-with-custom-algo.**
Here rawler's hardcoded dispatch (`develop.rs:200-221`) gets in the way. Two viable sub-options:

- **(2a) Minimal recorded patch (recommended for fidelity).** Add one small, upgrade-safe patch to rawler exposing a pre-demosaiced entry, e.g. `RawDevelop::develop_intermediate_from(&self, intermediate: Intermediate, rawimage: &RawImage)` that skips the Rescale/build/demosaic blocks and runs only `WhiteBalance → Calibrate → Crop → SRgb`. The binding then does demosaic itself with the public primitives (`PPGDemosaic`/`Bilinear4Channel`/`XTransBilinearDemosaic`/`Superpixel3/4Channel`), and hands the `Intermediate` to rawler for the color science. This keeps `map_3ch_to_rgb`/`Bradford` inside rawler (authoritative, no reimplementation) and is the smallest surface that satisfies R4 as a *recorded patch*.
- **(2b) Fully first-party calibrate (no patch).** Because `map_3ch_to_rgb` is `pub(crate)`, replicate the ~30-line camera→sRGB transform in the binding: white-balance multiply (`rawimage.wb_coeffs`), then `out = xyz2cam · in` per pixel, plus SRGB gamma. For an **external D65 matrix** this is a straight matrix multiply with no Bradford needed. Trade-off: we reimplement rawler's color math and must track it on upstream changes.

Recommend **2a** as the default (rawler stays the source of truth for color), with **2b** noted as the patch-free fallback if the team prefers zero upstream touch.

### C. Superpixel 1/4 semantics

- `DemosaicAlgo::Superpixel` → call `Superpixel3Channel`/`Superpixel4Channel` directly; output is inherently 1/4. This is the *preferred* 1/4 path for Bayer (it is the correct 2×2 combine, not a naive box-average).
- `ScaleMode::Quarter` with a **full-res** `demosaic` → after demosaic, do a 2×2 average bin in the binding (or a small rawler helper). 
- **Guard:** if `demosaic == Superpixel`, ignore `ScaleMode::Quarter` (or error) — never downscale twice.
- Replicate rawler's crop-scaling: when the result is 1/4, scale `active_area`/`crop_area` by 0.5 (cf. `develop.rs:301-304`).

### D. Validation the binding must do

- Reject `DemosaicAlgo::Superpixel` if `photometric` isn't `Cfa` / plane count mismatches (Superpixel3 needs 3 planes, Superpixel4 needs 4 — `superpixel.rs:29`, `:91`).
- Reject `external_matrix` whose length ≠ 12 (or ≠ 9 for a 3×3, if we support that) — `FlatColorMatrix` is `Vec<f32>`, length is the only shape signal (`xyz.rs:31`, `develop.rs:261`).
- Keep the existing `catch_unwind` boundary (`lib.rs:92`) so a bad config degrades to `Err`, not a process abort.

## Impact / Conflict

- **R4 compliance.** Layer 1 and option 2b need **no** upstream edit. Option 2a needs a *recorded patch* to rawler (exposing one method) — explicitly permitted by R4 as long as it is a patch file / documented procedure, not an in-place edit. Record it in `docs/external/index.md` and re-apply on submodule bump.
- **Authoritative color science.** By reusing `RawDevelop` (Layer 1, and Layer 2a via the patch), rawler's WB / Bradford / gamma remain the single implementation — consistent with `DNGLAB-RAWLER-000002` ("deepen, don't rewrite").
- **FFI shape.** `DevelopConfig` is a small exported record/enum; the output follows `FOTLAB-RAWLER-000002` — PNG for preview, or a `RawFrame` if Kotlin develops. Nothing in this design requires exporting the upstream `RawImage`.
- **Performance.** Superpixel 1/4 cuts pixel count 4× and skips the heaviest full-res demosaic — a good fast-preview mode. External matrix adds no per-pixel cost.
- **No conflict** with `FOTLAB-RAWLER-000001`/`000002`; this item is the concrete next step that makes those "RawImage is resolved but discarded" findings actionable.

## Recommendation

1. Ship `DevelopConfig` (enum + record) as the UniFFI input. Default everything to rawler's current behaviour so the change is backwards-compatible.
2. Implement **Layer 1** first (external matrix via `img.color_matrix` swap + `RawDevelop::default()`) — it needs **zero** upstream change and already covers the external-matrix use case.
3. For selectable algorithm + superpixel, add the **minimal recorded patch** (Layer 2a) exposing a pre-demosaiced `RawDevelop` entry; do demosaic in the binding with rawler's public primitives. Keep `map_3ch_to_rgb`/Bradford inside rawler.
4. Model superpixel-1/4 as `DemosaicAlgo::Superpixel` (preferred) plus an orthogonal `ScaleMode::Quarter` for full-res algos, with a double-downscale guard.
5. If the team refuses any upstream patch, fall back to **Layer 2b** (first-party calibrate ~30 LOC, D65 external matrix = straight multiply).

## Change History

- 2026-09-17 — Created as a Proposal. Surveyed rawler's develop pipeline: `ProcessingStep`/`RawDevelop` are step-selectable but demosaic algorithm is hardcoded by CFA/sensor (`develop.rs:68-228`); `Superpixel3/4Channel` exist and yield 1/4 but are unwired (`superpixel.rs:15-130`, `develop.rs:301-304` only detects them); calibrate uses `color_matrix_find_first` (`rawimage.rs:724`, `pub`) and `map_3ch_to_rgb/4` which are `pub(crate)` (`imgop/raw.rs:193,220`) — the one reachability gap; demosaic primitives are `pub` in `pub` modules (`lib.rs:85`). Proposed a UniFFI `DevelopConfig` (DemosaicAlgo / ScaleMode / external_matrix), a two-layer execution (Layer 1 reuses `RawDevelop` with a `color_matrix` swap — zero patch; Layer 2 adds a minimal recorded patch exposing a pre-demosaiced entry, or a first-party calibrate reimplementation), superpixel-1/4 semantics with a double-downscale guard, and required binding-side validation. Row to be appended to `rules/REVIEW/index.md`.
