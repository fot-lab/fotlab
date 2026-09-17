//! Develop glue — orchestrates the hand-built develop pipeline and is the FFI
//! entry point Kotlin calls.
//!
//! Pipeline (mirrors rawler's `RawDevelop::develop_intermediate`, minus the final
//! sRGB gamma so the output is a true **linear** image):
//!
//! 1. `decode`      — `rawler::decode` → rawler `RawImage`
//! 2. rescale       — black/white-level scaling into 0..1 float (rawler)
//! 3. `demosaic`    — selectable debayer + Fuji rotate + active-area crop
//! 4. crop-default  — crop to the recommended area (rawler `CropDefault`)
//! 5. `calibrate`   — white balance + cam→sRGB matrix + exposure EV
//!
//! The result is a [`LinearImage`]: linear RGB float, **before** any sRGB/BT.709
//! gamma. Kotlin owns the display transform.
//!
//! Every parameter change from Kotlin re-runs the whole pipeline (decoding
//! included) — acceptable for now; re-decoding is optimized later
//! (`FOTLAB-RAWLER-000003`). Identification/sniff/route are not repeated because
//! Kotlin only calls `develop` once the raw path is already chosen.

use std::panic::{self, AssertUnwindSafe};

use rawler::imgop::develop::Intermediate;
use rawler::RawImage;

use crate::calibrate::calibrate;
use crate::decode::decode_to_rawimage;
use crate::demosaic::{demosaic, DemosaicAlgorithm};
use crate::RawlerFotlabError;

/// The product of the develop pipeline: a linear RGB image (no gamma applied).
///
/// `rgb` is row-major linear RGB float, length `width * height * 3`.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LinearImage {
  pub width: u32,
  pub height: u32,
  pub rgb: Vec<f32>,
}

/// Develop parameters supplied by Kotlin for each render.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DevelopParams {
  /// Demosaic algorithm selection (defaults to rawler's CFA-appropriate choice).
  pub demosaic_algorithm: DemosaicAlgorithm,
  /// Exposure compensation in stops; linear multiplier `2^exposure_ev`.
  pub exposure_ev: f32,
  /// Optional white-balance multipliers (RGBE order). `None` → rawler's default.
  pub wb: Option<Vec<f32>>,
}

/// FFI entry point: develop `raw` (already routed to the raw path) into a linear
/// RGB image using `params`. Re-runs the full pipeline on every call.
#[uniffi::export]
pub fn develop(raw: &[u8], params: DevelopParams) -> Result<LinearImage, RawlerFotlabError> {
  if raw.is_empty() {
    return Err(RawlerFotlabError::Decode("empty input".to_string()));
  }
  panic::catch_unwind(AssertUnwindSafe(|| {
    let mut image = decode_to_rawimage(raw)?;
    image
      .apply_scaling()
      .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;

    let intermediate = demosaic(&image, params.demosaic_algorithm)?;
    let intermediate = crop_default(&image, intermediate);

    let wb = params.wb.as_ref().map(|v| {
      let mut a = [1.0f32; 4];
      for (i, x) in v.iter().take(4).enumerate() {
        a[i] = *x;
      }
      a
    });

    calibrate(&intermediate, &image, wb, params.exposure_ev)
  }))
  .unwrap_or_else(|_| Err(RawlerFotlabError::Decode("rawler panicked during develop".to_string())))
}

/// Crop the intermediate to the recommended area (rawler `CropDefault` step).
/// Superpixel 1/2 scaling is omitted because we never use superpixel demosaic.
fn crop_default(image: &RawImage, intermediate: Intermediate) -> Intermediate {
  if let Some(mut crop) = image.crop_area.or(image.active_area) {
    if crop.d != intermediate.dim() {
      return match intermediate {
        Intermediate::Monochrome(p) => Intermediate::Monochrome(p.crop(crop)),
        Intermediate::ThreeColor(p) => Intermediate::ThreeColor(p.crop(crop)),
        Intermediate::FourColor(p) => Intermediate::FourColor(p.crop(crop)),
      };
    }
  }
  intermediate
}
