# rawalchemy's D50 ProPhoto → Log step already performs a gamut (primary + white-point) transform — the graded output leaves ProPhoto

- ID: FOTLAB-RAWLER-000007
- Status: Observation
- Priority: P2
- Created: 2026-09-19
- Owner: —
- Related: `rules/REVIEW/detail/FOTLAB-RAWLER-000005.md` (ProPhoto D50 hub — refined: hub holds only up to the log boundary), `rules/REVIEW/detail/FOTLAB-RAWLER-000006.md` (rawalchemy handoff — gamut step already recorded there as part of the spec; this item draws out the contract consequence), `rules/DESIGN/detail/FOTLAB-PIPELN-000001.md` (pipeline; `FotDev` invariant refined: the develop→grade boundary is also a color-space boundary), `rules/REVIEW/detail/DNGLAB-RAWLER-000005.md` (rawler `Calibrate` sets D65; SRgb is only gamma)

## Background & Goal

`FOTLAB-RAWLER-000006` establishes that rawler emits a linear **ProPhoto D50** buffer (`RawlerImageDeveloped`) and that RawAlchemyCpp consumes it, assuming ProPhoto-D50 input. `FOTLAB-RAWLER-000005` argues for a **ProPhoto D50 hub** working space. Both are correct *for the develop output and the rawalchemy decode/handoff*.

This item records a contract consequence that neither states explicitly: once RawAlchemyCpp's fused grading step runs a **log space**, it immediately transforms the data **out of ProPhoto** — by a 3×3 gamut matrix that folds in *both* the RGB primaries change *and* the D50→target white-point chromatic adaptation. So "ProPhoto D50" is the working space **up to the grade boundary**, not through it. The consequence matters for the FotLab pipeline (what `FotDev` is allowed to be, and why the D50 ProPhoto intermediate must be the retained handle rather than the log output).

## Finding

### 1. The log step is two sub-steps: gamut matrix, then per-channel log OETF

`external/RawAlchemyCpp/include/log_transform.h:5-12` documents "Step 2 — Precise Log Signal Preparation" as:

1. **Gamut Transform** — `ProPhoto RGB (Linear) -> Target Gamut (Linear)`;
2. **Log Curve Encoding** — `Linear -> Log`.

`applyGamutTransform` is described (`log_transform.h:29-38`) as *"In-place 3×3 matrix multiplication … Matches Python: `colour.matrix_RGB_to_RGB(ProPhoto, TargetGamut)`"*. The fused path `applyGradingFused` runs the same order: `gain → saturation/contrast → gamut → log → LUT` (`grading_fused.h:8,50`; the matrix is read at `grading_fused.cpp:46`).

### 2. The gamut matrix is a real cross-channel multiply — not a scalar

`external/RawAlchemyCpp/src/log_transform.cpp:54-73` (`applyGamutTransform`):

```cpp
p[0] = r*m00 + g*m01 + b*m02;
p[1] = r*m10 + g*m11 + b*m12;
p[2] = r*m20 + g*m21 + b*m22;
```

The off-diagonal coefficients are non-zero, so every output channel mixes all three inputs — this is a **primary-coordinate change**, not a per-channel gain. The following `applyLogEncoding` (`log_transform.cpp:80-111`) is the genuinely per-channel OETF (`max(r,1e-6)` then `logEncode`).

### 3. The matrix folds in the white point too (CAT02)

`external/RawAlchemyCpp/include/color_data.h:8`:

> *"Gamut transform matrices: ProPhoto RGB -> Target Gamut (Linear). Computed using colour-science `matrix_RGB_to_RGB()` with **CAT02 adaptation**."*

`colour.matrix_RGB_to_RGB(ProPhoto, TargetGamut)` resolves to

```
(TargetPrimaries → XYZ at target WP) · CAT02(D50 → target WP) · (XYZ → ProPhoto primaries at D50)
```

ProPhoto RGB's reference white is **D50** by definition; every target gamut here (F-Gamut, S-Gamut3, V-Gamut, ARRI Wide Gamut, REDWideGamutRGB, BT.2020, Cinema Gamut, DJI D-Gamut) is a **D65** space. So the single 3×3 already performs **both** the primaries change *and* the D50→D65 chromatic adaptation. The matrices are precomputed constants (`color_data.h:24-105`, e.g. `MAT_PROPHOTO_TO_F_GAMUT` with off-diagonal terms ≈ −0.066 / −0.137), so the cost is one matrix multiply.

### 4. The output is no longer ProPhoto — it is the target gamut at D65

`color_data.h:134-149` (`LOG_SPACES`) binds each log space to a target gamut:

| log space | target gamut |
| --- | --- |
| F-Log / F-Log2 | F-Gamut |
| S-Log3 | S-Gamut3 |
| S-Log3.Cine | S-Gamut3.Cine |
| V-Log | V-Gamut |
| N-Log / L-Log | BT.2020 |
| Canon Log 2/3 | Cinema Gamut |
| Arri LogC3/4 | ARRI Wide Gamut 3/4 |
| Log3G10 | REDWideGamutRGB |
| D-Log | DJI D-Gamut |

So a graded buffer is **F-Gamut/S-Gamut3/V-Gamut + D65 + log-encoded** — not ProPhoto D50. (Per `FOTLAB-RAWLER-000006` §Finding, `applyGradingFused` with `"F-Log"` yields "target gamut (F-Gamut) + F-Log-encoded `float`".)

### 5. The color-space exit is conditional on a non-empty log space

`grading_fused.h:38` — `logSpaceInfo = nullptr` means *"skip gamut + log encoding"*. The shim passes an empty `log_space` → `p.logSpaceInfo` stays null → the gamut+log stage is skipped entirely (`FOTLAB-RAWLER-000006` "Default ownership"). **Therefore: with an empty log space the buffer legitimately stays in ProPhoto D50; the ProPhoto→target-gamut exit only happens when a log space is selected.**

## Impact / Conflict

- **Refines `FOTLAB-RAWLER-000005`.** The "ProPhoto D50 hub" claim is correct through `develop` and the rawalchemy decode/handoff, but the rawalchemy **grade/log** step exits ProPhoto. It must not be read as "the pipeline output stays ProPhoto". The sRGB(D65)→ProPhoto(D50) bridge removal in 000005 is still valid (our D50 input matches rawalchemy's D50-assuming grading matrices, per 000006 §Finding) — the refinement is about *output*, not *input*.
- **Refines `FOTLAB-PIPELN-000001`.** The design states `process` consumes `FotDev` (linear, source-agnostic). If `FotDev` is ProPhoto D50 and `process` includes boost/log/lut grading, then the moment a log space is active the working space leaves ProPhoto. Cleaner reading: the **develop→grade boundary is also a color-space boundary**, so the reusable editing object should be the ProPhoto D50 *pre-log* `RawlerImageDeveloped`, while the log/LUT result is a terminal, display/export-oriented image — not a re-editable `FotDev`.
- **Directly motivates retaining the D50 ProPhoto intermediate as the handle.** Because the gamut step is a wide→(vendor) transform with a D50→D65 CAT and the log OETF is nonlinear, there is **no cheap inverse** back to ProPhoto D50 (and the target gamut differs per selected log space). Re-grading (changing boost/log/lut) must therefore re-run from the ProPhoto D50 buffer. Caching the log output instead would freeze the look and force a full re-decode to re-develop/re-grade — which is exactly the re-render waste analyzed for the develop+rawalchemy coupling. Keeping `RawlerImageDeveloped` (ProPhoto D50, unclamped) resident as the handle is the correct response.
- **No upstream change** — the gamut behaviour lives in `external/RawAlchemyCpp` (submodule), so this is consistent with review principle #5 (upstream is a fixed constraint); it is a contract note for our pipeline, not a request to rawalchemy.

## Recommendation

1. Document the develop→grade (rawalchemy log) boundary explicitly as a **color-space boundary** (ProPhoto D50 → target gamut D65) in `FOTLAB-PIPELN-000001`, alongside the existing "develop output is linear" invariant.
2. Pin `RawlerImageDeveloped` (linear, wide-gamut ProPhoto D50, unclamped) as the **canonical reusable editing handle**; treat the graded/log output as a terminal result consumed as-is by Kotlin (already the decision in `FOTLAB-RAWLER-000006` §Decision 4).
3. Any *linear ProPhoto D50* work that must follow grading should run **before** the log/gamut step, or with `log_space` empty (skip gamut+log — the data then stays ProPhoto D50).
4. When a future linear-domain HDR or re-develop step is added, source it from the retained D50 ProPhoto handle, never from the graded buffer (negatives are clamped to ~0 by `max(r,1e-6)` at the log stage, per 000006 §"Negative / >1 handling").

## Change History

- 2026-09-19 — Review recorded. Established from `external/RawAlchemyCpp` source that RawAlchemyCpp's D50 ProPhoto → Log conversion is a two-sub-step operation: a 3×3 gamut matrix (`log_transform.cpp:54-73`, cross-channel, non-diagonal) that is `colour.matrix_RGB_to_RGB(ProPhoto, TargetGamut)` **with CAT02** (`color_data.h:8`), i.e. it changes *both* the RGB primaries *and* the white point (ProPhoto D50 → target gamut D65), followed by a per-channel log OETF (`log_transform.cpp:80-111`). The `LOG_SPACES` table (`color_data.h:134-149`) binds each log space to its vendor gamut (F-Log→F-Gamut, S-Log3→S-Gamut3, V-Log→V-Gamut, …), so the graded output is not ProPhoto. The exit is conditional: `logSpaceInfo = nullptr` (`grading_fused.h:38`) skips gamut+log, so an empty log space legitimately keeps the buffer in ProPhoto D50. Refines `FOTLAB-RAWLER-000005` (hub holds only to the log boundary) and `FOTLAB-PIPELN-000001` (develop→grade is also a color-space boundary), and motivates retaining `RawlerImageDeveloped` (ProPhoto D50, unclamped) as the reusable handle since there is no cheap inverse of the gamut+log transform. Row appended to `rules/REVIEW/index.md`.
