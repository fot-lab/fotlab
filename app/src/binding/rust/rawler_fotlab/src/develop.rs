//! Develop glue — orchestrates the hand-built develop pipeline and is the FFI
//! entry point Kotlin calls.
//!
//! Pipeline (mirrors rawler's `RawDevelop::develop_intermediate` step ORDER,
//! minus the final sRGB gamma so `develop_image` always returns a **linear**
//! image):
//!
//! 1. `decode`      — `rawler::decode` → rawler `RawImage`
//! 2. rescale       — black/white-level scaling into 0..1 float (rawler)
//! 3. `exposure_ev` — linear gain `2^exposure_ev` on the **single-channel** scaled
//!    mosaic, *before* demosaic (one mul per photosite instead of per output
//!    channel; demosaic is linear so the result is identical)
//! 4. `demosaic`    — selectable debayer + Fuji rotate + active-area crop (ROI)
//! 5. `calibrate`   — white balance + cam→working-space matrix (exposure already
//!    applied); `WorkingSpace` selects sRGB D65 (presentation) or ProPhoto D50
//!    (editing). **No clipping** — out-of-[0,1] is kept for the editing branch.
//! 6. crop-default  — crop to the recommended area (rawler `CropDefault`)
//!
//! Dual fork (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`): the linear result
//! is finished into a display-ready sRGB PNG (gamma + clip) for the UI by
//! `bound::rawlerimagedeveloped_to_png`, or returned unclamped as ProPhoto D50 for the
//! rawalchemy pipeline by `develop`. Kotlin owns only the UI PNG.
//!
//! Every parameter change from Kotlin re-runs the whole pipeline (decoding
//! included) — acceptable for now; re-decoding is optimized later
//! (`FOTLAB-RAWLER-000003`). Identification/sniff/route are not repeated because
//! Kotlin only calls `develop` once the raw path is already chosen.

use std::panic::{self, AssertUnwindSafe};

use rawler::rawimage::RawImageData;
use rawler::RawImage;

use crate::calibrate::{calibrate, WorkingSpace};
use crate::decode::decode_to_rawimage;
use crate::demosaic::{demosaic, DemosaicAlgorithm};
use crate::RawlerFotlabError;

/// The product of the develop pipeline: a linear RGB image (no gamma applied).
///
/// `rgb` is row-major linear RGB float, length `width * height * 3`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct RawlerImageDeveloped {
  pub width: u32,
  pub height: u32,
  pub rgb: Vec<f32>,
}

/// Develop parameters supplied by Kotlin for each render.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DevelopParams {
  /// Demosaic algorithm selection (defaults to rawler's CFA-appropriate choice).
  pub demosaic_algorithm: DemosaicAlgorithm,
  /// Exposure compensation in stops; applied as the linear multiplier
  /// `2^exposure_ev` (the linear `exp_scale`) to the scaled mosaic *before*
  /// demosaic (single-channel). `None` = as-shot: no compensation, unity gain —
  /// exactly mirroring rawler's `RawDevelop::default()` (the pipeline dnglab uses
  /// to render its DNG thumbnail, which applies no exposure step at all;
  /// `FOTLAB-RAWLER-000004` §as-shot).
  #[uniffi(default = None)]
  pub exposure_ev: Option<f32>,
  /// Optional white-balance multipliers (RGBE order). `None` → rawler's as-shot
  /// `wb_coeffs`.
  pub wb: Option<Vec<f32>>,
}

/// FFI entry point: develop `raw` (already routed to the raw path) into a linear
/// **ProPhoto D50** RGB image (`RawlerImageDeveloped`) using `params` — the object handed
/// to the rawalchemy pipeline. This is the *editing* branch of the dual-fork
/// (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`): wide gamut and **unclamped**,
/// so negative and >1 components survive for downstream tone/exposure work. No
/// gamma is applied — ProPhoto is a linear editing space.
///
/// Re-runs the full pipeline (decode included) on every call; the cached-decode
/// path lives in [`crate::loaded::RawlerImageLoaded`] (`FOTLAB-RAWLER-000004`).
#[uniffi::export]
pub fn develop(raw: &[u8], params: DevelopParams) -> Result<RawlerImageDeveloped, RawlerFotlabError> {
  if raw.is_empty() {
    return Err(RawlerFotlabError::Decode("empty input".to_string()));
  }
  panic::catch_unwind(AssertUnwindSafe(|| {
    let image = decode_to_rawimage(raw)?;
    develop_image(image, params, WorkingSpace::ProPhotoD50)
  }))
  .unwrap_or_else(|_| Err(RawlerFotlabError::Decode("rawler panicked during develop".to_string())))
}

/// Develop an already-decoded [`RawImage`] into a linear RGB image in the
/// requested [`WorkingSpace`] (no gamma).
///
/// This is the shared core of the dual-fork (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`):
///
/// * `WorkingSpace::SrgbD65` — the *presentation* branch. The result is later
///   finished into a display-ready sRGB PNG (gamma + clip) by
///   `bound::rawlerimagedeveloped_to_png`; the rawalgebra object is never touched.
/// * `WorkingSpace::ProPhotoD50` — the *editing* branch for the rawalchemy
///   pipeline. Wide gamut and **unclamped**: the returned [`RawlerImageDeveloped`] keeps
///   its negative and >1 components.
///
/// Shared by the stateless `develop` FFI entry point (ProPhoto) and
/// `crate::loaded::RawlerImageLoaded::develop_to_png` (sRGB, which then encodes
/// with gamma). The pipeline mutates `image` in place — callers that must keep
/// their `RawImage` must clone it first (`FOTLAB-RAWLER-000004` §clone).
pub(crate) fn develop_image(
  mut image: RawImage,
  params: DevelopParams,
  space: WorkingSpace,
) -> Result<RawlerImageDeveloped, RawlerFotlabError> {
  image
    .apply_scaling()
    .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;

  // Move the scaled f32 pixels OUT of the RawImage before demosaic so the
  // ~210 MB (50 MP) buffer is handed over zero-copy instead of duplicated;
  // the now-empty image still carries every metadata field the later stages
  // read (CFA/photometric, color matrix, wb, active/crop areas).
  let mut pixels = take_scaled_pixels(&mut image)?;

  // Apply exposure compensation as a linear gain `2^exposure_ev` (the linear
  // `exp_scale`) to the *single-channel* scaled mosaic, BEFORE demosaic. This is
  // one multiply per photosite (N) instead of per output channel (3N/4N) after
  // demosaic, and is mathematically identical because demosaic is linear and the
  // gain is uniform across channels. `None` → unity gain (as-shot); `Some(0.0)`
  // also collapses to unity, so the no-compensation path pays nothing — matching
  // rawler's `RawDevelop::default()` (dnglab's DNG thumbnail pipeline).
  let ev_scale = params.exposure_ev.map_or(1.0, |ev| 2f32.powf(ev));
  if ev_scale != 1.0 {
    for p in pixels.iter_mut() {
      *p *= ev_scale;
    }
  }

  // Demosaic stage — its ROI is already active_area, exactly like rawler's
  // Demosaic + FujiRotate + CropActiveArea steps.
  let intermediate = demosaic(&image, pixels, params.demosaic_algorithm)?;

  let wb = params.wb.as_ref().map(|v| {
    let mut a = [1.0f32; 4];
    for (i, x) in v.iter().take(4).enumerate() {
      a[i] = *x;
    }
    a
  });

  // Calibrate first, then CropDefault — the same order as rawler's
  // `RawDevelop::develop_intermediate` (Calibrate → CropDefault). Both are
  // per-pixel/rect-selection operations, so order is numerically equivalent,
  // but keeping the identical order means the crop coordinates resolve
  // exactly the way upstream resolves them.
  let linear = calibrate(intermediate, &image, wb, space)?;
  Ok(crop_default(&image, linear))
}

/// Take ownership of the scaled f32 pixel buffer from [RawImage] without a
/// copy. [RawImage::apply_scaling] always converts the data to
/// [RawImageData::Float], so an integer buffer here means the scaling contract
/// changed upstream and is reported instead of silently converting.
fn take_scaled_pixels(image: &mut RawImage) -> Result<Vec<f32>, RawlerFotlabError> {
  match std::mem::replace(&mut image.data, RawImageData::Float(Vec::new())) {
    RawImageData::Float(v) => Ok(v),
    RawImageData::Integer(_) => Err(RawlerFotlabError::Decode(
      "scaled RawImage pixels are not f32 — apply_scaling contract changed".to_string(),
    )),
  }
}

/// Crop the developed image to the recommended area — rawler's `CropDefault`
/// step, applied after calibrate. Superpixel 1/2 scaling is omitted because we
/// never use superpixel demosaic.
///
/// CRITICAL coordinate fix (the "every format develops to Unsupported" bug):
/// `RawImage.crop_area` is in **full-sensor** coordinates, but the demosaic
/// stage already cropped its ROI to `RawImage.active_area` — so the
/// intermediate (and the flattened [`RawlerImageDeveloped`] calibrated from it) is in
/// **active-area** coordinates. rawler re-bases the crop with
/// `crop.adapt(active_area)` (`imgop/develop.rs`, CropDefault block) before
/// applying it. The previous code skipped that re-basing and sliced the
/// smaller buffer at full-sensor offsets, which panicked out of bounds on
/// essentially every real camera file (CR2 carries an embedded sensor-area
/// crop; DNG's DefaultCropOrigin is offset by ActiveArea in the decoder); the
/// `catch_unwind` boundary turned the panic into a Decode error and the UI
/// showed "Unsupported Format". When `active_area` is `None` the demosaic ROI
/// was the full frame, so no re-basing happens — matching upstream.
fn crop_default(image: &RawImage, mut linear: RawlerImageDeveloped) -> RawlerImageDeveloped {
  let Some(mut crop) = image.crop_area.or(image.active_area) else {
    return linear;
  };
  if let Some(active_area) = image.active_area {
    crop = crop.adapt(&active_area);
  }
  let (cw, ch) = (crop.width() as u32, crop.height() as u32);
  if cw == linear.width && ch == linear.height {
    return linear;
  }

  let src_w = linear.width as usize;
  let (x, y) = (crop.x(), crop.y());
  let mut rgb = Vec::with_capacity(cw as usize * ch as usize * 3);
  for row in 0..ch as usize {
    let start = ((y + row) * src_w + x) * 3;
    rgb.extend_from_slice(&linear.rgb[start..start + cw as usize * 3]);
  }
  linear.width = cw;
  linear.height = ch;
  linear.rgb = rgb;
  linear
}
