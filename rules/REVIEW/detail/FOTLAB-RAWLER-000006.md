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
- `cpp/CMakeLists.txt`: compiles ONLY `grading_fused.cpp` + `log_transform.cpp` + `lut_applier.cpp` + `metering.cpp` (all STL-only, `#include <omp.h>` + header-only `half_buffer.h`) into `librawalchemy_grading.a`. **No** decode / demosaic / NN sources, so LibRaw/libtiff/turbojpeg/libexif/ONNX are never pulled in. `-fopenmp` for the `#pragma omp` loops.
- `cpp/rawalchemy_shim.cc` + `src/lib.rs`: the cxx bridge. `grade` assembles an `ImageBuffer`, resolves the log space by name from `LOG_SPACES`, optionally loads a `.cube` LUT, sets `gain = 2^ev_offset` (skips `computeAutoGain`), then calls `applyGradingFused` and returns the buffer as `Vec<f32>`.

### Public Rust surface (matches the implemented crate)
```rust
// rawalchemy_fotlab::grade — called directly from rawler_fotlab
pub fn grade(
    data: &[f32], w: u32, h: u32,
    log_space: &str,      // e.g. "F-Log"; looked up in LOG_SPACES
    lut_path: &str,       // "" => no LUT
    ev_offset: f32,       // rawler already applied as-shot exposure
) -> Result<Vec<f32>, String>
```
Returns the graded `w*h*3` float buffer (F-Gamut + F-Log by default). The error `String` is the C++ `std::exception::what()` (cxx converts a thrown `std::runtime_error` at the FFI boundary).

### C++ shim (`cpp/rawalchemy_shim.cc`, as implemented)
```cpp
#include "rust/cxx.h"
#include "cxxbridge.h"            // generated by cxx-build
#include "grading_fused.h"        // applyGradingFused, GradingParams
#include "color_data.h"           // LOG_SPACES, LogSpaceInfo
#include "lut_applier.h"          // loadCubeLUT, LUT3D

rust::Vec<float> grade(rust::Slice<const float> data,
                       uint32_t width, uint32_t height,
                       rust::Str log_space, rust::Str lut_path,
                       float ev_offset) {
  using namespace rawalchemy;
  const size_t n = static_cast<size_t>(width) * height * 3u;
  ImageBuffer buf(static_cast<int>(width), static_cast<int>(height), 3);
  std::copy(data.data(), data.data() + n, buf.data.begin());

  auto it = LOG_SPACES.find(std::string(log_space.data(), log_space.size()));
  if (it == LOG_SPACES.end()) throw std::runtime_error("unknown log space");
  const LogSpaceInfo& info = it->second;

  LUT3D lut;
  std::string lp(lut_path.data(), lut_path.size());
  if (!lp.empty()) lut = loadCubeLUT(lp);

  GradingParams p;
  p.gain = std::pow(2.0f, ev_offset);   // rawler exposure is authoritative
  p.enableBoost = true;
  p.saturation  = 1.25f; p.contrast = 1.10f; p.pivot = 0.18f;
  p.logSpaceInfo = &info;
  p.lut = lut.empty() ? nullptr : &lut;
  applyGradingFused(buf, p);

  rust::Vec<float> out; out.reserve(buf.data.size());
  for (float v : buf.data) out.push_back(v);
  return out;
}
```

### Status — implemented (2026-09-19)
- `app/src/binding/cxx/rawalchemy_fotlab/` created: `Cargo.toml`, `build.rs`, `cpp/CMakeLists.txt`, `cpp/rawalchemy_shim.cc`, `src/lib.rs`.
- `rawler_fotlab` gains `feature = "rawalchemy"` (default **off**) + optional dep `rawalchemy_fotlab`, and two entry points gated on that feature:
  - `develop.rs::develop_and_grade(raw, params, log_space, lut_path, ev_offset)` — stateless, re-decodes then grades.
  - `loaded.rs::RawlerImageLoaded::develop_and_grade(...)` — cached decode, grades in place.
  Both call `rawler::develop(ProPhotoD50)` then `rawalchemy_fotlab::grade(&dev.rgb, ...)`, returning the graded `Vec<f32>` to Kotlin via UniFFI.
- **Not yet compiled.** No local Rust/C++ toolchain; the `rawalchemy` feature is off by default so the existing rawler CI build is unaffected. A build with the feature on (needs the RawAlchemyCpp submodule + a C++ toolchain) is required to verify the cxx + CMake wiring.

### Orchestration — direct Rust handoff (no Kotlin hop)
`rawler_fotlab` takes `rawalchemy_fotlab` as a **Cargo path dependency** (compiled as an rlib into the *same* cdylib). The develop→grade handoff is an internal Rust call: `&RawlerImageDeveloped.rgb` is passed by reference straight into `rawalchemy_fotlab::grade` — **zero-copy, no JVM buffer**:
```
rawler_fotlab::develop_and_grade(bytes, params, log_space="F-Log", ...) 
    -> RawlerImageGraded { width, height, rgb: Vec<f32> }   // UniFFI Record, final result
        │ internal, Rust-only (invisible to UniFFI):
        │   dev    = develop(bytes, params, ProPhotoD50)     // RawlerImageDeveloped
        │   graded = rawalchemy_fotlab::grade(&dev.rgb, dev.width, dev.height, "F-Log", "", 0.0f)
        ▼
Kotlin:  val r = rawler.developAndGrade(...)   // graded: FloatArray (F-Gamut + F-Log), consumed as-is
```
- **Single `.so`.** C++ `rawalchemy` static lib links into `rawler_fotlab`'s cdylib (its `build.rs` link directive applies transitively); Kotlin loads one library. UniFFI only wraps the *outer* Kotlin API, so the internal Rust→Rust→cxx call is transparent to it.
- **No ~600 MB `FloatArray` copy.** The 50 MP buffer stays in native memory; Kotlin receives the graded `Vec<f32>` exactly once, mapped by UniFFI — the earlier `RawlerImageDeveloped` bounce-through-JVM is gone.
- The two crates stay **logically decoupled** (`rawalchemy_fotlab` has no knowledge of rawler types), but they are *composed at the Rust layer*, not via Kotlin. (If a "grading-only" feature later needs rawalchemy without rawler, also expose `rawalchemy_fotlab` independently to UniFFI — but the develop→grade path itself never needs Kotlin.)
- Cleanup option: since the handoff is now Rust-internal, `RawlerImageDeveloped` can drop its `#[uniffi::Record]` attribute and stay a plain internal struct; left as-is unless Kotlin needs to inspect the linear edit.

### Exposure metering note
RawAlchemyCpp's `runGradingOnly` does `gain = computeAutoGain(img) * 2^evOffset`. Because rawler has already applied as-shot exposure + white balance in `develop`, the bridge **defaults `gain = 2^ev_offset` and skips `computeAutoGain`** (no double exposure). Expose an explicit flag only if a re-metering mode is later wanted.

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
