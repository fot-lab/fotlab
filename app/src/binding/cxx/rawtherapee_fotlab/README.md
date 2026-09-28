# rawtherapee_fotlab

FotLab's binding over **RawTherapee's DCP / LCP profile decoders**, plus a Rust
re-implementation of the **deprofile apply** step for the CFA-space develop
pipeline.

This crate does **not** demosaic and does **not** link RawTherapee's
`librtengine`. It vendors ONLY the self-contained DCP/LCP **decode** code
(constructors + getters + `calcParams` + `prepareParams`) from RawTherapee, and
re-writes the **apply** (vignette + distortion) in Rust for our single-channel
CFA mosaic. No submodule hooks, no `glibmm`, no `lcms2`/`exiv2` — the only
compiled C++ is the vendored decoders + a tiny expat replacement.

See `rules/DESIGN/detail/FOTLAB-NATIVE-000005.md` for the product decision; the
**vendor decode only / Rust apply** split is recorded in the task log (the
original plan to link `librtengine` was dropped).

## What it does

```
develop.rs (has CFA, pre-demosaic)
   │ Rust call
   ▼
rawtherapee_fotlab::{parse_dcp, parse_lcp, compute_lcp_model, apply_lcp_cfa}  (this crate, Rust)
   │ cxx call  (decode only)
   ▼
rt_deprofile_shim.cc  (extern "C++", cxx ABI)   — cxx/rt_deprofile_shim.{h,cc}
   │ constructs rtengine::DCPProfile / LCPProfile (vendored decode ctor)
   ▼
vendored RT decoders  — cxx/vendor/rtengine/{dcp,lcp}.cc (trimmed) + expat_minimal
   ▼
parsed params / interpolated model back to Rust → Rust apply (vignette/distortion)
```

## Directory layout

```
rawtherapee_fotlab/
├── Cargo.toml
├── build.rs                      # compiles vendored decoders + shim, links only the C++ stdlib
├── README.md
├── cxx/
│   ├── rt_deprofile_shim.h        # C ABI declaration (cxx types) — the `include!` target
│   ├── rt_deprofile_shim.cc       # C++ adapter → rtengine::DCPProfile / LCPProfile (decode only)
│   └── vendor/                    # trimmed copies of RT's decoders (no apply, no glibmm)
│       ├── rtengine/
│       │   ├── dcp.cc  dcp.h       # DCP (TIFF) profile parser
│       │   ├── lcp.cc  lcp.h       # LCP (XML)  profile parser
│       │   └── rt_math.h           # self-contained math helper (intp / max)
│       └── expat/
│           └── expat_minimal.{h,cc}  # minimal SAX XML reader driving lcp.cc's handlers
└── src/
    ├── lib.rs                    # cxx bridge (decode fns) + panic boundary + re-exports
    ├── dcp.rs                    # parse_dcp -> DcpParams (wraps rt_parse_dcp)
    ├── lcp.rs                    # parse_lcp / compute_lcp_model / apply_lcp_cfa (Rust apply)
    └── deprofile_error.rs        # DeprofileError
```

## Vendoring rules (how the decode copies stay GPL-isolated)

The files under `cxx/vendor/` are **trimmed** copies of RawTherapee's
`rtengine/dcp.{h,cc}` and `lcp.{h,cc}`:

* **Apply code removed.** `DCPProfile::apply*` / `DCPStore` / `LCPStore` /
  `LCPMapper` (the geometry/vignette/CA machinery) are NOT vendored.
* **`Glib::ustring` → `std::string`**, `g_fopen` → `std::fopen`; all
  `settings->verbose` lines dropped. No `glibmm` dependency remains.
* **expat replaced** by `cxx/vendor/expat/expat_minimal.{h,cc}` — a small
  self-contained SAX reader that implements only the `XML_Parser*` API
  `lcp.cc` uses.
* **Only constructors + getters + `calcParams` + `prepareParams`** are kept, so
  the crate never links `librtengine` and is **not** a GPL `librtengine`
  derivative. The Rust apply math in `lcp.rs` is original (derived from the
  decoded coefficients), not copied from RT's apply source.

When upstream RT changes a parser, re-run the same trim (keep the decode, strip
the apply) — do not vendor `librtengine` or add submodule hooks.

## FFI contract

`src/lib.rs` declares three `extern "C++"` functions (mirrored in
`cxx/rt_deprofile_shim.h`; cxx emits static assertions against that header):

* `rt_parse_dcp(path, …) -> i32` — fills the four colour matrices, has-flags,
  illuminants, baseline offset into out-params.
* `rt_parse_lcp(path, …) -> i32` — fills profile metadata (name / camera / lens /
  fisheye / sensor-format / frame count).
* `rt_compute_lcp_model(path, focal…, w, h, model, is_fisheye, swap_xy, err)
  -> i32` — runs `calcParams(VIGNETTE)` + `calcParams(DISTORTION)` +
  `prepareParams` for the given geometry and packs **13 floats** into `model`
  (`x0, y0, fx, fy, vign0..3, dist0..4`); `rfx/rfy` are `1/fx, 1/fy` on the Rust
  side. `swap_xy` reflects the raw-rotation handling RT's `LCPMapper` applies.

All return `0` on success, `<0` with a message in `err` on failure.

## Rust API (consumed by `rawler_fotlab::develop`)

* `parse_dcp(&str) -> Result<DcpParams>` — colour matrices / illuminants /
  baseline offset (read-only parse).
* `parse_lcp(&str) -> Result<LcpParams>` — profile metadata.
* `compute_lcp_model(&str, focal…, w, h) -> Result<LcpModel>` — interpolated
  correction coefficients.
* `apply_lcp_cfa(&str, focal…, vignette, distortion, raw_rotation_deg, w, h,
  &mut [f32]) -> Result<()>` — applies vignette (radial multiplier) and/or
  distortion (geometric reverse-map + bilinear resample) **in place** to a
  single-channel CFA mosaic. CA is intentionally skipped (per-channel, RGB stage).

The apply math mirrors RT's `LCPMapper::processVignette` /
`correctDistortion` (see `rtengine/lcp.cc`) but is original Rust over the decoded
coefficients. Vignette is applied first (RAW-space), then distortion warps the
vignetted mosaic — matching RT's staging order.

## Build integration

`build.rs` uses `cxx_build::bridge` and compiles:

* `cxx/rt_deprofile_shim.cc`
* `cxx/vendor/rtengine/dcp.cc`, `cxx/vendor/rtengine/lcp.cc`
* `cxx/vendor/expat/expat_minimal.cc`

with include paths `cxx/vendor/rtengine` and `cxx/vendor/expat`. It links **only**
the C++ standard library (`dylib=c++` on Android, `dylib=stdc++` on host) — no
`RAWTHERAPEE_SRC` / `RAWTHERAPEE_LIB` env vars, no `librtengine`.

## Licensing

RawTherapee is **GPL v3**, but this crate vendors ONLY the decoder constructors +
getters + `calcParams`/`prepareParams` (apply methods stripped; `Glib::ustring`
→ `std::string`), so it does **not** link `librtengine` and is not a GPL
`librtengine` derivative. The apply code in this crate (`src/lcp.rs`) is original
Rust. If you modify the vendored decoders, preserve their GPL headers.
