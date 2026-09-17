//! Calibrate glue — white balance + colour-matrix (cam → sRGB) mapping that
//! turns a debayered intermediate into a **linear** RGB image (no sRGB gamma /
//! BT.709 applied yet — that is a display transform the client owns).
//!
//! This is the "calibrate" half of our hand-built develop pipeline
//! (`FOTLAB-RAWLER-000003`). rawler's own `map_3ch_to_rgb` / `map_4ch_to_rgb`
//! are `pub(crate)`, so we replicate their math here using rawler's *public*
//! matrix primitives (`multiply`, `normalize`, `pseudo_inverse`,
//! `SRGB_TO_XYZ_D65`) and `clip_euclidean_norm_avg`. The colour matrix is always
//! taken from rawler's resolved `RawImage.color_matrix` (D65-normalized via
//! Bradford adaptation when only another illuminant is available), exactly as
//! rawler does. Exposure compensation (`ev`) is applied as a linear multiplier
//! `2^ev` on the resulting linear RGB.

use rawler::imgop::develop::Intermediate;
use rawler::imgop::matrix::{multiply, normalize, pseudo_inverse};
use rawler::imgop::raw::clip_euclidean_norm_avg;
use rawler::imgop::chromatic_adaption::adapt_bradford;
use rawler::imgop::xyz::{Illuminant, SRGB_TO_XYZ_D65};
use rawler::RawImage;

use crate::develop::LinearImage;
use crate::RawlerFotlabError;

/// White balance multipliers (RGBE order); `None` means "use rawler's default
/// from the file".
///
/// Takes OWNERSHIP of the intermediate: the colour mapping is per-pixel (each
/// output channel only depends on the same pixel's input channels), so the
/// 3-colour case is transformed IN PLACE and the buffer is zero-copy flattened
/// into the [LinearImage]. Allocating a second ~630 MB f32 buffer on a 50 MP
/// frame was the other half of the mid-develop OOM (low-memory-kill).
pub(crate) fn calibrate(
    intermediate: Intermediate,
    image: &RawImage,
    wb: Option<[f32; 4]>,
    ev: f32,
) -> Result<LinearImage, RawlerFotlabError> {
  // Resolve the D65 camera→XYZ matrix, falling back to identity and adapting
  // from another illuminant via Bradford when needed (rawler's logic).
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
    .unwrap_or_else(|| (Illuminant::D65, vec![1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0]));
  let d65_matrix: Vec<f32> = if illu == Illuminant::D65 {
    matrix
  } else {
    match matrix.len() {
      9 => adapt_bradford(&illu, &Illuminant::D65, &transform_1d_3x3(&matrix))
        .into_iter()
        .flatten()
        .collect(),
      _ => return Err(RawlerFotlabError::Decode("color matrix has unexpected size".to_string())),
    }
  };
  assert_eq!(d65_matrix.len() % 3, 0);
  let components = d65_matrix.len() / 3;
  for i in 0..components {
    for j in 0..3 {
      xyz2cam[i][j] = d65_matrix[i * 3 + j];
    }
  }

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

  let rgb2cam = normalize(multiply(&xyz2cam, &SRGB_TO_XYZ_D65));
  let cam2rgb = pseudo_inverse(rgb2cam);
  let ev_scale = 2f32.powf(ev);

  match intermediate {
    Intermediate::Monochrome(pix) => {
      // No per-channel colour mapping for monochrome; replicate the single
      // channel across RGB and apply EV. (3x size expansion is unavoidable.)
      let mut rgb: Vec<f32> = Vec::with_capacity(pix.data.len() * 3);
      for &v in &pix.data {
        let v = v * ev_scale;
        rgb.extend_from_slice(&[v, v, v]);
      }
      Ok(LinearImage {
        width: pix.width as u32,
        height: pix.height as u32,
        rgb,
      })
    }
    Intermediate::ThreeColor(mut pixels) => {
      // In-place: the 3x3 matrix maps each pixel from its own three channels,
      // so compute the result into a local before overwriting the source pixel.
      let (w, h) = (pixels.width, pixels.height);
      for px in pixels.pixels_mut() {
        let r = px[0] * wb[0] * ev_scale;
        let g = px[1] * wb[1] * ev_scale;
        let b = px[2] * wb[2] * ev_scale;
        let srgb = [
          cam2rgb[0][0] * r + cam2rgb[0][1] * g + cam2rgb[0][2] * b,
          cam2rgb[1][0] * r + cam2rgb[1][1] * g + cam2rgb[1][2] * b,
          cam2rgb[2][0] * r + cam2rgb[2][1] * g + cam2rgb[2][2] * b,
        ];
        *px = clip_euclidean_norm_avg(&srgb);
      }
      // Reinterpret the same allocation as flat RGB — no ~630 MB copy.
      Ok(LinearImage {
        width: w as u32,
        height: h as u32,
        rgb: flatten_rgb3(pixels.into_inner()),
      })
    }
    Intermediate::FourColor(pixels) => {
      // 4-channel -> 3-channel shrinks the data; the new vec is 3/4 the size
      // of the source and the source is dropped right after.
      let mut out: Vec<f32> = Vec::with_capacity(pixels.data.len() * 3);
      for px in pixels.pixels() {
        let ch0 = px[0] * wb[0] * ev_scale;
        let ch1 = px[1] * wb[1] * ev_scale;
        let ch2 = px[2] * wb[2] * ev_scale;
        let ch3 = px[3] * wb[3] * ev_scale;
        let srgb = [
          cam2rgb[0][0] * ch0 + cam2rgb[0][1] * ch1 + cam2rgb[0][2] * ch2 + cam2rgb[0][3] * ch3,
          cam2rgb[1][0] * ch0 + cam2rgb[1][1] * ch1 + cam2rgb[1][2] * ch2 + cam2rgb[1][3] * ch3,
          cam2rgb[2][0] * ch0 + cam2rgb[2][1] * ch1 + cam2rgb[2][2] * ch2 + cam2rgb[2][3] * ch3,
        ];
        let c = clip_euclidean_norm_avg(&srgb);
        out.extend_from_slice(&c);
      }
      Ok(LinearImage {
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

/// Tiny helper replicating `rawler::imgop::matrix::transform_1d::<3,3>` — reshapes
/// a 9-element flat colour matrix into `[[f32;3];3]` for `adapt_bradford`.
fn transform_1d_3x3(matrix: &[f32]) -> [[f32; 3]; 3] {
  let mut out = [[0.0f32; 3]; 3];
  for (i, v) in matrix.iter().copied().enumerate() {
    out[i / 3][i % 3] = v;
  }
  out
}
