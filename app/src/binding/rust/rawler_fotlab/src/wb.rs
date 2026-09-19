//! White-balance color-temperature helpers for the Studio UI's "Kelvin" control.
//!
//! # What rawler actually speaks (the upstream contract)
//!
//! Upstream `rawler` / `dnglab` has **no Kelvin concept at all**. White balance is
//! stored and applied exclusively as camera-space RGBE channel multipliers
//! ([`rawler::rawimage::RawImage::wb_coeffs`]), normalized so the green channel is
//! `1.0`; the develop pipeline (`calibrate`) multiplies each photosite by them and
//! then maps camera→working space with a fixed colour matrix. There is no mired
//! field, no reciprocal-temperature field anywhere in the pipeline.
//!
//! The only temperature-related data rawler carries are **per-illuminant colour
//! matrices** (`RawImage.color_matrix`: `Illuminant → XYZ→camera`). Those matrices
//! are illuminant-specific: a matrix is valid for XYZ expressed under ITS OWN
//! calibration illuminant (typically Illuminant A / 2856 K and/or D65 / 6504 K).
//!
//! # Kelvin → multipliers, the way the DNG model defines it
//!
//! To keep the multipliers consistent with the fixed matrix `calibrate` renders
//! through, we follow the Adobe DNG rule for a camera neutral at an arbitrary
//! correlated colour temperature:
//!
//! 1. white-point chromaticity of the target CCT: the **Planckian** locus below
//!    4000 K (Krystek 1985 approximation) and the **CIE daylight** locus above it
//!    (Wyszecki–Stiles) — the daylight polynomial is invalid under ~4000 K;
//! 2. when TWO colour matrices exist, interpolate them in **reciprocal-CCT** space
//!    (`g = (1/T₁ − 1/T) / (1/T₁ − 1/T₂)`, i.e. mired interpolation — the
//!    "normalized reciprocal" the colour-science standard uses); with one matrix
//!    use it unadapted at every CCT (the single-matrix DNG rule);
//! 3. the camera neutral is `neutral = M(T) · XYZ_white(T)` — each matrix applied
//!    at its own illuminant, with NO Bradford adaptation on this path (Bradford is
//!    only used by `calibrate::resolve_xyz_to_cam` to anchor the DISPLAY matrix at
//!    D65/D50);
//! 4. multipliers are the reciprocal neutral, green-normalized (`G = 1`), so they
//!    speak exactly the `wb_coeffs` convention the rest of the pipeline uses.
//!
//! # Why the previous implementation rendered black
//!
//! It applied `image.xyz_to_cam` (the RAW, usually Illuminant-A, matrix) directly
//! to a daylight white and wrote `0.0` for every non-positive channel
//! (`if c > 0.0 { 1/c } else { 0 }`). A colour matrix contains deliberately
//! negative coefficients, so an Illuminant-A matrix evaluated at a daylight white
//! yields negative camera components — two channels were multiplied by exactly
//! zero, which is a black frame rather than a colour cast.

use rawler::imgop::matrix::pseudo_inverse;
use rawler::imgop::xyz::Illuminant;
use rawler::rawimage::RawImage;

use crate::calibrate::resolve_xyz_to_cam;

/// Supported CCT band, Kelvin. Covers every practical light source (candle ~1900 K
/// to open shade / north sky ~20000 K+). Inputs are clamped to it.
const MIN_KELVIN: f32 = 2000.0;
const MAX_KELVIN: f32 = 25000.0;

/// White-point CIE xy chromaticity for a CCT `t` (Kelvin): Planckian (black-body)
/// locus below 4000 K via Krystek's 1985 formula (CIE 1960 UCS, converted to xy),
/// CIE daylight locus (Wyszecki & Stiles) from 4000 K up. The daylight polynomial
/// is only valid ~4000–25000 K. `t` is clamped to the supported band.
/// Verified against the standard illuminants: `cct_to_xy(2856)` ≈ A
/// `(0.4476, 0.4075)` and `cct_to_xy(6504)` ≈ D65 `(0.3127, 0.3290)`.
pub fn cct_to_xy(t: f32) -> (f32, f32) {
    let t = t.clamp(MIN_KELVIN, MAX_KELVIN);
    if t < 4000.0 {
        // Krystek (1985), u'v' 1960 UCS approximation of the Planckian locus,
        // 1000..15000 K, then u'v' → xy.
        let t2 = t * t;
        let up = (0.860117757 + 1.5411829e-4 * t + 1.2864121e-7 * t2)
            / (1.0 + 8.42420235e-4 * t + 7.08145163e-7 * t2);
        let vp = (0.317398726 + 4.22806245e-5 * t + 4.20481691e-8 * t2)
            / (1.0 - 2.89741816e-5 * t + 1.61456053e-7 * t2);
        let denom = 6.0 * up - 16.0 * vp + 12.0;
        (9.0 * up / denom, 4.0 * vp / denom)
    } else {
        let x = if t <= 7000.0 {
            -4.6070e9 / (t * t * t) + 2.9678e6 / (t * t) + 99.11 / t + 0.244063
        } else {
            -2.0064e9 / (t * t * t) + 1.9018e6 / (t * t) + 247.48 / t + 0.237040
        };
        let y = -3.0 * x * x + 2.870 * x - 0.275;
        (x, y)
    }
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

/// CCT (Kelvin) of the calibration illuminant rawler tags a colour matrix with.
/// Matches the tristimulus table in rawler's `chromatic_adaption.rs` (Daylight →
/// D65 proxy, Flash → D55 proxy). `None` for illuminants rawler cannot resolve to a
/// white point — such matrices are skipped for temperature interpolation.
fn illuminant_cct(illuminant: &Illuminant) -> Option<f32> {
    Some(match illuminant {
        Illuminant::A => 2856.0,
        Illuminant::B => 4874.0,
        Illuminant::C => 6774.0,
        Illuminant::D50 => 5003.0,
        Illuminant::D55 => 5503.0,
        Illuminant::D65 => 6504.0,
        Illuminant::D75 => 7504.0,
        Illuminant::Daylight => 6500.0,
        Illuminant::Flash => 5500.0,
        _ => return None,
    })
}

/// One rawler colour matrix with the CCT of its calibration illuminant.
struct CamMatrix {
    cct: f32,
    /// RGB rows only (4th/emerald row stays zero for ordinary Bayer cameras).
    m: [[f32; 3]; 4],
}

/// Collect the raw (unadapted, illuminant-native) XYZ→camera matrices rawler
/// decoded, each tagged with its calibration CCT. Empty when the decoder supplied
/// no tagged colour matrix.
fn raw_color_matrices(image: &RawImage) -> Vec<CamMatrix> {
    let mut out = Vec::new();
    for (illuminant, flat) in &image.color_matrix {
        let Some(cct) = illuminant_cct(illuminant) else {
            continue;
        };
        if flat.len() % 3 != 0 || flat.len() / 3 == 0 || flat.len() / 3 > 4 {
            continue;
        }
        let mut m = [[0.0f32; 3]; 4];
        for (i, row) in flat.chunks_exact(3).enumerate() {
            m[i] = [row[0], row[1], row[2]];
        }
        out.push(CamMatrix { cct, m });
    }
    out.sort_by(|a, b| a.cct.partial_cmp(&b.cct).unwrap());
    out
}

/// Linearly blend two XYZ→camera matrices. The interpolation WEIGHT is computed
/// by the caller in reciprocal-CCT space.
fn blend_matrices(g: f32, lo: &[[f32; 3]; 4], hi: &[[f32; 3]; 4]) -> [[f32; 3]; 4] {
    let mut m = [[0.0f32; 3]; 4];
    for i in 0..4 {
        for j in 0..3 {
            m[i][j] = (1.0 - g) * lo[i][j] + g * hi[i][j];
        }
    }
    m
}

/// The XYZ→camera matrix valid for a target CCT, per the DNG rule: matrices at the
/// bracketing illuminants are interpolated in **mired (reciprocal-CCT) space**;
/// outside the bracket the nearest matrix is used; with a single matrix that
/// matrix is used unchanged (never Bradford-adapted on this path — a colour matrix
/// is valid at its own illuminant and that is exactly where it is evaluated).
fn matrix_for_cct(matrices: &[CamMatrix], kelvin: f32) -> Option<[[f32; 3]; 4]> {
    match matrices.len() {
        0 => None,
        1 => Some(matrices[0].m),
        _ => {
            // Bracketing pair around the target CCT.
            let hi = matrices.iter().position(|m| m.cct >= kelvin);
            let matrix = match hi {
                Some(0) => matrices[0].m,
                Some(i) if matrices[i].cct == kelvin => matrices[i].m,
                Some(i) => {
                    let (lo, hi_m) = (&matrices[i - 1], &matrices[i]);
                    // Reciprocal-temperature (mired) interpolation.
                    let g = ((1.0 / lo.cct) - (1.0 / kelvin))
                        / ((1.0 / lo.cct) - (1.0 / hi_m.cct));
                    blend_matrices(g.clamp(0.0, 1.0), &lo.m, &hi_m.m)
                }
                None => matrices[matrices.len() - 1].m,
            };
            Some(matrix)
        }
    }
}

/// White point as XYZ with `Y = 1` from CIE xy.
fn white_xyz(x: f32, y: f32) -> [f32; 3] {
    [x / y, 1.0, (1.0 - x - y) / y]
}

/// Convert a camera-space neutral (the camera's response to a white object under
/// the target light) into `wb_coeffs`-style multipliers, green-normalized.
///
/// A real colour matrix can still yield a tiny/negative component far outside its
/// calibration band; such a value is never a physical neutral, so components are
/// floored relative to the strongest channel instead of becoming a zero multiplier
/// (which is what produced the all-black frame), and the finished multipliers are
/// clamped to a photographic band. The 4th (emerald) multiplier is identity for
/// ordinary Bayer sensors.
fn neutral_to_multipliers(neutral: [f32; 4], channels: usize) -> Vec<f32> {
    let strongest = neutral[..channels]
        .iter()
        .cloned()
        .fold(f32::MIN, f32::max)
        .max(0.0);
    let floor = (strongest * 1e-3).max(1e-6);
    let safe: [f32; 4] = std::array::from_fn(|i| {
        if i < channels {
            neutral[i].max(floor)
        } else {
            1.0
        }
    });
    // Reciprocal neutral, anchored on green.
    let g_inv = 1.0 / safe[1];
    let raw: [f32; 4] = std::array::from_fn(|i| (1.0 / safe[i]) * (1.0 / g_inv));
    // Photographic sanity band: channel ratios beyond 16:1 against green are not a
    // real light source — clamp rather than emit a destabilizing gain.
    let clamp = |v: f32| v.clamp(1.0 / 16.0, 16.0);
    if channels >= 4 {
        vec![clamp(raw[0]), 1.0, clamp(raw[2]), clamp(raw[3])]
    } else {
        vec![clamp(raw[0]), 1.0, clamp(raw[2]), 1.0]
    }
}

/// Estimated as-shot CCT (Kelvin) from the decoded multipliers. The as-shot camera
/// neutral is the reciprocal multiplier and is projected back through the SAME
/// D65-referenced matrix the calibrate render uses (so the estimate matches the
/// matrix our custom multipliers are paired with), then McCamy. Returns 0.0 when
/// the multipliers are unavailable (NaN / zero) or the projection is degenerate.
pub fn as_shot_color_temp_kelvin(image: &RawImage) -> f32 {
    let wb = image.wb_coeffs;
    if wb.iter().any(|v| !v.is_finite() || *v == 0.0) {
        return 0.0;
    }
    // The as-shot multipliers bring the camera response of the scene white to
    // neutral, so the white point's un-white-balanced camera response is reciprocal.
    let cam_neutral = [1.0 / wb[0], 1.0 / wb[1], 1.0 / wb[2]];
    let Ok(xyz2cam) = resolve_xyz_to_cam(image, Illuminant::D65) else {
        return 0.0;
    };
    // Invert the RGB block (pseudo_inverse of an invertible 3×3 is its inverse).
    let m3 = [
        [xyz2cam[0][0], xyz2cam[0][1], xyz2cam[0][2]],
        [xyz2cam[1][0], xyz2cam[1][1], xyz2cam[1][2]],
        [xyz2cam[2][0], xyz2cam[2][1], xyz2cam[2][2]],
    ];
    let cam2xyz = pseudo_inverse(m3);
    let mut xyz = [0.0f32; 3];
    for i in 0..3 {
        for j in 0..3 {
            xyz[i] += cam2xyz[i][j] * cam_neutral[j];
        }
    }
    let sum = xyz[0] + xyz[1] + xyz[2];
    if !(sum > 0.0) {
        return 0.0;
    }
    xy_to_cct(xyz[0] / sum, xyz[1] / sum)
}

/// Camera-space white-balance multipliers (RGBE order, `G = 1`) for a target CCT
/// (`kelvin`), computed with rawler's own colour matrices under the DNG rule
/// documented at the top of this module. Falls back to the raw `xyz_to_cam` field
/// when the decoder provided no illuminant-tagged matrix. The result speaks the
/// exact convention of `RawImage.wb_coeffs`, so it can be passed straight to the
/// develop pipeline as a custom `wb`.
pub fn wb_from_color_temp(image: &RawImage, kelvin: f32) -> Vec<f32> {
    let kelvin = kelvin.clamp(MIN_KELVIN, MAX_KELVIN);
    let (x, y) = cct_to_xy(kelvin);
    let white = white_xyz(x, y);

    let matrices = raw_color_matrices(image);
    let channels = image.cpp.max(3).min(4);

    // Prefer the illuminant-tagged matrices + DNG interpolation; otherwise fall
    // back to the raw field rawler populated (same no-adaptation rule).
    let matrix = matrix_for_cct(&matrices, kelvin).unwrap_or(image.xyz_to_cam);

    let mut neutral = [0.0f32; 4];
    for i in 0..channels {
        for j in 0..3 {
            neutral[i] += matrix[i][j] * white[j];
        }
    }
    if !neutral[..channels].iter().all(|v| v.is_finite()) {
        // Degenerate matrix: neutral multipliers are the only non-destructive answer.
        return vec![1.0, 1.0, 1.0, 1.0];
    }
    neutral_to_multipliers(neutral, channels)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn planckian_2856_matches_illuminant_a() {
        let (x, y) = cct_to_xy(2856.0);
        assert!((x - 0.44757).abs() < 2e-3, "x = {x}");
        assert!((y - 0.40745).abs() < 2e-3, "y = {y}");
    }

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
    fn daylight_round_trips() {
        for t in [4000.0, 4500.0, 5500.0, 6504.0, 9000.0] {
            let (x, y) = cct_to_xy(t);
            let back = xy_to_cct(x, y);
            assert!((back - t).abs() / t < 0.05, "t = {t}, back = {back}");
        }
    }

    #[test]
    fn planckian_round_trips() {
        for t in [2000.0, 2500.0, 3000.0, 3500.0] {
            let (x, y) = cct_to_xy(t);
            let back = xy_to_cct(x, y);
            assert!((back - t).abs() / t < 0.06, "t = {t}, back = {back}");
        }
    }

    #[test]
    fn non_positive_y_is_unavailable() {
        assert_eq!(xy_to_cct(0.3, 0.0), 0.0);
    }

    #[test]
    fn mired_interpolation_at_anchors_equals_anchor_matrix() {
        // Identity at 2856 K, 2x identity-ish scale at 6504 K: at the anchor CCTs
        // the resolved matrix must equal the corresponding anchor exactly.
        let id = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0], [0.0; 3]];
        let scaled = [[2.0, 0.0, 0.0], [0.0, 2.0, 0.0], [0.0, 0.0, 2.0], [0.0; 3]];
        let matrices = vec![
            CamMatrix { cct: 2856.0, m: id },
            CamMatrix { cct: 6504.0, m: scaled },
        ];
        let at_a = matrix_for_cct(&matrices, 2856.0).unwrap();
        let at_d65 = matrix_for_cct(&matrices, 6504.0).unwrap();
        assert_eq!(at_a, id);
        assert_eq!(at_d65, scaled);
        // Midpoint (in mired space) is a strict convex blend.
        let mid = matrix_for_cct(&matrices, 4000.0).unwrap();
        assert!(mid[0][0] > 1.0 && mid[0][0] < 2.0);
        assert_eq!(mid[1][1], mid[0][0]);
    }

    #[test]
    fn single_matrix_is_used_unadapted() {
        let id = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0], [0.0; 3]];
        let matrices = vec![CamMatrix { cct: 2856.0, m: id }];
        assert_eq!(matrix_for_cct(&matrices, 10000.0).unwrap(), id);
    }

    #[test]
    fn multipliers_are_finite_green_anchored_and_never_black() {
        // Deliberately awkward "neutral" with a negative and a tiny component: the
        // old code emitted exact zeros here. Nothing may be zero/non-finite now.
        let m = neutral_to_multipliers([0.8, 0.5, -0.1, 1.0], 3);
        assert_eq!(m.len(), 4);
        assert_eq!(m[1], 1.0, "green anchored: {m:?}");
        for v in &m {
            assert!(v.is_finite() && *v > 0.0, "non-positive multiplier: {m:?}");
        }
    }

    #[test]
    fn warmer_light_asks_for_more_red_than_blue_gain() {
        // With an identity camera, white XYZ always has X > Z under warm light, so
        // the red neutral exceeds the blue one and its gain is smaller: warming
        // reduces red gain relative to blue (the physical direction of WB).
        let warm = {
            let (x, y) = cct_to_xy(2856.0);
            let w = white_xyz(x, y);
            neutral_to_multipliers([w[0], w[1], w[2], 1.0], 3)
        };
        let cool = {
            let (x, y) = cct_to_xy(9000.0);
            let w = white_xyz(x, y);
            neutral_to_multipliers([w[0], w[1], w[2], 1.0], 3)
        };
        assert!(warm[0] < warm[2], "warm: red gain {warm:?}");
        assert!(cool[0] > cool[2], "cool: red gain {cool:?}");
    }
}
