//! cxx bridge into the RawAlchemyCpp grading engine.
//!
//! `grade` takes the linear **ProPhoto D50** float buffer produced by
//! `rawler_fotlab`'s editing branch and runs the fused grading pipeline
//! (gain → saturation/contrast → gamut → log → optional LUT) via the upstream
//! `rawalchemy::applyGradingFused`. The C++ side lives in `cpp/rawalchemy_shim.cc`.
//!
//! This crate deliberately does NOT modify the RawAlchemyCpp submodule
//! (`FOTLAB-RAWLER-000006`): it only calls the public grading API
//! (`applyGradingFused`, `LOG_SPACES`, `loadCubeLUT`, `GradingParams`,
//! `ImageBuffer`) and re-implements the small parameter assembly that the
//! submodule's file-decoding C API keeps in an anonymous-namespace helper.

#[cxx::bridge]
mod ffi {
    extern "C++" {
        /// Run the fused grading pipeline over a linear ProPhoto-D50 RGB buffer.
        ///
        /// `data` is row-major interleaved `width*height*3` float32 — the same
        /// layout as `rawler_fotlab::RawlerImageDeveloped.rgb`. Returns the graded
        /// buffer in that same layout. `log_space` names a registered LogSpace
        /// (e.g. `"F-Log"`, `"S-Log3"`); `lut_path` is an optional `.cube` path
        /// (`""` = none); `ev_offset` is an additive exposure in stops applied as
        /// `2^ev_offset` (rawler already applied as-shot exposure, so this is a
        /// relative tweak).
        fn grade(
            data: &[f32],
            width: u32,
            height: u32,
            log_space: &str,
            lut_path: &str,
            ev_offset: f32,
        ) -> Result<Vec<f32>, String>;
    }
}

/// Rust wrapper around the cxx `grade` call. Mirrors the C++ contract.
pub fn grade(
    data: &[f32],
    width: u32,
    height: u32,
    log_space: &str,
    lut_path: &str,
    ev_offset: f32,
) -> Result<Vec<f32>, String> {
    ffi::grade(data, width, height, log_space, lut_path, ev_offset)
}
