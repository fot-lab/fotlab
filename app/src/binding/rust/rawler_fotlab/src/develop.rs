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

use rayon::prelude::*;

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

/// Grading parameters supplied by Kotlin for [`develop_and_grade`].
///
/// **Every field is optional, and `None` means "the engine decides"** — either
/// "use upstream's own `rawalchemy::GradingParams` default" or "skip this stage".
/// This Rust side performs **no defaulting of its own**: the values are handed to
/// the glue as "unset" sentinels precisely so that upstream stays the single
/// owner of every default it declares. If upstream changes one, we follow it
/// without touching this crate (`rules/REVIEW/detail/FOTLAB-RAWLER-000006.md`).
#[derive(Debug, Clone, uniffi::Record)]
pub struct GradeParams {
  /// Log space selecting the camera log curve and the ProPhoto→target gamut
  /// matrix (e.g. `"FUJIFILM F-Log2 C"`, `"Sony S-Log3"`, `"ARRI LogC4"`), i.e.
  /// the linear→log encode stage. Pass a display name as returned by
  /// `supported_log_spaces()`; the cxx shim also still accepts upstream's
  /// canonical key (`"F-Log2C"`) for values that predate the aliasing, and it is
  /// the shim — not this crate — that spells either vocabulary. `None` = skip
  /// **both** the gamut transform and the log encoding (upstream:
  /// `logSpaceInfo == nullptr`).
  #[uniffi(default = None)]
  pub log_space: Option<String>,
  /// Path to a `.cube` 3D LUT, applied to the log-encoded image. `None` = no LUT.
  #[uniffi(default = None)]
  pub lut_path: Option<String>,
  /// Metering mode for automatic exposure (`computeAutoGain`), e.g. `"matrix"`.
  /// `None` = skip automatic metering, leaving the metered base at unity.
  #[uniffi(default = None)]
  pub metering_mode: Option<String>,
  /// Upstream's raw `GradingParams::gain` **exposure multiplier** — a linear
  /// factor, *not* an EV and **not** [`DevelopParams::exposure_ev`]. The develop
  /// exposure is applied by rawler to the mosaic before demosaic and never
  /// reaches the grading stage; this one scales the linear ProPhoto data the
  /// grading loop receives, so the two are separate controls that must not be
  /// wired to the same UI value. `None` = upstream default (unity) = don't touch
  /// exposure. Metering, when enabled, is the base this multiplier scales.
  #[uniffi(default = None)]
  pub gain: Option<f32>,
  /// Target gray level for `computeAutoGain` (upstream default `0.18`).
  /// `None` = upstream default. Only meaningful with `metering_mode`.
  #[uniffi(default = None)]
  pub target_gray: Option<f32>,
  /// Saturation/contrast boost switch. `None` = upstream default.
  #[uniffi(default = None)]
  pub enable_boost: Option<bool>,
  /// Saturation multiplier. `None` = upstream default.
  #[uniffi(default = None)]
  pub saturation: Option<f32>,
  /// Contrast multiplier. `None` = upstream default.
  #[uniffi(default = None)]
  pub contrast: Option<f32>,
  /// Contrast pivot point. `None` = upstream default.
  #[uniffi(default = None)]
  pub pivot: Option<f32>,
}

/// Lift the FFI record onto the glue's override struct.
///
/// A pure field-for-field mapping — including the `None`s, which stay `None` so
/// the glue can tell "unset" from "explicitly set to the upstream default value".
#[cfg(feature = "rawalchemy")]
impl From<&GradeParams> for rawalchemy_fotlab::GradeOverrides {
  fn from(p: &GradeParams) -> Self {
    Self {
      log_space: p.log_space.clone(),
      lut_path: p.lut_path.clone(),
      metering_mode: p.metering_mode.clone(),
      gain: p.gain,
      target_gray: p.target_gray,
      enable_boost: p.enable_boost,
      saturation: p.saturation,
      contrast: p.contrast,
      pivot: p.pivot,
    }
  }
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

/// Develop `raw` into linear ProPhoto-D50 and immediately hand the buffer to the
/// rawalchemy grading engine, returning the graded float buffer (e.g. F-Gamut +
/// F-Log). This is the single Rust→cxx hop that replaces the earlier
/// Kotlin-mediated handoff (`rules/REVIEW/detail/FOTLAB-RAWLER-000006`): rawler
/// owns decode + develop, `rawalchemy_fotlab` owns the grade, and Kotlin only
/// receives the final `Vec<f32>`.
///
/// Which stages run is decided entirely by [`GradeParams`] — an all-`None`
/// record means "run whatever upstream's defaults say" (gamut + log skipped,
/// LUT skipped, no metering, upstream's boost defaults). Kotlin therefore reaches
/// upstream's full parameter surface; this crate adds no policy of its own.
///
/// Requires the `rawalchemy` feature (which pulls in the `rawalchemy_fotlab` cxx
/// crate + the grading static lib). Without it this entry point is not compiled.
#[cfg(feature = "rawalchemy")]
#[uniffi::export]
pub fn develop_and_grade(
  raw: &[u8],
  params: DevelopParams,
  grade_params: GradeParams,
) -> Result<Vec<f32>, RawlerFotlabError> {
  if raw.is_empty() {
    return Err(RawlerFotlabError::Decode("empty input".to_string()));
  }
  let dev = develop(raw, params)?;
  let overrides = rawalchemy_fotlab::GradeOverrides::from(&grade_params);
  rawalchemy_fotlab::grade(&dev.rgb, dev.width, dev.height, &overrides)
    .map_err(|e| RawlerFotlabError::Decode(format!("rawalchemy grade failed: {e}")))
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
    // One multiply per photosite, no cross-element dependency: chunked so a rayon task
    // processes a whole slice instead of a single float (`ACTION-PERFOR-000007`). A raw
    // `par_iter_mut` here would be dominated by per-element scheduling overhead.
    pixels.par_chunks_mut(64 * 1024).for_each(|chunk| {
      for p in chunk {
        *p *= ev_scale;
      }
    });
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
  let cw_usize = cw as usize;
  let row_len = cw_usize * 3;
  if cw_usize == 0 || ch == 0 || row_len == 0 {
    return RawlerImageDeveloped { width: cw, height: ch, rgb: Vec::new() };
  }
  // Row-wise copy, so each row is an independent contiguous memcpy — parallelised
  // with rayon instead of being walked sequentially (`ACTION-PERFOR-000007`).
  let mut rgb = vec![0f32; cw_usize * ch as usize * 3];
  rgb.par_chunks_mut(row_len).enumerate().for_each(|(row, dst)| {
    let start = ((y + row) * src_w + x) * 3;
    dst.copy_from_slice(&linear.rgb[start..start + row_len]);
  });
  linear.width = cw;
  linear.height = ch;
  linear.rgb = rgb;
  linear
}
