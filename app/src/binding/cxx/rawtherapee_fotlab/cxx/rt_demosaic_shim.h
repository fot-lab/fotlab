#pragma once

// ===========================================================================
// rawtherapee_fotlab — C ABI surface between the Rust crate and RawTherapee's
// C++ demosaic algorithms.
//
// This header is the single declaration the cxx bridge needs. It is referenced
// from the Rust side via `#[cxx::bridge] extern "C++" { include!("rt_demosaic_shim.h"); ... }`
// so that cxx's generated C++ header pulls in this exact signature and both sides
// agree on the ABI. The implementation lives in `rt_demosaic_shim.cc`.
//
// Data contract (see rawtherapee_fotlab/src/demosaic.rs for the full story):
//   * `cfa`      — caller-owned single-channel CFA mosaic, row-major, length w*h.
//                  MUST be linear, black/white scaled into 0..1, NOT white-balanced
//                  (this matches the state our develop pipeline reaches *before*
//                  demosaic). Orientation must match `filters` below.
//   * `filters`  — dcraw 4x4 Bayer bitmask for Bayer; for X-Trans pass 9
//                  (RawImage::isXtrans() returns `filters == 9`) AND supply `xtrans`.
//   * `is_xtrans`— true => treat `cfa` as a Fuji X-Trans mosaic; `xtrans` (36 bytes,
//                  row-major 6x6) is read.
//   * `out_rgb`  — caller-owned interleaved linear RGB buffer, length w*h*3, written.
//   * `err`      — caller-owned message buffer (size `err.size()`); on failure a
//                  NUL-terminated message is written here.
//   * returns    — 0 on success, <0 on error.
//
// NOTE on licensing: RawTherapee is GPL v3. Linking this shim (and librtengine)
// into the rawtherapee_fotlab binary makes the combined work a GPL v3 derivative.
// ===========================================================================

#include <rust/cxx.h>
#include <cstdint>

// RawTherapee's demosaic output is linear RGB, still in camera/CFA space (no WB).
// `method` codes (must stay in sync with RtDemosaicAlgorithm in src/demosaic.rs):
//   0 AMAZE       1 RCD        2 VNG4      3 LMMSE     4 IGV      (Bayer)
//   5 XTRANS_1PASS 6 XTRANS_3PASS 7 XTRANS_FAST                 (X-Trans)
int32_t rt_demosaic(int32_t method,
                    rust::Slice<const float> cfa,
                    int32_t w,
                    int32_t h,
                    uint32_t filters,
                    bool is_xtrans,
                    rust::Slice<const uint8_t> xtrans,
                    rust::Slice<float> out_rgb,
                    rust::Slice<uint8_t> err);
