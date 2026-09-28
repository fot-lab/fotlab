//! DCP profile parsing (wraps the cxx shim over RawTherapee's `DCPProfile`).
//!
//! Per the design doc, DCP is treated as a **read-only** source: we extract the
//! colour matrices, illuminants, baseline offset and curve flags. The CFA-stage
//! apply is only the scalar `BaselineExposure`, and even that is gated by the
//! caller's `apply_baseline_exposure` decision (Rust side, not here).

use crate::deprofile_error::DeprofileError;
use crate::ffi_deprofile::rt_parse_dcp;
use cxx::Vec as CxxVec;
use std::panic::{self, AssertUnwindSafe};

#[derive(Debug, Clone)]
pub struct DcpParams {
    pub has_color_matrix: [bool; 2],
    pub color_matrix: [[f64; 3]; 3],
    pub has_forward_matrix: [bool; 2],
    pub forward_matrix: [[f64; 3]; 3],
    pub temperature: [f64; 2],
    pub light_source: [i16; 2],
    pub will_interpolate: bool,
    pub has_tone_curve: bool,
    pub has_look_table: bool,
    pub has_hue_sat_map: bool,
    pub has_baseline_exposure: bool,
    pub baseline_exposure_offset: f64,
    // Camera identifiers for auto-matching (OQ4). `DCPProfile` does NOT retain
    // make/model, so these stay `None` in v1; filled later via DCPStore or a
    // DNG-tag read. The struct shape is ready so downstream code can consume them.
    pub unique_camera_model: Option<String>,
    pub camera_model: Option<String>,
    pub make: Option<String>,
    pub model: Option<String>,
}

fn flat_to_3x3(v: &[f64]) -> [[f64; 3]; 3] {
    debug_assert!(v.len() >= 9);
    let mut m = [[0f64; 3]; 3];
    for i in 0..3 {
        for j in 0..3 {
            m[i][j] = v[i * 3 + j];
        }
    }
    m
}

/// Parse a DCP file, returning the extracted parameters. On RT failure the error
/// string from the shim is surfaced as [`DeprofileError::Parse`].
pub fn parse_dcp(path: &str) -> Result<DcpParams, DeprofileError> {
    let mut cm1 = CxxVec::default();
    let mut cm2 = CxxVec::default();
    let mut fm1 = CxxVec::default();
    let mut fm2 = CxxVec::default();
    let mut has_cm1 = false;
    let mut has_cm2 = false;
    let mut has_fm1 = false;
    let mut has_fm2 = false;
    let mut will_interp = false;
    let mut temp1 = 0.0f64;
    let mut temp2 = 0.0f64;
    let mut baseline = 0.0f64;
    let mut light1 = 0i16;
    let mut light2 = 0i16;
    let mut has_tone = false;
    let mut has_look = false;
    let mut has_huesat = false;
    let mut has_baseline = false;
    let mut errbuf = [0u8; 256];

    cxx::let_cxx_string!(cpath = path);
    let rc = panic::catch_unwind(AssertUnwindSafe(|| {
        unsafe {
            rt_parse_dcp(
                &cpath,
                &mut cm1,
                &mut cm2,
                &mut fm1,
                &mut fm2,
                &mut has_cm1,
                &mut has_cm2,
                &mut has_fm1,
                &mut has_fm2,
                &mut will_interp,
                &mut temp1,
                &mut temp2,
                &mut baseline,
                &mut light1,
                &mut light2,
                &mut has_tone,
                &mut has_look,
                &mut has_huesat,
                &mut has_baseline,
                &mut errbuf,
            )
        }
    }))
    .unwrap_or(-99);

    if rc < 0 {
        let msg = String::from_utf8_lossy(&errbuf)
            .trim_end_matches('\0')
            .to_string();
        return Err(DeprofileError::Parse(msg));
    }

    Ok(DcpParams {
        has_color_matrix: [has_cm1, has_cm2],
        color_matrix: flat_to_3x3(cm1.as_slice()),
        has_forward_matrix: [has_fm1, has_fm2],
        forward_matrix: flat_to_3x3(fm2.as_slice()),
        temperature: [temp1, temp2],
        light_source: [light1, light2],
        will_interpolate: will_interp,
        has_tone_curve: has_tone,
        has_look_table: has_look,
        has_hue_sat_map: has_huesat,
        has_baseline_exposure: has_baseline,
        baseline_exposure_offset: baseline,
        unique_camera_model: None,
        camera_model: None,
        make: None,
        model: None,
    })
}
