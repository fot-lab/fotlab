#pragma once
/**
 * @file rawalchemy_api.h
 * @brief The one C++ symbol this crate exposes to Rust, declared for the cxx bridge.
 *
 * Why a hand-written header instead of the `.rs.h` that cxx generates: cxx only
 * generates that header from an `extern "Rust"` block or from shared structs. A
 * bridge made purely of `extern "C++"` declarations — ours — produces no header
 * declaring anything, so the function has to be declared here and named by
 * `include!("cpp/rawalchemy_api.h")` in `src/lib.rs`. cxx's own reference puts it
 * plainly: `include!` names "one or more headers with the matching C++
 * declarations ... it gets #include'd and used in static assertions to ensure our
 * picture of the FFI boundary is accurate".
 *
 * The signatures below MUST stay identical to the bridge, and to the definition in
 * `rawalchemy_shim.cc`. cxx emits static assertions against this header, so a
 * mismatch is a C++ compile error rather than a runtime surprise.
 *
 * cxx's type mapping for the declaration:
 *   Rust `&[f32]`  -> `rust::Slice<const float>`
 *   Rust `&str`    -> `rust::Str`
 *   Rust `u32`/`i32`/`f32` -> `uint32_t`/`int32_t`/`float`
 *   Rust `Vec<f32>` <- `rust::Vec<float>`
 * A bridge `Result<T>` return does NOT change the C++ signature: the C++ side
 * returns `T` and throws, and cxx wraps the call in the try/catch.
 */
#include "rust/cxx.h"

#include <cstdint>

/// Run the upstream fused grading pipeline over a linear ProPhoto-D50 RGB buffer.
///
/// `data` is row-major interleaved `width*height*3` float32 — the same layout as
/// `RawlerImageDeveloped.rgb`. Returns the graded buffer in that same layout.
/// Throws `std::runtime_error` on bad input, which the bridge (declared
/// `Result<..>`) turns into `Err(cxx::Exception)` on the Rust side.
///
/// `log_space` is accepted in either vocabulary: the display name that
/// [`log_spaces`] hands the UI (`"FUJIFILM F-Log2 C"`) or the upstream canonical
/// key (`"F-Log2C"`). The shim resolves the display name back to the canonical one
/// before touching upstream's `LOG_SPACES`, so the submodule stays unmodified; the
/// canonical spelling is still accepted so values that predate the aliasing keep
/// working. Empty = skip the gamut and log stages.
rust::Vec<float> grade(rust::Slice<const float> data,
                       uint32_t width,
                       uint32_t height,
                       rust::Str log_space,
                       rust::Str lut_path,
                       rust::Str metering_mode,
                       float gain,
                       float target_gray,
                       int32_t enable_boost,
                       float saturation,
                       float contrast,
                       float pivot);

/// Every log curve the grader accepts, as DISPLAY names (`"FUJIFILM F-Log2 C"`,
/// `"Sony S-Log3"`, `"ARRI LogC4"`, …).
///
/// Self-contained: the list is the display column of the shim's own alias table
/// and does NOT read upstream's `LOG_SPACES` map, so the enumeration can never be
/// affected by how the parallel grading engine (`applyGradingFused`, built under
/// `RA_USE_OPENMP`) is linked. The same table maps each display name back to the
/// canonical key `grade` needs, so the two directions cannot drift apart.
///
/// Names here are for the UI only. Upstream's `LOG_SPACES` keys live in
/// external/RawAlchemyCpp/include/color_data.h and stay untouched; keep the
/// table's canonical column in sync with them (14 curves).
rust::Vec<rust::String> log_spaces();
