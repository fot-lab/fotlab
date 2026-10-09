//! OKLab highlight-chroma compression bypass (`FOTLAB-RENDER-000001`).
//!
//! This module owns the *entire* OKLab round trip. The boundary with the rest of the
//! pipeline (e.g. `calibrate`) is **camera-space**: a post-white-balance camera triple
//! is passed in, and a camera triple is returned, before the working-space `cam2rgb`
//! multiply. The camera-specific 3×3 maps (`cam2xyz` / `xyz2cam_eff`) are **always
//! supplied by the caller** — `calibrate` queries the camera colour matrix and builds
//! them, then hands them to the functions here. Nothing in this module depends on a
//! database, a `data` struct, or on the destination working-space primaries.
//!
//! The four reusable transforms — [`CameraSpaceRGB2D65XYZ`], [`D65XYZ2CameraSpaceRGB`],
//! [`D65XYZ2OKLab`], [`OKLab2D65XYZ`] — are pure functions: every input is an explicit
//! parameter (matrices are passed in by reference; large buffers would likewise be passed
//! by reference), and they reference only the fixed standard OKLab constants. They are
//! `pub` so any other Rust software can call them directly. The OKLab matrices are the
//! standard Ottosson constants (D65), exact inverses of one another, so the two OKLab
//! transforms are exact, order-symmetric inverses.
//!
//! The OKLab round trip is anchored on D65, so the same maps serve both the `SrgbD65`
//! presentation branch and the `ProPhotoD50` editing branch with no D50↔D65 Bradford
//! bridge (the step is invisible to the destination primaries).

use rawler::imgop::matrix::{multiply, normalize, pseudo_inverse};
use rawler::imgop::xyz::SRGB_TO_XYZ_D65;

/// Standard Ottosson OKLab (D65) forward matrices — reference constants from
/// `external/colour/colour/models/oklab.py`. Forward: `XYZ →(M1)→ LMS →∛→ LMS' →(M2)→ OKLab`.
/// These are fixed, camera-independent standard numbers; they are `pub` so callers can
/// build inverses or inspect them.
pub const OKLAB_M1: [[f32; 3]; 3] = [
  [0.8189330101, 0.3618667424, -0.1288597137],
  [0.0329845436, 0.9293118715, 0.0361456387],
  [0.0482003018, 0.2643662691, 0.6338517070],
];
pub const OKLAB_M2: [[f32; 3]; 3] = [
  [0.2104542553, 0.7936177850, -0.0040720468],
  [1.9779984951, -2.4285922050, 0.4505937099],
  [0.0259040371, 0.7827717662, -0.8086757660],
];

/// Exact inverses of [`OKLAB_M1`] / [`OKLAB_M2`] (the published Ottosson inverses; for a
/// full-rank square matrix the pseudo-inverse equals the exact inverse, which is what the
/// pipeline used to compute here at runtime). Backward: `OKLab →(M2⁻¹)→ LMS' →(·)³→ LMS
/// →(M1⁻¹)→ XYZ`. The pair is an exact, order-symmetric inverse of the forward pair.
pub const OKLAB_M1_INV: [[f32; 3]; 3] = [
  [1.2270138511, -0.5577999807, 0.2812561490],
  [-0.0405801784, 1.1122568696, -0.0716766787],
  [-0.0763812845, -0.4214819784, 1.5861632204],
];
pub const OKLAB_M2_INV: [[f32; 3]; 3] = [
  [1.0, 0.3963377774, 0.2158037573],
  [1.0, -0.1055613458, -0.0638541728],
  [1.0, -0.0894841775, -1.2914855480],
];

/// OKLab `L` at/above which the highlight chroma roll-off begins, and where it reaches
/// full desaturation. Tunable constants (the design leaves the exact curve open, Q1).
/// `KNEE_START` is kept below 1.0 so the clipped-highlight band — where the per-channel
/// sRGB clamp would otherwise freeze a hue error — is caught.
pub const OKLAB_KNEE_START: f32 = 0.92;
pub const OKLAB_KNEE_END: f32 = 1.0;

/// Matrix × vector for a 3×3 matrix and a 3-vector. Internal helper; the four public
/// transforms are the reusable surface.
#[inline(always)]
fn matvec3(m: &[[f32; 3]; 3], v: [f32; 3]) -> [f32; 3] {
  [
    m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2],
  ]
}

#[inline(always)]
fn clamp01(x: f32) -> f32 {
  if x < 0.0 {
    0.0
  } else if x > 1.0 {
    1.0
  } else {
    x
  }
}

/// Camera-space linear RGB → CIE XYZ (D65).
///
/// Pure: `cam2xyz` (the caller-supplied camera→XYZ(D65) 3×3 map) and the pixel are the
/// only inputs. No state, no database.
#[inline(always)]
pub fn CameraSpaceRGB2D65XYZ(cam: [f32; 3], cam2xyz: &[[f32; 3]; 3]) -> [f32; 3] {
  matvec3(cam2xyz, cam)
}

/// CIE XYZ (D65) → camera-space linear RGB.
///
/// Pure: `xyz2cam_eff` (the caller-supplied XYZ(D65)→camera 3×3 map) and the pixel are
/// the only inputs. This is the exact inverse of [`CameraSpaceRGB2D65XYZ`] when the two
/// maps are each other's inverse (which the pipeline guarantees by construction).
#[inline(always)]
pub fn D65XYZ2CameraSpaceRGB(xyz: [f32; 3], xyz2cam_eff: &[[f32; 3]; 3]) -> [f32; 3] {
  matvec3(xyz2cam_eff, xyz)
}

/// CIE XYZ (D65) → OKLab.
///
/// Pure: uses only the fixed standard [`OKLAB_M1`] / [`OKLAB_M2`] constants (no inputs
/// beyond the pixel). Exact inverse of [`OKLab2D65XYZ`].
#[inline(always)]
pub fn D65XYZ2OKLab(xyz: [f32; 3]) -> [f32; 3] {
  let lms = matvec3(&OKLAB_M1, xyz);
  let lms = [lms[0].cbrt(), lms[1].cbrt(), lms[2].cbrt()];
  matvec3(&OKLAB_M2, lms)
}

/// OKLab → CIE XYZ (D65).
///
/// Pure: uses only the fixed standard [`OKLAB_M1_INV`] / [`OKLAB_M2_INV`] constants (no
/// inputs beyond the pixel). Exact inverse of [`D65XYZ2OKLab`].
#[inline(always)]
pub fn OKLab2D65XYZ(lab: [f32; 3]) -> [f32; 3] {
  let lms = matvec3(&OKLAB_M2_INV, lab);
  let lms = [lms[0] * lms[0] * lms[0], lms[1] * lms[1] * lms[1], lms[2] * lms[2] * lms[2]];
  matvec3(&OKLAB_M1_INV, lms)
}

/// Precomputed camera↔XYZ(D65) maps for the OKLab highlight-compression bypass.
///
/// Holds only the camera-dependent 3×3 maps (built once per image from the camera colour
/// matrix by [`OklabBypassMaps::new`]). The OKLab matrices are the fixed constants above,
/// so they are not stored here.
pub struct OklabBypassMaps {
  /// Camera → XYZ(D65) map (caller-supplied, stored for reuse).
  pub cam2xyz: [[f32; 3]; 3],
  /// XYZ(D65) → camera map (caller-supplied, stored for reuse).
  pub xyz2cam_eff: [[f32; 3]; 3],
}

impl OklabBypassMaps {
  /// Build the camera↔XYZ(D65) round-trip maps from the resolved camera colour matrix
  /// `xyz2cam` (XYZ→camera, RGBE). Anchored on D65 regardless of the destination working
  /// space (camera ↔ XYZ(D65)), so the same maps serve both the sRGB and ProPhoto branches.
  ///
  /// The maps are built from the *same* factors the pipeline uses — the effective 3×3 of
  /// the D65-anchored camera→linear RGB and the sRGB→XYZ(D65) matrix — so for any pixel
  /// the compression leaves untouched the round-trip is the *exact* identity.
  pub fn new(xyz2cam: &[[f32; 3]; 4]) -> Self {
    // OKLab is defined in XYZ(D65); anchor BOTH maps on D65 regardless of `space`
    // (camera ↔ XYZ(D65)), so the round trip is correct on the ProPhotoD50 branch too.
    let to_xyz = SRGB_TO_XYZ_D65;
    let to_xyz_inv = pseudo_inverse(to_xyz);
    // D65-anchored camera→linear RGB (3×3): camera → XYZ(D65) = to_xyz · cam2rgb_eff.
    let rgb2cam_d65 = normalize(multiply(xyz2cam, &to_xyz));
    let cam2rgb_d65 = pseudo_inverse(rgb2cam_d65);
    let cam2rgb_eff = [
      [cam2rgb_d65[0][0], cam2rgb_d65[0][1], cam2rgb_d65[0][2]],
      [cam2rgb_d65[1][0], cam2rgb_d65[1][1], cam2rgb_d65[1][2]],
      [cam2rgb_d65[2][0], cam2rgb_d65[2][1], cam2rgb_d65[2][2]],
    ];
    let cam2rgb_eff_inv = pseudo_inverse(cam2rgb_eff);
    // camera → XYZ(D65) = sRGB→XYZ(D65) · (camera→sRGB-linear D65)
    let cam2xyz = multiply(&to_xyz, &cam2rgb_eff);
    // XYZ(D65) → camera = (camera→sRGB-linear D65)⁻¹ · (sRGB→XYZ(D65))⁻¹
    let xyz2cam_eff = multiply(&cam2rgb_eff_inv, &to_xyz_inv);
    OklabBypassMaps {
      cam2xyz,
      xyz2cam_eff,
    }
  }

  /// Camera-space-in / camera-space-out highlight-chroma compression for one pixel.
  #[inline(always)]
  pub fn compress_pixel(&self, cam: [f32; 3]) -> [f32; 3] {
    oklab_highlight_compress_pixel(cam, &self.cam2xyz, &self.xyz2cam_eff)
  }
}

/// Camera-space-in / camera-space-out OKLab highlight-chroma compression for one pixel.
///
/// Pure: `cam2xyz` / `xyz2cam_eff` (the camera↔XYZ(D65) maps) are passed in explicitly by
/// the caller; the OKLab constants and the knee are fixed. Built on the four reusable
/// transforms, so for any pixel the compression leaves untouched the round-trip is the
/// *exact* identity (camera → XYZ → OKLab → XYZ → camera = I). Pixels with
/// `L ≤ OKLAB_KNEE_START` (and *perceptually*-neutral pixels — zero OKLab chroma, e.g. the
/// white point — **not** merely equal XYZ channels, which carry chroma in OKLab) are
/// returned unchanged with no OKLab round-trip at all, so the bypass perturbs *only*
/// near-clipped highlights and leaves the rest of the image bit-identical to the bypass-off
/// path.
#[inline(always)]
pub fn oklab_highlight_compress_pixel(
  cam: [f32; 3],
  cam2xyz: &[[f32; 3]; 3],
  xyz2cam_eff: &[[f32; 3]; 3],
) -> [f32; 3] {
  let xyz = CameraSpaceRGB2D65XYZ(cam, cam2xyz);
  let lab = D65XYZ2OKLab(xyz);
  let l = lab[0];
  // Below the knee — or a neutral / out-of-gamut-negative-L pixel — leave the camera pixel
  // exactly as-is: no f32 round-trip error bleeds into non-highlight regions.
  if l <= OKLAB_KNEE_START {
    return cam;
  }
  let a = lab[1];
  let b = lab[2];
  let c = (a * a + b * b).sqrt();
  if c <= 0.0 {
    return cam;
  }
  // Lightness-driven chroma roll-off: as L climbs from KNEE_START to KNEE_END, scale chroma
  // from 1 down to 0 (smoothstep), desaturating the highlight toward neutral so the frozen
  // sRGB-clamp hue error is reduced.
  let t = clamp01((l - OKLAB_KNEE_START) / (OKLAB_KNEE_END - OKLAB_KNEE_START));
  let f = t * t * (3.0 - 2.0 * t);
  let scale = 1.0 - f;
  let lab2 = [l, a * scale, b * scale];
  let xyz2 = OKLab2D65XYZ(lab2);
  D65XYZ2CameraSpaceRGB(xyz2, xyz2cam_eff)
}

#[cfg(test)]
mod oklab_tests {
  use super::*;

  #[test]
  fn oklab_round_trip() {
    for xyz in [
      [0.5_f32, 0.5, 0.5],
      [0.9, 0.8, 1.0],
      [0.2, 0.4, 0.3],
      [0.8718, 0.8427, 1.0626],
    ] {
      let lab = D65XYZ2OKLab(xyz);
      let back = OKLab2D65XYZ(lab);
      for i in 0..3 {
        assert!(
          (back[i] - xyz[i]).abs() < 1e-3,
          "XYZ→OKLab→XYZ round trip off: {:?} vs {:?}",
          back,
          xyz
        );
      }
    }
  }

  #[test]
  fn bypass_identity_for_non_highlight_and_neutral() {
    let cam2xyz = [[1.0_f32, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
    let xyz2cam_eff = [[1.0_f32, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
    // A *perceptually* neutral bright pixel (OKLab chroma ≈ 0). Equal XYZ channels are NOT
    // neutral in OKLab — only zero OKLab chroma is — so we build the neutral point by
    // inverting a neutral lab. With zero chroma the bypass short-circuits (or round-trips to
    // ~machine precision) and returns the pixel unchanged, leaving the white point exactly
    // where the matrix put it.
    let neutral_xyz = OKLab2D65XYZ([0.95_f32, 0.0, 0.0]);
    let out = oklab_highlight_compress_pixel(neutral_xyz, &cam2xyz, &xyz2cam_eff);
    for i in 0..3 {
      assert!(
        (out[i] - neutral_xyz[i]).abs() < 1e-5,
        "neutral not preserved: {:?} vs {:?}",
        out,
        neutral_xyz
      );
    }
    // Below the knee, any pixel → identity (no round trip, no f32 drift).
    let out2 = oklab_highlight_compress_pixel([0.3, 0.1, 0.2], &cam2xyz, &xyz2cam_eff);
    assert!(
      (out2[0] - 0.3).abs() < 1e-6 && (out2[1] - 0.1).abs() < 1e-6 && (out2[2] - 0.2).abs() < 1e-6,
      "below-knee not identity: {:?}",
      out2
    );
  }

  #[test]
  fn bypass_reduces_chroma_on_highlight() {
    let cam2xyz = [[1.0_f32, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
    let xyz2cam_eff = [[1.0_f32, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];
    // A bright, chroma-rich pixel above the knee — this is the sRGB [1.00, 0.78, 1.00]
    // magenta cast, expressed in XYZ so camera == XYZ under the identity maps.
    let lab_in = D65XYZ2OKLab([1.0, 0.78, 1.0]);
    let c_in = (lab_in[1] * lab_in[1] + lab_in[2] * lab_in[2]).sqrt();
    let out = oklab_highlight_compress_pixel([1.0, 0.78, 1.0], &cam2xyz, &xyz2cam_eff);
    let lab_out = D65XYZ2OKLab(out);
    let c_out = (lab_out[1] * lab_out[1] + lab_out[2] * lab_out[2]).sqrt();
    assert!(c_out < c_in, "highlight chroma should shrink: {} vs {}", c_out, c_in);
  }
}
