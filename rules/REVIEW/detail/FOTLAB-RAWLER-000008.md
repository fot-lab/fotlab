# RawAlchemy's boost block: saturation is anchored on the per-pixel ProPhoto luma, contrast on a global pivot — both are affine-linear scene-linear scaling inside linear ProPhoto D50, before the gamut+log boundary

- ID: FOTLAB-RAWLER-000008
- Status: Observation
- Priority: P2
- Created: 2026-09-19
- Owner: —
- Related: `rules/REVIEW/detail/FOTLAB-RAWLER-000005.md` (ProPhoto D50 hub — boost confirms the hub holds through rawalchemy's *pre-log* stages), `rules/REVIEW/detail/FOTLAB-RAWLER-000006.md` (rawalchemy handoff — boost is one of the fused stages named there), `rules/REVIEW/detail/FOTLAB-RAWLER-000007.md` (D50 ProPhoto → Log leaves ProPhoto; boost sits *before* that boundary), `rules/DESIGN/detail/FOTLAB-PIPELN-000001.md` (pipeline; boost belongs to the post-`FotDev` process/grade stage), `rules/REVIEW/detail/FOTLAB-RAWLER-000004.md` (decoded-RAW handle — the caching boundary that makes boost edits re-run develop)

## Background & Goal

The `rawalchemy_fotlab` cxx bridge exposes a **boost** group of knobs to the Fabric UI (saturation / contrast / pivot / enable). Users change them and expect a colour-only adjustment; the code, however, treats "boost" as a specific stage inside RawAlchemyCpp's fused grading. This item records the exact parameterisation, the per-pixel math, and — critically — **which colour space and which kind of linearity** the operation lives in, so that UI labels, pipeline placement and the re-render/caching contract are all grounded in the real implementation rather than in the everyday meaning of the words "saturation" and "contrast".

## Finding

### 1. "boost" is four parameters, and they are the saturation/contrast block

`external/RawAlchemyCpp/include/grading_fused.h:27-42` (`GradingParams`) defines the boost group inside the fused grader:

| parameter | type | upstream default | meaning |
| --- | --- | --- | --- |
| `enableBoost` | `bool` | `true` | master switch for the whole block; `false` → `doBoost = false` and the other three are inert |
| `saturation` | `float` | `1.25` | chroma multiplier (scale the deviation from the pixel's own luma) |
| `contrast` | `float` | `1.10` | contrast multiplier |
| `pivot` | `float` | `0.18` | the contrast fulcrum (the one untouched tone) |

`gain` is **not** part of boost — it is a separate front stage (exposure multiplier, default `1.0`). In the FOTLAB shim, boost is surfaced as four `Option` fields in `GradeOverrides` (`app/src/binding/cxx/rawalchemy_fotlab/src/lib.rs:91-119`); `None` means "use the upstream default" and is encoded across cxx as a tri-state `i32` for `enable_boost` (`BOOST_UNSET`/`BOOST_OFF`/`BOOST_ON`, `lib.rs:79-83`) and as `NaN` for the three floats — never a restated upstream value.

### 2. The per-pixel math — and the order

`external/RawAlchemyCpp/src/grading_fused.cpp:84-92`:

```cpp
float lum = Lr * r + Lg * g + Lb * b;          // per-pixel ProPhoto luma
float rs  = lum + (r - lum) * sat;             // saturation
r = max(0.0f, (rs - pivot) * cont + pivot);    // contrast, clamped >= 0
// g and b use the same two lines
```

Saturation runs **first**; contrast consumes the already-saturated value. The clamp `max(0, ·)` belongs to the contrast line.

### 3. Saturation's anchor is the pixel's own ProPhoto luma (dynamic) — and the scaling is affine-linear

Rewriting the saturation line as a function of a channel value at a fixed pixel (luma `lum` fixed, `sat` fixed):

```
channel' = sat · channel + (1 − sat) · lum
```

- This is an **affine** map (a straight line with the constant term `(1−sat)·lum`), i.e. a linear interpolation/extrapolation of the channel along the `(channel − lum)` direction. There is **no** gamma, S-curve, log or other nonlinearity applied to the saturation scaling itself.
- Anchor behaviour: `sat = 0` collapses every channel to `lum` (fully grey); `sat = 1` is identity; `sat = 2` doubles the chroma offset from grey. The scaling is proportional and unclamped in this stage.
- Saturation has **no** pivot parameter. Its fulcrum is the per-pixel `lum`, which changes from pixel to pixel, so the axis is dynamic/local. (Contrast, by contrast, has the single named parameter `pivot`, shared by R/G/B and by the whole image, `= 0.18` by default.)

### 4. The luma uses ProPhoto coefficients — so the axis *is* ProPhoto

`external/RawAlchemyCpp/include/metering.h:21-23`:

```
PROPHOTO_LUMA_R = 0.2880747
PROPHOTO_LUMA_G = 0.7118632
PROPHOTO_LUMA_B = 0.0000622     // sum = 1.0
```

`grading_fused.cpp:36-38` binds `Lr/Lg/Lb` to these. They are the **ProPhoto RGB (D50) luma weights**, not sRGB's `0.2126 / 0.7152 / 0.0722`. So the achromatic axis around which saturation scales is the ProPhoto grey axis — it is not an arbitrary neutral.

### 5. It operates in *linear* ProPhoto D50, before gamut+log — so the linearity is scene-linear, not perceptual

`FOTLAB-RAWLER-000007` established the fused order as `gain → saturation/contrast → gamut → log → LUT` (`grading_fused.h:8,50`). Boost therefore runs on **linear-light ProPhoto D50 RGB**, *before* the wide→vendor gamut matrix and the log OETF. Two consequences:

- The input channels are linear light, and the luma anchor is a linear-light weighted sum — the operation is **radiometrically (optically) linear**, not perceptually uniform. The same `sat`/`cont` multiplier produces a different *perceived* shift in shadows than in highlights.
- It is one of the stages that still legitimately lives in the **ProPhoto D50 hub**; only the subsequent gamut+log step takes the data out of ProPhoto (and only when a non-empty `log_space` is set, `grading_fused.h:38`).

## Impact / Conflict

- **Confirms and extends `FOTLAB-RAWLER-000005`.** The "ProPhoto D50 hub" holds not only through `develop` and the rawalchemy decode/handoff but through the **boost** stage too — boost is a pre-log, in-ProPhoto operation. The only ProPhoto exit is the gamut+log step (`FOTLAB-RAWLER-000007`).
- **Clarifies `FOTLAB-RAWLER-000006`.** "boost" is exactly the `saturation/contrast` stage named in that item's fused-order note; the shim deliberately carries *no* upstream defaults, matching 000006's "never restate an upstream value" rule (`GradeOverrides` are all `Option`, encoded as NaN / tri-state).
- **Bounds `FOTLAB-PIPELN-000001` terminology.** Boost belongs to the post-`FotDev` process/grade stage, and its "saturation/contrast" are **scene-linear** controls in linear ProPhoto D50. If a *perceptual* saturation is ever wanted, it cannot be this block — it would have to be a separate stage placed **after** the log encoding (where the signal is closer to perceptually uniform). Re-labelling the existing block as "perceptual saturation" would be wrong.
- **Feeds the re-render / caching question.** Changing any boost knob is numerically cheap, but because the current resident handle is the *decoded* `RawImage`, not the *developed* `RawlerImageDeveloped` (`FOTLAB-RAWLER-000004`), a boost edit re-runs `demosaic` + `calibrate`. The correct fix remains: retain the linear ProPhoto D50 developed buffer as the editing handle so a boost-only change re-runs only the (cheap) boost+grade, not the (expensive) develop.
- **No upstream change** — the behaviour lives in `external/RawAlchemyCpp` (submodule). This is a contract/terminology note for our pipeline, consistent with review principle #5 (upstream is a fixed constraint).

## Recommendation

1. Record boost's semantics in the pipeline/design docs: four parameters (`enableBoost`, `saturation`, `contrast`, `pivot`), saturation anchored on the **per-pixel ProPhoto luma** with affine-linear scaling, contrast anchored on the **global `pivot`** (default 0.18), both evaluated in **linear ProPhoto D50** and **before** the gamut+log boundary.
2. Do not present boost as a perceptual saturation/contrast control in the UI copy; if a perceptually-uniform control is wanted, add it as a distinct post-log stage rather than reshaping this block.
3. When the D50 ProPhoto developed buffer is retained as a handle (`FOTLAB-RAWLER-000004` / 000006 / 000007), route boost-only edits to re-run from that handle — boost must never force a re-develop.
4. Keep hiding upstream defaults behind `Option`/NaN/tri-state; do not hard-code `1.25 / 1.10 / 0.18` into the FOTLAB layer (that would freeze upstream policy in our code).

## Change History

- 2026-09-19 — Review recorded. Established from `external/RawAlchemyCpp` source that the "boost" group is the fused grader's saturation/contrast block, defined as four parameters in `GradingParams` (`grading_fused.h:27-42`: `enableBoost` true, `saturation` 1.25, `contrast` 1.10, `pivot` 0.18) and applied per pixel in `grading_fused.cpp:84-92` as `lum = Lr·r+Lg·g+Lb·b; rs = lum + (r−lum)·sat; r = max(0,(rs−pivot)·cont+pivot)`. Saturation's anchor is the per-pixel ProPhoto luma (dynamic) with an **affine-linear** scaling `channel' = sat·channel + (1−sat)·lum` (no curve/gamma; sat 0 → grey, 1 → identity, >1 → extrapolate); contrast's anchor is the single global `pivot`, shared by all channels and pixels. The luma weights are ProPhoto's `PROPHOTO_LUMA_{R,G,B} = 0.2880747 / 0.7118632 / 0.0000622` (`metering.h:21-23`), so the scaling axis is the ProPhoto grey axis, and the whole block runs in **linear-light ProPhoto D50** *before* the gamut+log step (`grading_fused.h:8,38,50`; cf. `FOTLAB-RAWLER-000007`), i.e. it is scene-linear, not perceptual. Confirms the ProPhoto D50 hub holds through boost (`FOTLAB-RAWLER-000005`), matches the shim's "no restated upstream defaults" rule (`FOTLAB-RAWLER-000006`; `GradeOverrides` as `Option`, NaN/tri-state), and feeds the retain-the-developed-buffer argument (`FOTLAB-RAWLER-000004`). Row appended to `rules/REVIEW/index.md`.
