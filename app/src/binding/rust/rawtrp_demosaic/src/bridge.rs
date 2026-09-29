//! Bridge from the ported kernels' output to the rawler `Intermediate` the
//! develop pipeline already speaks.
//!
//! `rules/DESIGN/detail/FOTLAB-NATIVE-000004.md` R3: the crate boundary hands
//! back exactly `rawler::imgop::develop::Intermediate::ThreeColor`, which is what
//! `RawDevelop::develop_intermediate` produces at its Demosaic step and what the
//! **calibrate** step consumes (`rules/STRUCT/detail/DNGLAB-PIPELN-000002.md`
//! §7.1). Calibrate only depends on the `Intermediate`'s shape, so swapping the
//! producer — rawler's PPG vs. a ported RawTherapee kernel — is invisible to it
//! and to every later stage (crop, PNG encode, rawalchemy grade).

use rawler::imgop::develop::Intermediate;
use rawler::pixarray::Color2D;

use crate::Rgb;

/// Interleave the ported kernels' three split planes into the rawler
/// `Intermediate::ThreeColor` the rest of the pipeline consumes.
#[must_use]
pub fn to_intermediate(rgb: Rgb) -> Intermediate {
  let (w, h) = rgb.dims();
  let mut data: Vec<[f32; 3]> = Vec::with_capacity(w * h);

  for i in 0..h {
    let (r, g, b) = (rgb.red.row(i), rgb.green.row(i), rgb.blue.row(i));
    for j in 0..w {
      data.push([r[j], g[j], b[j]]);
    }
  }

  Intermediate::ThreeColor(Color2D::<f32, 3>::new_with(data, w, h))
}

/// Convenience: demosaic a Bayer mosaic and hand the result straight to the
/// pipeline as an `Intermediate`.
///
/// # Errors
/// Propagates [`crate::demosaic_bayer`].
pub fn demosaic_bayer_to_intermediate(
  algo: crate::BayerAlgo,
  cfa: &crate::CfaDesc,
  mosaic: &crate::Array2D<f32>,
  params: &crate::BayerParams,
) -> Result<Intermediate, crate::Error> {
  crate::demosaic_bayer(algo, cfa, mosaic, params).map(to_intermediate)
}

/// Convenience: demosaic an X-Trans mosaic and hand the result straight to the
/// pipeline as an `Intermediate`.
///
/// # Errors
/// Propagates [`crate::demosaic_xtrans`].
pub fn demosaic_xtrans_to_intermediate(
  algo: crate::XTransAlgo,
  cfa: &crate::CfaDesc,
  mosaic: &crate::Array2D<f32>,
  params: &crate::XTransParams,
) -> Result<Intermediate, crate::Error> {
  crate::demosaic_xtrans(algo, cfa, mosaic, params).map(to_intermediate)
}
