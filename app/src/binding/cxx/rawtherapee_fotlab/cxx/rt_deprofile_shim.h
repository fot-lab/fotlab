#pragma once

// ===========================================================================
// rawtherapee_fotlab — C ABI surface for DCP/LCP deprofile.
//
// Mirrors rt_demosaic_shim.h: this header is the single declaration the cxx
// bridge needs (`include!("rt_deprofile_shim.h")`). The implementation lives in
// rt_deprofile_shim.cc and calls RawTherapee's DCPProfile / LCPProfile /
// LCPMapper (all in the vendored submodule worktree; see README.md for the
// required out-of-band hooks + inline getters).
//
// Convention (same as rt_demosaic_shim.h):
//   * `err`  — caller-owned message buffer; on failure a NUL-terminated
//              message is written here.
//   * returns — 0 on success, <0 on error.
//
// NOTE on licensing: RawTherapee is GPL v3. Linking this shim (and
// librtengine) into the rawtherapee_fotlab binary makes the combined work a
// GPL v3 derivative.
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

// Apply LCP vignette and/or distortion IN CFA mosaic space (colour-independent).
// `pixels` is a single-channel CFA mosaic, row-major, length w*h, modified
// in place. CA is intentionally skipped (useCADistP = false).
int32_t rt_apply_lcp_cfa(const std::string& path,
                         float focal_length, float focal_length_35mm, float focus_dist,
                         float aperture, bool vignette, bool distortion,
                         int32_t raw_rotation_deg, int32_t w, int32_t h,
                         rust::Slice<float> pixels, rust::Slice<uint8_t> err);
