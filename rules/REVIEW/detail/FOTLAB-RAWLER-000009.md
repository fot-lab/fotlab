# Pre-demosaic stage slot — exposure extraction + RawTherapee-style CFA impulse denoise / histogram-floor dehaze

- ID: FOTLAB-RAWLER-000009
- Status: Implemented
- Priority: P2
- Created: 2026-09-23
- Owner: —
- Related: [`FOTLAB-RAWLER-000003`](FOTLAB-RAWLER-000003.md) (hand-built develop pipeline design), [`FOTLAB-RAWLER-000004`](FOTLAB-RAWLER-000004.md) (decode-once / develop-reuse; `develop_image` is the shared core), `app/src/binding/rust/rawler_fotlab/src/{develop,exposure,denoise,dehaze,lib}.rs`, RawPedia — [About Noise Reduction](https://rawpedia.rawtherapee.com/About_Noise_Reduction)

## Background & Goal

The develop pipeline (`develop.rs::develop_image`) materialises the scaled single-channel CFA mosaic with `take_scaled_pixels`, then runs `demosaic → calibrate → crop`. The brief was to (a) confirm **where exposure currently plugs in** and (b) insert two new pre-demosaic algorithms — **denoise** and **dehaze** — at the same position (after the loader/scale step, before demosaic), and to extract the inline exposure block into its own module.

The constraint carried over from [`FOTLAB-RAWLER-000004`](FOTLAB-RAWLER-000004.md) §as-shot: the pre-demosaic mosaic is **linear, single-channel, 0..1** (post `apply_scaling`). Any stage at this slot operates on the raw CFA grid, not on RGB. That dictates *which kind* of denoise/dehaze is appropriate here.

## Research Finding 1 — how exposure is wired

Exposure is not a separate stage file in the original pipeline; it is an **inline block inside `develop_image`**. The slot is unambiguous:

```rust
// develop.rs — after `take_scaled_pixels`, before `demosaic`
let mut pixels = take_scaled_pixels(&mut image)?;
//  ← exposure (inline linear gain 2^exposure_ev on the single-channel mosaic)
//  ← [new] denoise / dehaze
let intermediate = demosaic(&image, pixels, ...)?;   // demosaic consumes the mosaic
```

Key properties established by reading the code:

- **Single insertion point.** There is exactly one place the mosaic buffer exists as a 0..1 f32 grid (`develop_image`), shared by both the stateless `develop` FFI path and the cached-decode `RawlerImageLoaded` path ([`FOTLAB-RAWLER-000004`](FOTLAB-RAWLER-000004.md)). So a stage added here is automatically exercised by both forks.
- **Order invariant.** Exposure is a *channel-uniform linear gain*, so it commutes with the linear `demosaic` — applying it before or after demosaic is numerically identical. It therefore sits correctly at the pre-demosaic slot with no re-derivation.
- **`None` = identity.** `exposure_ev: Option<f32>` with `None` (and `Some(0.0)`) collapses to unity gain, so an unconfigured stage is free and matches rawler's `RawDevelop::default()` (dnglab's DNG-thumbnail pipeline, which has no exposure step). This identity convention is the contract every new pre-demosaic stage must honour, so the default pipeline output is unchanged until a strength is supplied.

**Decision (extract exposure):** the inline gain block became `exposure::apply_exposure(pixels, exposure_ev)` — a pure function consuming and returning the buffer. This establishes the compositional pattern the other stages reuse: `let pixels = apply_exposure(pixels, …);` then `let pixels = denoise(pixels, …);` etc.

## Research Finding 2 — where RawTherapee puts denoise

RawTherapee has **two** denoise positions, and they are different algorithms:

| RT module | Pipeline position | Domain | Reference |
| --- | --- | --- | --- |
| **Impulse Denoise** (Hot/Dead Pixels) | **before demosaic**, on the single-channel CFA | raw Bayer mosaic | Raw tab |
| **Wavelet / Noise Reduction** (luminance + chroma) | **after demosaic**, on RGB / L\*a\*b\* | demosaiced colour | Details tab, Wavelet Levels |

Per RawPedia — [About Noise Reduction](https://rawpedia.rawtherapee.com/About_Noise_Reduction): the wavelet/Noise-Reduction modules run in **RGB or L\*a\*b\* mode**; a CFA is single-channel and neither, so they *must* run after demosaic. The page also notes the Noise-Reduction module historically sat at the **end** of the process (a non-linear stage). Impulse Denoise, by contrast, is a raw/CFA defect remover (hot pixels, dead pixels, salt-and-pepper) and lives in the Raw tab, i.e. pre-demosaic.

**Consequence for our slot.** Our pre-demosaic position (single-channel CFA, linear, before demosaic) is the *direct analogue of RT's Impulse Denoise*, **not** of RT's wavelet denoise. A true luminance/chroma denoiser (wavelet/LMMSE) belongs **after demosaic**, in the RGB domain, and is out of scope for this slot. So the correct borrow from RT is the **CFA impulse denoise**, not a smoothing blur.

## Research Finding 3 — dehaze landscape, and where our stage sits

The earlier note that "RawTherapee has no dehaze" was wrong. The survey:

| Software | Dehaze? | Algorithm | Domain | Pipeline stage |
| --- | --- | --- | --- | --- |
| **RawTherapee** | yes — two | (1) standard **Dehaze** tool, Exposure tab, on the demosaiced image; (2) **Raw Dehaze** (2024, Ingo's design) at the *Raw black-point* stage — per-raw-channel **global minimum subtraction** | (1) dark-channel-style; (2) per-channel offset only | (1) RGB / Lab; (2) **CFA** (pre-demosaic) |
| **darktable** | yes — `hazeremoval` | **Dark Channel Prior** (He 2009) + **guided filter** transmission refinement; `strength` + `distance` sliders | RGB (post-demosaic) | darkroom |
| **G'MIC** | **no core command** | only community/shared filters: "Dcp dehaze" (DCP-based, since 1.7.5) and `retinex` (Colours) — both **RGB** | RGB | plugin |
| **OpenCV / academic** | n/a | DCP + Fast Guided Filter, Haze-Line (Berman), Ancuti fusion, CAP, Tarel, Fattal | RGB | post-demosaic |

**Input-domain rule (what each algorithm eats).** A CFA mosaic is single-channel and not a true colour image, so only *defect/impulse removal* and *per-plane scalar / offset* operations belong there. Everything that needs real colour or spatial structure — DCP needs the cross-channel dark channel; a guided filter needs edges; NL-means needs patches; fusion needs derived images — wants **RGB**, i.e. *after* demosaic. **Lab** is the choice when you want to denoise *luminance only* and leave colour untouched (RT Noise Reduction, darktable profiled). Our pre-demosaic slot is therefore correctly limited to a CFA-domain dehaze.

**Our dehaze vs RT's Raw Dehaze — same family, different details.** Both model haze as a single constant per colour plane and remove it on the CFA; neither builds a spatial transmission map. The differences:

| | RT Raw Dehaze | our `dehaze` (enhanced) |
| --- | --- | --- |
| estimator | per-channel **global minimum** (non-robust to one hot pixel) | per-plane **configurable percentile** (default 1%, robust to outliers) |
| correction | **offset only** (`x − min`) | **offset + contrast gain** (`(x − h)/(1 − h)`), DCP-style |
| control | checkbox (on/off) | `strength` continuous blend **+ `percentile` parameter** |
| colour planes | per raw channel (R/G/B) | per CFA colour via `color_at` — **Bayer *and* X-Trans** (RT's CFA impulse denoise skips X-Trans; ours does not) |
| borders | whole frame | restricted to `active_area` so masked borders don't bias the percentile |

So the two are conceptually identical (global per-colour haze-floor removal on the raw mosaic); ours is the more robust, more configurable variant, and the enhanced version closes the earlier gap where the baseline treated the whole mosaic as one channel.

## Decision — the two new stages

Both are **pure functions** with the same contract as `apply_exposure`: `denoise(pixels, width, height, strength, cfa)` and `dehaze(pixels, width, height, strength, percentile, cfa, active)` — `strength = None`/`0` is identity, no shared state, deterministic. Both take the CFA config so they are colour-aware on every CFA (no 2×2-only gating); a stronger model later (e.g. a real wavelet denoiser at a post-demosaic slot) drops in without touching the call site.

### `denoise.rs` — RawTherapee-style CFA impulse denoise (generalised to any CFA)

Ports RT's CFA Impulse Denoise and improves it in two ways:

1. **Per-colour grouping via `CFA::color_at`, no colour bleed, no 2×2 limitation.** Unlike the first port (which hard-coded the 2×2 Bayer sublattices and had to skip X-Trans), this version groups every photosite by its CFA colour using `CFA::color_at(row, col)` and therefore runs on **every periodic CFA** — 2×2 Bayer (RGGB / four-colour), 6×6 X-Trans, and anything else rawler describes — plus single-channel (non-CFA, monochrome) input. The same-colour neighbour set of a pixel is the fixed list of `(Δrow, Δcol)` offsets (within a 5×5 mosaic window) whose CFA colour matches the pixel's own; because the CFA is periodic this list depends only on `(row mod period, col mod period)` and is **precomputed once**, so the per-pixel cost is a constant gather + a tiny median regardless of CFA family. A defect is detected only against same-colour neighbours, so a spike is never averaged with a neighbouring colour.
2. **Beyond-neighbour-range test + soft knee** (the enhancement over RT's raw "deviation-from-median threshold", which can nick genuine high-contrast edges). We gather the pixel's same-colour neighbours (8 for Bayer; ~5–14 for X-Trans, depending on the colour), take their `min`/`max`/`median`, and treat the pixel as an impulse only if it lies **outside `[min − thr, max + thr]`**; when it does, it is pulled toward the median through a soft knee (`frac = excess / soft`, clamped to 1) so the keep→replace transition is continuous and seam-free.

`strength` is a **sensitivity multiplier** on `thr` (`base 0.05 × strength`, clamped to `[0,8]`); `≈1.0` is mild, higher is more aggressive. Computation clones the grid, reads the original and writes a separate buffer, and runs row-parallel under rayon — deterministic, O(N), no data race. A `RADIUS`-pixel border is left untouched so every gathered neighbour is in-bounds.

**No CFA gating.** `develop.rs` passes the `CFAConfig` to `denoise` and no longer skips any CFA; the stage is colour-aware on Bayer *and* X-Trans (the `is_2x2_cfa` predicate was removed). Pre-coloured (`cpp > 1`) input is still identity via the length check, and `strength = None`/`0` is identity, so default renders are unchanged. The original placeholder (a plane-aware box blur) was removed: a smoothing blur is the *post-demosaic* wavelet kind and was semantically wrong at this slot.

### `dehaze.rs` — per-colour-plane histogram-floor dehaze (enhanced)

A single-image dehaze in the dark-channel spirit, adapted to the single-channel CFA mosaic. Where the original baseline estimated *one* haze floor over the whole mosaic, the enhanced stage estimates a **separate floor per CFA colour plane** (R / G / B; the two Bayer greens and all X-Trans greens share a plane) — exactly the per-raw-channel idea of RT's Raw Dehaze, but driven by a configurable **`percentile`** (default 1%) of each plane's 0..1 histogram rather than the global minimum, and closed over by a contrast-restoring divide: `cleared = (in − haze[plane]) / (1 − haze[plane])`, blended back by `strength`.

- **Per-plane via `CFA::color_at(row, col)`.** The stage is colour-aware on **every** CFA — 2×2 Bayer (3 planes) and 6×6 X-Trans (also 3 planes) — using the same `CFA::color_at` grouping that `denoise` now uses (no 2×2-only gating). Non-CFA input falls back to a single global plane; `cpp > 1` buffers are left untouched by the length check.
- **`percentile` is a parameter** (0..1), passed from Kotlin and **clamped to `[0,1]` internally**; `None` → 1%. Lower is more conservative (toward a pure minimum), higher lifts more of the low tail.
- **`strength` is kept** (0..1 blend) from the baseline.
- **Active-area restriction.** The percentile histograms accumulate only inside the sensor `active_area`, so masked / black borders cannot bias the estimate.

`dehaze(pixels, width, height, strength, percentile, cfa, active) -> pixels`; `strength = None`/`0` is identity, so default renders are unchanged. Swapping in a colour-aware / spatial dehaze (DCP, Haze-Line, fusion) needs a post-demosaic slot and is out of scope here.

## Pros / Cons of our pre-demosaic dehaze

**Pros**
- Runs on the **raw CFA** before demosaic — zero extra resize, no colour bleed, and it lifts the additive haze offset while it is still a clean per-photosite quantity.
- **Per-colour-plane** (R/G/B, incl. X-Trans): each plane is corrected against its own floor, so green (which carries more signal) is not over-/under-lifted relative to red/blue.
- **Percentile, not minimum**: robust to a few hot/dead pixels and to low tails, so it does not over-correct when one stray photosite spikes.
- **Offset + contrast gain** (`(x−h)/(1−h)`) restores the contrast haze washed out — closer to the DCP physical model than RT's offset-only Raw Dehaze.
- **Continuous, safe control**: `strength` blends original↔dehazed, and `percentile` tunes the floor; both are user-facing from Kotlin.
- **Active-area masked**: masked borders cannot drag the percentile down.

**Cons / limits**
- Still a **global, per-plane, spatially-uniform** model — it assumes one haze level per colour across the frame. Real depth-varying haze (near/far) and skies need a *spatial* transmission map (DCP / Haze-Line / fusion), which requires RGB and a post-demosaic slot.
- It only removes the **additive offset** of *uniform* haze/flare; it cannot recover lost saturation or detail the way a full DCP + guided-filter pipeline (darktable `hazeremoval`) can.
- Runs on the linear mosaic, so it has **no colour matrix / white balance** context yet — chromatic haze tints are handled coarsely per plane, not as a cross-channel colour correction.
- Slightly more work than the old single-channel version (per-pixel `color_at` ×2 passes + per-plane histograms), though still O(N) and rayon-parallel.

## Impact / Conflict

- **Single slot, three pure stages.** Denoise → Dehaze → Exposure is now a composition of pure functions at the one pre-demosaic mosaic point; both forks (stateless `develop`, cached `RawlerImageLoaded`) inherit it unchanged ([`FOTLAB-RAWLER-000004`](FOTLAB-RAWLER-000004.md)). Denoise and dehaze run on the *normalised* 0..1 mosaic, before the `2^exposure_ev` gain — dehaze bins a 0..1 histogram (a positive EV would push values >1.0 into the top bin and bias the per-plane floor), while denoise is scale-invariant under a uniform linear gain so its result is unchanged by the placement.
- **Default output preserved.** All three stages are identity when unconfigured (`None`/`0`), so existing renders (as-shot, no denoise/dehaze) are bit-identical to before — the only behavioural change is when Kotlin supplies `denoise_strength` / `dehaze_strength`.
- **No upstream edit.** `rawlER` stays read-only ([`FOTLAB-NATIVE-000001`](../../DESIGN/index.md)); the stages are first-party and live in `rawler_fotlab`.
- **Algorithm placement is now principled.** The pre-demosaic slot carries CFA-domain work (impulse denoise, haze floor) by construction; a future luminance denoiser is steered to a post-demosaic slot rather than mis-filed here.

## Open Questions

- Kotlin currently exposes `DevelopParams.denoise_strength` / `dehaze_strength`; confirm the Studio denoise/dehaze controls map to these and that `0`/`null` both resolve to identity on the Kotlin side.
- Should `denoise` expose the RT **False-Colour / fringe** suppression (also a Raw-tab, pre-demosaic CFA stage) as a second mode, or stay single-purpose impulse removal?
- The post-demosaic luminance-denoise slot (wavelet/LMMSE) is still unowned — file separately when a real algorithm is ported.
- The enhanced `dehaze` now estimates the haze floor **per CFA colour plane** (R/G/B, incl. X-Trans via `color_at`) from a Kotlin-supplied `dehaze_percentile` (clamped to `[0,1]`, default 1%) and keeps `dehaze_strength` as the blend — closing the earlier gap where the baseline treated the mosaic as one channel. Confirm the Studio dehaze UI exposes both `dehaze_strength` and `dehaze_percentile`. A genuinely colour-aware / spatial dehaze (DCP, Haze-Line, fusion) belongs **post-demosaic** and is still unowned.

## Change History

- 2026-09-23 — Research + implementation record. **Finding 1 (exposure wiring):** exposure is an inline block in `develop.rs::develop_image`, at the single unambiguous pre-demosaic slot between `take_scaled_pixels` and `demosaic`; it is a channel-uniform linear gain so it commutes with the linear demosaic and is identity for `None`/`0`, matching rawler's `RawDevelop::default()`. Extracted it into `exposure::apply_exposure` as the compositional pattern. **Finding 2 (RT denoise placement, RawPedia — About Noise Reduction):** RT's Impulse Denoise (hot/dead pixels) runs *before* demosaic on the single-channel CFA, while its wavelet/Noise-Reduction runs *after* demosaic on RGB/L\*a\*b\* — so our pre-demosaic slot is the analogue of RT's CFA impulse denoise, not its wavelet denoise. **Decision:** added `denoise.rs` (RT-style CFA impulse denoise — 4 same-colour planes, 8-neighbour range test + soft-knee median pull, `strength` = threshold multiplier) replacing the placeholder box blur, and `dehaze.rs` (histogram-floor haze lift baseline); gated denoise to 2×2-periodic CFAs via `is_2x2_cfa` (X-Trans skipped). Both are pure `Option<f32>` identity-on-`None` functions. `lib.rs` gained the three `mod` declarations; `DevelopParams` already carried the two `*_strength` fields. Row appended to `rules/REVIEW/index.md` (next `FOTLAB-RAWLER` = `000010`).
- 2026-09-23 (2nd) — Enhanced `dehaze`. **Finding 3 (dehaze landscape):** RawTherapee *does* have dehaze — a standard Exposure-tab tool (demosaiced) and a 2024 Raw Dehaze (per-raw-channel global-minimum subtraction, pre-demosaic); darktable has `hazeremoval` (DCP + guided filter, post-demosaic); G'MIC has **no core dehaze** (only community Dcp dehaze / Retinex filters, all RGB). Input-domain rule: CFA only for defect/offset ops, RGB for real dehaze/denoise, Lab for luminance-only denoise. **Decision:** rewrote `dehaze.rs` to estimate a separate haze floor **per CFA colour plane** (via `CFA::color_at`, covering Bayer *and* X-Trans) as a configurable `percentile` of each plane's 0..1 histogram (default 1%, clamped `[0,1]`), with the DCP-style `(x−h)/(1−h)` contrast restore and the existing `strength` blend; histograms are restricted to `active_area`. Added `DevelopParams.dehaze_percentile` (`Option<f32>`, default `None`→0.01) and threaded `cfa` + `active_area` through `develop.rs`; `strength = None`/`0` stays identity so default renders are unchanged.
- 2026-09-23 (3rd) — Generalised `denoise` to **every** CFA. The original port hard-coded 2×2 Bayer sublattices and was skipped on X-Trans via `is_2x2_cfa`. Rewrote `denoise.rs` to group pixels by `CFA::color_at(row, col)` and precompute, for each CFA parity `(row mod period, col mod period)`, the fixed list of same-colour neighbour offsets within a 5×5 window — so it now runs on 2×2 Bayer, 6×6 X-Trans, four-colour, and single-channel monochrome input with a constant per-pixel gather + tiny median, independent of CFA family. Removed the `is_2x2_cfa` gate and predicate from `develop.rs` (kept the range-test + soft-knee impulse model and `strength` sensitivity multiplier, clamped `[0,8]`); `cpp > 1` and `strength = None`/`0` stay identity. Both pre-demosaic stages now take the `CFAConfig` and are colour-aware on every CFA.
- 2026-09-23 (4th) — Reordered the pre-demosaic mosaic stages to **Denoise → Dehaze → Exposure**. Previously `develop_image` ran `apply_exposure` first, then `denoise`, then `dehaze` — and `dehaze` was accidentally invoked **twice** (a leftover duplicate). Denoise and dehaze now run on the *normalised* 0..1 mosaic before the `2^exposure_ev` gain: dehaze bins a 0..1 histogram, so a positive EV would have pushed values >1.0 into the top bin and biased the per-plane haze floor; denoise is scale-invariant under a uniform linear gain, so its result is unchanged by the move. Removed the duplicate `dehaze` call. Updated the `develop.rs` module doc (steps 3 / 3a / 3b) and the `DevelopParams.denoise_strength` / `dehaze_strength` field docs to reflect the new order; `strength = None`/`0` stays identity so default renders are unchanged.
