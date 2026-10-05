# External module study — LOCA (axial CA / purple-fringe) correction transposed into our OKLab/Oklch bypass

> **Naming**: `RAWTRP-` project code (RawTherapee) + six-character `SURVEY` category. Lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified. The *subject* of the study is our own OKLab/Oklch highlight-compression bypass (`FOTLAB-RENDER-000001`), but the two reference algorithms come from RawTherapee and RapidRAW, so the `RAWTRP-` prefix stays (ID is permanent; scope can widen without renaming).
>
> **Scope**: research only — **no code change**. Goal: how RawTherapee (`PF_correct_RT`) and RapidRAW (`recover_clipped_pixel`) implement LOCA / purple-fringe correction, and how the *same* correction can be expressed inside our existing OKLab/Oklch camera-space bypass, where Oklab/Oklch is a better coordinate than the CIELAB/CIECAM02 the references use. Distinct from LCA (lateral CA), which is already ported.

## Background — what LOCA is, and the terminology trap

- **LOCA = Longitudinal / Axial Chromatic Aberration** = *axial dispersion*. Different wavelengths focus at different distances, so at a hard bright↔dark edge the in-focus wavelength band differs between the two sides of the edge. Visible as **hue fringing**: magenta on one side, green on the other, concentrated where scene contrast is high. Also called **purple/green fringing**.
- **LCA = Lateral / Transverse Chromatic Aberration** = *geometric* red/blue misregistration (radial scaling difference). This is the part already ported — in RapidRAW it is `chromaticAberrationRedCyan` / `chromaticAberrationBlueYellow` (`ca_rc` / `ca_by`, `shader.wgsl:1713-1718`, applied via `apply_ca_correction`); in our tree it is the ported lateral-CA pass. **Not** the subject of this study.
- **Colloquial "purple fringing" conflates two distinct phenomena** (important for scoping):
  1. **True axial CA** at focus edges — hue fringe with a *local chroma gradient* (edge-aware detector needed).
  2. **Clipped-highlight chromatic bleed** (sensor bloom): in a near-clipped highlight, R and B clip but G does not, leaving magenta. This is *not* axial CA; it is a highlight-encoding artifact. It is what RapidRAW's `recover_clipped_pixel` fixes, and **it is already partially covered by our bypass's global highlight-chroma roll-off** (`FOTLAB-RENDER-000001`).

The value of this study: (a) the two reference algorithms give us two *families* of LOCA correction; (b) our OKLab/Oklch bypass is the ideal place to host either/both, because it is already a transparent camera-space-in/out, D65-anchored, OKLab round trip.

## Chapter 1 — RawTherapee LOCA: `PF_correct_RT` ("Defringe in Lab mode")

Source: `external/RawTherapee/rtengine/PF_correct_RT.cc:51-214`. (Sibling `PF_correct_RTcam` at `:217` is the identical algorithm in CIECAM02 LCh; `Badpixelscam`/`BadpixelsLab` at `:434`/`:867` reuse the same detector for chroma bad-pixel filtering.)

Verified step-by-step:

1. **Per-channel blur of the opponent axes**: Gaussian-blur `lab->a` and `lab->b` with `radius` → `tmpa`, `tmpb` (`:73-74`).
2. **Fringe metric** (per pixel): `chroma = (a − tmpa)² + (b − tmpb)²` (`:117`). This is the *local chroma variance* — a pixel whose `(a,b)` differs strongly from its blurred neighbourhood is a fringe pixel. **This formula is space-agnostic**: it only needs opponent axes `a,b`; it drops into Oklab unchanged.
3. **Optional hue restriction** (`:100-115`): a user `defringe.huecurve` modulates the metric:
   `chparam = chCurve->getVal(Color::huelab_to_huehsv2(atan2(b,a))) − 0.5;` then `chromaChfactor = SQR(1 + chparam)` (and `chparam` is doubled if negative for stronger action). The call to **`huelab_to_huehsv2` exists precisely because CIELAB hue is not a clean angle** — magenta is not at a tidy 300°. (This remap vanishes under Oklch — see Chapter 4.2.)
4. **Global mean** `chromave = mean(chroma)` (`:124`).
5. **Reciprocal weight** `fringe[j] = 1 / (chroma[j] + chromave)` (`:133`) — high-fringe pixels get a *small* weight, smooth regions a large one.
6. **Threshold** `threshfactor = 1 / (SQR(thresh/33) * chromave * 5.0 + chromave)` (`:136`).
7. **Correct**: for every pixel with `fringe[j] < threshfactor`, replace its chroma with the **chroma-weighted neighbourhood average**:
   `a ← Σ wt·a_orig / Σ wt`, `b ← Σ wt·b_orig / Σ wt`, where `wt = fringe[i1][j1]` over a `(2·halfwin+1)` window (`:151-212`). **`L` is never touched.** The window is `halfwin = ceil(2·radius)+1` (`:137`).

**Takeaway**: the entire kernel is a blur-diff detector + weighted-a,b replacement. Every step operates on `(a,b)` opponent axes or on `L` alone — no CIELAB-specific constant except the *threshold calibration* (`thresh/33`, `5.0`), which is tuned for CIELAB's `a,b` magnitude (order 10²). Those constants must be re-derived for Oklab scale, but the *structure* is identical.

## Chapter 2 — RapidRAW LOCA: `recover_clipped_pixel`

Source: `external/RapidRAW/src-tauri/src/raw_processing.rs:60-100+`. This is the only LOCA-adjacent routine in RapidRAW; it lives in **linear RGB**, not Lab/LCH.

- **Detector**: `let magenta = (cur_r.min(cur_b) − cur_g).max(0.0);` (`:74`). Magenta = R and B high, G low.
- **Highlight gate**: `outer_blend = smootherstep(0.50, 1.5, max_c);` (`:72`); the correction only fires when `max_c > 0.50` (near-clipped highlight) — i.e. it targets phenomenon (2) above, the clipped-highlight bloom, not true edge LOCA.
- **Correction**: lift G toward `min(R,B)*0.80 + (R+B)/2*0.20` (`:76-78`), then a residual loop (`:81-84`). This directly desaturates the magenta in RGB.

Supporting evidence that RapidRAW thinks in hue bands for these colours:
- `shaders/shader.wgsl:193-194` `HSL_RANGES`: `HslRange(280.0, 55.0) // Purple`, `HslRange(330.0, 50.0) // Magenta`.
- `tagging.rs:110`: `_ if (260.0..340.0).contains(&h) => "purple"`.
- `ca_rc` / `ca_by` (`:1713-1718`) are **lateral CA**, already ported — excluded here.
- `shader.wgsl:1670-1698` (`halation`) is an *artistic* glow, not a correction.

**Takeaway**: RapidRAW's approach is the *highlight-gated magenta suppression* family — per-pixel, RGB-domain, no edge awareness. It solves phenomenon (2) cleanly; it does not address true edge LOCA (1).

## Chapter 3 — Our OKLab/Oklch bypass (`FOTLAB-RENDER-000001`)

Sources: `app/src/binding/rust/rawler_fotlab/src/calibrate.rs:120-166` (setup), `:385-415` (`oklab_highlight_compress_pixel`), `:445-490` (tests).

The bypass is exactly the transparent stage the user describes:
- Enabled *per branch* (`oklab_highlight_compress_srgb` for the `SrgbD65` presentation path, `oklab_highlight_compress_prophoto` for `ProPhotoD50`; sRGB on, ProPhoto off by default) (`:121-124`).
- Runs **camera-space-in / camera-space-out**, anchored on **XYZ(D65)** — `camera → XYZ(D65) → OKLab → XYZ(D65) → camera` — so it is transparent to the destination primaries; **no D50↔D65 Bradford bridge** is needed on the ProPhoto branch (`:125-129`, `:138-158`).
- The 3×3 maps are built from the *same* factors the pipeline already uses, so the round trip is the exact identity for untouched pixels (`:131-136`).

The current pixel kernel (`:385-415`):
```
xyz = cam2xyz · cam
lab = xyz_to_oklab(xyz)
if l <= OKLAB_KNEE_START { return cam }        // no round trip, no f32 drift
c = sqrt(a*a + b*b)
if c <= 0 { return cam }
t = clamp01((l - KNEE_START)/(KNEE_END - KNEE_START))
f = t*t*(3 - 2*t)                              // smoothstep
scale = 1 - f
lab2 = [l, a*scale, b*scale]                   // keep L & hue, shrink C
xyz2 = oklab_to_xyz(lab2); return xyz2cam_eff · xyz2
```
The test `bypass_reduces_chroma_on_highlight` (`:476-490`) feeds XYZ `[1.0, 0.78, 1.0]` (sRGB magenta cast) and asserts `C` shrinks — i.e. **the bypass already desaturates magenta in highlights**. But it is a **global `L`-knee**, not edge-aware: it desaturates *every* high-lightness chroma pixel, not only genuine fringe. So it covers phenomenon (2) and leaves true edge LOCA (1) unaddressed.

This is the key architectural hook: **LOCA correction is an extra stage inside the same OKLab/Oklch round trip** — after `xyz_to_oklab`, before `oklab_to_xyz` — reusing `cam2xyz`/`xyz2cam_eff`/`m1_inv`/`m2_inv`. No new colour-space machinery needed.

## Chapter 4 — Why Oklab/Oklch beats CIELAB/CIECAM02 here

1. **Chroma-uniformity suppresses false positives.** CIELAB overstates blue/magenta chroma — exactly the fringe band. RT's `chroma = (a−blur a)²+(b−blur b)²` therefore *over-fires* in magenta regions even with no fringe, causing unwanted desaturation. Oklab/Oklch has "improved hue linearity, hue uniformity, and chroma uniformity compared to CIE LCH" (CSSWG, CSS Color 4) → the detector is hue-stable. Corroborating in-tree evidence: RT's own vibrance (`ipvibrance.cc:226-320`) needs a hand-tuned per-hue × per-L gain table + skin protection precisely *because* CIELAB chroma is perceptually uneven.
2. **Clean hue gate.** RT's `huelab_to_huehsv2` remap (`PF_correct_RT.cc:108,304`) exists only because CIELAB hue is not a clean angle. In Oklch, `h` is already a clean 0–360° perceptual hue, so restricting correction to purple/magenta is simply `h ∈ [260°, 340°]` — matching RapidRAW's `tagging.rs:110` and `HSL_RANGES` 280°/330°. No remap, no LUT.
3. **The correction is a chroma-vector average.** The weighted-average replacement of `(a,b)` (RT step 7) pulls a fringe pixel toward the local chroma centre. In a more uniform space the inter-vector "distance" is more perceptual, so the pull is more natural and leaves fewer colour artefacts.

## Chapter 5 — Two design options inside the bypass

### Option A — Edge-aware (RawTherapee family, true LOCA / phenomenon 1)
Add a local `(a,b)` window inside the round trip:
1. separable Gaussian blur of Oklab `a,b` over the full image (two extra full-res `f32` buffers, the Oklab analogue of RT's `tmpa`/`tmpb`);
2. `chroma = (a−tmpa)² + (b−tmpb)²`;
3. `fringe = 1/(chroma + mean(chroma))`, threshold vs `mean`;
4. for flagged pixels, replace `a,b` with chroma-weighted neighbourhood average (keep `L`).

*Pros*: correct true axial-CA detection; math is a literal port of `PF_correct_RT` with `(a,b)` = Oklab axes. *Cons*: **breaks the current per-pixel, rayon-parallel, no-cross-pixel design** (needs a second pass + a window); architectural change. The `thresh/33` and `5.0` constants must be re-derived on Oklab scale (Oklab `a,b` of sRGB primaries ≈ 0.3–0.4, vs CIELAB `a,b` in the hundreds — ~2–3 orders of magnitude difference), but the *structure* is unchanged.

### Option B — Hue-gated highlight-magenta (RapidRAW family, phenomenon 2)
Per-pixel, fits the current design with no second pass. Add a hue gate `h ∈ [260°,340°]` (purple/magenta) to the existing `L`-knee so only purple/magenta *highlights* are desaturated, not all highlights. The knee, the OKLab round trip, and the `C`-shrink are already present (Chapter 3) — this is a ~90%-done easy win that converts the global knee into a *targeted* purple-fringe suppressor.

> **Caveat — a *pure* hue gate false-positives on legitimate purple.** Hue only encodes "what colour", not "is this an artifact": a real purple flower, garment, or sunset shares the same `h ∈ [260°,340°]` band as a clipped magenta fringe. Both reference implementations confirm the correct design is **clip/edge-gated, with hue at most a qualifier**:
> - RT's `PF_correct_RT` triggers on a **blur-difference chroma edge** `chroma = (a−blur a)² + (b−blur b)²` (`PF_correct_RT.cc:117`); the hue curve (`:108`) is only a *strength modulator* (`chromaChfactor`), never the gate — a uniform purple region has `a≈blur a`, `b≈blur b` ⇒ `chroma≈0` ⇒ not flagged.
> - RapidRAW's `recover_clipped_pixel` triggers on `max_c > 0.5` **and** the magenta signature `min(R,B) − G > 0` (`raw_processing.rs:64,74`); its `260..340 => purple` tag (`tagging.rs:110`) is content *classification*, never reaching the recovery path.
> Oklab's cleaner hue does **not** remove this ambiguity — it only makes a hue *modulator* more stable. The fold's current `L`-knee is already safe (luminance-gated); if a hue gate is added it must keep the clip/edge condition as the **primary** trigger and treat hue as an `AND` qualifier only. See the corresponding constraint in `FOTLAB-RENDER-000002`.

### Hybrid (recommended research direction)
Ship **B first** (cheap; covers clipped-highlight bloom, the most common "purple fringe" complaint) and **A later** (covers true edge LOCA). Both live in the same OKLab/Oklch round trip, so LOCA becomes one transparent stage instead of two scattered fixes.

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` and `external/RapidRAW` remain fixed constraints — this study records their algorithms and proposes re-expressing the *idea* inside our first-party Rust bypass; no patch to the external trees is specified or permitted. The OKLab math we would reuse is already vendored into `calibrate.rs` (`OKLAB_M1`, `OKLAB_M2`, `xyz_to_oklab`, `oklab_to_xyz`), consistent with `RAWTRP-SURVEY-000002` (which established RawTherapee also ships an `rgb2oklab`/`oklab2rgb` pair, shipped but unbound). Whether and how to implement remains a first-party DESIGN/RENDER decision (promote to a `FOTLAB-RENDER-*` or `FOTLAB-PIPELN-*` item when coding begins).

## Open Questions

- **Q1** — Which family first: B (cheap, covers bloom) or A (true edge LOCA)?
- **Q2** — For A, what default blur `radius` (RT's default is small, ~1–2 px)? And separable vs. box?
- **Q3** — Should LOCA be a *sub-stage* of the existing highlight-compression bypass, or a *sibling* stage with its own gate?
- **Q4** — True axial CA also carries a luminance envelope; RT leaves `L` alone (chromatic-only). Should we also damp `L` at detected fringe pixels?
- **Q5** — Hue-gate window for "purple/magenta": Oklch `[260°,340°]` or wider (RapidRAW uses 280°±55 and 330°±50)?

## Change History

- **2026-10-04** — Filed `RAWTRP-SURVEY-000005`. Research only (no code). Established: (1) LOCA ≠ LCA; LCA already ported, LOCA = axial/purple-fringe. (2) RT's `PF_correct_RT` (PF_correct_RT.cc:51-214) = blur-diff chroma detector + chroma-weighted a,b average, CIELAB/CIECAM02, with a `huelab_to_huehsv2` hue remap. (3) RapidRAW's `recover_clipped_pixel` (raw_processing.rs:60-100+) = RGB-domain, highlight-gated magenta suppressor (clipped-highlight bloom, not true edge LOCA); `ca_rc`/`ca_by` = lateral CA (already ported). (4) Our `FOTLAB-RENDER-000001` OKLab highlight-compression bypass (calibrate.rs:120-166,385-415) already desaturates magenta highlights via a global `L`-knee but is not edge-aware. (5) Oklab/Oklch is better for this: chroma uniformity cuts false positives, clean `h` removes the hue remap, vector-average is more perceptual. (6) Two design options — A edge-aware (RT port, needs second pass) and B hue-gated (RapidRAW-style, per-pixel) — proposed as a hybrid, both hosted in the same OKLab/Oklch round trip.
- **2026-10-05** — Added a caveat to Option B: a *pure* hue-band gate (`h ∈ [260°,340°]`) false-positives on legitimate purple objects; both references gate on clip/edge (RT blur-diff chroma `PF_correct_RT.cc:117` with hue only as modulator `:108`; RapidRAW `max_c>0.5` + `min(R,B)−G` signature `raw_processing.rs:64,74`, with `tagging.rs:110` purple tag being classification-only). Oklab's clean hue does not resolve the ambiguity; hue must be an AND-qualifier, never the sole trigger. Cross-linked to the new constraint in `FOTLAB-RENDER-000002`.
