//! LCP profile parsing + CFA-space application (wraps the cxx shim over RT's
//! `LCPProfile` / `LCPMapper`).
//!
//! LCP vignette and distortion are **colour-independent**, so they are applied
//! directly in CFA mosaic space by reusing RT's `LCPMapper` (`processVignette`
//! single-channel RAW path + `correctDistortion`). CA is per-channel and is
//! deliberately skipped here (`useCADistP = false`); it belongs to the RGB stage.

use crate::deprofile_error::DeprofileError;
use crate::ffi_deprofile::rt_apply_lcp_cfa;
use crate::ffi_deprofile::rt_parse_lcp;
use cxx::Vec as CxxVec;
use std::panic::{self, AssertUnwindSafe};

#[derive(Debug, Clone)]
pub struct LcpParams {
    pub profile_name: String,
    pub camera: String,
    pub lens: String,
    pub is_raw: bool,
    pub is_fisheye: bool,
    pub sensor_format_factor: f32,
    pub pers_model_count: i32,
}

/// Parse an LCP file into its profile metadata.
pub fn parse_lcp(path: &str) -> Result<LcpParams, DeprofileError> {
    let mut profile_name = CxxVec::default();
    let mut camera = CxxVec::default();
    let mut lens = CxxVec::default();
    let mut is_raw = false;
    let mut is_fisheye = false;
    let mut sensor_format_factor = 0.0f32;
    let mut pers_model_count = 0i32;
    let mut errbuf = [0u8; 256];

    cxx::let_cxx_string!(cpath = path);
    let rc = panic::catch_unwind(AssertUnwindSafe(|| {
        rt_parse_lcp(
            &cpath,
            &mut profile_name,
            &mut camera,
            &mut lens,
            &mut is_raw,
            &mut is_fisheye,
            &mut sensor_format_factor,
            &mut pers_model_count,
            &mut errbuf,
        )
    }))
    .unwrap_or(-99);

    if rc < 0 {
        let msg = String::from_utf8_lossy(&errbuf)
            .trim_end_matches('\0')
            .to_string();
        return Err(DeprofileError::Parse(msg));
    }

    Ok(LcpParams {
        profile_name: String::from_utf8_lossy(profile_name.as_slice()).into_owned(),
        camera: String::from_utf8_lossy(camera.as_slice()).into_owned(),
        lens: String::from_utf8_lossy(lens.as_slice()).into_owned(),
        is_raw,
        is_fisheye,
        sensor_format_factor,
        pers_model_count,
    })
}

/// Apply LCP vignette and/or distortion in CFA mosaic space (colour-independent).
///
/// * `pixels` — single-channel CFA mosaic, row-major, length `w*h`; modified in place.
/// * `focal_length_35mm` — 35mm-equivalent focal length (pass `focal_length` if unknown).
/// * `raw_rotation_deg` — raw rotation applied before correction (0 if none).
///
/// CA is never applied (geometry/distortion only).
pub fn apply_lcp_cfa(
    path: &str,
    focal_length: f32,
    focal_length_35mm: f32,
    focus_dist: f32,
    aperture: f32,
    vignette: bool,
    distortion: bool,
    raw_rotation_deg: i32,
    w: usize,
    h: usize,
    pixels: &mut [f32],
) -> Result<(), DeprofileError> {
    if pixels.len() < w * h {
        return Err(DeprofileError::Invalid(format!(
            "pixels too small ({} < {})",
            pixels.len(),
            w * h
        )));
    }
    let mut errbuf = [0u8; 256];
    cxx::let_cxx_string!(cpath = path);
    let rc = panic::catch_unwind(AssertUnwindSafe(|| {
        rt_apply_lcp_cfa(
            &cpath,
            focal_length,
            focal_length_35mm,
            focus_dist,
            aperture,
            vignette,
            distortion,
            raw_rotation_deg,
            w as i32,
            h as i32,
            pixels,
            &mut errbuf,
        )
    }))
    .unwrap_or(-99);

    if rc < 0 {
        let msg = String::from_utf8_lossy(&errbuf)
            .trim_end_matches('\0')
            .to_string();
        return Err(DeprofileError::Apply(msg));
    }
    Ok(())
}
