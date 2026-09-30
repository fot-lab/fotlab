//! rawtherapee_fotlab — vendored DCP/LCP profile **parsers** (extracted from
//! RawTherapee) plus a Rust re-implementation of the deprofile **apply** step.
//!
//! # What this crate is
//!
//! A standalone `cdylib` (mirroring `rawler_fotlab`) that the develop pipeline
//! calls to **read** Adobe DCP (camera colour) and LCP (lens correction)
//! profiles. The heavy binary/XML *decode* is vendored from RawTherapee — but
//! trimmed to the profile **constructors + getters only**: no `librtengine` link,
//! no colour-management or apply code. The *apply* (vignette, distortion,
//! baseline-exposure, colour matrix) is re-implemented in Rust in this crate
//! (`lcp.rs` / `dcp.rs`) for our single-channel CFA-space pipeline.
//!
//! # FFI chain
//!
//! ```text
//! develop.rs (has CFA, pre-demosaic)
//!    │ Rust call
//!    ▼
//! rawtherapee_fotlab::{parse_dcp, parse_lcp, compute_lcp_model}  (this crate, Rust)
//!    │ cxx call  (decode only)
//!    ▼
//! rt_deprofile_shim.cc  (extern "C++", cxx ABI)   — cxx/rt_deprofile_shim.cc
//!    │ constructs rtengine::DCPProfile / LCPProfile (vendored parse ctor)
//!    ▼
//! vendored RT DCP/LCP parsers  — cxx/vendor/rtengine/{dcp,lcp}.cc
//!    ▼
//! parsed params back to Rust → Rust apply (vignette/distortion/baseline/matrix)
//! ```
//!
//! # Licensing
//!
//! We vendor ONLY RawTherapee's self-contained DCP/LCP *parsers* (constructors +
//! getters; apply methods stripped; `Glib::ustring` → `std::string` so glibmm is
//! not needed). This keeps the crate clear of the GPL `librtengine` link while
//! reusing RT's exact decode. The apply math in this crate is original Rust, not
//! derived from RT's apply code.

// (the `std::panic::{self, AssertUnwindSafe}` imports used for catch_unwind live
// in the modules that actually wrap the FFI calls, not here)

// ---------------------------------------------------------------------------
// cxx bridge: the C++ *decode* functions we call. `include!` pulls in the exact
// declaration from cxx/rt_deprofile_shim.h so cxx's generated C++ header and this
// Rust declaration agree on the ABI. The apply step is NOT here — it is Rust.
// ---------------------------------------------------------------------------
#[cxx::bridge]
mod ffi_deprofile {
    // `unsafe extern "C++"`: every shim function has a fully-safe signature
    // (only CxxString / Vec / bool / [u8] / scalar params), so cxx treats them
    // as safe-to-call C++ and *requires* the block to be `unsafe extern "C++"`.
    // The generated `unsafe fn`s are wrapped in `unsafe {}` by the Rust modules.
    unsafe extern "C++" {
        // Spelled relative to the crate root so the cxx-generated header resolves
        // it via the `manifest` include path (mirrors rawalchemy_fotlab's
        // `cpp/rawalchemy_api.h`). The shim .cc includes it the same way.
        include!("cxx/rt_deprofile_shim.h");

        /// Parse a DCP file into its colour matrices / illuminants / baseline flags.
        /// Returns 0 on success, <0 on error (message written into `err`).
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

        /// Parse an LCP file into its profile metadata.
        /// Returns 0 on success, <0 on error (message written into `err`).
        fn rt_parse_lcp(
            path: &CxxString,
            profile_name: &mut Vec<u8>,
            camera: &mut Vec<u8>,
            lens: &mut Vec<u8>,
            is_raw: &mut bool,
            is_fisheye: &mut bool,
            sensor_format_factor: &mut f32,
            pers_model_count: &mut i32,
            focal_length: &mut f32,
            err: &mut [u8],
        ) -> i32;

        /// Decode + interpolate the LCP correction model for the given focal /
        /// geometry. Fills `model` with 13 floats in this exact order:
        ///   x0, y0, fx, fy, vign0, vign1, vign2, vign3, dist0, dist1, dist2, dist3, dist4
        /// (rfx/rfy are derived as 1/fx, 1/fy on the Rust side). Sets `is_fisheye`
        /// and `swap_xy`. Returns 0 on success, <0 on error (msg in `err`).
        fn rt_compute_lcp_model(
            path: &CxxString,
            focal_length: f32,
            focal_length_35mm: f32,
            focus_dist: f32,
            aperture: f32,
            raw_rotation_deg: i32,
            w: i32,
            h: i32,
            model: &mut Vec<f32>,
            is_fisheye: &mut bool,
            swap_xy: &mut bool,
            err: &mut [u8],
        ) -> i32;
    }
}

mod dcp;
mod lcp;
mod deprofile_error;

pub use deprofile_error::DeprofileError;
pub use dcp::{parse_dcp, DcpParams};
pub use lcp::{apply_lcp_cfa, compute_lcp_model, parse_lcp, LcpModel, LcpParams};
