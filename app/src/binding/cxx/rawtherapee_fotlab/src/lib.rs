//! rawtherapee_fotlab — FotLab's binding over RawTherapee's C++ demosaic algorithms.
//!
//! # What this crate is
//!
//! A standalone `cdylib` (mirroring `rawler_fotlab`) that exposes ONE capability
//! to Kotlin/the develop pipeline: run a **selected RawTherapee demosaic algorithm**
//! over a caller-supplied CFA mosaic — no file decode, no white balance, no colour
//! management. The output is **linear RGB, still in camera/CFA space**, which is
//! exactly the state our develop pipeline expects *before* it applies white balance
//! and the cam→ProPhoto(D50) transform (tying back to the colour-pipeline research:
//! dnglab's `develop` lands on sRGB D65, rawalchemy wants ProPhoto D50; RawTherapee's
//! demosaic output is the neutral linear-RGB hub we feed downstream).
//!
//! # Why RawTherapee at all
//!
//! RawTherapee ships notably stronger demosaic algorithms than rawler/dnglab's
//! bilinear/PPG set — AMAZE, RCD, LMMSE, IGV, and a high-quality 3-pass X-Trans
//! interpolator. Our `develop.rs` already does the pre-demosaic work (decode,
//! black/white scaling, exposure); this crate lets us bolt RT's demosaic onto that
//! existing pipeline instead of re-implementing it.
//!
//! # Architecture (the FFI chain)
//!
//! ```text
//! Kotlin / develop.rs (has CFA, pre-demosaic)
//!    │ cxx call
//!    ▼
//! rawtherapee_fotlab::demosaic::demosaic_cfa   (this crate, Rust; panic-safe)
//!    │ cxx bridge
//!    ▼
//! rt_demosaic_shim.cc  (extern "C++", cxx ABI)   — cxx/rt_demosaic_shim.cc
//!    │ constructs RawImageSource + RawImage, calls:
//!    ▼
//! RawImageSource::demosaic_external            — hook in external/RawTherapee worktree
//!    │ (applied manually; this repo ships NO patch — see README.md)
//!    ▼
//! rtengine (static lib) — amaze/rcd/vng4/lmmse/igv/xtrans algorithms
//!    ▼
//! linear RGB (w*h*3) back to Rust → LinearImage
//! ```
//!
//! # Licensing
//!
//! RawTherapee is **GPL v3**. Linking `librtengine` (and this shim) into the
//! `rawtherapee_fotlab` binary makes the combined work a GPL v3 derivative. If the
//! wider fotlab distribution must avoid GPL propagation, move the shim into a
//! separate process and call it over files/pipes (the GPL does not extend across a
//! process boundary) instead of linking.

use std::panic::{self, AssertUnwindSafe};

mod demosaic;
mod error;

pub use demosaic::{demosaic_cfa, CfaPattern, LinearImage, RtDemosaicAlgorithm};
pub use error::RtDemosaicError;

// ---------------------------------------------------------------------------
// cxx bridge: the single C++ function we call. `include!` pulls in the exact
// declaration from cxx/rt_demosaic_shim.h so cxx's generated C++ header and this
// Rust declaration agree on the ABI.
// ---------------------------------------------------------------------------
#[cxx::bridge]
mod ffi {
    extern "C++" {
        include!("rt_demosaic_shim.h");

        /// Run one RT demosaic algorithm. Returns 0 on success, <0 on error
        /// (message written into `err`).
        fn rt_demosaic(
            method: i32,
            cfa: &[f32],
            w: i32,
            h: i32,
            filters: u32,
            is_xtrans: bool,
            xtrans: &[u8],
            out_rgb: &mut [f32],
            err: &mut [u8],
        ) -> i32;
    }
}

/// Raw C++ call, wrapped in a panic boundary.
///
/// RawTherapee's demosaic code may `throw` or assert on inputs it dislikes; a C++
/// exception unwinding across the `extern "C"` cxx frame is UB and aborts the
/// process. We catch C++ exceptions inside the shim (rt_demosaic_shim.cc) by
/// returning an error code, and additionally guard the whole Rust call with
/// `catch_unwind` so a Rust-side panic also degrades to `Err` instead of SIGABRT.
/// This mirrors the crash-hardening pattern in rawler_fotlab (FOTLAB-CRASH-000001).
fn rt_demosaic_safe(
    method: i32,
    cfa: &[f32],
    w: i32,
    h: i32,
    filters: u32,
    is_xtrans: bool,
    xtrans: &[u8],
    out_rgb: &mut [f32],
) -> Result<(), RtDemosaicError> {
    let mut errbuf = [0u8; 256];
    let rc = panic::catch_unwind(AssertUnwindSafe(|| {
        ffi::rt_demosaic(method, cfa, w, h, filters, is_xtrans, xtrans, out_rgb, &mut errbuf)
    }))
    .unwrap_or(-99); // panic across FFI => treat as failure

    if rc < 0 {
        let msg = std::str::from_utf8(&errbuf)
            .map(|s| s.trim_end_matches('\0').to_string())
            .unwrap_or_else(|_| format!("rt_demosaic returned {rc}"));
        return Err(RtDemosaicError::Demosaic(msg));
    }
    Ok(())
}
