# rawtherapee_fotlab

> **No patch shipped.** This crate's C++ shim calls two hooks that must exist in the
> `external/RawTherapee` submodule worktree: `RawImage::set_xtrans` and
> `RawImageSource::demosaic_external`. Those hooks are applied **manually / out-of-band**
> to the submodule — this repository deliberately does **not** carry a `.patch` file.
> Without the hooks present in the submodule, the crate will **not** build/link.

FotLab's binding over **RawTherapee's C++ demosaic algorithms** (`external/RawTherapee`).
It lets the develop pipeline run a selected RawTherapee demosaic kernel over a
caller-supplied CFA mosaic — no file decode, no white balance, no colour management.

This crate mirrors [`../rawler_fotlab`](../rawler_fotlab) in layout and intent.

## What it does

```
develop.rs (already has CFA, pre-demosaic)
   │ cxx call: demosaic_cfa(cfa, w, h, pattern, algorithm)
   ▼
src/demosaic.rs            (Rust; panic-safe, validates, allocates out)
   │ cxx bridge (src/lib.rs)
   ▼
cxx/rt_demosaic_shim.cc    (C++ adapter; builds RawImageSource + RawImage)
   │ calls
   ▼
RawImageSource::demosaic_external   (hook added to external/RawTherapee)
   │
   ▼
librtengine.a  →  amaze / rcd / vng4 / lmmse / igv / xtrans kernels
   ▼
LinearImage (interleaved linear RGB, camera-space, NOT white-balanced)
```

## Directory layout

```
rawtherapee_fotlab/
├── Cargo.toml
├── build.rs                      # compiles the shim + links librtengine
├── README.md
├── cxx/
│   ├── rt_demosaic_shim.h        # C ABI declaration (cxx types)
│   └── rt_demosaic_shim.cc       # C++ adapter → RawImageSource::demosaic_external
└── src/
    ├── lib.rs                    # cxx bridge + panic boundary + re-exports
    ├── demosaic.rs               # demosaic_cfa + CfaPattern + LinearImage + RtDemosaicAlgorithm
    └── error.rs                  # RtDemosaicError
```

## Required RawTherapee hooks (apply to the submodule worktree, out-of-band)

RawTherapee's demosaic algorithms are `RawImageSource` **member methods**, not free
functions, and they read the CFA pattern through the `RawImage` (`ri`), not from
`this`. `RawImageSource` is `final`. So the only clean way to call them is a small
public hook, added to the vendored `external/RawTherapee` (branch `dev`):

1. `RawImage::set_xtrans` — public setter mirror of `getXtransMatrix` (so an X-Trans
   pattern can be supplied without decoding a file).
2. `RawImageSource::demosaic_external` — runs ONE selected algorithm over a
   caller-supplied CFA and writes interleaved linear RGB.

**This repository does NOT ship a patch for these hooks.** Apply them by editing the
submodule sources directly (the exact edits are documented in the code comments of
`cxx/rt_demosaic_shim.cc` and `src/demosaic.rs`, and summarised here):

- In `rtengine/rawimage.h`, add a public `set_xtrans(const int xtransMatrix[6][6])`
  next to `getXtransMatrix`.
- In `rtengine/rawimagesource.h`, add a `public` declaration
  `int demosaic_external(...)` to `RawImageSource` (it is `final`, so no subclassing).
- In `rtengine/rawimagesource.cc`, implement `demosaic_external` (build the `RawImage`,
  set `filters`/`xtrans`, copy the CFA into an owned `this->rawData`, force
  `initialGain = 1.0`, then dispatch the selected kernel).

Because the hooks are maintained in the submodule worktree by hand, they are **not**
part of fotlab's tracked source and will not appear in a fresh `git submodule update`.
If you would rather not keep the hooks in the submodule at all, this crate stays
disabled — the glue code remains as reference.

## Build integration

`build.rs` compiles `cxx/rt_demosaic_shim.cc` and links `librtengine.a`. The
surrounding Android/NDK build must provide:

| Env var            | Meaning                                                      |
|--------------------|-------------------------------------------------------------|
| `RAWTHERAPEE_SRC`  | root of the RawTherapee submodule (for include paths)       |
| `RAWTHERAPEE_GEN`  | dir of RT's *generated* `procparams` headers (if separate)  |
| `RAWTHERAPEE_LIB`  | dir containing `librtengine.a` + transitive `.a` files      |

The link step also needs RT's transitive deps (lcms2, exiv2, fftw3, png, z, glibmm,
OpenMP runtime) — trim/add per target once you see the real undefined symbols.

## Data contract

* **Input `cfa`**: single-channel CFA mosaic, row-major, length `w*h`, **linear**,
  black/white scaled into 0..1, **not** white-balanced. Orientation must match
  `filters` (Bayer bitmask; or `filters = 9` + 6×6 `xtrans` for Fuji X-Trans).
* **Output `LinearImage.rgb`**: interleaved linear RGB, length `w*h*3`, **still in
  camera/CFA space — not white-balanced, no gamma.** The downstream pipeline applies
  white balance + the cam→ProPhoto(D50) matrix afterwards (this is what rawalchemy's
  Log pipeline expects as input).

### Implementation notes baked into the code (also see `src/demosaic.rs`)

* The CFA is **copied** into an OWNED `this->rawData`, not zero-copy wrapped:
  `array2D` copy/assign drops the `ARRAY2D_BYREFERENCE` flag (array2d.h:147-164), so a
  by-reference view assigned to a member would dangle.
* `initialGain` is forced to `1.0` in `demosaic_external` — the ctor leaves it `0.0`,
  and `amaze_demosaic_RT` computes `1.0 / initialGain` (divide-by-zero at 0.0).

## Licensing

RawTherapee is **GPL v3**. Linking `librtengine` makes this binary a GPL v3 derivative.
If fotlab must avoid GPL propagation, run the shim as a separate process and call it
over files/pipes instead of linking.
