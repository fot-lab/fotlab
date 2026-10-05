# External module study — per-pixel purple-fringing removal: algorithm families beyond RawTherapee and RapidRAW

> **Naming**: `RAWTRP-` project code (RawTherapee) + six-character `SURVEY` category, under `rules/STRUCT/detail/` per STRUCT.md principle 5 (`external/` modules as fixed constraints). ID is permanent; scope can widen without renaming.
>
> **Scope**: research only — **no code change**. Sequel to `RAWTRP-SURVEY-000005`, which split purple-fringe correction into phenomenon (1) true axial-CA edge fringes (RT's edge-aware family) and phenomenon (2) clipped-highlight bloom (RapidRAW's highlight-gated family). Motivation for this study: the edge-aware family couples per-pixel cost to a **blur radius** (the edge "search range"), which scales poorly at modern sensor resolutions. This file surveys what exists on the **per-pixel side** — papers, patents, and open-source implementations beyond the two already studied — and records their detector/corrector structure and compute-cost profile. Findings are recorded as facts; no recommendation is made.

## Background — why "per-pixel" is a real design space

`RAWTRP-SURVEY-000005` recorded RT `PF_correct_RT` (Gaussian blur of `a,b`, radius parameter, window average) and darktable `defringe` ("uses the difference between the input image and a gaussian-blurred version … to detect edges", radius parameter) as the edge-aware family. Both couple the *edge cue* to a variable-radius neighbourhood. The surveyed alternatives show the edge cue can instead be:

- **(a) dropped entirely** — colour-only gating (hue band + saturation/luminance + channel ordering);
- **(b) a fixed 3×3 gradient stencil** — O(1) per pixel, line-bufferable, resolution-independent;
- **(c) one fixed global blur** — a budgeted constant-cost pass, not a per-pixel search.

RapidRAW's `recover_clipped_pixel` (already studied) is the minimal member of (a). The families below fill out the rest of the landscape.

## Family 1 — Pure per-pixel colour gating (O(1)/px, no neighbourhood)

### 1a. Microsoft patent US 7,577,292 (S. Kang, 2007) — "Automatic removal of purple fringing from images"

Verified from the patent text (freepatentsonline.com/7577292):

- **Detector**: per-pixel — *candidate* pixels satisfy "blue and red intensity values that are considerably larger than a green intensity value"; *near-saturated* pixels satisfy a per-pixel saturation threshold. The only spatial step is a **region-adjacency test** (a candidate region adjacent to a near-saturated region is designated purple-fringed) — a connectivity operation, not a blur or a window search.
- **Corrector**: pulls R and B **toward G** (opposite direction to RapidRAW, which lifts G toward `min(R,B)`):
  `R_corr = βR·R + (1−βR)·G`, `B_corr = βB·B + (1−βB)·G`, with `βR, βB ∈ [0,1]`; variants blend toward a weighted-average intensity `I = λR·R + (1−λR−λB)·G + λB·B`, and a feathering variant ramps `((N−L)·I + L·C)/N` over distance `L` from the monochrome core.
- Same author lineage: Kang, "Automatic Removal of Chromatic Aberration from a Single Image" (CVPR 2007) and US patent application US 2007/0153341 "Automatic removal of purple fringing from images".

### 1b. US 2024/0273690 A1 — "Systems and methods for purple fringe correction" (2024, ISP pipeline)

Verified from the patent text (patents.google.com). This documents the per-pixel approach inside a **hardware ISP real-time budget** (line-buffered RGB, block 402):

- **Detector**: compares R and B against G, with a **confidence** value produced by a detector module.
- **Corrector, two per-pixel paths blended by confidence**:
  1. **"Correction to Gray"** — a *square coring function* adjusts R and B toward G (pushes small chroma toward zero, preserves large), strictly per-pixel;
  2. **"Correction to Average"** — adjusts chroma toward an average chrominance estimated during detection (this path needs line-buffer statistics, not a full second pass).
- A `TotalCorrectionWeight` blends corrected vs original pixels to avoid abrupt transitions. Explicitly motivated as "computationally economical" real-time processing.

### 1c. STMicroelectronics — YCrCb hue-band gating (Tomaselli, Guarnera, Bruna, Curti)

Paper: "Automatic detection and correction of purple fringing artifacts through a window based approach" (ST Catania; ResearchGate 235991291). Verified from the abstract/section text:

- **Detector**: per pixel, hue `θ = atan2(Cr, Cb)` and saturation `SAT` from chrominances; a purple hue range is `F_θ ± θ_max` in the CbCr plane, floored by a saturation threshold; a continuous **purple degree** `PD(x,y) ∈ [0,1]` measures closeness of the pixel's hue to the purple centre (their eq. 2). The prior art they cite ("The method in [x] calculates the purple degree of a pixel … correction … achieved through a simple desaturation on chrominances") is the strictly per-pixel form.
- **Corrector**: proportional desaturation of chrominance weighted by `PD` — a *continuous* hue gate, not a hard window. Their own novelty is a **running window** (one pixel at a time, small fixed footprint) for gradual transitions; window size must match the device's fringe width, and downsampling chroma shrinks it.
- This is the closest published precedent to the hue-gated structure described as Option B in `RAWTRP-SURVEY-000005`, in YCrCb with an `atan2` hue extraction (which Oklch's clean `h` makes unnecessary).

### 1d. Boundedness of the Family-1 RGB detector (static analysis, this session)

For RapidRAW's `magenta = (min(R,B) − G).max(0)` with G-lift corrector (`raw_processing.rs:74-84`), verified line-by-line earlier in this session:

- The `min()` binds the metric to the **smaller** channel: a lone-high-R pixel (B low) yields `min(R,B)=B` (low), so the metric is capped by the low channel; pure primaries (`B ≤ G` for red) produce zero. Over-flagging is confined to primaries that already lean magenta (B > G for red, R > G for blue).
- The residual step never pushes G **above** `min(R,B)`, and `outer_blend = smootherstep(0.50, 1.5, max_c) ≤ 0.5` for `max_c ≤ 1.0` — so the spurious G-lift on a saturated (non-clipped) primary is bounded by the complementary low channel. Worked example: sRGB (255,25,45) → linear (1.0, 0.0038, 0.0154); G lifts 0.0038 → 0.0588 (linear +0.055, ≈ +53 sRGB levels), saturation index `1 − min/max` drops ≈ 1.1 pp.
- External corroboration: DCA-LUT (Family 4) names this exact rule-based detector ("red and blue channel values being notably higher than the green one") as unreliable — it "often mistakes legitimate purple objects for artifacts" — and uses that as motivation for a learned approach.

## Family 2 — Per-pixel + fixed 3×3 gradient stencil (O(1)/px, line-bufferable)

**Kim & Park**: "Automatic Detection and Correction of Purple Fringing Using the Gradient Information and Desaturation" (EUSIPCO 2008, paper 1569101556) and the journal version "Detection and correction of purple fringing using color desaturation in the xy chromaticity diagram and the gradient information" (*Image and Vision Computing* 28(6):952-964, 2010).

Verified from the journal abstract:

- **Detector**: pixels with **large gradient magnitude** whose chromaticity falls inside a **purple region preset in the CIE xy chromaticity diagram**. The gradient is a fixed small stencil (the paper positions it as in-camera post-processing), *not* a variable-radius blur — per-pixel cost is constant, requires only a line buffer, and does not scale with resolution or radius.
- **Corrector**: colour **desaturation of the detected pixels in the CIE xy chromaticity diagram** (scale toward the achromatic axis).
- Claimed benefit: detects fringe artifacts "more precisely" than colour-only methods by requiring both the chromaticity condition and an edge condition.

This is the structural middle ground: it keeps an edge cue (covers phenomenon (1)-adjacent cases colour gating alone can miss, e.g. shadow-side fringes below a luminance gate) while keeping O(1) per-pixel cost.

## Family 3 — One fixed global blur mask + per-pixel subtraction (open source)

**Unpurple** — `github.com/mjambon/purple-fringe` (OCaml CLI, ~1 s/megapixel; forks `rnbguy/purple-fringe`, `angryPsybear/purple-fringe`; ported to G'MIC by Stanislav Paskalev).

Verified from the README ("Algorithm outline"):

- **Detector/corrector combined**: produce a **blurred mask from the blue component** of the image (one global blur — a budgeted fixed pass); then **subtract** an amount of blue and red proportional to the mask, under three per-pixel constraints: blue may not drop below green; red may not drop below green; the red:blue ratio may not drop below a constant.
- Direction is again "remove R/B" (like the Microsoft patent), the dual of RapidRAW's "lift G".
- The author's recorded mental model: short wavelengths (violet/UV) defocus and leak; the bright regions "still contain most of the purple they should contain", so the method reconstructs an *artificial* fringe from the image and subtracts it, rather than locating the real fringe.

## Family 4 — ML / LUT (inference ~per-pixel; LUT application is O(1))

- **DCA-LUT** — "Deep Chromatic Alignment with 5D LUT for purple fringing removal" (AAAI 2026; arXiv:2511.12066). States it is the first deep-learning framework for purple fringing. A Chromatic-Aware Coordinate Transformation (CA-CT) module learns an image-adaptive colour space that decouples fringing into a dedicated "purple fringe channel"; final colour correction is a **learned 5D LUT** (per-pixel table lookup). Ships a synthetic dataset **PF-Synth**. The network front-end contains convolution (neighbourhood) stages; only the final correction is compressed into a per-pixel LUT.
- **CAST-LUT** — "CAST-LUT: tokenizer-guided HSV LUT for purple glow removal" (AAAI 2026; same author cluster: Shandong University / UCAS / Hubei University et al.). Two-stage: a Chroma-Aware Spectral Tokenizer encodes H/V as semantic tokens; an HSV-LUT module **dynamically generates independent 1D-LUTs for H, S, V**. Conceptually a *learned per-pixel hue gate* — the trained counterpart of the Family-1c hue band. Introduces the PFSD dataset and fringe-specific metrics (PSNR-F/NF, hue-alignment error HAE).

## Verified non-members (recorded for completeness)

- **darktable `defringe`** — deprecated since 3.6 in favour of the chromatic aberrations module. Official docs: "uses edge-detection … the difference between the input image and a gaussian-blurred version … to detect edges", with an `edge detection radius` control and global/local-average colour references — i.e. the same radius-coupled family as RT `PF_correct_RT` (the module author's mailing-list post describes it as "similar to what RAW Therapee does"). Not per-pixel.
- **Adobe ACR/Lightroom Defringe** — closed source; user-tuned hue bands + amount applied per pixel (manual-hue-band variant of Family 1). No automatic per-pixel detector is documented.
- **lensfun / RT `cacorrection` / RapidRAW `ca_rc`/`ca_by`** — lateral (transverse) CA, geometric channel realignment; a different problem, already ported.

## Compute-cost profile (resolution/radius coupling, low → high)

| Method | Per-pixel cost | Extra memory | Radius/resolution coupling |
| --- | --- | --- | --- |
| Family 1 colour gating (patents, ST, RapidRAW) | O(1) arithmetic | none | none |
| Family 2 fixed gradient stencil (Kim & Park) | O(1) | 1-2 line buffers | none |
| Family 3 one global blur (Unpurple) | O(1) + one constant-cost full pass | full-image mask | one fixed pass |
| Family 4 LUT inference | LUT application O(1); fixed conv front-end | model + buffers | constant (network-fixed) |
| RT `PF_correct_RT` / darktable `defringe` | O(k) per pixel, k = radius | full-image blur buffers | **scales with radius and resolution** |

## Correspondence with RAWTRP-SURVEY-000005 options (factual mapping)

- ST's continuous hue gate (`F_θ ± θ_max` + saturation floor + `PD(x,y)`-weighted desaturation) matches the **structure of Option B** (hue-gated per-pixel suppression inside the OKLab round trip); the difference is coordinate space (YCrCb + `atan2` vs Oklch's clean `h`).
- Kim & Park's fixed-stencil detector shows the **edge cue of Option A** can be obtained at O(1) per-pixel cost with a line buffer, rather than via the radius-coupled Gaussian blur that made Option A an architectural change in the 000005 analysis.
- The two corrector directions in the wild are duals: **remove R/B toward G** (Microsoft patent, Unpurple) vs **lift G toward min(R,B)** (RapidRAW); the ISP patent adds a third target (average chrominance) and blends it with "toward gray" by confidence.
- DCA-LUT's critique of the rule-based `min(R,B)−G`-style detector (false positives on legitimate purple objects) is consistent with the boundedness analysis in §1d: the rule's failure mode is confined to magenta-leaning primaries, which is exactly the legitimate-purple ambiguity zone.

## Constraints (STRUCT.md principle 5)

All referenced patents, papers, and repositories are external references only; no patch to any external tree is specified or permitted. RapidRAW and RawTherapee remain covered by `RAWTRP-SURVEY-000005`; this file adds external landscape only. Whether and how any of this is implemented remains a first-party DESIGN/RENDER decision.

## Open Questions

- **Q1** — Is a hue gate sufficient alone, or does the fixed gradient stencil (Kim & Park) pay for itself against the false-positive evidence DCA-LUT documents for colour-only rules?
- **Q2** — Corrector direction: remove R/B toward G (patent/Unpurple duality), lift G (RapidRAW), or desaturate chroma in a perceptual space (ST/Kim&Park)? The choice interacts with the frozen-hue problem recorded in `FOTLAB-RAWLER-000018`.
- **Q3** — If Family 2 is used, which gradient stencil and which purple chromaticity region — and in which space (CIE xy as published, or Oklch `h`/`C`)?
- **Q4** — Are the AAAI 2026 LUT approaches relevant at all for a raw pipeline (they operate post-encode on display-referred images), or only as evidence about detector reliability?

## Change History

- **2026-10-04** — Filed `RAWTRP-SURVEY-000006`. Research only (no code). Established: (1) a four-family per-pixel landscape beyond RT/RapidRAW — pure colour gating (Microsoft US7577292; US20240273690A1 ISP "correction to gray/average" with square coring; ST YCrCb hue band + purple degree PD(x,y)); fixed 3×3 gradient stencil + CIE xy purple region (Kim & Park, EUSIPCO 2008 / IVC 2010); one global blur mask + constrained R/B subtraction (mjambon/Unpurple, G'MIC port); ML/LUT (DCA-LUT AAAI'26 5D-LUT + PF-Synth; CAST-LUT AAAI'26 per-channel 1D-LUTs). (2) Recorded the boundedness of the Family-1 RGB detector (min() caps by the low channel; over-flag confined to magenta-leaning primaries; worked example +0.055 linear G lift, ≈1.1 pp saturation drop) and DCA-LUT's published critique of the same rule. (3) Verified darktable `defringe` and Adobe Defringe as non-members (radius-coupled / manual bands). (4) Compute-cost table: Families 1-2 are radius/resolution-independent; only RT/darktable blur couples cost to radius. (5) Factual mapping to 000005: ST ≈ Option B structure in YCrCb; Kim & Park ≈ Option A's edge cue at O(1) cost.
