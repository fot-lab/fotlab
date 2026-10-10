# Reference defringe design-philosophy comparison — Unpurple (forward synthesis + bounded subtraction) vs RawTherapee PF_correct_RT (statistical detection + robust chroma replacement)

- ID: FOTLAB-DEFRNG-000003
- Status: Draft
- Priority: P2
- Created: 2026-10-10
- Owner: —
- Related: FOTLAB-DEFRNG-000001 (the project's OKLab defringe — sits in the Unpurple school), FOTLAB-DEFRNG-000002 (color-space basis of Unpurple), FOTLAB-RENDER-000003 (pre-demosaic ACA), FOTLAB-RENDER-000004 (shearlet CA); references analysed: `external/purple-fringe/src/unpurple.ml`, `external/RawTherapee/rtengine/PF_correct_RT.cc`, `external/RawTherapee/rtgui/tools/defringe.cc`

## Background & Goal

This record captures a research question about the two reference defringe
implementations vendored under `external/`:

> **How do `external/purple-fringe` (Unpurple, OCaml) and the RawTherapee defringe
> (`PF_correct_RT.cc`) differ in algorithm design philosophy?**

The answer informs the project's own defringe (`FOTLAB-DEFRNG-000001`,
`defringe_oklab_aca.rs`), which inherited Unpurple's "reconstruct-and-subtract" idea.
This is a research/survey record, not a new feature spec — it records the outcome of
the comparison so future design decisions can cite it.

## Finding

### The core divergence

Both tools solve the same problem, but their philosophies are nearly orthogonal:

- **Unpurple — forward physical modeling.** It commits to an optical mental model
  (short wavelengths blur out of focus → purple fringe), then *synthesizes* an
  artificial fringe from the image and subtracts it under hard bounds. The README
  (`external/purple-fringe/README.md`, "Intuition and future prospects") states the
  idea explicitly: *"we create a purple fringe from the image which already has a
  purple fringe."*
- **RawTherapee — backward statistical detection.** `PF_correct_RT.cc` makes no
  causal assumption. A fringe is defined as a *local chroma outlier*: pixels whose
  chroma deviates from a Gaussian-blurred neighborhood reference are detected and
  repaired by replacement. The file header notes the mechanism is *"not restricted
  to 'Purple'"* — it is a generic defringe.

### Pipeline comparison

**Unpurple** (`external/purple-fringe/src/unpurple.ml`):

1. **Blue channel + brightness gate** (`make_purple_blur`, lines 103–122): the blue
   channel is the physical proxy for defocused short-wavelength light; gated by
   `min_brightness` so only bright fringe light seeds the mask.
2. **Tent blur = defocus simulation** (lines 99–121): two separable box-blur passes,
   O(1) per pixel via sliding window. The blur radius has an explicit *physical*
   meaning — it models the defocus PSF spread.
3. **Bounded subtraction** (`remove_purple_blur`, lines 124–177): remove
   `r_diff`/`b_diff` where `db = max(b−g, 0)`, `dr = max(r−g, 0)`, capped by
   `mb = min(mask, db)` and the `min/max_red_to_blue_ratio` constraints. R and B can
   never drop below G — **worst case the pixel turns grey, never green**
   ("grey … has the advantage of being discreet", README).

**RawTherapee** (`external/RawTherapee/rtengine/PF_correct_RT.cc`):

1. **Neighborhood chroma reference** (lines 73–74): Gaussian blur of Lab `a`/`b`;
   the luminance channel `L` is never touched.
2. **Chroma-outlier detection** (lines 117–136): `chroma = (Δa)² + (Δb)²` — a
   chroma high-pass energy. Compared against the image-wide mean `chromave`:
   weight `fringe = 1/(chroma + chromave)`, threshold
   `threshfactor = 1/((thresh/33)²·chromave·5 + chromave)`. Detection criteria
   auto-scale with image content.
3. **Chroma-weighted replacement** (lines 151–211): flagged pixels get their `a`/`b`
   replaced by the chroma-weighted neighborhood mean of the original image. The
   weight is the inverse anomaly `1/(chroma+chromave)` — fringe pixels (including
   the fringe itself) contribute little; clean flat pixels dominate. This is robust
   estimation in chroma space; the replacement is a convex combination of
   neighborhood chroma, so it is **bounded by construction** (no clamping needed).
4. **Hue selectivity is user-provided, not modeled** (lines 99–115): an optional flat
   curve `C = f(H)` modulates strength via `chromaChfactor = (1 + chparam)²` — this
   is what narrows the generic detector to purple when desired.
5. **Engineering posture**: OpenMP with dynamic scheduling (Issue 1674), SSE2/SIMD
   vectorization, three-way loop split to avoid min/max (Issue 1972), double
   precision for global sums, buffer reuse, and a shared "chroma-outlier → weighted
   mean" machinery reused by `Badpixelscam`/`BadpixelsLab` — fringes and bad pixels
   are treated as **one phenomenon class**.

### Dimension-by-dimension

| Dimension | Unpurple (OCaml) | RawTherapee defringe (C++) |
| --- | --- | --- |
| Causal assumption | Optical model: short-wavelength defocus | None; chroma-outlier phenomenon |
| Working space | 8-bit sRGB channel algebra | Lab / CIECAM02 chroma axes, `L` untouched |
| "Purple" defined by | Fixed channel-ratio bounds (`max_red_to_blue_ratio` 0.33) + brightness gate | Global chroma statistics + user hue curve |
| Repair operator | One-directional subtraction (can only desaturate) | Weighted-mean replacement (can shift hue toward neighborhood) |
| Safety philosophy | Hard invariants — worst case grey | Soft weights + convex combination — bounded by construction |
| Adaptivity | None; global constants + `-gentle` preset | Per-image `chromave`; threshold and weights self-scale |
| Luminance | `min_brightness` gate on the blue channel | `L` never modified (structure zero-damage) |
| Engineering form | ~260-line personal tool, ~1 s/MP, 8-bit JPEG in/out | Production pipeline component: OpenMP, SIMD, crop-aware, GUI curve |
| Defaults | radius 5 px, intensity 1.0, max_red 0.33 | radius 2.0 (0.5–5.0), threshold 13 (0–100) (`rtgui/tools/defringe.cc` lines 59–60) |

### Convergent safety doctrine

Despite the opposite philosophies, both share the same conservative bottom line:

1. **Anchor on the trusted part.** Unpurple uses G as the floor (green is orthogonal
   to the purple fringe); RawTherapee leaves `L` untouched. Neither damages
   luminance structure.
2. **Remove only the excess/deviation.** Unpurple subtracts `max(ch − G, 0)`;
   RawTherapee only acts on pixels above the adaptive chroma threshold. Legitimately
   saturated content is not touched by either — until it misfires (below).
3. **Blur as the reference frame — with different meanings.** Unpurple's blur is a
   *physical simulation* (defocus PSF); RawTherapee's blur is a *statistical
   baseline* (low-pass neighborhood reference).
4. **Neither restores "true color".** Unpurple settles for grey; RawTherapee settles
   for the neighborhood mean chroma.

### Complementary false-positive profiles

Each school's blind spot is the other's strength:

- **RawTherapee's detector ignores luminance** — a legitimately saturated color
  transition (red flower against green foliage) is also a local chroma outlier, so
  the generic detector misfires there and must be narrowed by the user's hue curve.
  Unpurple's `min_brightness` gate addresses exactly this failure mode.
- **Unpurple has no image statistics** — its fixed constants cannot adapt across
  images (a dark moody frame and a high-key frame get identical treatment).
  RawTherapee's per-image `chromave` normalization addresses exactly this.

### Implication for FOTLAB

The project's `defringe_oklab_aca` (`FOTLAB-DEFRNG-000001`) sits in the Unpurple
school (defocus halo + `min(halo, C)` cap). If saturated-content false positives
appear in practice, the remedy is to borrow from the other school: normalize the
halo/cap by a per-image chroma statistic (a RawTherapee-style `chromave` analog in
OKLab), keeping Unpurple's brightness gate as the classification prior. The two
reference designs are complementary, not competing.

## Constraints

- This is a research record; it specifies **no code change**. `external/purple-fringe`
  and `external/RawTherapee` are fixed upstream constraints per `rules/DESIGN.md`
  principle 5.
- All file/line references were verified by direct reading of the vendored sources
  on 2026-10-10.
- The legacy comment at `PF_correct_RT.cc` line 155 ("pixel darker than … near an
  edge …") predates the current chroma-only test; the code as written tests chroma
  deviation only. Cited behaviour follows the code, not the comment.

## Acceptance Criteria

Not applicable — this document records an analysis, not a buildable change. The
analysis is accepted as correct if a reviewer confirms:

- The cited line ranges match the vendored sources (`unpurple.ml` lines 103–122,
  124–177; `PF_correct_RT.cc` lines 73–74, 99–115, 117–136, 151–211).
- The two safety invariants are stated correctly: Unpurple's R/B ≥ G bound, and
  RawTherapee's untouched `L` with convex-combination `a`/`b` replacement.
- The complementarity claim maps to actual code: RawTherapee's detection contains no
  luminance term, and Unpurple contains no image-derived statistics.

## Impacted Modules

- `external/purple-fringe/src/unpurple.ml` — reference analysed (no change).
- `external/RawTherapee/rtengine/PF_correct_RT.cc` — reference analysed (no change).
- `app/src/binding/rust/rawler_fotlab/src/defringe_oklab_aca.rs` — the project's
  defringe (FOTLAB-DEFRNG-000001); potential future consumer of the adaptive-
  normalization finding (see Open Questions).

## Open Questions

- Should `defringe_oklab_aca` adopt a per-image adaptive cap (a RawTherapee
  `chromave` analog computed in OKLab) in addition to the fixed `defocus_cap`, to
  self-scale correction strength across images?
- Does RawTherapee's inverse-chroma weighting have an OKLab analog worth porting —
  e.g. weighting the defocus halo by neighborhood chroma uniformity so that outlier
  halo sources contribute less?

## Change History

- 2026-10-10 — Created (Draft). Records the design-philosophy comparison of the two
  reference defringe implementations: Unpurple = forward physical modeling
  (synthesize the fringe from the brightness-gated blue channel via tent blur, then
  bounded subtraction with R/B ≥ G invariants), RawTherapee `PF_correct_RT` =
  backward statistical detection (Lab a/b high-pass energy vs per-image mean, then
  inverse-chroma-weighted neighborhood replacement, `L` untouched). Documents the
  convergent safety doctrine (anchor on the trusted part, remove only excess, blur
  as reference frame, never restore true color) and the complementary false-positive
  profiles (RawTherapee lacks a luminance gate → misfires on saturated transitions;
  Unpurple lacks image statistics → does not adapt across images), with the
  implication that `defringe_oklab_aca` can borrow RawTherapee-style per-image
  normalization while keeping Unpurple's brightness gate.
