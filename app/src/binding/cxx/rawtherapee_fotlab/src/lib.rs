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

// ---------------------------------------------------------------------------
// Deprofile bridge: DCP/LCP parse + LCP CFA-space apply.
//
// Same discipline as the demosaic bridge above: i32 rc + err buffer, panic-guarded
// on the Rust side (rt_demosaic_safe). The C++ shim (rt_deprofile_shim.cc) does
// the try/catch so a C++ exception never unwinds across the FFI frame.
// ---------------------------------------------------------------------------
#[cxx::bridge]
mod ffi_deprofile {
    extern "C++" {
        include!("rt_deprofile_shim.h");

        fn rt_parse_dcp(
            path: &CxxString,
            cm1: &mut Vec<f64>,
            cm2: &mut Vec<f64>,
            fm1: &mut Vec<f64>,
            fm2: &mut Vec<f64>,
            has_cm1: &mut bool,
            has_cm2: &mut bool,
            has_fm1: &mut bool,
            has_fm2: &mut bool,
            will_interp: &mut bool,
            temp1: &mut f64,
            temp2: &mut f64,
            baseline: &mut f64,
            light1: &mut i16,
            light2: &mut i16,
            has_tone: &mut bool,
            has_look: &mut bool,
            has_huesat: &mut bool,
            has_baseline: &mut bool,
            err: &mut [u8],
        ) -> i32;

        fn rt_parse_lcp(
            path: &CxxString,
            profile_name: &mut Vec<u8>,
            camera: &mut Vec<u8>,
            lens: &mut Vec<u8>,
            is_raw: &mut bool,
            is_fisheye: &mut bool,
            sensor_format_factor: &mut f32,
            pers_model_count: &mut i32,
            err: &mut [u8],
        ) -> i32;

        fn rt_apply_lcp_cfa(
            path: &CxxString,
            focal_length: f32,
            focal_length_35mm: f32,
            focus_dist: f32,
            aperture: f32,
            vignette: bool,
            distortion: bool,
            raw_rotation_deg: i32,
            w: i32,
            h: i32,
            pixels: &mut [f32],
            err: &mut [u8],
        ) -> i32;
    }
}

mod dcp;
mod lcp;
mod deprofile_error;

pub use deprofile_error::DeprofileError;
pub use dcp::{parse_dcp, DcpParams};
pub use lcp::{apply_lcp_cfa, parse_lcp, LcpParams};
