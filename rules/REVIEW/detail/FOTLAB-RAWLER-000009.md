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

## Decision — the two new stages

Both are **pure functions** with the same contract as `apply_exposure`: `fn(Vec<f32>, width, height, Option<f32>) -> Vec<f32>`, `None`/`0` = identity, no shared state, deterministic. Adding a stronger model later (e.g. a real wavelet denoiser at a post-demosaic slot) does not touch the call site.

### `denoise.rs` — RawTherapee-style CFA impulse denoise

Ports RT's CFA Impulse Denoise and improves it in two ways:

1. **Per-colour planes, no colour bleed.** The RGGB (any 2×2-periodic) mosaic is split into its four same-colour sublattices; a defect is detected only against same-colour neighbours, so a spike is never averaged with a neighbouring colour.
2. **Beyond-neighbour-range test + soft knee** (the enhancement over RT's raw "deviation-from-median threshold", which can nick genuine high-contrast edges). For each photosite we gather its **8 same-colour neighbours** (3×3 in the sublattice = 5×5 in the mosaic, distance 2). We take their `min`/`max`/`median`. The pixel is an impulse only if it lies **outside `[min − thr, max + thr]`**; when it does, it is pulled toward the median through a soft knee (`frac = excess / soft`, clamped to 1) so the keep→replace transition is continuous and seam-free.

`strength` is a **sensitivity multiplier** on `thr` (`base 0.05 × strength`); `≈1.0` is mild, higher is more aggressive. Computation reads the original grid and writes a separate buffer, and runs row-parallel under rayon — deterministic, O(N·8), no data race. The original placeholder (a plane-aware box blur) was removed: a smoothing blur is the *post-demosaic* wavelet kind and was semantically wrong at this slot.

**2×2 gating.** The parity grouping only compares same colours when the CFA is 2×2-periodic. X-Trans (6×6) is *not*, so `develop.rs` skips the stage via `is_2x2_cfa()` (the same `cfa.width == 2 && cfa.height == 2` predicate `bayer_cfa_desc` uses); pre-coloured (non-CFA) input is skipped too. The stage is therefore a no-op on X-Trans, exactly as the original box blur would have been harmless-but-meaningless there.

### `dehaze.rs` — histogram-floor haze lift (baseline)

A single-image dehaze in the dark-channel spirit, adapted to the single-channel CFA: estimate a haze floor from the ~1% percentile of the mosaic histogram and lift it out per pixel via `cleared = (in − haze)/(1 − haze)`, blended back by `strength`. This is a deliberately lightweight baseline — the slot is pre-demosaic and colour-blind, so a full dark-channel-prior (per-colour dark channel, soft matting, transmission refinement) is not applicable here; a colour-aware dehaze likewise belongs post-demosaic.

## Impact / Conflict

- **Single slot, three pure stages.** Exposure → Denoise → Dehaze is now a composition of pure functions at the one pre-demosaic mosaic point; both forks (stateless `develop`, cached `RawlerImageLoaded`) inherit it unchanged ([`FOTLAB-RAWLER-000004`](FOTLAB-RAWLER-000004.md)).
- **Default output preserved.** All three stages are identity when unconfigured (`None`/`0`), so existing renders (as-shot, no denoise/dehaze) are bit-identical to before — the only behavioural change is when Kotlin supplies `denoise_strength` / `dehaze_strength`.
- **No upstream edit.** `rawlER` stays read-only ([`FOTLAB-NATIVE-000001`](../../DESIGN/index.md)); the stages are first-party and live in `rawler_fotlab`.
- **Algorithm placement is now principled.** The pre-demosaic slot carries CFA-domain work (impulse denoise, haze floor) by construction; a future luminance denoiser is steered to a post-demosaic slot rather than mis-filed here.

## Open Questions

- Kotlin currently exposes `DevelopParams.denoise_strength` / `dehaze_strength`; confirm the Studio denoise/dehaze controls map to these and that `0`/`null` both resolve to identity on the Kotlin side.
- Should `denoise` expose the RT **False-Colour / fringe** suppression (also a Raw-tab, pre-demosaic CFA stage) as a second mode, or stay single-purpose impulse removal?
- The post-demosaic luminance-denoise slot (wavelet/LMMSE) is still unowned — file separately when a real algorithm is ported.

## Change History

- 2026-09-23 — Research + implementation record. **Finding 1 (exposure wiring):** exposure is an inline block in `develop.rs::develop_image`, at the single unambiguous pre-demosaic slot between `take_scaled_pixels` and `demosaic`; it is a channel-uniform linear gain so it commutes with the linear demosaic and is identity for `None`/`0`, matching rawler's `RawDevelop::default()`. Extracted it into `exposure::apply_exposure` as the compositional pattern. **Finding 2 (RT denoise placement, RawPedia — About Noise Reduction):** RT's Impulse Denoise (hot/dead pixels) runs *before* demosaic on the single-channel CFA, while its wavelet/Noise-Reduction runs *after* demosaic on RGB/L\*a\*b\* — so our pre-demosaic slot is the analogue of RT's CFA impulse denoise, not its wavelet denoise. **Decision:** added `denoise.rs` (RT-style CFA impulse denoise — 4 same-colour planes, 8-neighbour range test + soft-knee median pull, `strength` = threshold multiplier) replacing the placeholder box blur, and `dehaze.rs` (histogram-floor haze lift baseline); gated denoise to 2×2-periodic CFAs via `is_2x2_cfa` (X-Trans skipped). Both are pure `Option<f32>` identity-on-`None` functions. `lib.rs` gained the three `mod` declarations; `DevelopParams` already carried the two `*_strength` fields. Row appended to `rules/REVIEW/index.md` (next `FOTLAB-RAWLER` = `000010`).
