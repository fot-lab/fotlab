//! White-balance color-temperature helpers for the Studio UI's "Kelvin" control.
//!
//! The decode path exposes the as-shot white balance only as camera-space RGBE
//! multipliers ([`rawler::rawimage::RawImage::wb_coeffs`]); upstream `rawler` / `dnglab` has no
//! color-temperature concept of its own. The Studio UI, however, wants a
//! correlated-color-temperature (CCT) in Kelvin. These helpers bridge the two:
//!
//! * [`as_shot_color_temp_kelvin`] — project the as-shot multipliers through the camera→XYZ matrix
//!   ([`rawler::rawimage::RawImage::cam_to_xyz`]) and approximate the CCT of the resulting
//!   chromaticity (McCamy). This is an *estimate*: a 3-vector of multipliers cannot pin a unique
//!   CCT, so the value is the daylight-locus approximation of whatever white point the multipliers
//!   imply. The UI surfaces it as "As-shot: xxxx K" and lets the user type a new Kelvin target.
//! * [`wb_from_color_temp`] — the inverse: a target CCT → daylight xy → camera multipliers, using the
//!   same XYZ→camera matrix the rest of the develop pipeline uses, so a re-develop stays consistent
//!   with as-shot rendering.
//!
//! All math stays in Rust: Kotlin only ever passes a single `f32` Kelvin across the FFI, which keeps
//! the camera→multiplier projection (and its array result) on the native side
//! (`rules/REVIEW/detail/FOTLAB-RAWLER-000004.md` §as-shot; Studio WB UI).

use rawler::rawimage::RawImage;

/// CIE daylight-locus approximation (Wyszecki & Stiles). Valid in the ~2000–25000 K band; inputs
/// are clamped to it. Verified against the standard D illuminants: `cct_to_xy(6504.0)` returns the
/// D65 chromaticity `(0.3127, 0.3290)` to three decimals. `t` is the temperature in Kelvin.
pub fn cct_to_xy(t: f32) -> (f32, f32) {
    let t = t.clamp(2000.0, 25000.0);
    let x = if t <= 7000.0 {
        -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 99.11 / t + 0.244063
    } else {
        -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 247.48 / t + 0.237040
    };
    let y = -3.0 * x * x + 2.870 * x - 0.275;
    (x, y)
}

/// McCamy's approximation: CCT (Kelvin) from CIE xy chromaticity. Accurate to a few tens of Kelvin
/// across the photographic range (~2800–10000 K). Returns 0.0 on a non-positive `y` or a
/// non-finite result (callers treat 0 as "unavailable").
pub fn xy_to_cct(x: f32, y: f32) -> f32 {
    if !(y > 0.0) {
        return 0.0;
    }
    let n = (x - 0.3320) / (y - 0.1858);
    let cct = 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33;
    if cct.is_finite() && cct > 0.0 {
        cct
    } else {
        0.0
    }
}

/// Estimated as-shot CCT (Kelvin) from the decoded multipliers and camera matrix. Returns 0.0 when
/// the multipliers are unavailable (NaN / zero) or the matrix is degenerate.
pub fn as_shot_color_temp_kelvin(image: &RawImage) -> f32 {
    let wb = image.wb_coeffs;
    if wb.iter().any(|v| !v.is_finite() || *v == 0.0) {
        return 0.0;
    }
    // The as-shot multipliers bring the camera response to the scene white point to neutral, so the
    // white point's un-white-balanced camera response is their reciprocal.
    let cam_neutral = [1.0 / wb[0], 1.0 / wb[1], 1.0 / wb[2], 1.0 / wb[3]];
    let cam2xyz = image.cam_to_xyz(); // [[f32; 4]; 3] — XYZ rows, camera RGBE cols.
    let mut xyz = [0.0f32; 3];
    for i in 0..3 {
        for j in 0..4 {
            xyz[i] += cam2xyz[i][j] * cam_neutral[j];
        }
    }
    let sum = xyz[0] + xyz[1] + xyz[2];
    if !(sum > 0.0) {
        return 0.0;
    }
    xy_to_cct(xyz[0] / sum, xyz[1] / sum)
}

/// Camera-space white-balance multipliers (RGBE order, length 4) for a target CCT (Kelvin). The
/// RGB multipliers come from the XYZ→camera matrix (`xyz_to_cam`) and are normalized on green
/// (G = 1), matching rawler's as-shot `wb_coeffs` convention — so switching white balance shifts
/// chroma only, never overall exposure. The 4th E channel is identity for the common Bayer case.
pub fn wb_from_color_temp(image: &RawImage, kelvin: f32) -> Vec<f32> {
    let (x, y) = cct_to_xy(kelvin);
    // White point as XYZ with Y = 1.
    let white = [x / y, 1.0, (1.0 - x - y) / y];
    let xyz2cam = image.xyz_to_cam; // [[f32; 3]; 4] — RGBE rows, XYZ cols; use RGB rows only.
    let mut coeffs = [0.0f32; 3];
    for i in 0..3 {
        let c = xyz2cam[i][0] * white[0] + xyz2cam[i][1] * white[1] + xyz2cam[i][2] * white[2];
        if c > 0.0 {
            coeffs[i] = 1.0 / c;
        }
    }
    // Anchor on green, the same scale rawler's as-shot multipliers use (G multiplier == 1).
    if coeffs[1] > 0.0 {
        coeffs[0] /= coeffs[1];
        coeffs[2] /= coeffs[1];
        coeffs[1] = 1.0;
    }
    vec![coeffs[0], coeffs[1], coeffs[2], 1.0]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn d65_maps_to_d65_xy() {
        let (x, y) = cct_to_xy(6504.0);
        assert!((x - 0.31271).abs() < 1e-3, "x = {x}");
        assert!((y - 0.32902).abs() < 1e-3, "y = {y}");
    }

    #[test]
    fn d50_maps_to_d50_xy() {
        let (x, y) = cct_to_xy(5003.0);
        assert!((x - 0.34570).abs() < 2e-3, "x = {x}");
        assert!((y - 0.35850).abs() < 2e-3, "y = {y}");
    }

    #[test]
    fn cct_xy_round_trips() {
        for t in [3000.0, 4500.0, 5500.0, 6504.0, 9000.0] {
            let (x, y) = cct_to_xy(t);
            let back = xy_to_cct(x, y);
            assert!((back - t).abs() / t < 0.05, "t = {t}, back = {back}");
        }
    }

    #[test]
    fn non_positive_y_is_unavailable() {
        assert_eq!(xy_to_cct(0.3, 0.0), 0.0);
    }
}
