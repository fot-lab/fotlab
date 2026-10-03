//! Calibrate glue — white balance + colour-matrix mapping that turns a debayered
//! intermediate into a **linear** image in the requested [`WorkingSpace`].
//!
//! Two spaces are supported (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`):
//!
//! * [`WorkingSpace::SrgbD65`] — presentation. Finished to sRGB (gamma + gamut mapping)
//!   at PNG encode time in `bound`, never here.
//! * [`WorkingSpace::ProPhotoD50`] — editing, for the rawalchemy pipeline. Wide gamut and
//!   **unclamped**: negatives and >1 survive into the returned buffer on purpose.
//!
//! This is the "calibrate" half of our hand-built develop pipeline
//! (`FOTLAB-RAWLER-000003`). rawler's own `map_3ch_to_rgb` / `map_4ch_to_rgb`
//! are `pub(crate)`, so we replicate their math here using rawler's *public*
//! matrix primitives (`multiply`, `normalize`, `pseudo_inverse`,
//! `SRGB_TO_XYZ_D65`) and `clip_euclidean_norm_avg`. The colour matrix is always
//! taken from rawler's resolved `RawImage.color_matrix` (D65-normalized via
//! Bradford adaptation when only another illuminant is available), exactly as
//! rawler does. Exposure compensation is **not** applied here — it is applied as
//! the linear gain `2^exposure_ev` to the single-channel mosaic *before*
//! demosaic in `develop`, since demosaic is linear and the gain is
//! channel-uniform, so shifting it earlier is numerically identical.

use rayon::prelude::*;

use rawler::imgop::develop::Intermediate;
use rawler::imgop::matrix::{multiply, normalize, pseudo_inverse};
use rawler::imgop::chromatic_adaption::adapt_bradford;
use rawler::imgop::xyz::{Illuminant, SRGB_TO_XYZ_D65, XYZ_TO_PROFOTORGB_D50};
use rawler::RawImage;

use crate::develop::RawlerImageDeveloped;
use crate::RawlerFotlabError;

/// Which RGB primaries (and white point) the developed result lives in.
///
/// The two paths exist because they have opposite requirements
/// (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`):
///
/// * [`WorkingSpace::SrgbD65`] — the *presentation* path. Small gamut, but it is what a
///   display can actually show, so this is the space the UI PNG is finished in (gamma and
///   gamut mapping included, applied at PNG encode time — see `bound::rawlerimagedeveloped_to_png`).
/// * [`WorkingSpace::ProPhotoD50`] — the *editing* path handed to the rawalchemy pipeline.
///   Wide gamut: colours outside sRGB survive here. It is deliberately **not** clipped —
///   negative and >1 components are legitimate and only get resolved at final export.
///   D50 matches rawalchemy and RawTherapee, so no chromatic-adaptation bridge is needed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkingSpace {
    /// Linear sRGB, white point D65.
    SrgbD65,
    /// Linear ProPhoto RGB, white point D50.
    ProPhotoD50,
}

impl WorkingSpace {
    /// The illuminant the camera colour matrix must be adapted to for this space.
    fn illuminant(self) -> Illuminant {
        match self {
            WorkingSpace::SrgbD65 => Illuminant::D65,
            WorkingSpace::ProPhotoD50 => Illuminant::D50,
        }
    }

    /// Forward matrix from this working space to XYZ, at this space's own white point.
    ///
    /// `sRGB → XYZ` is a published constant; rawler only ships `XYZ → ProPhoto`, so that
    /// one is inverted here (`pseudo_inverse` on a 3×3 is negligible next to the per-pixel
    /// loop).
    fn to_xyz_matrix(self) -> [[f32; 3]; 3] {
        match self {
            WorkingSpace::SrgbD65 => SRGB_TO_XYZ_D65,
            WorkingSpace::ProPhotoD50 => pseudo_inverse(XYZ_TO_PROFOTORGB_D50),
        }
    }
}

/// White balance multipliers (RGBE order); `None` means "use rawler's default
/// from the file".
///
/// Takes OWNERSHIP of the intermediate: the colour mapping is per-pixel (each
/// output channel only depends on the same pixel's input channels), so the
/// 3-colour case is transformed IN PLACE and the buffer is zero-copy flattened
/// into the [RawlerImageDeveloped]. Allocating a second ~630 MB f32 buffer on a 50 MP
/// frame was the other half of the mid-develop OOM (low-memory-kill).
pub(crate) fn calibrate(
    intermediate: Intermediate,
    image: &RawImage,
    wb: Option<[f32; 4]>,
    space: WorkingSpace,
    oklab_compress: bool,
) -> Result<RawlerImageDeveloped, RawlerFotlabError> {
  // Resolve the camera color matrix at the target white point (D65 presentation /
  // D50 editing), Bradford-adapted from another illuminant (rawler's logic).
  let target_illu = space.illuminant();
  let xyz2cam = resolve_xyz_to_cam(image, target_illu)?;

  // White balance: explicit override, else rawler default (1.0 if NaN).
  let wb = match wb {
    Some(wb) => wb,
    None => {
      if image.wb_coeffs[0].is_nan() {
        [1.0, 1.0, 1.0, 1.0]
      } else {
        image.wb_coeffs
      }
    }
  };

  // Anchor the camera matrix on the requested working space: sRGB→XYZ (D65) for the
  // presentation path, ProPhoto→XYZ (D50) for the wide-gamut editing path.
  //
  // NOTE: no gamut clamping happens here any more. `clip_euclidean_norm_avg` used to run
  // per-pixel right after this matrix, which forced every colour inside the sRGB cube and
  // irreversibly destroyed anything outside it *before* the FFI. Clamping now happens only
  // where a finished image is actually produced (`bound::rawlerimagedeveloped_to_png`), so the
  // wide-gamut result handed to rawalchemy keeps its negative and >1 components
  // (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`).
  let rgb2cam = normalize(multiply(&xyz2cam, &space.to_xyz_matrix()));
  let cam2rgb = pseudo_inverse(rgb2cam);

  // --- OKLab highlight-compression bypass (`FOTLAB-RENDER-000001`) -----------------
  // Enabled only on the SrgbD65 presentation branch (`oklab_compress` is a no-op on
  // ProPhotoD50). We build the two fixed 3×3 maps camera ↔ XYZ(D65) from the *same*
  // factors the loop already uses — the effective 3×3 of `cam2rgb` (the RGB part the
  // 3-colour arm actually multiplies, the 4th E column is unused for RGGB) and the
  // working→XYZ matrix — so that for any pixel the compression leaves untouched the
  // XYZ round-trip is the *exact* identity. We deliberately do NOT decompose `cam2rgb`
  // by inverting it alone: `normalize` entangled its neutral diagonal into it, so the
  // round-trip through the genuine pipeline factors is exact (design doc C3).
  let bypass_active = oklab_compress && space == WorkingSpace::SrgbD65;
  let (cam2xyz, xyz2cam_eff, m1_inv, m2_inv) = if bypass_active {
    let to_xyz = space.to_xyz_matrix();
    let to_xyz_inv = pseudo_inverse(to_xyz);
    // The 3×3 the 3-colour arm really multiplies: drop the unused E column.
    let cam2rgb_eff = [
      [cam2rgb[0][0], cam2rgb[0][1], cam2rgb[0][2]],
      [cam2rgb[1][0], cam2rgb[1][1], cam2rgb[1][2]],
      [cam2rgb[2][0], cam2rgb[2][1], cam2rgb[2][2]],
    ];
    let cam2rgb_eff_inv = pseudo_inverse(cam2rgb_eff);
    // camera → XYZ(D65) = (working→XYZ) · (camera→working)
    let cam2xyz = multiply(&to_xyz, &cam2rgb_eff);
    // XYZ(D65) → camera = (camera→working)⁻¹ · (working→XYZ)⁻¹
    let xyz2cam_eff = multiply(&cam2rgb_eff_inv, &to_xyz_inv);
    let m1_inv = pseudo_inverse(OKLAB_M1);
    let m2_inv = pseudo_inverse(OKLAB_M2);
    (cam2xyz, xyz2cam_eff, m1_inv, m2_inv)
  } else {
    (
      [[0.0_f32; 3]; 3],
      [[0.0_f32; 3]; 3],
      [[0.0_f32; 3]; 3],
      [[0.0_f32; 3]; 3],
    )
  };

  // Every arm below is a per-pixel mapping with no cross-pixel dependency, so each one is
  // parallelised with rayon — this is the cost centre the upstream `map_*_to_rgb` helpers
  // cannot cover for us, because they are `pub(crate)` in rawler
  // (`rules/REVIEW/detail/OPTIMZ-PERFRM-000007.md`, `FOTLAB-RAWLER-000003`).
  match intermediate {
    Intermediate::Monochrome(pix) => {
      // No per-channel colour mapping for monochrome; replicate the single
      // channel across RGB. (3x size expansion is unavoidable.) Exposure EV is
      // already baked into the source mosaic by `develop` before demosaic.
      let mut rgb: Vec<f32> = vec![0f32; pix.data.len() * 3];
      pix.data
          .par_iter()
          .zip(rgb.par_chunks_exact_mut(3))
          .for_each(|(&v, out)| out.copy_from_slice(&[v, v, v]));
      Ok(RawlerImageDeveloped {
        width: pix.width as u32,
        height: pix.height as u32,
        rgb,
      })
    }
    Intermediate::ThreeColor(mut pixels) => {
      // In-place: the 3x3 matrix maps each pixel from its own three channels,
      // so compute the result into a local before overwriting the source pixel.
      let (w, h) = (pixels.width, pixels.height);
      pixels.pixels_mut().par_iter_mut().for_each(|px| {
        let r = px[0] * wb[0];
        let g = px[1] * wb[1];
        let b = px[2] * wb[2];
        let (r, g, b) = if bypass_active {
          oklab_highlight_compress_pixel([r, g, b], &cam2xyz, &xyz2cam_eff, &m1_inv, &m2_inv)
        } else {
          (r, g, b)
        };
        let mapped = [
          cam2rgb[0][0] * r + cam2rgb[0][1] * g + cam2rgb[0][2] * b,
          cam2rgb[1][0] * r + cam2rgb[1][1] * g + cam2rgb[1][2] * b,
          cam2rgb[2][0] * r + cam2rgb[2][1] * g + cam2rgb[2][2] * b,
        ];
        // No clamp: the result stays in the working space, out-of-[0,1] included.
        *px = mapped;
      });
      // Reinterpret the same allocation as flat RGB — no ~630 MB copy.
      Ok(RawlerImageDeveloped {
        width: w as u32,
        height: h as u32,
        rgb: flatten_rgb3(pixels.into_inner()),
      })
    }
    Intermediate::FourColor(pixels) => {
      // 4-channel -> 3-channel shrinks the data; the new vec is 3/4 the size
      // of the source and the source is dropped right after.
      let mut out: Vec<f32> = vec![0f32; pixels.data.len() * 3];
      pixels
          .pixels()
          .par_iter()
          .zip(out.par_chunks_exact_mut(3))
          .for_each(|(px, dst)| {
            let ch0 = px[0] * wb[0];
            let ch1 = px[1] * wb[1];
            let ch2 = px[2] * wb[2];
            let ch3 = px[3] * wb[3];
            let mapped = [
              cam2rgb[0][0] * ch0 + cam2rgb[0][1] * ch1 + cam2rgb[0][2] * ch2 + cam2rgb[0][3] * ch3,
              cam2rgb[1][0] * ch0 + cam2rgb[1][1] * ch1 + cam2rgb[1][2] * ch2 + cam2rgb[1][3] * ch3,
              cam2rgb[2][0] * ch0 + cam2rgb[2][1] * ch1 + cam2rgb[2][2] * ch2 + cam2rgb[2][3] * ch3,
            ];
            // No clamp — see the ThreeColor arm.
            dst.copy_from_slice(&mapped);
          });
      Ok(RawlerImageDeveloped {
        width: pixels.width as u32,
        height: pixels.height as u32,
        rgb: out,
      })
    }
  }
}

/// Zero-copy reinterpretation of `Vec<[f32; 3]>` as `Vec<f32>`.
///
/// Sound: `[f32; 3]` has `align_of::<f32>()` and size exactly
/// `3 * size_of::<f32>()` with no padding, so the backing allocation of an
/// array vector is a contiguous run of `len * 3` f32s — same invariants
/// `Vec::from_raw_parts` needs after repointing length/capacity in elements.
fn flatten_rgb3(v: Vec<[f32; 3]>) -> Vec<f32> {
  let mut v = std::mem::ManuallyDrop::new(v);
  // SAFETY: ptr/len/cap stay within the original allocation; the element type
  // change [f32;3] -> f32 preserves layout contiguity and alignment.
  unsafe { Vec::from_raw_parts(v.as_mut_ptr() as *mut f32, v.len() * 3, v.capacity() * 3) }
}

/// Resolve the camera color matrix (XYZ→camera, `[[f32;3];4]`, RGBE rows) at the
/// requested reference [illuminant]: rawler's preferred-matrix lookup, Bradford
/// adapted from the stored illuminant when it differs, identity fallback. This is
/// the matrix the calibrate render pairs its white-balance multipliers with, and
/// the same resolution the white-balance Kelvin helpers use
/// (`wb::as_shot_color_temp_kelvin`), so a custom multiplier is always computed
/// against the exact matrix it will be rendered through.
pub(crate) fn resolve_xyz_to_cam(
    image: &RawImage,
    illuminant: Illuminant,
) -> Result<[[f32; 3]; 4], RawlerFotlabError> {
    let mut xyz2cam: [[f32; 3]; 4] = [[0.0; 3]; 4];
    let (illu, matrix) = image
        .color_matrix_find_first([
            Illuminant::D65,
            Illuminant::A,
            Illuminant::B,
            Illuminant::C,
            Illuminant::D50,
            Illuminant::D55,
            Illuminant::D75,
            Illuminant::Daylight,
            Illuminant::Flash,
        ])
        .unwrap_or_else(|| (illuminant, vec![1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]));
    let target_matrix: Vec<f32> = if illu == illuminant {
        matrix
    } else {
        match matrix.len() {
            9 => adapt_bradford(&illu, &illuminant, &transform_1d_3x3(&matrix))
                .into_iter()
                .flatten()
                .collect(),
            _ => return Err(RawlerFotlabError::Decode("color matrix has unexpected size".to_string())),
        }
    };
    assert_eq!(target_matrix.len() % 3, 0);
    let components = target_matrix.len() / 3;
    for i in 0..components {
        for j in 0..3 {
            xyz2cam[i][j] = target_matrix[i * 3 + j];
        }
    }
    Ok(xyz2cam)
}

/// Tiny helper replicating `rawler::imgop::matrix::transform_1d::<3,3>` — reshapes
/// a 9-element flat colour matrix into `[[f32;3];3]` for `adapt_bradford`.
fn transform_1d_3x3(matrix: &[f32]) -> [[f32; 3]; 3] {
  let mut out = [[0.0f32; 3]; 3];
  for (i, v) in matrix.iter().copied().enumerate() {
    out[i / 3][i % 3] = v;
  }
  out
}

// --- OKLab highlight-chroma compression (`FOTLAB-RENDER-000001`) ------------------
// Standard Ottosson OKLab (D65); reference constants from `external/colour/colour/models/oklab.py`.
// Forward:  XYZ →(M1)→ LMS →∛→ LMS' →(M2)→ OKLab.
// Backward: OKLab →(M2⁻¹)→ LMS' →(·)³→ LMS →(M1⁻¹)→ XYZ.  The two are exact, order-symmetric
// inverses: `∛` ↔ `(·)³` (per channel), `M2` ↔ `M2⁻¹`, `M1` ↔ `M1⁻¹`. `M1⁻¹`/`M2⁻¹` are the exact
// matrix inverses of the same constants used forward (computed once per image via `pseudo_inverse`,
// never transposed or approximated).

const OKLAB_M1: [[f32; 3]; 3] = [
  [0.8189330101, 0.3618667424, -0.1288597137],
  [0.0329845436, 0.9293118715, 0.0361456387],
  [0.0482003018, 0.2643662691, 0.6338517070],
];
const OKLAB_M2: [[f32; 3]; 3] = [
  [0.2104542553, 0.7936177850, -0.0040720468],
  [1.9779984951, -2.4285922050, 0.4505937099],
  [0.0259040371, 0.7827717662, -0.8086757660],
];

/// OKLab `L` at/above which the highlight chroma roll-off begins, and where it reaches full
/// desaturation. Both tunable constants (the design leaves the exact curve open, Q1). `KNEE_START`
/// is kept below 1.0 so the clipped-highlight band — where the per-channel sRGB clamp would
/// otherwise freeze a hue error — is caught.
const OKLAB_KNEE_START: f32 = 0.92;
const OKLAB_KNEE_END: f32 = 1.0;

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

#[inline(always)]
fn xyz_to_oklab(xyz: [f32; 3]) -> [f32; 3] {
  let lms = matvec3(&OKLAB_M1, xyz);
  let lms = [lms[0].cbrt(), lms[1].cbrt(), lms[2].cbrt()];
  matvec3(&OKLAB_M2, lms)
}

#[inline(always)]
fn oklab_to_xyz(lab: [f32; 3], m2_inv: &[[f32; 3]; 3], m1_inv: &[[f32; 3]; 3]) -> [f32; 3] {
  let lms = matvec3(m2_inv, lab);
  let lms = [lms[0] * lms[0] * lms[0], lms[1] * lms[1] * lms[1], lms[2] * lms[2] * lms[2]];
  matvec3(m1_inv, lms)
}

/// Camera-space-in / camera-space-out OKLab highlight-chroma compression.
///
/// `cam2xyz` / `xyz2cam_eff` are the 3×3 maps built in [`calibrate`] from the same factors the
/// pipeline uses, so for any pixel the compression leaves untouched the round-trip is the *exact*
/// identity (camera → XYZ → OKLab → XYZ → camera = I). Pixels with `L ≤ OKLAB_KNEE_START` (and
/// neutral pixels) are returned unchanged with no OKLab round-trip at all, so the bypass perturbs
/// *only* near-clipped highlights and leaves the rest of the image bit-identical to the bypass-off
/// path.
#[inline(always)]
fn oklab_highlight_compress_pixel(
  cam: [f32; 3],
  cam2xyz: &[[f32; 3]; 3],
  xyz2cam_eff: &[[f32; 3]; 3],
  m1_inv: &[[f32; 3]; 3],
  m2_inv: &[[f32; 3]; 3],
) -> [f32; 3] {
  let xyz = matvec3(cam2xyz, cam);
  let lab = xyz_to_oklab(xyz);
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
  // Lightness-driven chroma roll-off: as L climbs from KNEE_START to KNEE_END, scale chroma from
  // 1 down to 0 (smoothstep), desaturating the highlight toward neutral so the frozen sRGB-clamp
  // hue error is reduced.
  let t = clamp01((l - OKLAB_KNEE_START) / (OKLAB_KNEE_END - OKLAB_KNEE_START));
  let f = t * t * (3.0 - 2.0 * t);
  let scale = 1.0 - f;
  let lab2 = [l, a * scale, b * scale];
  let xyz2 = oklab_to_xyz(lab2, m2_inv, m1_inv);
  matvec3(xyz2cam_eff, xyz2)
}

#[cfg(test)]
mod oklab_tests {
  use super::*;

  #[test]
  fn oklab_round_trip() {
    let m1_inv = pseudo_inverse(OKLAB_M1);
    let m2_inv = pseudo_inverse(OKLAB_M2);
    for xyz in [
      [0.5_f32, 0.5, 0.5],
      [0.9, 0.8, 1.0],
      [0.2, 0.4, 0.3],
      [0.8718, 0.8427, 1.0626],
    ] {
      let lab = xyz_to_oklab(xyz);
      let back = oklab_to_xyz(lab, &m2_inv, &m1_inv);
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
    let m1_inv = pseudo_inverse(OKLAB_M1);
    let m2_inv = pseudo_inverse(OKLAB_M2);
    // Neutral (equal channels) bright pixel → chroma 0 → exact identity.
    let out =
      oklab_highlight_compress_pixel([0.95, 0.95, 0.95], &cam2xyz, &xyz2cam_eff, &m1_inv, &m2_inv);
    for i in 0..3 {
      assert!((out[i] - 0.95).abs() < 1e-5, "neutral not preserved: {:?}", out);
    }
    // Below the knee, non-neutral → identity (no round trip, no f32 drift).
    let out2 =
      oklab_highlight_compress_pixel([0.3, 0.1, 0.2], &cam2xyz, &xyz2cam_eff, &m1_inv, &m2_inv);
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
    let m1_inv = pseudo_inverse(OKLAB_M1);
    let m2_inv = pseudo_inverse(OKLAB_M2);
    // A bright, chroma-rich pixel above the knee — this is the sRGB [1.00, 0.78, 1.00] magenta
    // cast, expressed in XYZ so camera == XYZ under the identity maps.
    let lab_in = xyz_to_oklab([1.0, 0.78, 1.0]);
    let c_in = (lab_in[1] * lab_in[1] + lab_in[2] * lab_in[2]).sqrt();
    let out =
      oklab_highlight_compress_pixel([1.0, 0.78, 1.0], &cam2xyz, &xyz2cam_eff, &m1_inv, &m2_inv);
    let lab_out = xyz_to_oklab(out);
    let c_out = (lab_out[1] * lab_out[1] + lab_out[2] * lab_out[2]).sqrt();
    assert!(c_out < c_in, "highlight chroma should shrink: {} vs {}", c_out, c_in);
  }
}
