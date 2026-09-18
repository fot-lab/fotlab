# Handoff: rawler ProPhoto-D50 linear → RawAlchemyCpp post-processing

- ID: FOTLAB-RAWLER-000006
- Status: Approved
- Priority: P1
- Created: 2026-09-19
- Owner: —
- Related: FOTLAB-RAWLER-000005 (wide-gamut dual-fork), FOTLAB-RAWLER-000004 (decode-once), FOTLAB-PIPELN-000001 (pipeline; Q2 resolved by this item)

## Background & Goal

`FOTLAB-RAWLER-000005` split `develop` into two forks:

- **UI fork** — `develop_to_png` → sRGB D65 + gamma → finished display PNG.
- **Editing fork** — `RawlerImageDeveloped` (`rawler_fotlab::develop::RawlerImageDeveloped`), a `#[uniffi::Record]` of `width/height + rgb: Vec<f32>`, holding **linear ProPhoto RGB at D50, unclamped** (negatives and >1 retained). This is the object destined for RawAlchemyCpp downstream post-processing.

This item specifies how that object crosses into RawAlchemyCpp, the color-space contract on both sides, and the four design decisions that remove the earlier open risks. It is the implementation plan for the decided approach (no longer a `Proposal`).

## Finding — the two data contracts

### rawler side (`RawlerImageDeveloped`, editing fork)
- `rgb: Vec<f32>`, **RGB interleaved, row-major, `w*h*3`, no stride**.
- Linear **ProPhoto RGB, D50** white point, **unclamped** (negative / >1 values survive).
- Defined at `app/src/binding/rust/rawler_fotlab/src/develop.rs:43`.

### RawAlchemyCpp side (external/RawAlchemyCpp, AGPL-3.0)
- Internal `rawalchemy::ImageBuffer` (`include/common.h:21`): `std::vector<float> data`, RGB interleaved, row-major, `channels==3`, constructor `ImageBuffer(w,h,3)` allocates `w*h*3`. **Byte-for-byte identical layout to `RawlerImageDeveloped.rgb`** → one `memcpy` in, one read-back out; no re-ordering.
- The single fused post-process step is **`applyGradingFused(ImageBuffer&, const GradingParams&)`** (`include/grading_fused.h:60`). Per-pixel order: gain → saturation/contrast → gamut (ProPhoto→target, 3×3) → log OETF → optional 3D LUT.
- Color-space assumptions:
  - Gamut matrices in `include/color_data.h` (`MAT_PROPHOTO_TO_*`) are "ProPhoto RGB → Target Gamut (Linear)" computed by colour-science `matrix_RGB_to_RGB` with **CAT02** adaptation. **ProPhoto RGB's reference white is D50** → the grading stage *assumes ProPhoto-D50 input*.
  - **Important latent inconsistency in RawAlchemyCpp's own file path**: `src/raw_decoder.cpp:416` states its `decodeRaw` output "carries the correct **D65** white-point normalization". So feeding that D65 image into the D50-assuming `LOG_SPACES` matrices is internally slightly off. **Our D50 output therefore matches the grading contract better than rawalchemy's own decode path does** — a benefit, not a problem.
- Output of `applyGradingFused` with a log space (e.g. `"F-Log"`): **target gamut (F-Gamut) + F-Log-encoded `float`** — a log-encoded *creative* image, not display-ready. (`common.h:26` documents `[0,1]` but the pipeline passes unclamped values through; negatives are clamped to ~0 at the log stage via `max(r,1e-6)` per `grading_fused.cpp:104`, values >1 survive into the LUT which clamps to its domain.)
- The C API's in-memory entry `runGradingOnly(ImageBuffer&, logSpace, lut, metering, evOffset)` lives in the **anonymous namespace** of `src/raw_alchemy_capi.cpp:300` — file-local, not exportable. Its building blocks are all **public & header-exposed**: `applyGradingFused`, `LOG_SPACES` (`color_data.h`), `loadCubeLUT` (`lut_applier.h`), `computeAutoGain` (`metering.h`), `GradingParams`, `ImageBuffer`. So a binding shim can **reproduce the ~10-line param assembly** using only public API — no submodule edit.

## Decision (locked — 2026-09-19)

1. **No white-point residual comparison.** ProPhoto RGB is, by definition, D50. Our editing-fork output is D50, which aligns with RawAlchemyCpp's grading contract. No D65→D50 bridge is required for the rawalchemy path.
2. **License:** the fotlab project is already **GPL v3**. RawAlchemyCpp is AGPL-3.0, but linking it (static) into a GPL v3 binding crate is treated as acceptable — no separate `.so` isolation or process-boundary trick needed. (Earlier drafts cited `rawtherapee_fotlab` as the precedent; note that `rawtherapee_fotlab` is **dead/unused glue** — rawler does *not* actually integrate it, so it is *not* a template for this work. We connect rawler → `rawalchemy_fotlab` directly.)
3. **No patch, no upstream submit.** All bridge code lives under `app/src/binding/cxx/`; cross-folder `#include` of `external/RawAlchemyCpp/include` is allowed. The shim reproduces the public `runGradingOnly` logic and calls only public rawalchemy functions.
4. **Kotlin consumes RawAlchemyCpp's graded output as-is** — no inverse log/gamut transform is performed on our side. Display/export handling of the log-encoded result is Kotlin's concern.

## Recommendation — the bridge

### New crate `app/src/binding/cxx/rawalchemy_fotlab/` (fresh cxx bridge — NOT modeled on `rawtherapee_fotlab`, which is dead/unused glue)
- `Cargo.toml`: `crate-type = ["rlib","staticlib"]` (consumed as an rlib by `rawler_fotlab`; the staticlib is only for a standalone build). Deps: `cxx`; build-deps: `cxx-build`, `cmake`.
- `build.rs`: runs `cpp/CMakeLists.txt` (the grading subset) via the `cmake` crate, then `cxx_build::bridge` over `cpp/rawalchemy_shim.cc`. Links `librawalchemy_grading.a` + the C++ runtime (`-fopenmp`, `stdc++` on desktop / `c++` on Android). `RAWALCHEMY_SRC` env overrides the default `external/RawAlchemyCpp`.
- `cpp/CMakeLists.txt`: compiles ONLY `grading_fused.cpp` + `log_transform.cpp` + `lut_applier.cpp` + `metering.cpp` (all STL-only) into `librawalchemy_grading.a`. **No** decode / demosaic / NN sources, so LibRaw/libtiff/turbojpeg/libexif/ONNX are never pulled in. **No OpenMP either**: upstream only defines `RA_USE_OPENMP` after a successful `find_package(OpenMP)`, so without it every `#pragma omp` in these files is compiled out — passing `-fopenmp` would parallelise nothing while still making the final link demand a libgomp/libomp runtime. Grading therefore runs single-threaded; enabling it later means defining `RA_USE_OPENMP` *and* proving the runtime links for all four Android ABIs.
- `cpp/rawalchemy_shim.cc` + `src/lib.rs`: the cxx bridge. `grade` builds an `ImageBuffer` and assembles a `GradingParams` **that starts from upstream's own declared defaults and only overrides what the caller explicitly asked for** (see "Default ownership" below): resolves the log space by name from `LOG_SPACES` (empty = skip gamut+log), optionally loads a `.cube` LUT (empty = skip), optionally meters via `computeAutoGain` (empty mode = unmetered, base stays unity) times an optional raw `gain` multiplier, then calls `applyGradingFused` and returns the buffer as `Vec<f32>`.

### Public Rust surface (matches the implemented crate)
```rust
// rawalchemy_fotlab::GradeOverrides — every field None/"" means "the engine decides"
pub struct GradeOverrides {
    pub log_space: Option<String>,      // None = skip gamut + log encode
    pub lut_path: Option<String>,       // None = no LUT
    pub metering_mode: Option<String>,  // None = no auto metering (base stays unity)
    pub gain: Option<f32>,              // upstream's raw gain MULTIPLIER (not an EV)
    pub target_gray: Option<f32>,       // None = upstream default (0.18)
    pub enable_boost: Option<bool>,     // None = upstream default
    pub saturation: Option<f32>,        // None = upstream default
    pub contrast: Option<f32>,          // None = upstream default
    pub pivot: Option<f32>,             // None = upstream default
}

// rawalchemy_fotlab::grade — called directly from rawler_fotlab
pub fn grade(
    data: &[f32], w: u32, h: u32,
    overrides: &GradeOverrides,
) -> Result<Vec<f32>, String>
```
Returns the graded `w*h*3` float buffer (F-Gamut + F-Log by default). The error `String` is the C++ `std::exception::what()` (cxx converts a thrown `std::runtime_error` at the FFI boundary).

#### Default ownership — the glue pins nothing upstream owns
Because cxx has no `Option<f32>` / `Option<bool>`, "the caller did not set this" crosses the cxx boundary as an out-of-band sentinel:

| concept | cxx encoding | meaning |
| --- | --- | --- |
| unset float | quiet NaN | leave the upstream `GradingParams` default |
| unset boost | `i32` tri-state `-1` / `0` / `1` | `-1` unset (upstream default is `true`), `0` off, `1` on |
| skip stage | empty string | log / LUT / metering stage is skipped |

The shim constructs `GradingParams{}` (upstream defaults) and assigns a field only when the sentinel says it was provided, so **if upstream changes a default we follow it with no code change here**. Note that upstream's *own* file-decoding C API (`src/raw_alchemy_capi.cpp:199-204`) hardcodes `enableBoost=true, saturation=1.25, contrast=1.10, pivot=0.18` — that is upstream's policy, not ours, and we no longer restate it.

### C++ shim (`cpp/rawalchemy_shim.cc`, as implemented)
```cpp
#include "rust/cxx.h"
#include "rawalchemy_fotlab/src/lib.rs.h"   // generated by cxx-build into $OUT_DIR/cxxbridge
#include "grading_fused.h"        // applyGradingFused, GradingParams
#include "color_data.h"           // LOG_SPACES, LogSpaceInfo
#include "lut_applier.h"          // loadCubeLUT, LUT3D
#include "metering.h"             // computeAutoGain, isMeteringModeSupported

namespace {
constexpr int32_t kBoostUnset = -1;
inline bool is_unset(float v) { return std::isnan(v); }
}  // namespace

rust::Vec<float> grade(rust::Slice<const float> data,
                       uint32_t width, uint32_t height,
                       rust::Str log_space, rust::Str lut_path, rust::Str metering_mode,
                       float gain, float target_gray,
                       int32_t enable_boost,
                       float saturation, float contrast, float pivot) {
  using namespace rawalchemy;
  const size_t n = static_cast<size_t>(width) * height * 3u;
  if (data.size() != n) throw std::runtime_error("grade: input length != width*height*3");

  ImageBuffer buf(static_cast<int>(width), static_cast<int>(height), 3);
  std::copy(data.data(), data.data() + static_cast<std::ptrdiff_t>(n), buf.data.begin());

  GradingParams p;                       // upstream defaults; nothing else is pinned

  std::string ls(log_space.data(), log_space.size());
  if (!ls.empty()) {                     // "" == upstream's null logSpace
    auto it = LOG_SPACES.find(ls);
    if (it == LOG_SPACES.end()) throw std::runtime_error("grade: unknown log space '" + ls + "'");
    p.logSpaceInfo = &it->second;        // otherwise stays nullptr -> gamut+log skipped
  }

  LUT3D lut;
  std::string lp(lut_path.data(), lut_path.size());
  if (!lp.empty()) { lut = loadCubeLUT(lp); p.lut = &lut; }

  // Upstream's own shape: metered base × multiplier. Our deviation is only that
  // the base is metered when asked (upstream always meters, mode defaults to
  // "matrix") and the multiplier is the raw GradingParams::gain rather than a
  // 2^evOffset we invented.
  std::string mm(metering_mode.data(), metering_mode.size());
  const bool metered = !mm.empty();
  if (metered && !isMeteringModeSupported(mm)) throw std::runtime_error("grade: unsupported metering mode '" + mm + "'");
  if (metered || !is_unset(gain)) {      // both unset -> p.gain keeps the upstream default
    const float base_gain = metered
        ? computeAutoGain(buf, mm, is_unset(target_gray) ? 0.18f : target_gray)
        : 1.0f;                          // unity stands in for the missing side
    p.gain = base_gain * (is_unset(gain) ? 1.0f : gain);
  }

  if (enable_boost != kBoostUnset) p.enableBoost = (enable_boost != 0);
  if (!is_unset(saturation)) p.saturation = saturation;
  if (!is_unset(contrast))   p.contrast   = contrast;
  if (!is_unset(pivot))      p.pivot      = pivot;
  applyGradingFused(buf, p);

  rust::Vec<float> out; out.reserve(buf.data.size());
  for (float v : buf.data) out.push_back(v);
  return out;
}
```

### Cold symbols compiled into `librawalchemy_grading.a` but never called
The CMake subset compiles four translation units, but the grading path only uses `grading_fused.cpp` (+ `log_transform.cpp` via it). Two symbols are linked in yet unreachable today — recorded so a later feature knows they are already available:
- `applyLUT3D` / `applyLUT3DF16` (`lut_applier.cpp`) — `applyGradingFused` does its **own** inline tetrahedral interpolation (`grading_fused.cpp:109-150`), so the standalone applier is dead. Only `loadCubeLUT` from that file is used.
- `computeAutoGain` / `metering.cpp` — used only when `metering_mode` is non-empty; with the default `None` it is not on the path.

### Status — implemented (2026-09-19)
- `app/src/binding/cxx/rawalchemy_fotlab/` created: `Cargo.toml`, `build.rs`, `cpp/CMakeLists.txt`, `cpp/rawalchemy_shim.cc`, `src/lib.rs`.
- `rawler_fotlab` gains `feature = "rawalchemy"` (default **ON** — CI must compile the C++ path) + optional dep `rawalchemy_fotlab`, and two entry points gated on that feature:
  - `develop.rs::develop_and_grade(raw, params, grade_params: GradeParams)` — stateless, re-decodes then grades.
  - `loaded.rs::RawlerImageLoaded::develop_and_grade(params, grade_params: GradeParams)` — cached decode, grades in place.
  Both call `develop(ProPhotoD50)` then `rawalchemy_fotlab::grade(&dev.rgb, w, h, &overrides)`, returning the graded `Vec<f32>` to Kotlin via UniFFI. `GradeParams` is the UniFFI `Record` mirror of `GradeOverrides` (all fields `Option`, `#[uniffi(default = None)]`, so Kotlin can omit any of them).
- **Not yet compiled.** No local Rust/C++ toolchain; the `rawalchemy` feature is off by default so the existing rawler CI build is unaffected. A build with the feature on (needs the RawAlchemyCpp submodule + a C++ toolchain) is required to verify the cxx + CMake wiring.

### Orchestration — direct Rust handoff (no Kotlin hop)
`rawler_fotlab` takes `rawalchemy_fotlab` as a **Cargo path dependency** (compiled as an rlib into the *same* cdylib). The develop→grade handoff is an internal Rust call: `&RawlerImageDeveloped.rgb` is passed by reference straight into `rawalchemy_fotlab::grade` — **zero-copy, no JVM buffer**:
```
rawler_fotlab::develop_and_grade(bytes, params, grade_params) 
    -> Vec<f32>                                            // UniFFI, final result
        │ internal, Rust-only (invisible to UniFFI):
        │   dev    = develop(bytes, params, ProPhotoD50)     // RawlerImageDeveloped
        │   graded = rawalchemy_fotlab::grade(&dev.rgb, dev.width, dev.height, &overrides)
        ▼
Kotlin:  val r = rawler.developAndGrade(...)   // graded: FloatArray (F-Gamut + F-Log), consumed as-is
```
- **Single `.so`.** C++ `rawalchemy` static lib links into `rawler_fotlab`'s cdylib (its `build.rs` link directive applies transitively); Kotlin loads one library. UniFFI only wraps the *outer* Kotlin API, so the internal Rust→Rust→cxx call is transparent to it.
- **No ~600 MB `FloatArray` copy.** The 50 MP buffer stays in native memory; Kotlin receives the graded `Vec<f32>` exactly once, mapped by UniFFI — the earlier `RawlerImageDeveloped` bounce-through-JVM is gone.
- The two crates stay **logically decoupled** (`rawalchemy_fotlab` has no knowledge of rawler types), but they are *composed at the Rust layer*, not via Kotlin. (If a "grading-only" feature later needs rawalchemy without rawler, also expose `rawalchemy_fotlab` independently to UniFFI — but the develop→grade path itself never needs Kotlin.)
- Cleanup option: since the handoff is now Rust-internal, `RawlerImageDeveloped` can drop its `#[uniffi::Record]` attribute and stay a plain internal struct; left as-is unless Kotlin needs to inspect the linear edit.

### Exposure: grading `gain` vs. develop `exposure_ev` (two different controls)
Upstream's `runGradingOnly` does `gain = computeAutoGain(img, mode) * 2^evOffset` — it meters **by default** (its `mode` falls back to `"matrix"`) and its EV is the only exposure in that pipeline, because upstream decodes and grades in one call.

Our pipeline splits that, so the two exposures are genuinely different stages and must not be wired to the same UI value:

| | front end | where it acts | how |
| --- | --- | --- | --- |
| develop exposure | `DevelopParams.exposure_ev` | rawler, on the **single-channel mosaic before demosaic** (`develop.rs:215-222`) | `2^ev` linear scale |
| grading gain | `GradeParams.gain` | `applyGradingFused` stage 1, on **linear ProPhoto RGB** already developed | raw multiplier on `GradingParams::gain` |

The bridge therefore exposes upstream's **raw `gain` multiplier** rather than an EV of our own invention: an EV wrapper would look like the develop exposure dialog (same unit, same `2^ev`) while meaning something else, which invites wiring the two together. `gain = None` and no metering leaves `p.gain` at the upstream default, so the developed exposure stays exactly as rendered. Metering is the optional base this multiplier scales — `None` (the default) means *no* metering at all, because rawler has already applied as-shot exposure and white balance; `Some("matrix")` etc. reproduces upstream's behaviour, with `target_gray` (upstream default 0.18) also overridable, and the mode validated via `isMeteringModeSupported`.

### Negative / >1 handling (recorded)
Unclamped ProPhoto from rawler carries small negatives from the inverse camera matrix; `applyGradingFused` clamps them to ~0 at the log stage (`max(r,1e-6)`). Values >1 survive into the LUT, which clamps to its `[domainMin,domainMax]` box. Both are acceptable for a Studio proxy; noted so a future linear-domain HDR step knows the linear negatives are gone after grading.

## Impact / Conflict

- Eliminates the highest-risk open question from `FOTLAB-RAWLER-000005`'s follow-up (white-point residual). Resolves **FOTLAB-PIPELN-000001 Q2** for the rawalchemy path: the ProPhoto-D50 develop branch *is* `FotDev`'s process-stage input; no separate D65→D50 bridge is needed.
- Confirms the correct architecture is a **direct Rust→cxx handoff inside one cdylib** — no Kotlin hop, no JVM-buffered copy of the `RawlerImageDeveloped` — consistent with the zero-copy philosophy already in `rawler_fotlab`.
- No change to `external/RawAlchemyCpp` (submodule stays pristine), satisfying review principle #5 (upstream is a fixed constraint).

## Change History

- 2026-09-19 — Created as `Approved`. Locked the four design decisions (no white-point test; GPL v3 so no license isolation; no patch — bridge entirely in `app/src/binding/cxx/`; Kotlin consumes graded output as-is) and recorded the `rawalchemy_fotlab` crate design + shim sketch.
- 2026-09-19 — Corrected the orchestration topology: the develop→grade handoff is a **direct Rust→cxx call inside one cdylib** (`rawler_fotlab` path-depends on `rawalchemy_fotlab`; `&dev.rgb` passed zero-copy; single `.so`; no Kotlin hop, no JVM `FloatArray` copy). Kotlin only consumes the final graded `Vec<f32>` via UniFFI.
- 2026-09-19 — **Implemented.** Created `app/src/binding/cxx/rawalchemy_fotlab/` (Cargo.toml + build.rs + cpp/CMakeLists.txt + cpp/rawalchemy_shim.cc + src/lib.rs) and wired `rawler_fotlab` via `feature = "rawalchemy"` (default off) + two gated entry points (`develop_and_grade` free fn and `RawlerImageLoaded::develop_and_grade`). Not yet compiled (no local toolchain; `rawalchemy` feature is off by default so existing CI is unaffected). Also corrected the record: `rawtherapee_fotlab` is **dead/unused glue** and is explicitly *not* a template for this work.
- 2026-09-19 — Feature flipped to **default ON** and the Android build fixed: `build.rs` now passes the NDK cmake toolchain + `ANDROID_ABI` when cross-compiling (a host-arch static lib made `cargo ndk` fail to link), `cpp/CMakeLists.txt` resolves OpenMP via `find_package` with a `-fopenmp` fallback, and the workflow installs `cmake`. CI then compiles the C++ path rather than silently skipping it.
- 2026-09-19 — **Default ownership corrected (glue is now a thin pass-through).** The shim no longer restates `enableBoost/saturation/contrast/pivot` (upstream's `GradingParams` defaults already hold those values, and upstream's own C API hardcodes the same ones), and does not force `logSpaceInfo` non-null. `grade` now takes a `rawalchemy_fotlab::GradeOverrides` whose every field is optional — `None`/`""` means "the engine decides" (skip the stage, or keep the upstream default) — carried over cxx as NaN / tri-state-`i32` / empty-string sentinels. Added: optional `metering_mode` (+ `target_gray`) so upstream's `computeAutoGain` is reachable at all, and `None` log now **skips** gamut+log instead of throwing. `GradeParams` (UniFFI `Record`, all fields `Option` with `uniffi(default = None)`) exposes the same surface to Kotlin, so the front-end reaches upstream's full parameter set; both `develop_and_grade` entry points now take it in place of the old `log_space`/`lut_path`/`ev_offset` triple. **Still not compiled** — needs a CI run.
- 2026-09-19 — **Grading `gain` re-shaped to upstream's own field.** The pass-through had introduced `GradeParams.ev_offset` (stops, applied as `2^ev_offset`), which was a re-parameterisation of upstream that we invented: `GradingParams::gain` is a plain linear multiplier, and an EV wrapper reads like the develop exposure dialog (same unit, same `2^ev`) while acting on a different stage — it invited wiring it to `DevelopParams.exposure_ev`. Replaced by `gain: Option<f32>`, upstream's raw multiplier, combined with the optional metered base as `metered_base * gain` — exactly upstream's `computeAutoGain(...) * 2^evOffset` shape minus our EV. Metering retained (optional). Both unset now leaves `p.gain` at the upstream default, i.e. the developed exposure is untouched; new "Exposure: grading `gain` vs. develop `exposure_ev`" section records the distinction and the fact that there is no Kotlin caller of `develop_and_grade` yet.
