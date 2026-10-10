# Color-space basis of Unpurple — sRGB→ProPhoto swap: does the algorithm fail?

- ID: FOTLAB-DEFRNG-000002
- Status: Draft
- Priority: P2
- Created: 2026-10-10
- Owner: —
- Related: FOTLAB-DEFRNG-000001 (Oklch post-demosaic defringe — the OKLab port that this analysis justifies); `external/purple-fringe/src/unpurple.ml` (the reference implementation analysed); FOTLAB-RENDER-000003 (pre-demosaic ACA / purple-fringe), FOTLAB-RENDER-000004 (shearlet CA)

## Background & Goal

This record captures a research question about the upstream `external/purple-fringe`
("Unpurple", `unpurple.ml`) defringe heuristic:

> **If the working color-space basis is swapped from sRGB to ProPhoto RGB, does the
> algorithm fail, and what are the parameter differences?**

The question matters because the project's own OKLab defringe (`FOTLAB-DEFRNG-000001`)
inherited Unpurple's "reconstruct-and-subtract" idea but **deliberately dropped the
fixed red:blue ratio** used by `unpurple.ml`. This document records *why* that
decision is sound and what a raw sRGB→ProPhoto swap would actually do to the
reference algorithm. It is a research/survey record, not a new feature spec.

## Reference evidence — where the color-space assumption lives

Reading `external/purple-fringe/src/unpurple.ml`:

- The algorithm performs **no color-space conversion**. It reads raw 8-bit `Rgb24`
  `r,g,b` (0–255) straight from the JPEG and operates on the channel values:
  - blur mask: `b /. 255.` then `grey_level = max(0, b - thresh) /. (1 - thresh)`,
    times `intensity` (`make_purple_blur`, lines 103–122).
  - per-pixel repair: `db = max(b - g, 0)`, `dr = max(r - g, 0)`,
    `mb = min(bl, db)`, `r_diff = min(dr, mb * max_red_to_blue_ratio)`,
    then `b_diff` honouring `min_red_to_blue_ratio`, subtract from `r`/`b`
    (`remove_purple_blur`, lines 124–177).
- The **sRGB basis is implicit**, not enforced: it comes from (a) the JPEG intake
  defaulting to sRGB-encoded values, and (b) the default parameters being eyeballed
  on sRGB images.

Default parameters (`unpurple.ml` lines 15–19, `main` lines 196–224):

| Param | Default | Gentle mode (`-gentle`) |
| --- | --- | --- |
| `radius` | 5 (px) | 5 |
| `intensity` | 1.0 | 1.0 |
| `min_brightness` | 0.0 | 0.8 |
| `min_red_to_blue_ratio` | 0.0 | 0.15 |
| `max_red_to_blue_ratio` | 0.33 | 0.33 |

## Conclusion

**The algorithm does not structurally fail on ProPhoto input.** Because there is no
conversion step and all arithmetic is valid for any `[0,1]` channel values, feeding
ProPhoto-encoded RGB produces a legal `[0,255]` result — no crash, no NaN, no
out-of-bounds. However, the sRGB basis is baked into the default parameters, so
**correctness degrades systematically** unless the parameters are re-tuned for the
new basis.

Two mechanisms drive the degradation:

### 1. Transfer function (gamma) — bias toward *under-correction*

- sRGB uses γ≈2.4 (piecewise; effective ~2.2). ProPhoto uses a pure γ=1.8.
- Encoding is `encoded = linear^(1/γ)`. For 0<v<1: `1/1.8 ≈ 0.556 > 1/2.4 ≈ 0.417`,
  so `v^0.556 < v^0.417` — **ProPhoto stores a *smaller* value for the same linear
  light**. Concrete: linear 0.5 → sRGB ≈ 0.736, ProPhoto ≈ 0.681.
- Consequence inside `unpurple.ml`: `grey_level` (the blur-mask source), and the
  per-pixel excesses `db = b-g`, `dr = r-g`, all shrink. The cap
  `mb = min(bl, db)` therefore allows *less* removal → **the purple fringe is
  under-corrected (more residual fringe)** when the same defaults are kept.

### 2. Primaries / gamut — the red:blue "purple signature" shifts

- sRGB primaries: R(0.640,0.330) G(0.300,0.600) B(0.150,0.060), white D65.
- ProPhoto primaries: R(0.7347,0.2653) G(0.1596,0.8404) B(0.0366,0.0001), white D50.
- ProPhoto's blue primary is far deeper / more saturated than sRGB's, and its red
  primary leans more orange. The same out-of-focus short-wavelength fringe, mapped
  through the ProPhoto matrix, carries a **different R:B proportion** than through
  sRGB's matrix.
- The `max_red_to_blue_ratio = 0.33` (and `min_red_to_blue_ratio = 0.15` in gentle)
  caps are calibrated on how sRGB *packs* a saturated purple, not on a physical
  constant. In ProPhoto they must be re-derived from the actual R:B of representative
  ProPhoto fringes; the direction of the needed change is hue-dependent and cannot be
  predicted from the sRGB values alone.

## Parameter differences — sRGB vs ProPhoto

| Param | sRGB default | ProPhoto — does it need to change? | Direction |
| --- | --- | --- | --- |
| `radius` | 5 px | **No** — spatial blur radius, color-space independent | unchanged |
| `intensity` | 1.0 | **Yes** — gamma makes the mask/repair weaker → under-correction | raise above 1.0 (main lever) |
| `min_brightness` | 0.0 (gentle 0.8) | Already at floor 0; gentle mode would under-correct even more | cannot lower; rely on `intensity`; re-tune empirically |
| `max_red_to_blue_ratio` | 0.33 | **Yes** — R:B signature shifts with primaries | re-derive per basis |
| `min_red_to_blue_ratio` | 0.0 (gentle 0.15) | **Yes** — same reason | re-derive per basis |

**Net:** `radius` is basis-agnostic. The other four are coupled to sRGB's gamma +
primaries and must be re-tuned per basis — and only by eye, because the "purple =
high B, moderate R, low G" heuristic is itself a property of the working space, not
of the optics.

## Why the OKLab port already resolved this

`FOTLAB-DEFRNG-000001` (`defringe_oklab_aca.rs`) moved the defringe into OKLab
`(L,a,b)`, operating **post-demosaic, post-WB, in camera space**, and explicitly
**dropped the fixed R:B ratio** — noting that "R:B (and a*:b*) is NOT a fixed physical
ratio: it is nonlinear and luminance-dependent." Instead it gates on the **purple
quadrant `a>0, b<0`** plus the **a/b slope `s = a/(-b)`** (no hue-angle wrap-around).

This is the robust answer to the sRGB→ProPhoto question:

- In a *primaries-independent* space (OKLab), the sRGB↔ProPhoto choice only swaps the
  `RGB → linear → OKLab` input matrix. The purple quadrant and the slope band stay
  approximately valid, so the **basis choice becomes nearly irrelevant** — exactly the
  property the raw `unpurple.ml` lacks.
- The reference `unpurple.ml`'s whole selectivity (`b` above `g`, `r` above `g`, and a
  capped R:B) is the weak point the OKLab port removes.

## Constraints

- This is a research record; it specifies **no code change**. `external/purple-fringe`
  is treated as an upstream fixed constraint per `rules/DESIGN.md` principle 5.
- All numeric transfer-function and primary coordinates cited are standard published
  values (sRGB IEC 61966-2-1; ProPhoto RGB, Ref. ICC.1-2001-04 / Kodak). The gamma
  direction claim is verified by the linear-0.5 worked example above.

## Acceptance Criteria

Not applicable — this document records an analysis, not a buildable change. The
analysis is accepted as correct if a reviewer confirms:

- `unpurple.ml` contains no color-space conversion (grep for any sRGB/ProPhoto/XYZ
  matrix → none found).
- The gamma-direction example (linear 0.5 → sRGB ≈ 0.736, ProPhoto ≈ 0.681) holds.
- The parameter table correctly marks `radius` as basis-independent and the other
  four as basis-coupled.

## Impacted Modules

- `external/purple-fringe/src/unpurple.ml` — reference analysed (no change).
- `app/src/binding/rust/rawler_fotlab/src/defringe_oklab_aca.rs` — the OKLab port
  that already implements the robust (basis-independent) approach; this document
  justifies its design choice.

## Open Questions

- If a raw (pre-OKLab) defringe in the camera RGB space is ever desired, which basis
  should the R:B constants be tuned against — and should the constants ship per-basis
  or be auto-derived from a representative fringe sample?
- Should the project expose the RGB→OKLab input matrix choice (sRGB vs ProPhoto vs
  camera-native) as an explicit user/developer setting, given the pipeline already
  feeds camera-space RGB? (Currently the OKLab path is camera-space; the question is
  moot there, but a future direct-RGB defringe would face it.)

## Change History

- 2026-10-10 — Created (Draft). Records the research conclusion that swapping the
  Unpurple basis from sRGB to ProPhoto RGB does **not** crash the algorithm (no
  conversion step; arithmetic valid for any `[0,1]` channels) but **does** degrade
  correctness: ProPhoto's lower gamma (1.8 vs sRGB ~2.4) shrinks the mask/excesses
  → under-correction, and ProPhoto's shifted primaries change the R:B "purple
  signature" so `max/min_red_to_blue_ratio` must be re-derived per basis. `radius` is
  basis-independent. Documents that `FOTLAB-DEFRNG-000001`'s OKLab port (quadrant +
  a/b slope, no fixed R:B ratio) is the robust, basis-agnostic resolution.
