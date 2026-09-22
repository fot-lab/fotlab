# External module study — RawTherapee `ImProcFunctions` & `ColorTemp`: deep dive on the reusable grading/WB primitives (incl. fringe & CA correction)

- ID: RAWTRP-SURVEY-000004
- Status: Draft
- Priority: P2
- Created: 2026-09-21
- Owner: —
- Related: `rules/STRUCT/detail/RAWTRP-SURVEY-000003.md` (the three-tier no-patch surface — this doc drills into the two B-tier classes that carry ALL basic grading + WB, plus the fringe/CA correction that rides on them), `rules/STRUCT/detail/RAWTRP-SURVEY-000001.md` (working colour space profiles consumed by `rgb2lab`/`lab2rgb`), `rules/STRUCT/detail/RAWTRP-DECODE-000003.md` (the demosaic kernel contract we must vendor instead)

> **Note on naming**: this study uses the `RAWTRP-` project code and the `SURVEY` category, under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified.

> **Scope**: a **deep dive into the two GUI-internal classes RawTherapee exposes with no patch** — `ImProcFunctions` (the entire basic-grading algorithm container, including the fringe/CA correction that rides on it) and `ColorTemp` (the white-balance math primitive). The parent survey `RAWTRP-SURVEY-000003` established that both are B-tier: usable by `#include` + link, no patch, but outside the `rtengine.h` stable umbrella. This document records the exact data contracts, constructors, threading gate, colour-conversion dependencies, and — added in revision 2 — the **purple/green fringe (`defringe`) and chromatic-aberration correction** code locations and call paths, so first-party integration can be designed against them. No upstream source is modified.

All paths are inside the pinned `external/RawTherapee/` submodule. Line numbers verified against the checked-out tree (cross-checked with `RAWTRP-SURVEY-000003`).

## Background & Goal

We want to reuse RT's **basic grading** (tone/curve/colour/sharpen/denoise), **white-balance** math, and **fringe / chromatic-aberration correction** without patching `external/RawTherapee`. The parent survey confirmed grading + WB live behind `ImProcFunctions` and `ColorTemp`. This document answers the practical questions an integrator needs:

1. What does `ImProcFunctions` actually take and return? (the per-method data contract)
2. How is it constructed, and what controls its threading?
3. Which methods depend on a running engine / `ICCStore`, and which are fully standalone?
4. Is `ColorTemp` truly decoupled from the rest of RT, and what WB math does it expose?
5. **Where is the purple-fringe / green-fringe removal code, and how is it called?** (added rev 2 — see §5)

## 1. `ImProcFunctions` — the grading algorithm container

### 1.1 Declaration & construction

- Declared `class ImProcFunctions` at `improcfun.h:137`; `public:` block opens at `:169`.
- **Constructor**: `explicit ImProcFunctions(const procparams::ProcParams* iparams, bool imultiThread = true)` (`improcfun.h:197`).
  - It consumes **only a `ProcParams*` and a threading flag** — never an `ImageSource*` or `ImProcCoordinator*`. This is the single most important integration fact: **grading is parameter-driven, not object-coupled.** You build `ImProcFunctions` anywhere; it does not need an open RT file or a coordinator.
  - `ProcParams` is the *full* RT parameter tree (`procparams::ProcParams`), so each grading method reads the sub-struct it cares about (e.g. tone curve reads `pp->toneCurve`, `vibrance` reads `pp->vibrance`). The method pulls its own slice of the tree.
- Member `bool multiThread` (`improcfun.h:145`) gates OpenMP. `improcfun.cc` contains **47 `#pragma omp` regions**; every one is conditional on `multiThread`. Pass `false` to get a fully deterministic, single-threaded path — useful for a binding where we control threading ourselves.

### 1.2 Data contracts (the three carrier types)

Every public grading method eats one of three RT-internal image types:

| Carrier type | Meaning | Used by |
| --- | --- | --- |
| `Imagefloat*` | RGB working-space buffer (3 planar float channels, working colour space) | tone/colour at the RGB stage, sharpen/denoise variants, lens/transform, TRC |
| `LabImage*` | CIELAB 1976 (`float` L/a/b planes) | most colour/grading ops, wavelet, curves, **defringe** |
| `CieImage*` | CIECAM (J/a/b/Q/M/s/C) for perceptual tone mapping | `EPDToneMapCIE`, `MLmicrocontrastcam`, `sharpeningcam`, **defringecam** |

To reuse a method from our Rust/binding we **marshal our f32 planes into the appropriate carrier** (e.g. `Imagefloat` for RGB-stage ops, `LabImage` for the bulk of colour grading) and call it. We get the result back out of the same carrier. No patch — this is plain `#include` + link + data marshalling.

### 1.3 Public method catalogue (all reachable no-patch)

Verified presence in `improcfun.h` (line) + implementation region in `improcfun.cc` where relevant.

| Group | Method(s) | Line | Input type |
| --- | --- | --- | --- |
| **Tone / exposure** | `tone_eqcam`, `tone_eqcam2`, `tone_eqdehaz` | `:452 / :450 / :451` | `Imagefloat*` |
| | `EPDToneMap`, `EPDToneMaplocal`, `EPDToneMapCIE` | `:311 / :312 / :313` | `LabImage*` / `CieImage*` |
| | `sigmoid_main`, `tonemapFreeman`, `tonemapFreemanQ` | `:459 / :453 / :454` | scalar helpers |
| **Curves / colour (RGB stage)** | `rgbProc(...)` | `:217 / :223` (impl `improcfun.cc:2050`) | `Imagefloat* working` + `LabImage* lab` — master dispatcher: tone curve, saturation, channel mixer, film sim, colour boost |
| | `luminanceCurve`, `chromiLuminanceCurve` | `:239 / :249` | `LabImage*` |
| **Saturation / colour** | `vibrance` | `:250` | `LabImage*` + working profile |
| | `shadowsHighlights` | `:599` | `LabImage*` |
| | `defringe`, `defringecam` | `:583 / :584` | `LabImage*` / `CieImage*` — **purple/green fringe removal (see §5)** |
| | `dehaze` | `:593` | `Imagefloat*` |
| | `labtoning` / `toning2col` / `toningsmh` / `toningsmh2` | `:229–:232` | per-pixel RGB helpers |
| **Sharpening** | `sharpening` | `:254` | `LabImage*` |
| | `deconvsharpening`, `deconvsharpeningloc` | `:289 / :290` | `float**` luminance |
| | `MLsharpen`, `MLmicrocontrast` (+cam) | `:292 / :293–:295` | `LabImage*` / `CieImage*` |
| | `doCapture_Sharpening_SE`, `sharpeningcam` | `:256 / :260` | `Imagefloat*` / `CieImage*` |
| **Denoise** | `impulsedenoise` (+cam) | `:297 / :298` | `LabImage*` / `CieImage*` |
| | `dirpyrdenoise` (Emil's pyramid) | `:302` | `LabImage*` |
| | `DeNoise`, `impulse_nr` (+cam) | `:504 / :299` | `LabImage*` |
| | `fftw_denoise`, `NLMeans` | `:511 / :340` | buffers |
| **Wavelet / local** | `dirpyrequalizer`, `ip_wavelet`, `complete_local_contrast`, `wavcontrast4`, `Lab_Local` | `:303 / :520 / :537 / :470 / :381` | `LabImage*` |
| **Colour conversion** | `rgb2lab(const Imagefloat&, LabImage&, workingSpace)`, `lab2rgb(const LabImage&, Imagefloat&, workingSpace)` | `:624 / :625` | working-space-name driven |
| | `lab2rgb(... icm)`, `rgb2lab(Image8...)`, `lab2rgbOut`, `rgb2lab(uint8…)` | `:605 / :606 / :608 / :607` | overloads |
| **TRC** | `workingtrc(sp, src, dst, …, profile, gampos, slpos, …)` | `:614` | `Imagefloat*` (applies the working TRC) |
| **Lens / transform / CA** | `transform`, `resize`, `Lanczos`, `drawFrame`, `filmNegativeProcess` | `:261 / :265 / :266 / :286 / :209` | `Imagefloat*` — **`transform`/`resize` also apply lateral `cacorrection` (see §5.3)** |
| **Exposure PDE / texture** | `exposure_pde`, `retinex_pde`, `detail_mask`, `laplacian`, `blendstruc` | `:349 / :348 / :339 / :338 / :468` | buffers |

### 1.4 Dependency on `ICCStore` (only the colour-conversion pair)

`rgb2lab`/`lab2rgb` at `:624 / :625` internally call `ICCStore::getInstance()->workingSpaceMatrix(name)` (and its inverse), so **only the RGB↔Lab conversion methods** need `ICCStore` populated. Everything else in §1.3 is pure arithmetic on the carrier planes and needs no profile store. `defringe` (§5.1) and the lateral `cacorrection` (§5.3) are pure arithmetic too — no `ICCStore`.

- `ICCStore::getInstance()` is a `final` singleton (`iccstore.h:57 / :66`); it loads the 12 built-in + `workingspaces.json` profiles at construction (`iccstore.cc:404–411 / :833`).
- Integration implication: to call `rgb2lab`/`lab2rgb` from our binding we must ensure the singleton is constructed at least once (a single `getInstance()` call does it). For grading that stays in Lab we still need it once; for grading that stays in RGB working space (e.g. `rgbProc`, `dehaze`, `tone_eqcam`) we do not. `ColorTemp` (§2) never touches `ICCStore`.

### 1.5 `rgbProc` — the master RGB-stage dispatcher

`rgbProc` (`improcfun.h:217`, impl `improcfun.cc:2050`) is the workhorse that runs the RGB-stage chain: tone curve → saturation → channel mixer → film simulation → colour boost. It is the single entry that fans out to the per-effect helpers (`colorBoost`, `channelMixer`, `filmSimulation`, …). An integrator who wants "give me RT's colour grading" calls `rgbProc(Imagefloat*, LabImage*, ProcParams*)` once; an integrator who wants fine control calls the leaf methods (`vibrance`, `shadowsHighlights`, `defringe`, …) individually. Either way the contract is a carrier + a `ProcParams*` slice.

### 1.6 Threading gate summary

- `multiThread` (`improcfun.h:145`) is the only threading switch; default `true`.
- All 47 OpenMP regions in `improcfun.cc` branch on it.
- For a binding that already parallelises per-tile (or wants deterministic results for tests), construct with `new ImProcFunctions(&pp, /*multiThread=*/false)`.

## 2. `ColorTemp` — white-balance math (fully standalone)

### 2.1 Declaration & members

- `class ColorTemp` at `colortemp.h:44`.
- Data members (`colortemp.h:5–9`): `double temp; double green; double equal; std::string method; StandardObserver observer;`.
- **No pointer to any RT engine / `ImProcCoordinator` / `ICCStore`.** The class is self-contained arithmetic over a temperature + per-channel multiplier model.

### 2.2 Constructors

| Ctor | Line | Builds from |
| --- | --- | --- |
| `ColorTemp()` | `:61` | defaults: `temp=-1, green=-1, equal=1, method="Custom"` |
| `explicit ColorTemp(double e)` | `:62` | single equalisation factor |
| `ColorTemp(t, g, e, method, observer)` | `:63` | temperature + green + equal + method + observer |
| **`ColorTemp(mulr, mulg, mulb, e, observer)`** | `:64` | **per-channel WB multipliers directly** — what `ImageSource::getWB()` returns, and what a demosaic/WB step consumes |

The multiplier ctor (`:64`) is the bridge to our own pipeline: we feed camera WB multipliers (from rawler metadata or our calibration) straight in, no temperature needed.

### 2.3 Conversion math (all public, no engine instance)

| Method | Line | Direction |
| --- | --- | --- |
| `getMultipliers(mulr, mulg, mulb)` | `:107` | → `temp2mul(...)` |
| `temp2mul(temp, green, equal, observer, rmul, gmul, bmul)` | `:56` | temperature → per-channel multipliers |
| `mul2temp(rmul, gmul, bmul, equal, observer, temp, green)` | `:112` | multipliers → temperature + green |
| `update(rmul, gmul, bmul, equal, observer, tempBias=0)` | `:67` | recompute temp/green from multipliers (calls `mul2temp`) |
| `cieCAT02` / `icieCAT02float` / `cieCAT02float` | `:115–:117` | **Bradford CAT02** chromatic-adaptation matrices |
| `clip` (static) | `:53 / :54` | clamp temp/green to valid ranges |
| `spectrum_to_xyz_*` / `whitepoint` | `:634–:643` | spectral→XYZ for daylight / blackbody / preset illuminants |

### 2.4 Reuse verdict

`ColorTemp` is the **cleanest reusable primitive** in RT: `#include "colortemp.h"`, construct from multipliers or temperature, call `getMultipliers`/`temp2mul`/`mul2temp`, and you have RT-grade WB math. **No patch, no engine handle, no `ICCStore`, no `ProcParams`.** This is the natural WB layer for our own calibration/demosaic stage (the array+CFA demosaic side, see `RAWTRP-DECODE-000003`), independent of the grading side.

## 3. Integration pattern (no patch)

```
// WB primitive — fully standalone
colortemp::ColorTemp wb(mulR, mulG, mulB, 1.0, ColorTemp::StandardObserver::SO_D50);
wb.getMultipliers(&r, &g, &b);   // apply to our mosaic in f32

// Grading — parameter-driven, link rtengine, marshal planes
procparams::ProcParams pp = /* build from FotDev params */;
ImProcFunctions ipf(&pp, /*multiThread=*/false);
ICCStore::getInstance();         // once, if we call rgb2lab/lab2rgb
Imagefloat working = /* marshal our f32 planes */;
ipf.rgbProc(working, lab, &pp);   // or call leaf methods (vibrance/shadowsHighlights/defringe/...)
```

## 4. What this closes

- It concretises the **B-tier claim** of `RAWTRP-SURVEY-000003` with exact constructors, carrier types, and the `ICCStore` dependency boundary.
- It confirms `ColorTemp` is the decoupled WB primitive we can adopt for our own demosaic/array+CFA path (`RAWTRP-DECODE-000003`) — grading and WB are both patch-free; only the demosaic kernels remain C-tier (patch or vendor).

## 5. Fringe removal — purple/green edge (`defringe`) & chromatic-aberration correction  *(added rev 2)*

"紫边 / 绿边" in RT are **colour fringing** (a symptom of chromatic aberration). RT attacks them at three layers; one is B-tier reusable, one is C-tier (needs patch), one is B-tier (lens lateral CA).

### 5.1 Defringing tool — purple **and** green fringe, same tool, hue-selected (B-tier, no patch)

- **Entry**: `ImProcFunctions::defringe(LabImage* lab)` @ `improcfun.cc:5111` (declared `improcfun.h:583`); CIECAM variant `ImProcFunctions::defringecam(CieImage* ncie)` @ `improcfun.cc:5121` (`improcfun.h:584`).
- **Real implementation**: `ImProcFunctions::PF_correct_RT(LabImage*, double radius, int thresh)` @ `PF_correct_RT.cc:51` (declared `improcfun.h:588`); `PF_correct_RTcam` @ `:589`.
- **Mechanism (why one tool covers both hues)**: in Lab space it Gaussian-blurs `lab->a`/`lab->b`, then compares each pixel's local chroma against the blurred neighbourhood (`PF_correct_RT.cc:73-119`). The action is gated by `params->defringe.huecurve` — a `FlatCurve` read at `PF_correct_RT.cc:55-56` and applied at `:100-114` (`chromaChfactor = SQR(1 + chparam)`, where `chparam` comes from the hue curve). **So the same routine removes purple *or* green fringing: the user lifts the purple (or green) segment of the hue curve, and only those hue pixels get desaturated.** `threshold` (`thresh`) controls aggressiveness; `radius` controls the blur/neighbourhood window.
- **Parameters**: `procparams::DefringeParams { enabled, radius, threshold, huecurve }` (fields confirmed in `rtgui/paramsedited.cc:498-501`).
- **How to call (no patch)**:
  ```
  procparams::ProcParams pp;
  pp.defringe.enabled = true;
  pp.defringe.radius = 1.0; pp.defringe.threshold = 17;
  pp.defringe.huecurve = /* FlatCurve selecting the purple or green hue */;
  ImProcFunctions ipf(&pp, /*multiThread=*/false);
  ipf.defringe(lab);   // lab = post-demosaic LabImage*
  ```
- **Pipeline call site**: `dcrop.cc:1416` `parent->ipf.defringe(labnCrop)` (also `defringecam` at `improcfun.cc:1709` when CIECAM is active).

### 5.2 RAW-stage CA auto-correction — root-cause, strongest, but C-tier (patch or `processImage`)

- **Code**: `RawImageSource::CA_correct_RT(...)` @ `CA_correct_RT.cc:120` (declared `rawimagesource.h:247`).
- **Position**: operates on the **mosaic, before demosaic** (Ingo Weyrich auto-fit algorithm). Called from `rawimagesource.cc:1765-1772` inside `getImage()` (per-frame `rawDataFrames[...]` + the full `rawData`), so it corrects R/B radially on the raw CFA — the most thorough removal of strong purple/green edges.
- **Reachability**: `rawimagesource.h:247` sits inside the `protected:` block opened at `:236`, and `RawImageSource` is `final` (`:43`) — **external call is impossible without a patch** (C-tier, same wall as the demosaic kernels in `RAWTRP-SURVEY-000003` §3). Options: drive the whole pipeline via `rtengine::processImage` (accept RT's own decode + CA), or patch/vendor.

### 5.3 Lens lateral CA correction — gentle R/B shift, B-tier (no patch)

- **Parameters**: `procparams::CACorrectionParams { red, blue }` — a uniform radial shift of the R and B channels relative to G.
- **Application**: inside the `ImProcFunctions::transform` / `resize` geometry-resample path `iptransform.cc`, which reads `params->cacorrection.red` / `.blue` at `:562` / `:564` (and the guards at `:1114` / `:1118` / `:1390`) to build per-channel sample coordinates `red[]` / `green[]` / `blue[]`.
- **How to call (no patch)**:
  ```
  pp.cacorrection.red = 0.003; pp.cacorrection.blue = -0.004;  // non-zero => active
  ipf.transform(...);   // or ipf.resize(...)
  ```
  This is a post-demosaic, mild lateral-CA correction — good for light magenta/green edges.

### 5.4 Reuse summary

| Goal | Recommended path | Tier | No patch? |
| --- | --- | --- | --- |
| Regular purple / green fringe | `ImProcFunctions::defringe` (huecurve selects the hue) | **B** | ✅ |
| Strong fringe, root-cause | `RawImageSource::CA_correct_RT` (RAW stage) | **C** | ❌ (patch or `processImage`) |
| Lateral CA (magenta/green edge) | `ImProcFunctions::transform` `cacorrection` | **B** | ✅ |

Consistent with `RAWTRP-SURVEY-000003`: grading, WB, defringe, and lens-CA are all patch-free via `ImProcFunctions`; only the RAW-stage CA and the demosaic kernels are C-tier.

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` remains a fixed constraint. This document records the *callable contracts* of `ImProcFunctions` and `ColorTemp` (and the fringe/CA correction riding on them) so first-party integration can target them without modifying upstream. No change to RawTherapee source is specified or permitted. Whether we link `rtengine` for grading, vendor kernels, adopt `ColorTemp` standalone, or call `defringe`/`cacorrection` are first-party decisions for a later DESIGN/STRUCT item.

## Open Questions

- Q1 — `ProcParams` construction cost: building a correct `procparams::ProcParams` from our `FotDev` params is non-trivial (many nested structs). Is a thin adapter worth it vs. calling individual `ImProcFunctions` leaf methods with hand-built sub-params?
- Q2 — `ICCStore` init outside a GUI: confirm the singleton constructs cleanly from our Rust/JNI binding with no `rtgui` event loop (profiles load at construction, `iccstore.cc:404–411`).
- Q3 — Grading input stage: do we feed `ImProcFunctions` the post-demosaic `Imagefloat` (RT or our own demosaic), keeping grading strictly downstream of demosaic? (See `RAWTRP-SURVEY-000003` Q4.)
- Q4 — Fringe ordering: `defringe` runs on `LabImage*` (post-demosaic, post-WB-ish Lab); RAW-stage `CA_correct_RT` runs pre-demosaic. If we keep our own demosaic, do we (a) call `ipf.defringe` downstream, or (b) accept the cost of RT's `processImage` to get `CA_correct_RT`? The two are not mutually exclusive but route through different tiers.

## Change History

- 2026-09-21 (rev 1) — Deep dive on the two B-tier reusable classes of `RAWTRP-SURVEY-000003`. **`ImProcFunctions`** (`improcfun.h:137`, `public:` `:169`, ctor `:197` takes `ProcParams*` + `bool multiThread` only — never an `ImageSource*`, so grading is parameter-driven not object-coupled; member `bool multiThread` `:145` gates 47 OpenMP regions in `improcfun.cc`; three carrier types `Imagefloat*`/`LabImage*`/`CieImage*`; full public grading catalogue with lines: tone `tone_eqcam` `:452`/`EPDToneMap` `:311`/`sigmoid_main` `:459`, RGB-stage `rgbProc` `:217` impl `improcfun.cc:2050`, colour `vibrance` `:250`/`shadowsHighlights` `:599`/`defringe` `:583`/`dehaze` `:593`/`toning*` `:229`, sharpen `sharpening` `:254`/`deconvsharpening` `:289`/`MLsharpen` `:292`/`MLmicrocontrast` `:293`, denoise `impulsedenoise` `:297`/`dirpyrdenoise` `:302`/`DeNoise` `:504`, wavelet `dirpyrequalizer` `:303`/`ip_wavelet` `:520`, colour-conv `rgb2lab`/`lab2rgb` `:624`/`:625`, TRC `workingtrc` `:614`, lens `transform` `:261`; only `rgb2lab`/`lab2rgb` depend on `ICCStore::getInstance()->workingSpaceMatrix(name)`). **`ColorTemp`** (`colortemp.h:44`, members `:5–:9`, fully standalone — no engine/`ICCStore` handle; ctors `:61`/`:62`/`:63`/`:64` (`:64` builds from per-channel multipliers — the bridge to our pipeline); `getMultipliers` `:107`, `temp2mul` `:56`, `mul2temp` `:112`, `update` `:67`, Bradford `cieCAT02` `:115`, `clip` `:53`, `spectrum_to_xyz_*`/whitepoint `:634` — the cleanest reusable WB primitive, patch-free, no `ProcParams`). Filed as `RAWTRP-SURVEY-000004`; row appended to `rules/STRUCT/index.md`; SURVEY next-sequence advanced to 000005.
- 2026-09-21 (rev 2) — Added §5 **fringe & CA correction** (purple/green edge removal). **Defringing** = `ImProcFunctions::defringe(LabImage*)` `improcfun.cc:5111` (`improcfun.h:583`), CIECAM `defringecam(CieImage*)` `:5121` (`:584`); real impl `PF_correct_RT(LabImage*,radius,thresh)` `PF_correct_RT.cc:51` (`improcfun.h:588`), `PF_correct_RTcam` `:589`; mechanism Lab a/b chroma blur vs neighbourhood gated by `defringe.huecurve` (FlatCurve) at `PF_correct_RT.cc:55-114` → one tool strips purple OR green by hue selection; params `DefringeParams{enabled,radius,threshold,huecurve}` (`rtgui/paramsedited.cc:498-501`); pipeline `dcrop.cc:1416`. **RAW-stage CA** = `RawImageSource::CA_correct_RT(...)` `CA_correct_RT.cc:120` (`rawimagesource.h:247`, inside `protected:` block from `:236`, class `final` `:43` → C-tier) called `rawimagesource.cc:1765-1772` in `getImage`, pre-demosaic. **Lens lateral CA** = `CACorrectionParams{red,blue}` applied in `ImProcFunctions::transform`/`resize` geometry path `iptransform.cc:562`/`:564`/`:1114`/`:1118`/`:1390` (B-tier, no patch). Updated §1.3 catalogue (added `defringe`/`defringecam` row + `cacorrection` note on `transform`), §1.4 (`defringe`/lateral-CA need no `ICCStore`), §5.4 reuse table, Q4. Title widened to include fringe & CA. Index row title for `RAWTRP-SURVEY-000004` updated accordingly.
