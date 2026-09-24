//! CA correction orchestration — translates the rawler `CFAConfig` into the
//! `CfaDesc` the `rawtrp_correct` kernels take and forwards the call.
//!
//! `rawtrp_correct::correct_ca_bayer` is the port of RawTherapee's
//! `CA_correct_RT` (`rules/REVIEW/detail/FOTLAB-RAWLER-000011.md`): a
//! pre-demosaic radial chromatic-aberration correction on the linear 0..1
//! mosaic. This module only bridges types; the algorithm lives in the crate.
//!
//! ## Contract
//!
//! `correct_ca(pixels, width, height, settings, cfa) -> pixels`. `settings =
//! None` (the Kotlin switch OFF) is the identity — the whole stage is skipped.
//! The correction runs on the **full-frame** scaled mosaic *before* exposure
//! (after dehaze), matching the other pre-demosaic neighbour-quality stages.
//!
//! ## Degrade-not-fail
//!
//! Like the RAWTRP demosaic arms, a kernel error degrades to the uncorrected
//! mosaic instead of failing the develop: a non-Bayer / four-colour CFA
//! (`UnsupportedCfa`), an odd width (`OddWidth`), or a failed auto-fit
//! (`AutoCaFailed`) all `log::warn!` and pass the pixels through unchanged.

use rawler::imgop::{Dim2, Point, Rect};
use rawler::CFA;

use crate::demosaic::bayer_cfa_desc;

/// CA correction settings from Kotlin (the Studio LCA dialog). `None` = the
/// stage is off. Mirrors `rawtrp_correct::CaParams`; `auto = true` runs pass 1
/// (auto-fit measurement) each render, otherwise the manual radial strengths
/// are used.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CaSettings {
  /// Auto-fit the residual-CA polynomial (pass 1) instead of using the manual
  /// radial red/blue strengths. `true` = auto (upstream `autoCA`).
  #[uniffi(default = true)]
  pub auto: bool,
  /// Manual radial CA strength for red (upstream `cared`); ignored when `auto`.
  #[uniffi(default = 0.0)]
  pub red: f32,
  /// Manual radial CA strength for blue (upstream `cablue`); ignored when `auto`.
  #[uniffi(default = 0.0)]
  pub blue: f32,
  /// Post-correction colour-balance step (upstream `avoidColourshift`).
  #[uniffi(default = false)]
  pub avoid_colourshift: bool,
}

/// Run the pre-demosaic CA correction over the full-frame mosaic.
///
/// `cfa = None` (non-CFA input) skips the stage — a CFA-domain correction has
/// nothing to key off. A kernel error degrades to the uncorrected input (see
/// the module docs).
pub(crate) fn correct_ca(
  pixels: Vec<f32>,
  width: usize,
  height: usize,
  settings: Option<&CaSettings>,
  cfa: Option<&CFA>,
) -> Vec<f32> {
  let Some(settings) = settings else {
    return pixels; // switch OFF: identity, free
  };
  let Some(config) = cfa else {
    return pixels; // non-CFA input: nothing to correct
  };
  // The correction sees the whole scaled frame, so the CFA description is built
  // at the full-frame origin — the same layout the demosaic stage later shifts
  // from its own (active-area) ROI.
  let roi = Rect::new(Point::new(0, 0), Dim2::new(width, height));
  let Some(cfa_desc) = bayer_cfa_desc(&config.cfa, roi) else {
    log::warn!("CA correction needs a 2x2 R/G/B Bayer CFA; skipping the stage");
    return pixels;
  };

  let params = rawtrp_correct::CaParams {
    auto_ca: settings.auto,
    auto_iterations: 1,
    ca_red: settings.red as f64,
    ca_blue: settings.blue as f64,
    avoid_colourshift: settings.avoid_colourshift,
    border_crop: 0,
  };

  // Wrap the mosaic, correct in place, hand the buffer back. `Array2D` owns a
  // plain row-major `Vec`, so the round trip is two row-wise moves.
  let mut mosaic = rawtrp_demos::Array2D::new(width, height);
  for row in 0..height {
    mosaic.row_mut(row).copy_from_slice(&pixels[row * width..(row + 1) * width]);
  }
  match rawtrp_correct::correct_ca_bayer(&mut mosaic, &cfa_desc, &params, None) {
    Ok(()) => {
      let mut out = pixels;
      for row in 0..height {
        out[row * width..(row + 1) * width].copy_from_slice(mosaic.row(row));
      }
      out
    }
    Err(e) => {
      log::warn!("CA correction failed ({e:?}); passing the mosaic through uncorrected");
      pixels
    }
  }
}
