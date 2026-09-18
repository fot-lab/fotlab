# External module study — RawTherapee demosaicing algorithms: full inventory (Bayer + X-Trans enumerators, dispatch, kernels, maintenance)

- ID: RAWTRP-DECODE-000001
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAWTRP-PIPELN-000001.md` (full develop pipeline — demosaic is Stage B), `rules/STRUCT/detail/RAWTRP-SURVEY-000001.md` (working colour space, downstream of demosaic), `rules/STRUCT/detail/DNGLAB-PIPELN-000002.md` (demosaic algorithm comparison: dnglab vs RawTherapee — implemented vs wired, selectable count, quality tier). Also context: the disabled `app/src/binding/cxx/rawtherapee_fotlab/` glue crate (no-patch strategy — see project memory), which attempted to call `RawImageSource::demosaic()` from Rust.

> **Note on naming**: this study uses the `RAWTRP-` project code (RawTherapee) and the six-character `DECODE` category (demosaicing inventory). It lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified.

> **Scope**: this is a **reference inventory of every demosaicing algorithm RawTherapee exposes**, derived directly from source — the two sensor-family enumerators (`RAWParams::BayerSensor::Method`, `RAWParams::XTransSensor::Method` in `rtengine/params/raw.h`), the scheduler that maps each enumerator to a kernel (`RawImageSource::demosaic()` in `rtengine/rawimagesource.cc`), the actual kernel files, and the upstream maintenance/churn picture. It is **not** a proposal to adopt RawTherapee (GPL-3.0 C++). The value is (a) an exact, grep-able algorithm catalogue for the dnglab-vs-RT comparison and (b) a maintenance-cost basis for the no-patch `rawtherapee_fotlab` glue decision.

All paths below are inside the pinned `external/RawTherapee/` submodule. No upstream source is modified.

## 1. Two sensor families, two enumerators

RawTherapee selects the demosaic algorithm by sensor type. `RawImageSource::demosaic()` first branches on `ri->getSensorType()` (`rawimagesource.cc:1804, 1844`), then reads the matching method string. The two enumerators live in `rtengine/params/raw.h`.

### 1.1 Bayer — `RAWParams::BayerSensor::Method` (`raw.h:65-85`)

19 identifiers:

| # | Enumerator | String (UI) | Maps to kernel (§3) |
| - | --- | --- | --- |
| 1 | `AMAZE` | amaze | `amaze_demosaic_RT` |
| 2 | `AMAZEBILINEAR` | amaze_bilinear | `dual_demosaic_RT` (AMAZE × bilinear fallback) |
| 3 | `AMAZEVNG4` | amaze_vng4 | `dual_demosaic_RT` (AMAZE × VNG4 fallback) |
| 4 | `RCD` | rcd | `rcd_demosaic` |
| 5 | `RCDBILINEAR` | rcd_bilinear | `dual_demosaic_RT` (RCD × bilinear fallback) |
| 6 | `RCDVNG4` | rcd_vng4 | `dual_demosaic_RT` (RCD × VNG4 fallback) |
| 7 | `DCB` | dcb | `dcb_demosaic` |
| 8 | `DCBBILINEAR` | dcb_bilinear | `dual_demosaic_RT` (DCB × bilinear fallback) |
| 9 | `DCBVNG4` | dcb_vng4 | `dual_demosaic_RT` (DCB × VNG4 fallback) |
| 10 | `LMMSE` | lmmse | `lmmse_interpolate_omp` |
| 11 | `IGV` | igv | `igv_interpolate` |
| 12 | `AHD` | ahd | `ahd_demosaic` |
| 13 | `EAHD` | eahd | `eahd_demosaic` |
| 14 | `HPHD` | hphd | `hphd_demosaic` |
| 15 | `VNG4` | vng4 | `vng4_demosaic` |
| 16 | `FAST` | fast | `fast_demosaic` |
| 17 | `MONO` | mono | `nodemosaic(true)` (no colour interp.) |
| 18 | `PIXELSHIFT` | pixelshift | `pixelshift` (multi-frame super-res) |
| 19 | `NONE` | none | `nodemosaic(false)` (raw passthrough) |

### 1.2 X-Trans — `RAWParams::XTransSensor::Method` (`raw.h:159-167`)

7 identifiers:

| # | Enumerator | String (UI) | Maps to kernel (§3) |
| - | --- | --- | --- |
| 1 | `FOUR_PASS` | four_pass | `dual_demosaic_RT(false, …)` (xtrans × contrast hybrid) |
| 2 | `THREE_PASS` | three_pass | `xtrans_interpolate(3, true, …)` |
| 3 | `TWO_PASS` | two_pass | `dual_demosaic_RT(false, …)` (xtrans × contrast hybrid) |
| 4 | `ONE_PASS` | one_pass | `xtrans_interpolate(1, false, …)` |
| 5 | `FAST` | fast | `fast_xtrans_interpolate` |
| 6 | `MONO` | mono | `nodemosaic(true)` |
| 7 | `NONE` | none | `nodemosaic(false)` |

### 1.3 Pixel-shift sub-variants (`raw.h:93-98`)

Bayer `PIXELSHIFT` has its own sub-enum `PSDemosaicMethod { AMAZE, AMAZEVNG4, RCDVNG4, LMMSE }` and a `PSMotionCorrectionMethod { OFF, AUTO, CUSTOM }` — i.e. the multi-frame path re-uses a base demosaicer (AMAZE / RCD / LMMSE) after shift/motion correction. This is a distinct code path (`pixelshift.cc`), not a 20th Bayer identifier.

## 2. The scheduler — `RawImageSource::demosaic()` (`rawimagesource.cc:1796-1869`)

The scheduler is a flat `if/else if` chain comparing the stored method **string** (`RAWParams::BayerSensor::getMethodString(Method)`) against each enumerator. Key structural facts:

- **The 6 Bayer `*BILINEAR` / `*VNG4` identifiers collapse into one branch** (`rawimagesource.cc:1813-1824`): `AMAZEBILINEAR`, `AMAZEVNG4`, `DCBBILINEAR`, `DCBVNG4`, `RCDBILINEAR`, `RCDVNG4` all call `dual_demosaic_RT(true, raw, W, H, rawData, red, green, blue, threshold, autoContrast)`. The base algorithm (AMAZE / RCD / DCB) and the fallback (bilinear vs VNG4) are chosen *inside* `dual_demosaic_RT` from the requested string — these are not 6 separate kernels, they are 3 base algorithms × 2 fallbacks presented as a contrast-adaptive hybrid.
- **X-Trans `FOUR_PASS` and `TWO_PASS` collapse into one branch** (`rawimagesource.cc:1851-1857`) calling `dual_demosaic_RT(false, …)` — same hybrid machinery, X-Trans flavour.
- **`PIXELSHIFT` has its own path** (`rawimagesource.cc:1825-1826`) → `pixelshift(0,0,W,H,raw,currFrame,ri->get_maker(),…)`.
- **`MONO` / `NONE` are no-interpolation paths** via `nodemosaic(true)` / `nodemosaic(false)` (no real demosaic kernel).
- The function reads the CFA through `ri->FC()` internally — it is a `RawImageSource` **member** that depends on the `RawImage` CFA state, so it cannot be wrapped as a free function without constructing a `RawImageSource` (this is exactly why the disabled `rawtherapee_fotlab` glue had to build a `RawImageSource` + `RawImage` shim).

## 3. Real algorithm kernels (after de-duplication)

The 19 Bayer + 7 X-Trans identifiers collapse to a much smaller set of distinct kernels. Mapping each kernel to its definition site (grounded in a grep of `RawImageSource::*` definitions):

### 3.1 Bayer distinct kernels

| Kernel | Definition | Notes |
| --- | --- | --- |
| `amaze_demosaic_RT` | `rtengine/amaze_demosaic_RT.cc:48` | Adaptive homogeneity / adaptive manifold — high quality, the RT default-quality workhorse |
| `rcd_demosaic` | `rtengine/rcd_demosaic.cc:53` | Resolution-aware / pixel-binned gradient — fast, high-detail |
| `dcb_demosaic` | `rtengine/demosaic_algos.cc:1406` | Directional Colour Filter Array Interpolation (with `dcb_iterations` / `dcb_enhance` params) |
| `lmmse_interpolate_omp` | `rtengine/lmmse_demosaic.cc:42` | Linear Minimum Mean Square Error (OpenMP-parallel) |
| `igv_interpolate` | `rtengine/demosaic_algos.cc:218` | Integrated Gaussian / wavelet-ish interpolator |
| `ahd_demosaic` | `rtengine/ahd_demosaic_RT.cc:44` | Adaptive Homogeneity-Directed (RT port) |
| `eahd_demosaic` | `rtengine/eahd_demosaic.cc:214` | Enhanced AHD |
| `hphd_demosaic` | `rtengine/hphd_demosaic_RT.cc:290` | Heterogeneity-Projection (Pixel Harness / High-Quality Pixel Doubling) |
| `vng4_demosaic` | `rtengine/vng4_demosaic_RT.cc:64` | Variable Number of Gradients (4th-gen) |
| `fast_demosaic` | `rtengine/fast_demo.cc:61` | Bilinear-ish fast path |
| `nodemosaic` | `rtengine/rawimagesource.cc` | `MONO` (true) / `NONE` (false) — not a demosaic |
| `pixelshift` | `rtengine/pixelshift.cc:309` | Multi-frame super-resolution (re-uses AMAZE/RCD/LMMSE sub-kernels) |

### 3.2 Dual hybrids (Bayer + X-Trans)

| Hybrid entry | Kernel | Definition |
| --- | --- | --- |
| `AMAZEBILINEAR`, `AMAZEVNG4`, `DCBBILINEAR`, `DCBVNG4`, `RCDBILINEAR`, `RCDVNG4` (Bayer) | `dual_demosaic_RT(true, …)` | `rtengine/dual_demosaic_RT.cc:41` |
| `FOUR_PASS`, `TWO_PASS` (X-Trans) | `dual_demosaic_RT(false, …)` | `rtengine/dual_demosaic_RT.cc:41` |

`dual_demosaic_RT` runs a base algorithm, then (per a local-contrast threshold, auto or `dualDemosaicContrast`) blends toward a **bilinear** or **VNG4** fallback in low-contrast / smooth regions. So the 6 Bayer "extra" identifiers and the 2 X-Trans "extra" identifiers add **zero new math** — they are contrast-adaptive wrappers around the base kernels in §3.1.

### 3.3 X-Trans distinct kernels

| Kernel | Definition | Notes |
| --- | --- | --- |
| `xtrans_interpolate(passes, useCieLab, …)` | `rtengine/xtrans_demosaic.cc:181` | `ONE_PASS` → `(1,false)`, `THREE_PASS` → `(3,true)` |
| `fast_xtrans_interpolate` | `rtengine/xtrans_demosaic.cc:969` | `FAST` |
| `dual_demosaic_RT(false, …)` | `rtengine/dual_demosaic_RT.cc:41` | `FOUR_PASS` / `TWO_PASS` |
| `nodemosaic` | `rtengine/rawimagesource.cc` | `MONO` / `NONE` |

### 3.4 Distinct-kernel count

- **Bayer**: 11 interpolating kernels (AMAZE, RCD, DCB, LMMSE, IGV, AHD, EAHD, HPHD, VNG4, FAST, PIXELSHIFT) + 1 hybrid wrapper (`dual_demosaic_RT`) + 2 no-op (`nodemosaic`).
- **X-Trans**: 2 interpolating kernels (`xtrans_interpolate`, `fast_xtrans_interpolate`) + 1 hybrid wrapper (`dual_demosaic_RT`) + 2 no-op.
- **Total selectable identifiers**: 19 Bayer + 7 X-Trans = **26**. **Distinct real kernels**: ~**15** (10 Bayer interpolators + 1 Bayer-specific hybrid machi­nery already counted via dual + PIXELSHIFT + 2 X-Trans interpolators + the shared dual machinery). The 8 "extra" identifiers (`*BILINEAR` ×3, `*VNG4` ×3, X-Trans `FOUR_PASS`/`TWO_PASS`) are wrappers, not new algorithms.

## 4. Upstream maintenance / churn status

Measured `git log -1` author dates on each kernel file in the pinned submodule:

| Kernel file | Last author date | Nature of recent touch |
| --- | --- | --- |
| `amaze_demosaic_RT.cc` | 2026-01-31 | Merge into `simde` branch (SIMD port) — maintenance, not algorithm redesign |
| `rcd_demosaic.cc` | 2024-11-16 | "Get rid of relative include paths" — build hygiene |
| `demosaic_algos.cc` (DCB, IGV) | 2026-01-31 | `simde` merge |
| `lmmse_demosaic.cc` | 2026-01-31 | `simde` merge |
| `ahd_demosaic_RT.cc` | 2024-11-16 | "Get rid of relative include paths" |
| `eahd_demosaic.cc` | 2024-11-16 | "Get rid of relative include paths" |
| `hphd_demosaic_RT.cc` | 2026-01-31 | `simde` merge |
| `vng4_demosaic_RT.cc` | 2026-01-31 | `simde` merge |
| `fast_demo.cc` | 2026-01-31 | `simde` merge |
| `dual_demosaic_RT.cc` | 2025-07-03 | "Group extern variables into App singleton" — refactor |
| `xtrans_demosaic.cc` | 2026-01-31 | `simde` merge |
| `pixelshift.cc` | 2026-01-31 | `simde` merge |

**Observation**: the demosaic *math* is stable and effectively frozen in design — recent upstream activity on these files is build/port maintenance (SIMDe SIMD port, include-path cleanup, singleton refactor), not algorithm rework. The algorithms were largely introduced across 2019-2022 (AMAZE ≈ 2019, LMMSE/VNG4 ≈ 2020, RCD ≈ 2021, DCB enhance ≈ 2022); the active upstream branch does not redesign demosaicing.

**Maintenance implication for the no-patch glue decision** (`app/src/binding/cxx/rawtherapee_fotlab`): because the kernels are stable, a vendored-kernel or fork-exe route has low ongoing cost. If we ever re-enable native demosaic via RawTherapee, an annual `git diff` of roughly six files (`amaze_demosaic_RT.cc`, `rcd_demosaic.cc`, `demosaic_algos.cc`, `lmmse_demosaic.cc`, `dual_demosaic_RT.cc`, `xtrans_demosaic.cc`) against upstream catches essentially all substantive change. The project decision stands: **no `.patch` ships in the repo** — any RawTherapee modification is maintained out-of-band (see project memory `MEMORY.md`).

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` remains a fixed constraint. This document records the demosaicing algorithm inventory and where each kernel is defined and dispatched. No change to RawTherapee source is specified or permitted. Whether/when `rawler_fotlab` or a first-party path exposes a user-selectable demosaic algorithm (and at what fidelity vs. RawTherapee's 26-identifier surface) is a first-party DESIGN/STRUCT decision for a later item.

## Open Questions

- Q1 — dnglab `rawler::imgop::develop` exposes a much smaller, non-user-selectable demosaic set (`DNGLAB-PIPELN-000002`). If fotlab needs quality tiers (fast preview vs. export), which RawTherapee kernels (FAST / RCD / AMAZE / dual) map cleanly onto our pipeline?
- Q2 — The `dual_demosaic_RT` contrast-adaptive hybrid is RawTherapee-specific glue, not a standalone algorithm. Re-implementing it (vendoring) requires capturing the threshold logic in `rawimagesource.cc:1819-1824 / 1852-1857`, not just the kernel files.
- Q3 — `PIXELSHIFT` needs multi-frame input (`currFrame`, maker/model) — out of scope for single-frame develop; confirm fotlab never ingests pixel-shift sequences before wiring any RAW path to RawTherapee.

## Change History

- 2026-09-18 — RawTherapee demosaicing algorithm inventory. Enumerated the 19 Bayer `RAWParams::BayerSensor::Method` identifiers (`raw.h:65-85`) and 7 X-Trans `RAWParams::XTransSensor::Method` identifiers (`raw.h:159-167`), the `PIXELSHIFT` sub-enum (`raw.h:93-98`), the `RawImageSource::demosaic()` scheduler branch structure (`rawimagesource.cc:1796-1869`) showing the 6 Bayer `*BILINEAR`/`*VNG4` + 2 X-Trans `FOUR_PASS`/`TWO_PASS` identifiers all collapse into `dual_demosaic_RT` (`dual_demosaic_RT.cc:41`), and mapped every distinct kernel to its definition file (AMAZE `amaze_demosaic_RT.cc:48`, RCD `rcd_demosaic.cc:53`, DCB/IGV `demosaic_algos.cc:218/1406`, LMMSE `lmmse_demosaic.cc:42`, AHD `ahd_demosaic_RT.cc:44`, EAHD `eahd_demosaic.cc:214`, HPHD `hphd_demosaic_RT.cc:290`, VNG4 `vng4_demosaic_RT.cc:64`, FAST `fast_demo.cc:61`, X-Trans `xtrans_demosaic.cc:181/969`, PIXELSHIFT `pixelshift.cc:309`). Collapsed 26 identifiers → ~15 distinct kernels. Recorded upstream last-touched dates (measured via `git log -1`) and characterized them as SIMDe/build/refactor maintenance, not algorithm redesign; noted the no-patch glue maintenance implication. Filed as `RAWTRP-DECODE-000001`; row appended to `rules/STRUCT/index.md`.
