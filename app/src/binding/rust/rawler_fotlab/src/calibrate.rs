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
use rawler::imgop::xyz::{adapt_bradford, Illuminant, SRGB_TO_XYZ_D65};
//! demosaic in `develop`, since demosaic is linear and the gain is
//! channel-uniform, so shifting it earlier is numerically identical.

use rawler::imgop::develop::Intermediate;
use rawler::imgop::matrix::{multiply, normalize, pseudo_inverse};
use rawler::imgop::chromatic_adaption::adapt_bradford;
use rawler::imgop::xyz::{Illuminant, SRGB_TO_XYZ_D65, XYZ_TO_PROFOTORGB_D50};
///
  intermediate: &Intermediate,
  image: &RawImage,
  wb: Option<[f32; 4]>,
  ev: f32,
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
) -> Result<RawlerImageDeveloped, RawlerFotlabError> {
  // Resolve the camera color matrix at the target white point (D65 presentation /
  // D50 editing), Bradford-adapted from another illuminant (rawler's logic).
  let target_illu = space.illuminant();
  let xyz2cam = resolve_xyz_to_cam(image, target_illu)?;

      // channel across RGB and apply EV.
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

    Intermediate::ThreeColor(pixels) => {
      let mut out: Vec<f32> = Vec::with_capacity(pixels.data.len() * 3);
      for px in pixels.pixels() {
  // irreversibly destroyed anything outside it *before* the FFI. Clamping now happens only
  // where a finished image is actually produced (`bound::rawlerimagedeveloped_to_png`), so the
  // wide-gamut result handed to rawalchemy keeps its negative and >1 components
  // (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`).
  let rgb2cam = normalize(multiply(&xyz2cam, &space.to_xyz_matrix()));
  let cam2rgb = pseudo_inverse(rgb2cam);

  match intermediate {
        let c = clip_euclidean_norm_avg(&srgb);
        out.extend_from_slice(&c);
      // No per-channel colour mapping for monochrome; replicate the single
      // already baked into the source mosaic by `develop` before demosaic.
        width: pixels.width as u32,
        height: pixels.height as u32,
        rgb: out,
      }
      Ok(RawlerImageDeveloped {
        width: pix.width as u32,
      })
    }
    Intermediate::ThreeColor(mut pixels) => {
      // In-place: the 3x3 matrix maps each pixel from its own three channels,
      // so compute the result into a local before overwriting the source pixel.
      let (w, h) = (pixels.width, pixels.height);
      for px in pixels.pixels_mut() {
        let r = px[0] * wb[0];
        let g = px[1] * wb[1];
        let b = px[2] * wb[2];
        let mapped = [
          cam2rgb[0][0] * r + cam2rgb[0][1] * g + cam2rgb[0][2] * b,
          cam2rgb[1][0] * r + cam2rgb[1][1] * g + cam2rgb[1][2] * b,
          cam2rgb[2][0] * r + cam2rgb[2][1] * g + cam2rgb[2][2] * b,
        ];
        // No clamp: the result stays in the working space, out-of-[0,1] included.
        *px = mapped;
      }
      // Reinterpret the same allocation as flat RGB — no ~630 MB copy.
      Ok(RawlerImageDeveloped {
        width: w as u32,
        let mapped = [
          cam2rgb[0][0] * ch0 + cam2rgb[0][1] * ch1 + cam2rgb[0][2] * ch2 + cam2rgb[0][3] * ch3,
          cam2rgb[1][0] * ch0 + cam2rgb[1][1] * ch1 + cam2rgb[1][2] * ch2 + cam2rgb[1][3] * ch3,
          cam2rgb[2][0] * ch0 + cam2rgb[2][1] * ch1 + cam2rgb[2][2] * ch2 + cam2rgb[2][3] * ch3,
        ];
        // No clamp — see the ThreeColor arm.
        out.extend_from_slice(&mapped);
      }
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
