//! LCP profile parsing + CFA-space application.
//!
//! * `parse_lcp` reads the LCP *metadata* (name / camera / lens / fisheye /
//!   sensor-format / frame count) through the cxx shim over RawTherapee's
//!   vendored `LCPProfile`.
//! * `compute_lcp_model` decodes + interpolates the correction coefficients
//!   (vignette + distortion) for the given focal/geometry through the shim, and
//!   returns them as a plain Rust [`LcpModel`].
//! * `apply_lcp_cfa` re-implements RT's *apply* in Rust (no `librtengine`):
//!   the vignette radial multiplier and the distortion geometric warp, both
//!   colour-independent, applied directly to the single-channel CFA mosaic.
//!   CA is intentionally skipped (per-channel, belongs to the RGB stage).
//!
//! The apply math mirrors RawTherapee's `LCPMapper::processVignette` /
//! `correctDistortion` (rtengine/lcp.cc) but is original Rust code deriving
//! from the decoded coefficients, not from RT's apply source.

use crate::deprofile_error::DeprofileError;
use crate::ffi_deprofile::rt_compute_lcp_model;
use crate::ffi_deprofile::rt_parse_lcp;
use cxx::Vec as CxxVec;
use std::panic::{self, AssertUnwindSafe};

/// Metadata decoded from an LCP profile (no correction coefficients).
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

/// Decoded + interpolated LCP correction model for one (focal, geometry) point.
///
/// `vign` are the 4 vignette polynomial coefficients `vign_param[0..3]`; `dist`
/// are the 5 radial-distortion coefficients `param[0..4]`. `x0/y0` are the
/// optical centre in absolute pixel coordinates; `fx/fy` are the focal lengths
/// in pixels (so `rfx = 1/fx`, `rfy = 1/fy` for the vignette radius scaling).
#[derive(Debug, Clone)]
pub struct LcpModel {
    pub x0: f32,
    pub y0: f32,
    pub fx: f32,
    pub fy: f32,
    pub vign: [f32; 4],
    pub dist: [f32; 5],
    pub is_fisheye: bool,
    pub swap_xy: bool,
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
        unsafe {
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
        }
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

/// Decode + interpolate the LCP correction model for the given focal / geometry.
///
/// Delegates the frame-interpolation + `prepareParams` to the vendored RT
/// decoder (via the cxx shim); returns a pure-Rust [`LcpModel`] the apply step
/// consumes. Returns [`DeprofileError::Parse`] if the C++ side failed.
pub fn compute_lcp_model(
    path: &str,
    focal_length: f32,
    focal_length_35mm: f32,
    focus_dist: f32,
    aperture: f32,
    raw_rotation_deg: i32,
    w: usize,
    h: usize,
) -> Result<LcpModel, DeprofileError> {
    let mut model = CxxVec::default();
    let mut is_fisheye = false;
    let mut swap_xy = false;
    let mut errbuf = [0u8; 256];

    cxx::let_cxx_string!(cpath = path);
    let rc = panic::catch_unwind(AssertUnwindSafe(|| {
        unsafe {
            rt_compute_lcp_model(
                &cpath,
                focal_length,
                focal_length_35mm,
                focus_dist,
                aperture,
                raw_rotation_deg,
                w as i32,
                h as i32,
                &mut model,
                &mut is_fisheye,
                &mut swap_xy,
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

    let m = model.as_slice();
    if m.len() < 13 {
        return Err(DeprofileError::Invalid(format!(
            "LCP model returned {} coefficients (expected 13)",
            m.len()
        )));
    }

    Ok(LcpModel {
        x0: m[0],
        y0: m[1],
        fx: m[2],
        fy: m[3],
        vign: [m[4], m[5], m[6], m[7]],
        dist: [m[8], m[9], m[10], m[11], m[12]],
        is_fisheye,
        swap_xy,
    })
}

/// Apply LCP vignette and/or distortion in CFA mosaic space (colour-independent).
///
/// * `pixels` — single-channel CFA mosaic, row-major, length `w*h`; modified in place.
/// * `focal_length_35mm` — 35mm-equivalent focal length (pass `focal_length` if unknown).
/// * `raw_rotation_deg` — raw rotation applied before correction (0 if none).
///
/// CA is never applied (geometry/distortion only). The model is decoded once per
/// call; the vignette (radial brightness) is applied first, then the distortion
/// geometric warp samples the vignetted mosaic — matching RT's staging order
/// (vignette in RAW space, distortion after).
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
    let model = compute_lcp_model(
        path,
        focal_length,
        focal_length_35mm,
        focus_dist,
        aperture,
        raw_rotation_deg,
        w,
        h,
    )?;

    if vignette {
        apply_vignette(pixels, w, h, &model);
    }
    if distortion {
        apply_distortion(pixels, w, h, &model);
    }
    Ok(())
}

/// Vignette: multiply each pixel by `1 + r²(p0 + r²(p1 − p2·r² + p3·r⁴))`, where
/// `r` is the normalised radius from the optical centre (RT `processVignette`).
fn apply_vignette(pixels: &mut [f32], w: usize, h: usize, m: &LcpModel) {
    let rfx = 1.0f32 / m.fx;
    let rfy = 1.0f32 / m.fy;
    for y in 0..h {
        let yd = ((y as f32) - m.y0) * rfy;
        let yd2 = yd * yd;
        for x in 0..w {
            let xd = ((x as f32) - m.x0) * rfx;
            let r2 = xd * xd + yd2;
            let vfac = r2 * (m.vign[0] + r2 * (m.vign[1] - m.vign[2] * r2 + m.vign[3] * r2 * r2));
            let idx = y * w + x;
            let v = pixels[idx];
            pixels[idx] = v * (1.0f32 + vfac);
        }
    }
}

/// Distortion: reverse-map every destination pixel to its source coordinate via
/// `correct_distortion` (RT `LCPMapper::correctDistortion`) and bilinearly sample
/// the (vignetted) source mosaic.
fn apply_distortion(pixels: &mut [f32], w: usize, h: usize, m: &LcpModel) {
    // Sample from a copy so the in-place write never reads a pixel it already
    // overwrote.
    let src: Vec<f32> = pixels.to_vec();
    let w2 = w as f32 * 0.5;
    let h2 = h as f32 * 0.5;
    for y in 0..h {
        for x in 0..w {
            // centred destination coordinate handed to correctDistortion
            let xc = x as f32 - w2;
            let yc = y as f32 - h2;
            let (sx, sy) = correct_distortion(xc, yc, w2, h2, m);
            pixels[y * w + x] = bilinear(&src, w, h, sx, sy);
        }
    }
}

/// Mirror of `LCPMapper::correctDistortion` (rtengine/lcp.cc). `xc,yc` are the
/// centred destination coordinates (relative to the image centre `cx,cy`); returns
/// the absolute source pixel coordinate to sample.
fn correct_distortion(xc: f32, yc: f32, cx: f32, cy: f32, m: &LcpModel) -> (f32, f32) {
    // x += cx; y += cy  -> absolute destination
    let ax = xc as f64 + cx as f64;
    let ay = yc as f64 + cy as f64;

    if m.is_fisheye {
        let du = ax - m.x0 as f64;
        let dv = ay - m.y0 as f64;
        let fx = m.fx as f64;
        let fy = m.fy as f64;
        let k1 = m.dist[0] as f64;
        let k2 = m.dist[1] as f64;
        let r = (du * du + dv * dv).sqrt();
        if r < 1e-8 {
            return (ax as f32, ay as f32);
        }
        let f = (fx * fy).sqrt();
        let th = r.atan2(f);
        let th2 = th * th;
        let cfact = (((k2 * th2 + k1) * th2 + 1.0) * th) / r;
        let ud = cfact * fx * du + m.x0 as f64;
        let vd = cfact * fy * dv + m.y0 as f64;
        (ud as f32, vd as f32)
    } else {
        let xd = (ax - m.x0 as f64) / m.fx as f64;
        let yd = (ay - m.y0 as f64) / m.fy as f64;
        let r2 = xd * xd + yd * yd;
        let xfac = (if m.swap_xy { m.dist[3] } else { m.dist[4] }) as f64;
        let yfac = (if m.swap_xy { m.dist[4] } else { m.dist[3] }) as f64;
        let a0 = m.dist[0] as f64;
        let a1 = m.dist[1] as f64;
        let a2 = m.dist[2] as f64;
        let common = (((a2 * r2 + a1) * r2 + a0) * r2 + 1.0) + 2.0 * (yfac * yd + xfac * xd);
        let xnew = xd * common + xfac * r2;
        let ynew = yd * common + yfac * r2;
        let out_x = xnew * m.fx as f64 + m.x0 as f64;
        let out_y = ynew * m.fy as f64 + m.y0 as f64;
        (out_x as f32, out_y as f32)
    }
}

/// Bilinear sample of `src` (row-major `w`×`h`) at `(sx, sy)`, clamped to edges.
fn bilinear(src: &[f32], w: usize, h: usize, sx: f32, sy: f32) -> f32 {
    let max_x = w as f32 - 1.0;
    let max_y = h as f32 - 1.0;
    let sx = if sx < 0.0 {
        0.0
    } else if sx > max_x {
        max_x
    } else {
        sx
    };
    let sy = if sy < 0.0 {
        0.0
    } else if sy > max_y {
        max_y
    } else {
        sy
    };
    let x0 = sx.floor() as usize;
    let y0 = sy.floor() as usize;
    let x1 = (x0 + 1).min(w - 1);
    let y1 = (y0 + 1).min(h - 1);
    let fx = sx - x0 as f32;
    let fy = sy - y0 as f32;
    let v00 = src[y0 * w + x0];
    let v01 = src[y0 * w + x1];
    let v10 = src[y1 * w + x0];
    let v11 = src[y1 * w + x1];
    v00 * (1.0 - fx) * (1.0 - fy)
        + v01 * fx * (1.0 - fy)
        + v10 * (1.0 - fx) * fy
        + v11 * fx * fy
}
