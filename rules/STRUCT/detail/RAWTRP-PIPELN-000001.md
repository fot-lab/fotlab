# External module study — RawTherapee develop (processing) pipeline

- ID: RAWTRP-PIPELN-000001
- Status: Draft
- Priority: P2
- Created: 2026-09-17
- Owner: —
- Related: `rules/STRUCT/detail/FOTLAB-STUDIO-000001.md` (Studio render contract; R4 demands a precise full develop), `rules/REVIEW/detail/DNGLAB-RAWLER-000001.md` (our current rawler preview is an unprocessed full-sensor dump), `rules/STRUCT/detail/DNGLAB-RAWDEV-000001.md` (dnglab `rawler::imgop::develop` — the in-crate alternative we already compile), `rules/STRUCT/detail/DNGLAB-SURVEY-000002.md` (rawler decode pipeline & `RawImage` contract)

> **Note on naming**: per the user's request this study file uses the `RAWTRP-` project code (RawTherapee) and the six-character `PIPELN` category (develop pipeline). It lives under `rules/STRUCT/detail/` because `STRUCT.md` principle 5 treats `external/` modules as fixed constraints to be documented, not modified.

> **Scope clarification up front**: this is a **study of what RawTherapee's develop pipeline does and where each stage lives in source** — it is a reference taxonomy of a complete RAW→raster developer. It is **not** a proposal to adopt RawTherapee (a GPL-3.0 C++ codebase of hundreds of KLOC; our native binding is Rust/dnglab, `FOTLAB-NATIVE-000001` R4 keeps `external/` read-only). The value is the *vocabulary of stages* a precise develop must contain, which our `rawler_fotlab` binding currently skips (`DNGLAB-RAWLER-000001`). Whether our future develop uses dnglab's `rawler::imgop::develop` (`DNGLAB-RAWDEV-000001` §7) or another path, this document is the completeness checklist.

## Background & Goal

`FOTLAB-STUDIO-000001` R4 says Studio must show the **true developed image** — real demosaic, white balance, camera→output colour — not the camera-baked preview. Today our `rawler_fotlab::decode_to_png` emits an unprocessed full-sensor dump (`v >> 8`, Bayer shown as grayscale, no black level / WB / demosaic / colour / gamma; `DNGLAB-RAWLER-000001`). The dnglab develop pipeline (`DNGLAB-RAWDEV-000001`) is the in-crate path we can call, but its default step list is minimal and some `todo!()` arms panic.

RawTherapee is the most complete open-source RAW developer in existence. Mapping its pipeline gives us:
1. an authoritative **ordered stage taxonomy** of a production develop,
2. the **working-colour-space / Lab split** pattern it uses,
3. the **data model** (RGB float working buffer vs. CIELAB float buffer) that the creative tools operate on,
4. a **completeness checklist** for whatever develop we eventually wire into `rawler_fotlab`.

All paths below are inside the pinned `external/RawTherapee/rtengine/` submodule. No upstream source is modified.

## 1. Crate/package topology — the engine is `rtengine/`

RawTherapee's processing engine is a single C++ library, `rtengine`, under `external/RawTherapee/rtengine/`. There is no separate "develop" crate; decode and develop coexist as classes in this one library (the same shape as dnglab's `rawler`, which puts decode + `imgop::develop` in one crate).

| Concern | Key files |
| --- | --- |
| Pipeline orchestration | `improccoordinator.cc/.h` (`ImProcCoordinator`), `improcfun.cc/.h` (`ImProcFunctions`) |
| Raw preprocessing + demosaic | `rawimagesource.cc/.h` (`RawImageSource`) |
| Base image source (non-raw) | `imagesource.cc/.h` (`ImageSource`) |
| Creative "Lab" tool passes | `ip*.cc` — `ipsharpen.cc`, `ipshadowshighlights.cc`, `iplocalcontrast.cc`, `ipvibrance.cc`, `ipwavelet.cc`, `ipsoftlight.cc`, `iplab2rgb.cc`, `iptransform.cc`, `ipretinex.cc`, `ipresize.cc`, `iptoneequalizer.cc`, `ipgrain.cc`, `ipdehaze.cc`, `iplocallab.cc` |
| Demosaic algorithms | `ahd_demosaic_RT.cc`, `amaze_demosaic_RT.cc`, `bayer_bilinear_demosaic.cc`, `eahd_demosaic.cc`, `hphd_demosaic_RT.cc`, `lmmse_demosaic.cc`, `rcd_demosaic.cc`, `dual_demosaic_RT.cc`, `fast_demo.cc`, `cfa_linedn_RT.cc`, `green_equil_RT.cc` |
| Colour management | `color.cc/.h` (Lab↔XYZ, matrices), `iccstore.cc/.h` (ICC profiles), `dcp.cc/.h` (DCP profiles), `iccmatrices.h`, `iptransform.cc`, `iplab2rgb.cc`, `curves.cc/.h` |
| Appearance model | `ciecam02.cc/.h` (CIECAM02) |
| Buffers | `imagefloat.cc/.h` (`Imagefloat`, RGB float), `labimage.cc/.h` (`LabImage`, CIELAB float), `image16.cc`, `image8.cc` |

## 2. The top-level driver — correction of the assumed name

There is **no `ImProcFunctions::process`** in this tree. The real GUI/preview/full-image driver is:

- **`ImProcCoordinator::updatePreviewImage(int todo, bool panningRelatedChange)`** at `improccoordinator.cc:339` — this is the function that calls `imgsrc->preprocess`, `imgsrc->demosaic`, `imgsrc->getImage`, `ipf.rgbProc`, every `ipf.*` Lab tool, and finally the output colour conversion. It is the canonical ordered pipeline.
- `ImProcCoordinator::process()` at `improccoordinator.cc:3489` is **not** the chain — it is the GUI dispatch loop that, on a parameter change, re-runs `updatePreviewImage` (`improccoordinator.cc:3558`).
- A second, independent path drives the **crop / detail window and final export**: `DetailCrop::update` in `dcrop.cc` (rgbProc at `dcrop.cc:1372`, Lab tools `dcrop.cc:1395-1545`, final `lab2rgbOut`/`lab2monitorRgb`). The export path in `simpleprocess.cc` calls `ipf.lab2rgbOut(...)` at `simpleprocess.cc:2112`.

The remainder of this document traces `ImProcCoordinator::updatePreviewImage`.

## 3. The ordered pipeline (preview / full-image path)

All stage calls are inside `ImProcCoordinator::updatePreviewImage` (`improccoordinator.cc:339`). The order is:

### Stage A — RAW preprocessing (RAW only)
- `imgsrc->preprocess(...)` — `improccoordinator.cc:398` → `RawImageSource::preprocess` (`rawimagesource.cc:1439`). Inside, in order:
  - reference WB multipliers via `get_colorsCoeff` — `rawimagesource.cc:1449`
  - dark-frame selection — `rawimagesource.cc:1467` (applied later in `copyOriginalPixels`)
  - zero-value bad-pixel map — `rawimagesource.cc:1486`
  - flat-field selection — `rawimagesource.cc:1495` (applied in `copyOriginalPixels`, `rawimagesource.cc:1517-1544`)
  - DNG gain map — `rawimagesource.cc:1549`
  - `.badpixels` file correction — `rawimagesource.cc:1553`
  - dark-frame hot pixels — `rawimagesource.cc:1571`
  - `scaleColors` (black/white level normalisation) — `rawimagesource.cc:1594`
  - lens vignetting — `rawimagesource.cc:1598`
  - hot/dead pixel filter — `rawimagesource.cc:1636`
- `imgsrc->demosaic(...)` — `improccoordinator.cc:470` → `RawImageSource::demosaic` (`rawimagesource.cc:1796`). The algorithm is dispatched by `params->raw.dmethod` (Bayer) / `params->raw.xtransmethod` (X-Trans) among: **AHD, AMAzE, VNG4, LMMSE, RCD, EAHD, HPHD, IGV, bilinear, dual, fast** (each implemented in the `rtengine/*_demosaic*.cc` files listed in §1). X-Trans uses Markesteijn/bilinear variants. `green_equil_RT.cc` (green-channel equalisation) and `cfa_linedn_RT.cc` (linenoise/CA) run around it.
- Capture sharpening: `imgsrc->captureSharpening(...)` — `improccoordinator.cc:487` (only if `params->pdsharpening.enabled`).
- Retinex prepare/curves/apply — `improccoordinator.cc:515, 532, 534` (only if `params->retinex.enabled`).

### Stage B — Produce the RGB working-space image (`getImage`)
- `imgsrc->getImage(currWB, tr, orig_prev, pp, params->toneCurve, params->raw)` — `improccoordinator.cc:768` → `RawImageSource::getImage` (`rawimagesource.cc:758`). Inside, in order:
  - compute WB multipliers — `rawimagesource.cc:767-817`
  - apply WB coefficients — `rawimagesource.cc:884-907`
  - highlight recovery (HDR / Color / Coloropp / Luminance) — `rawimagesource.cc:867-994`
  - transform (Fuji/Standard line) + flip — `rawimagesource.cc:998-1066`
  - false-colour correction — `rawimagesource.cc:1068-1083`
  - result is `orig_prev`, an **`Imagefloat`** (RGB, in the working colour space).

### Stage C — Spots / film negative
- Spot removal: `ipf.removeSpots(...)` — `improccoordinator.cc:773` / `868` (only if `params->spot.enabled`).
- Film-negative inversion: `ipf.filmNegativeProcess(...)` — `improccoordinator.cc:829` (wrapped around the colour-space conversion depending on `filmNegative.colorSpace`).

### Stage D — Colour-space conversion (input/camera profile → working space)
- `imgsrc->convertColorSpace(orig_prev, params->icm, currWB)` — `improccoordinator.cc:839`. This applies the **input/camera profile** (camera ICC, DCP profile via `dcp.cc`, or a standard matrix via `color.cc`/`iccmatrices.h`) and lands the pixels in the **working colour space** (default **ProPhoto**, `improcfun.cc:2241` `isProPhoto`; `params->icm.workingProfile`).
- Gamut compression: `ipf.gamutcompr(...)` — `improccoordinator.cc:851`.

### Stage E — HDR / dehaze tone map
- `ipf.dehaze(...)` then `ipf.ToneMapFattal02(...)` — `improccoordinator.cc:887-888` (only if `params->fattal.enabled || params->dehaze.enabled`).

### Stage F — Geometry transform (rotate / crop)
- `ipf.transform(...)` — `improccoordinator.cc:907` (only if `needstransform`), flipping/rotating/cropping the working image.

### Stage G — Wavelet equalizer ("bef" branch) in Lab
- `ipf.rgb2lab(*oprevi, labcbdl, workingProfile)` → `ipf.dirpyrequalizer(&labcbdl, scale)` → `ipf.lab2rgb(labcbdl, *oprevi, workingProfile)` — `improccoordinator.cc:927-929`. This is the first **RGB→Lab→RGB** round-trip; it establishes the pattern that most creative tools run in Lab.

### Stage H — Auto-exposure / tone-curve auto
- `ipf.getAutoExp(...)` — `improccoordinator.cc:937` (only if `params->toneCurve.autoexp` or histogram matching).

### Stage I — Local adjustments ("Locallab") in Lab
- `ipf.rgb2lab(*oprevi, *oprevl, workingProfile)` then `ipf.Lab_Local(...)` — `improccoordinator.cc:1181`, `1495`. The large locallab block (`improccoordinator.cc:1179-1667`) computes spot references and applies per-spot exposure/tone/colour/retinex/cie adjustments in Lab.

### Stage J — Main RGB→Lab tone & colour stage (`rgbProc`)
- `ipf.rgbProc(...)` — `ImProcFunctions::rgbProc` at `improcfun.cc:2063`. This is the **central creative stage**: it takes the working `Imagefloat` (RGB) and emits a **`LabImage`**. In order it applies exposure (`expcomp`), highlight/shadow tone compression (`hltonecurve`/`shtonecurve`), the user tone curve (`tonecurve`), per-channel RGB curves (`rCurve`/`gCurve`/`bCurve`), saturation, channel mixer (`chmixer`), colour toning (`ctColorCurve`/`ctOpacityCurve`), film-simulation CLUT (`filmSimulation`, `CLUTStore`), DCP step-2 (`dcpProf`), and black-and-white (`blackwhite`). It pulls working-space matrices from `ICCStore::getInstance()->workingSpaceMatrix/workingSpaceInverseMatrix` (`improcfun.cc:2101-2135`).

### Stage K — Lab-space creative tools
After `rgbProc`, the pixel buffer is a `LabImage` (`nprevl`) and the remaining tools run in CIELAB:
- Shadow / highlight: `ipshadowshighlights.cc`
- Local contrast: `iplocalcontrast.cc`
- USM sharpening: `ipsharpen.cc`
- Impulse (dead-pixel) denoise: `impulse_denoise.cc`
- Wavelet: `ipf.ip_wavelet(...)` — `improccoordinator.cc:2044` (incl. guided-filter soft radius), wrapping `ipwavelet.cc`
- Soft light: `ipf.softLight(...)` — `improccoordinator.cc:2216` (`ipsoftlight.cc`)
- Colour appearance (CIECAM02): `ciecam02.cc`, gated by `params->colorappearance`
- Grain, vibrance, tone equalizer, dehaze, retinex, resize, etc., each in its own `ip*.cc`

### Stage L — Output colour conversion (Lab → output RGB + TRC/gamma)
- Working-space TRC / gamma: `ipf.lab2rgb` then `ipf.workingtrc(...)` — `improccoordinator.cc:2244, 2281-2282` (applies the working-profile tone response curve, optional local contrast / saturation / primaries in CIE).
- Final Lab→output-RGB with the **output ICC profile** (and output TRC): `ipf.lab2rgbOut(...)` (used for export at `simpleprocess.cc:2112`; the preview path uses `ipf.lab2rgb`/`ipf.workingtrc` round-trips, e.g. `improccoordinator.cc:2330` `ipf.rgb2lab` closing a round-trip). The output profile and TRC come from `params->icm` via `iccstore.cc` / `iptransform.cc` / `iplab2rgb.cc`.

## 4. Data model — two buffers, one deliberate split

RawTherapee deliberately splits the pipeline between two buffer types:

| Buffer | Type | Used by | Notes |
| --- | --- | --- | --- |
| `Imagefloat` | RGB float (`r,g,b` planes) | Stages A–I, the working space | Produced by `getImage`; lives in the working colour space after `convertColorSpace` (`improccoordinator.cc:839`) |
| `LabImage` | CIELAB float (`L,a,b` planes) | Stages J–L creative tools | Most creative tools (shadow/highlight, local contrast, sharpen, wavelet, soft light, CIECAM) run here |
| `Image16` / `Image8` | integer | I/O, thumbnails | 16-bit for TIFF/export, 8-bit for display |

The conversion helpers are `ipf.rgb2lab` / `ipf.lab2rgb` (`iplab2rgb.cc`) and `Color::Lab2XYZ` / `Color::XYZ2Lab` (`color.cc`). The **RGB→Lab→RGB round-trip** (Stage G, and again at output) is the architectural backbone: RawTherapee does demosaic + geometry + base tone in RGB, then moves into Lab for the perceptual creative tools, then back to RGB for the output TRC. This contrasts with dnglab `rawler::imgop::develop`, which develops in **linear RGB** and only emits a Lab/CIELAB-free `Intermediate`.

## 5. Colour-management chain (explicit order)

1. **Input/camera profile** applied in `getImage` + `convertColorSpace` (`improccoordinator.cc:768, 839`): camera ICC, DCP (`dcp.cc`), or standard matrix (`color.cc`, `iccmatrices.h`) → **working space** (ProPhoto default).
2. **Working space** is where all RGB stages (A–I) and the Lab creative tools (J–K) operate; matrices fetched from `ICCStore` (`improcfun.cc:2101`).
3. **Output profile + TRC/gamma** at Stage L (`lab2rgbOut` / `workingtrc`, `improccoordinator.cc:2244-2330`): Lab → output RGB through `iccstore.cc` / `iptransform.cc` / `iplab2rgb.cc`, with the output tone-response curve and optional CIE primaries/saturation.

Tone/exposure/curve ordering relative to colour: exposure + tone curve + RGB curves + saturation + colour-toning + film-sim + DCP-step-2 all happen **inside `rgbProc` (Stage J)**, *before* the Lab creative tools and *after* the input→working colour conversion — i.e. tone is applied in RGB-working, colour/perceptual creatively happens in Lab, and the output TRC closes the chain.

## 6. Non-raw / preview forks

- **Non-raw input** (`imagesource.cc` `ImageSource`, not `RawImageSource`) skips Stages A–B preprocessing/demosaic and enters at the working-RGB `getImage`; the rest of the chain is shared.
- **Preview vs full vs detail/crop** are three drivers (`updatePreviewImage`, `DetailCrop::update`, `simpleprocess.cc` export) that reuse the same `ipf.*` stages at different scales; the stage *order* is identical, only the buffer size and the final output sink differ.

## 7. Implications for our `rawler_fotlab` develop work

Facts only; the implementation decision belongs to a future DESIGN/STRUCT item (linked to `DNGLAB-RAWLER-000001`, `DNGLAB-RAWDEV-000001`, `FOTLAB-STUDIO-000001`).

- RawTherapee confirms the **minimum complete develop ordering** our `DNGLAB-RAWLER-000001` recommendation already listed: black/white normalisation → white balance → demosaic → colour management (camera→working→output) → tone curve/exposure → local contrast → sharpening → denoise → output TRC/gamma. RawTherapee additionally shows the **RGB-working vs Lab-creative split** as the standard way to organise it.
- Our current `decode_to_png` does **none** of Stages A–L — it is a raw 16→8 shift (`DNGLAB-RAWLER-000001`). The dnglab `rawler::imgop::develop` default (`DNGLAB-RAWDEV-000001`) covers the linear-RGB half (black/white → WB → demosaic → camera→sRGB → gamma) but not RawTherapee's richer Lab creative set (shadow/highlight, local contrast, CIECAM, wavelet, film sim).
- **Architectural note**: dnglab develops in linear RGB; RawTherapee develops in RGB-working then Lab. If we deepen `rawler_fotlab` via `rawler::imgop::develop` we inherit the linear-RGB model; a RawTherapee-style Lab creative layer would be first-party work on top.
- **Licensing / feasibility**: RawTherapee is GPL-3.0 C++ (hundreds of KLOC, SIMD + OpenMP). Adopting it as our developer would invert `FOTLAB-NATIVE-000001` R4 (upstream read-only, single integration point) and contradict the "deepen the dnglab binding, do not rewrite in Kotlin/C++" conclusion of `DNGLAB-RAWLER-000002`. This study is therefore a **reference**, not a migration plan.

## Constraints (STRUCT.md principle 5)

`external/RawTherapee` remains a fixed constraint: this study records where the pipeline lives, its ordered stages, its colour-management chain and its data model. No change to RawTherapee source is specified or permitted. Whether/when `rawler_fotlab` gains a develop pipeline, and at what fidelity, are first-party decisions for a later item.

## Change History

- 2026-09-17 — RawTherapee develop-pipeline study. Traced `ImProcCoordinator::updatePreviewImage` (`improccoordinator.cc:339`) as the canonical driver (correcting the non-existent `ImProcFunctions::process` assumption), and documented the ordered stages A–L: raw preprocessing (`RawImageSource::preprocess`, `rawimagesource.cc:1439`; `scaleColors` black/white at `:1594`), demosaic dispatch (`rawimagesource.cc:1796`, algorithms AHD/AMAzE/VNG4/LMMSE/RCD/EAHD/HPHD/IGV/dual/fast), `getImage` WB+highlight-recovery (`rawimagesource.cc:758`), input→working colour conversion (`convertColorSpace`, `improccoordinator.cc:839`, ProPhoto default), HDR/dehaze, geometry transform, wavelet-in-Lab, auto-exp, locallab, the central `rgbProc` tone/colour stage (`improcfun.cc:2063`), the Lab creative tools (shadow/highlight, local contrast, USM, impulse denoise, wavelet, soft light, CIECAM), and the output Lab→RGB+TRC conversion (`lab2rgbOut`/`workingtrc`). Recorded the `Imagefloat`(RGB-working) vs `LabImage`(CIELAB) data-model split, the colour chain, and the non-raw/preview/export forks. Filed as `RAWTRP-PIPELN-000001`; row appended to `rules/STRUCT/index.md`.
