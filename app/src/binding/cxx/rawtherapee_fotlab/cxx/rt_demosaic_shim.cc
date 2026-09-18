// ===========================================================================
// rawtherapee_fotlab — C++ shim implementing `rt_demosaic`.
//
// Thin C++ adapter between the cxx-generated Rust ABI and RawTherapee's
// RawImageSource::demosaic_external (the public hook that must be present in the
// vendored RawTherapee submodule worktree — applied manually, no patch shipped in
// this repo; see README.md).
//
// Everything interesting (CFA wiring, algorithm dispatch, output interleave) is
// in RawImageSource::demosaic_external; this file only:
//   1. validates the cxx slices,
//   2. builds a placeholder RawImage carrying the caller's CFA pattern,
//   3. calls the hook,
//   4. propagates any error string back to Rust.
//
// Why a placeholder RawImage? RawTherapee's demosaic algorithms read the CFA
// colour at (row,col) through `FC()` -> `ri->FC()` / `ri->XTRANSFC()` — i.e. the
// pattern lives on the RawImage (`ri`), never on RawImageSource. RawImage's ctor
// only stores the filename and does NOT open the file (rawimage.cc), so a "" name
// is safe; we then push the pattern in via set_filters() / set_xtrans().
// ===========================================================================

#include "rt_demosaic_shim.h"

#include <cstdio>
#include <cstring>

// RawTherapee engine headers. The build (build.rs) must add rtengine's include
// directory (plus the generated `procparams` headers) to the include path.
#include "rawimagesource.h"
#include "rawimage.h"

int32_t rt_demosaic(int32_t method,
                    rust::Slice<const float> cfa,
                    int32_t w,
                    int32_t h,
                    uint32_t filters,
                    bool is_xtrans,
                    rust::Slice<const uint8_t> xtrans,
                    rust::Slice<float> out_rgb,
                    rust::Slice<uint8_t> err)
{
    // Local error buffer; copied into the Rust-provided `err` slice on failure.
    char ebuf[256];
    ebuf[0] = '\0';

    if (w <= 0 || h <= 0) {
        snprintf(ebuf, sizeof(ebuf), "rt_demosaic: bad dimensions %dx%d", w, h);
        snprintf(reinterpret_cast<char*>(err.data()), err.size(), "%s", ebuf);
        return -1;
    }
    if (cfa.size() < static_cast<size_t>(w) * h) {
        snprintf(ebuf, sizeof(ebuf), "rt_demosaic: cfa too small (%zu < %zu)",
                 cfa.size(), static_cast<size_t>(w) * h);
        snprintf(reinterpret_cast<char*>(err.data()), err.size(), "%s", ebuf);
        return -1;
    }
    if (out_rgb.size() < static_cast<size_t>(w) * h * 3) {
        snprintf(ebuf, sizeof(ebuf), "rt_demosaic: out_rgb too small (%zu < %zu)",
                 out_rgb.size(), static_cast<size_t>(w) * h * 3);
        snprintf(reinterpret_cast<char*>(err.data()), err.size(), "%s", ebuf);
        return -1;
    }
    if (is_xtrans && xtrans.size() < 36) {
        snprintf(ebuf, sizeof(ebuf), "rt_demosaic: xtrans too small (%zu < 36)", xtrans.size());
        snprintf(reinterpret_cast<char*>(err.data()), err.size(), "%s", ebuf);
        return -1;
    }

    // Build the placeholder RawImage carrying the caller's CFA pattern.
    RawImageSource rs;
    RawImage ri("");
    ri.set_filters(filters);
    if (is_xtrans) {
        int xt[6][6];
        const uint8_t* p = xtrans.data();
        for (int i = 0; i < 6; ++i) {
            for (int j = 0; j < 6; ++j) {
                xt[i][j] = static_cast<int>(p[i * 6 + j]);
            }
        }
        ri.set_xtrans(xt);
    }

    int rc = rs.demosaic_external(method,
                                  cfa.data(),
                                  w,
                                  h,
                                  &ri,
                                  out_rgb.data(),
                                  ebuf,
                                  static_cast<int>(sizeof(ebuf)));

    if (rc < 0) {
        snprintf(reinterpret_cast<char*>(err.data()), err.size(), "%s", ebuf);
    }
    return rc;
}
