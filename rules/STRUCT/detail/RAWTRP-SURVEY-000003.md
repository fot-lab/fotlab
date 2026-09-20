# External module study — RawTherapee externally-callable surface WITHOUT patching

- ID: RAWTRP-SURVEY-000003
- Status: Draft
- Priority: P2
- Created: 2026-09-20
- Owner: —
- Related: `rules/STRUCT/detail/RAWTRP-DECODE-000003.md` (the rawler→RT kernel bridge — why "feed our own array+CFA" needs a patch), `rules/STRUCT/detail/RAWTRP-SURVEY-000001.md` (working colour space: 12 built-in profiles + JSON), `rules/STRUCT/detail/RAWTRP-PIPELN-000001.md` (full develop pipeline — where each public method is invoked)

> **Note on naming**: this study uses the `RAWTRP-` project code and the `SURVEY` category. It lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified.

> **Scope**: this is a **reference map of what RawTherapee exposes to an external caller without any source patch** — i.e. the set of integration points we can lean on from Rust/our binding. The motivation is concrete: we previously wanted to reuse (a) the demosaicing algorithms and (b) the basic grading/tone/colour algorithms, and hit a wall because the algorithms are declared as members of `final` classes. This document separates what *is* reachable no-patch (the whole grading stack + WB math + the full-pipeline entry) from what is *not* (the demosaic kernels), so the no-patch vs. patch decision is evidence-based. No upstream source is modified.

All paths are inside the pinned `external/RawTherapee/` submodule. Line numbers verified against the checked-out tree.

## Background & Goal

RawTherapee (`rtengine`) is a C++ static library. Its `rtgui`/`rtcli` front-ends drive it through a small public umbrella (`rtengine.h`) plus a large set of GUI-internal headers. The question this study answers: **given the project rule "no patch ships in this repo", which parts of the pipeline can we actually call?**

The answer is a three-tier surface:

- **A — Stable public API** (umbrella `rtengine.h`, also the `ImageSource` abstract interface). Zero patch, stable ABI intent.
- **B — GUI-internal classes** (`ImProcFunctions`, `ColorTemp`, `ICCStore`). Usable by `#include` + link, no patch — but NOT in the `rtengine.h` umbrella, so they are not a stable ABI contract. This is where *all* basic grading lives.
- **C — Unreachable without patch**. The demosaic kernels are `protected` members of `RawImageSource final`; the public `demosaic()` is only a dispatcher that reads internal state. There is no getter to obtain an `ImProcFunctions` instance from a stable handle either.

## 1. A-tier — stable public API (rtengine.h umbrella)

These are the documented entry points. A front-end (or our binding) builds a `ProcessingJob` and calls `processImage`.

| Symbol | Location | Role |
| --- | --- | --- |
| `InitialImage::load(fname, isRaw, *errorCode, pl)` | `rtengine.h:226` | Open a file → `InitialImage*` (RAW vs std chosen by the `isRaw` flag the caller supplies) |
| `class ProcessingJob` | `rtengine.h:856` | Holds an `InitialImage` + `ProcParams` |
| `ProcessingJob::create(InitialImage*, const ProcParams&, fast=false)` | `rtengine.h:877` | Build a job from a loaded image + parameter block |
| `IImagefloat* processImage(ProcessingJob*, int& err, ProgressListener*, flush)` | `rtengine.h:893` | **Full-pipeline external entry** — decode → preprocess → demosaic → grade → colour → output |
| `void startBatchProcessing(ProcessingJob*, BatchProcessingListener*)` | `rtengine.h:913` | Batch variant |
| `class ImageSource : public InitialImage` | `imagesource.h:74` | Abstract base of every loaded image; its `public virtual` surface *is* the stable per-image API |

`processImage` is implemented in `simpleprocess.cc:2441` and internally drives `ImProcCoordinator::process()` (`improccoordinator.cc:3489`) — the staged pipeline. **This is the cleanest no-patch way to "reuse the whole pipeline"**: set `ProcParams.demosaic`/`raw`/`icm`/… and get a fully developed `Imagefloat` back. The cost is that demosaic runs *inside* RT on RT's own decoded buffer — you cannot substitute your own array+CFA (that is the C-tier wall, see §3).

### 1.1 The `ImageSource` virtual surface (stable, per-image)

Declared `public virtual` in `imagesource.h`. These are the granular knobs a caller can turn without the full `processImage`:

| Method | Line | Reachable no-patch? | Notes |
| --- | --- | --- | --- |
| `preprocess(raw, lensProf, coarse, …)` | `:94` | ✅ | Lens/coarse-transform prep |
| `demosaic(raw, autoContrast, contrastThreshold, cache)` | `:95` | ⚠️ dispatcher only (see §3) | Default impl is empty `{}` |
| `getWBMults(ctemp, raw, scale_mul[], …)` | `:109` | ✅ | WB multiplier math |
| `getImage(ctemp, tran, Imagefloat*, PreviewProps, hlp, raw)` | `:112` | ✅ | Stage-by-stage RGB output (ROI) |
| `convertColorSpace(Imagefloat*, cmp, wb)` | `:118` | ✅ | Working→output profile conversion |
| `getAutoWBMultipliers(rm, gm, bm)` | `:119` | ✅ | Auto-WB estimate |
| `getWB() const → ColorTemp` | `:148` | ✅ | Current WB as a `ColorTemp` (B-tier object) |
| `getSpotWB(red, green, blue, …)` | `:149` | ✅ | Spot WB |
| `getImageMatrices() → ImageMatrices*` | `:165` | ✅ | Camera→XYZ matrices |
| `isRAW() const` | `:166` | ✅ | RAW vs std flag |
| `getRAWHistogram(...)` | `:189` | ✅ | RAW histogram |

So the *public* surface already gives WB, colour-space conversion, staged RGB extraction, and histograms. What it does **not** give is a way to feed a custom mosaic array into the demosaic step.

## 2. B-tier — GUI-internal classes (no patch, but not in the umbrella)

RawTherapee's basic grading algorithms are **not** behind `rtengine.h`; they live in headers the GUI pulls in directly. Because `rtengine` is a static lib we link, we can `#include` these headers too — **no patch required** — but they carry no ABI-stability promise and depend on internal types (`LabImage`, `Imagefloat`, `CieImage`).

### 2.1 `ImProcFunctions` — the grading algorithm container

- Declared `class ImProcFunctions` at `improcfun.h:137`; `public:` at `:169`.
- **Constructor**: `explicit ImProcFunctions(const procparams::ProcParams* iparams, bool imultiThread = true)` (`improcfun.h:197`). It takes **only a `ProcParams*` and a threading flag — never an `ImageSource*`**. This is the key fact: grading is parameter-driven, not object-coupled.
- Member `bool multiThread` (`:145`) gates OpenMP; `improcfun.cc` contains 47 `#pragma omp` regions, so threading follows the flag.

**Catalogue of public grading methods (all reachable no-patch):**

| Group | Method(s) | Line | Input type |
| --- | --- | --- | --- |
| **Tone / exposure** | `tone_eqcam`, `tone_eqcam2`, `tone_eqdehaz` | `:452 / :450 / :451` | `Imagefloat*` (RGB working) |
| | `EPDToneMap`, `EPDToneMaplocal`, `EPDToneMapCIE` | `:311 / :312 / :313` | `LabImage*` / `CieImage*` |
| | `sigmoid_main`, `tonemapFreeman`, `tonemapFreemanQ` | `:459 / :453 / :454` | scalar helpers |
| **Curves / colour (RGB stage)** | `rgbProc(...)` | `:217 / :223` (impl `improcfun.cc:2050`) | `Imagefloat* working` + `LabImage* lab` — the master dispatcher that runs tone curve, saturation, channel mixer, film sim, colour boost |
| | `luminanceCurve`, `chromiLuminanceCurve` | `:239 / :249` | `LabImage*` |
| **Saturation / colour** | `vibrance` | `:250` | `LabImage*` + `workingProfile` |
| | `shadowsHighlights` | `:599` | `LabImage*` |
| | `defringe` | `:583` | `LabImage*` |
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
| **Lens / transform** | `transform`, `resize`, `Lanczos`, `drawFrame`, `filmNegativeProcess` | `:261 / :265 / :266 / :286 / :209` | `Imagefloat*` |
| **Exposure PDE / texture** | `exposure_pde`, `retinex_pde`, `detail_mask`, `laplacian`, `blendstruc` | `:349 / :348 / :339 / :338 / :468` | buffers |

**Integration cost (not a patch):** every method eats an RT-internal image type — `Imagefloat*` (the RGB working buffer), `LabImage*` (CIELAB), or `CieImage*` (CIECAM). To reuse a method you marshal your f32 mosaic/planes into the appropriate type and call `new ImProcFunctions(&pp)` (+ optionally `setScale`). The colour-conversion pair `rgb2lab`/`lab2rgb` at `:624/:625` internally calls `ICCStore::getInstance()->workingSpaceMatrix(name)`, so `ICCStore` must be initialised (see §2.3).

### 2.2 `ColorTemp` — white-balance math (fully standalone)

- `class ColorTemp` at `colortemp.h:44`. Data members (`:5–:9`): `double temp; double green; double equal; std::string method; StandardObserver observer;`.
- **Constructors**:
  - default `ColorTemp()` (`:61`) — `temp=-1, green=-1, equal=1, method="Custom"`
  - `explicit ColorTemp(double e)` (`:62`)
  - `ColorTemp(t, g, e, method, observer)` (`:63`)
  - **`ColorTemp(mulr, mulg, mulb, e, observer)`** (`:64`) — build directly from per-channel WB multipliers (this is what `ImageSource::getWB()` returns and what you'd feed a demosaic/WB step)
- **Conversion math** (all public, no engine instance needed):
  - `getMultipliers(mulr, mulg, mulb)` (`:107`) → `temp2mul(...)`
  - `temp2mul(temp, green, equal, observer, rmul, gmul, bmul)` (`:56`) — temperature → multipliers
  - `mul2temp(rmul, gmul, bmul, equal, observer, temp, green)` (`:112`) — multipliers → temperature
  - `update(rmul, gmul, bmul, equal, observer, tempBias=0)` (`:67`) — recompute temp/green from multipliers (calls `mul2temp`)
  - `cieCAT02` / `icieCAT02float` / `cieCAT02float` (`:115–:117`) — **Bradford CAT02 chromatic-adaptation** matrices
  - `clip` (static, `:53/:54`) — clamp temp/green to valid ranges; `spectrum_to_xyz_*` / `whitepoint` (`:634–:643`) — spectral→XYZ for daylight/blackbody/preset illuminants
- **Reuse verdict**: `ColorTemp` is a self-contained WB utility. `#include "colortemp.h"` and use it directly — no patch, no engine handle, no `ICCStore`. This is the cleanest reusable primitive for our own calibration/WB stage.

### 2.3 `ICCStore` — profile singleton (needed by colour conversion)

- `class ICCStore final` at `iccstore.h:57`; `static ICCStore* getInstance()` at `:66`.
- Public: `workingSpace(name)`, `workingSpaceMatrix(name)`, `workingSpaceInverseMatrix(name)` (`:70–:73`), `getWorkingProfiles()` (`:97`).
- The colour-conversion methods in §2.1 (`rgb2lab`/`lab2rgb` working-space overloads) and the working-space matrix consumption in `improcfun.cc` (see `RAWTRP-SURVEY-000001` §6) all route through this singleton. To call `ImProcFunctions` colour methods you must ensure `ICCStore::getInstance()` is populated (it loads the 12 built-in + JSON profiles at construction, `iccstore.cc:404-411 / :833`).

## 3. C-tier — unreachable WITHOUT a patch (the demosaic wall)

This is the root of the earlier difficulty.

- `RawImageSource` is declared **`final`** (`rawimagesource.h:43`) — it cannot be subclassed to reach protected members.
- A `protected:` block opens at `:53` and again at `:236`; **all demosaic kernels are declared `protected`** in the `:275–:286` region:
  - `eahd_demosaic` (`:276`), `hphd_demosaic` (`:277`), `vng4_demosaic(rawData, red, green, blue)` (`:278`), `igv_interpolate` (`:279`), `lmmse_interpolate_omp(winw, winh, rawData, red, green, blue, iterations)` (`:280`), `amaze_demosaic_RT(winx, winy, winw, winh, rawData, red, green, blue, …)` (`:281`), `dual_demosaic_RT(...)` (`:282`), `fast_demosaic` (`:283`), `dcb_demosaic` (`:284`), `ahd_demosaic` (`:285`), `rcd_demosaic(...)` (`:286`), `bayer_bilinear_demosaic(...)` (`:308`).
- The **public** `demosaic(raw, autoContrast, contrastThreshold, cache)` at `rawimagesource.h:130` is **only a dispatcher**: it reads `this->rawData` (the object's internal mosaic `array2D<float>`) and writes `this->red/green/blue`. It accepts **no** caller-supplied array and **returns nothing** — so you cannot route your own rawler-decoded buffer through it.
- `ImageSource` / `RawImageSource` expose **no getter** for an `ImProcFunctions` instance (`ipf` is an internal member used by the coordinator, not surfaced on the stable handle). You obtain grading only by constructing `ImProcFunctions` yourself (§2.1) — which is fine for grading, but grading is downstream of demosaic.

**Consequence for our two goals:**

| Goal | No-patch feasible? | Path |
| --- | --- | --- |
| Reuse **basic grading** (tone/curve/colour/sharpen/denoise/WB) | ✅ Yes | `ImProcFunctions` methods are all `public`; ctor takes `ProcParams*` only. Marshal data into `Imagefloat`/`LabImage`, link `rtengine`. `ColorTemp` is fully standalone. |
| Reuse **demosaic kernels** with our own array+CFA | ❌ No | Kernels are `protected` + class is `final`; public `demosaic()` is a stateful dispatcher. Feeding a custom `array2D<float>`+CFA requires either a patch (the previously-rejected `demosaic_external`) or re-hosting the kernel as a free function (vendoring, see `RAWTRP-DECODE-000003`). |
| Reuse **the whole pipeline** (accept RT's own demosaic) | ✅ Yes | `rtengine::processImage(ProcessingJob*, …)` (`rtengine.h:893`) + `procparams::ProcParams`. You lose control of the mosaic buffer but get everything else. |

## 4. Recommendation summary

- **Grading + WB: integrate no-patch.** Build `procparams::ProcParams` programmatically, marshal our f32 planes into `Imagefloat`/`LabImage`, `new ImProcFunctions(&pp)`, call the method(s) we want, and pull results back. `ColorTemp` covers WB math standalone. This satisfies "reuse basic grading algorithms" with zero repo patches.
- **Demosaic: do not block on RT.** Either (a) accept RT's internal demosaic via `processImage` for the cases where we don't need a custom buffer, or (b) vendor the kernel as a free function (per `RAWTRP-DECODE-000003`) so we keep the array+CFA contract without patching `external/RawTherapee`.
- **Do not add a patch** to expose `demosaic_external`/`getImProcFunctions` — it violates the project's no-patch rule and the `final` class already makes the getter route impossible anyway.

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` remains a fixed constraint. This study documents the *callable surface* (A/B tiers) and the *unreachable core* (C tier) so first-party integration can be designed without modifying upstream. No change to RawTherapee source is specified or permitted. Whether we link `rtengine` as a static lib for grading, vendor selected kernels, or drive `processImage` end-to-end are first-party decisions for a later DESIGN/STRUCT item.

## Open Questions

- Q1 — Linking cost: `rtengine` is a large static lib (pulls lcms2, fftw, lensfun, exiv2). Is the binary-size/licence (GPL-3.0) boundary acceptable for our product, or do we restrict reuse to *vendored* kernels (which also inherit GPL-3.0)? The licence question is unchanged by the no-patch decision.
- Q2 — `ProcParams` construction: building a correct `procparams::ProcParams` from our `FotDev` params is non-trivial (many nested structs). Is a thin adapter worth it vs. calling individual `ImProcFunctions` methods with hand-built sub-params?
- Q3 — `ICCStore` init in a non-GUI context: the singleton loads profiles at construction; confirm it initialises cleanly when called from our Rust/JNI binding without an `rtgui` event loop.
- Q4 — For grading we still need a demosaiced (or at least planar) input. Does our pipeline feed `ImProcFunctions` the post-demosaic `Imagefloat` (RT or our own demosaic), keeping grading strictly downstream?

## Change History

- 2026-09-20 — RawTherapee no-patch callable-surface study. Documented the three tiers: **A** stable public API (`rtengine.h:226` `InitialImage::load`, `:856`/`877` `ProcessingJob`, `:893` `processImage` impl `simpleprocess.cc:2441` → `ImProcCoordinator::process` `improccoordinator.cc:3489`; plus the `ImageSource` virtual surface `imagesource.h:74` `load/preprocess/demosaic/getImage/convertColorSpace/getWB/...` at `:94–:189`); **B** GUI-internal but patch-free classes — `ImProcFunctions` (`improcfun.h:137`, ctor `:197` takes `ProcParams*` only, `multiThread` `:145`, 47 OpenMP regions in `improcfun.cc`) with its full public grading catalogue (tone `tone_eqcam` `:452`/`EPDToneMap` `:311`/`sigmoid_main` `:459`, curves `rgbProc` `:217` impl `:2050`, colour `vibrance` `:250`/`shadowsHighlights` `:599`/`defringe` `:583`/`dehaze` `:593`/`toning*` `:229`, sharpen `sharpening` `:254`/`deconvsharpening` `:289`/`MLsharpen` `:292`/`MLmicrocontrast` `:293`, denoise `impulsedenoise` `:297`/`dirpyrdenoise` `:302`/`DeNoise` `:504`, wavelet `dirpyrequalizer` `:303`/`ip_wavelet` `:520`, colour-conv `rgb2lab`/`lab2rgb` `:624`/`:625`, TRC `workingtrc` `:614`), `ColorTemp` (`colortemp.h:44`, members `:5–:9`, ctor-from-multipliers `:64`, `getMultipliers`/`mul2temp`/`temp2mul` `:107`/`:112`/`:56`, Bradford `cieCAT02` `:115`), and `ICCStore` singleton (`iccstore.h:57`/`66`); **C** the unreachable demosaic core — `RawImageSource final` (`rawimagesource.h:43`), kernels `protected` at `:275–:286`, public `demosaic()` `:130` is a stateful dispatcher, no `ipf` getter. Reuse verdict: grading + WB are patch-free; demosaic kernels require a patch or vendoring. Filed as `RAWTRP-SURVEY-000003`; row appended to `rules/STRUCT/index.md`.
