#pragma once

// ===========================================================================
// rawtherapee_fotlab — C ABI surface for DCP/LCP deprofile (decode only).
//
// This header is the single declaration the cxx bridge needs
// (`include!("rt_deprofile_shim.h")`). The implementation lives in
// rt_deprofile_shim.cc and calls RawTherapee's vendored DCPProfile /
// LCPProfile (constructors + getters + calcParams + prepareParams only — the
// apply machinery is NOT vendored; apply is re-implemented in Rust in
// src/lcp.rs). See README.md for the licensing note: we vendor ONLY the
// GPL-isolated decoders, no librtengine link, so this crate is not a GPL
// derivative of the RT engine.
//
// Convention (same as rawalchemy_api.h):
//   * `err`   — caller-owned message buffer; on failure a NUL-terminated
//               message is written here.
//   * returns — 0 on success, <0 on error.
//
// cxx's type mapping used below:
//   Rust `&[u8]`   -> `rust::Slice<uint8_t>`
//   Rust `&str`    -> `const std::string&` (path)
//   Rust `Vec<T>`  <- `rust::Vec<T>`  (out-param, filled with push_back)
//   Rust `f32/f64/i32/i16/bool` -> `float/double/int32_t/int16_t/bool`
// The signatures MUST stay identical to src/lib.rs (cxx emits static
// assertions against this header).
// ===========================================================================

#include <rust/cxx.h>
#include <cstdint>
#include <string>

// Parse a DCP: fill the four colour matrices (row-major, 9 doubles each), the
// has-flags, illuminants/temperatures/light-sources, and the baseline offset.
int32_t rt_parse_dcp(const std::string& path,
                     rust::Vec<double>& cm1, rust::Vec<double>& cm2,
                     rust::Vec<double>& fm1, rust::Vec<double>& fm2,
                     bool& has_cm1, bool& has_cm2, bool& has_fm1, bool& has_fm2,
                     bool& will_interp,
                     double& temp1, double& temp2, double& baseline,
                     int16_t& light1, int16_t& light2,
                     bool& has_tone, bool& has_look, bool& has_huesat, bool& has_baseline,
                     rust::Slice<uint8_t> err);

// Parse an LCP: fill profile metadata. Strings are returned as UTF-8 byte
// vectors (caller converts).
int32_t rt_parse_lcp(const std::string& path,
                     rust::Vec<uint8_t>& profile_name, rust::Vec<uint8_t>& camera,
                     rust::Vec<uint8_t>& lens, bool& is_raw, bool& is_fisheye,
                     float& sensor_format_factor, int32_t& pers_model_count,
                     rust::Slice<uint8_t> err);

// Decode + interpolate the LCP correction model for the given focal / geometry.
// Fills `model` with 13 floats: x0, y0, fx, fy, vign0..3, dist0..4 (rfx/rfy are
// 1/fx, 1/fy on the Rust side), and the two model flags. The Rust apply step
// (src/lcp.rs) consumes these to vignette / warp the CFA mosaic.
int32_t rt_compute_lcp_model(const std::string& path,
                             float focal_length, float focal_length_35mm,
                             float focus_dist, float aperture,
                             int32_t raw_rotation_deg, int32_t w, int32_t h,
                             rust::Vec<float>& model, bool& is_fisheye, bool& swap_xy,
                             rust::Slice<uint8_t> err);
