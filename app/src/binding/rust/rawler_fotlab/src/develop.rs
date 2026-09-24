//! Develop glue — orchestrates the hand-built develop pipeline and is the FFI
//! entry point Kotlin calls.
//!
//! Pipeline (mirrors rawler's `RawDevelop::develop_intermediate` step ORDER,
//! minus the final sRGB gamma so `develop_image` always returns a **linear**
//! image):
//!
//! 1. `decode`      — `rawler::decode` → rawler `RawImage`
//! 2. rescale       — black/white-level scaling into 0..1 float (rawler)
//! 3. `denoise_strength` / `denoise_bm3d_strength` — two composed pre-demosaic
//!     mosaic denoise sub-stages (`denoise.rs` orchestrates them, in order):
//!     (a) a RawTherapee-style **CFA impulse denoise** (hot/dead-pixel /
//!     salt-and-pepper removal) driven by `denoise_strength`, then (b) a
//!     from-scratch **BM3D-CFA collaborative filter** on the raw mosaic driven
//!     by `denoise_bm3d_strength`. Both are `None` = identity; each photosite is
//!     handled on the normalised 0..1 mosaic, colour-aware on **every** CFA
//!     (2×2 Bayer, 6×6 X-Trans, four-colour, monochrome).
//! 3a. `dehaze_strength` / `dehaze_percentile` — optional pre-demosaic dehaze of
//!     the scaled mosaic (`dehaze.rs`); `None` = identity. The haze floor is
//!     estimated **per CFA colour plane** (R/G/B) as the `dehaze_percentile`
//!     quantile of each plane's 0..1 histogram (clamped to [0,1], default 1%),
//!     then lifted and contrast-restored per pixel, blended back by
//!     `dehaze_strength`. Runs on the *normalised* mosaic because it bins a 0..1
//!     histogram — a positive EV would push values >1.0 into the top bin.
//! 3b. `exposure_ev` — linear gain `2^exposure_ev` on the **single-channel** scaled
//!     mosaic, *before* demosaic (one mul per photosite instead of per output
//!     channel; demosaic is linear so the result is identical). Applied **last**
//!     among the mosaic stages, after denoise and dehaze have cleaned the
//!     normalised 0..1 source values.
//! 4. `demosaic`    — selectable debayer + Fuji rotate + active-area crop (ROI). When
//!    `downsample` is set this stage runs rawler's **superpixel** debayer instead: same
//!    input (the exposed mosaic), same slot, but the result is quarter-resolution. The
//!    switch chooses between two producers of the *same* `Intermediate`, so it never
//!    reaches the stages below (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`).
//! 5. `calibrate`   — white balance + cam→working-space matrix (exposure already
//!    applied); `WorkingSpace` selects sRGB D65 (presentation) or ProPhoto D50
//!    (editing). **No clipping** — out-of-[0,1] is kept for the editing branch.
//! 6. crop-default  — crop to the recommended area (rawler `CropDefault`); the crop
//!    rectangle is halved when the demosaic stage produced a quarter-resolution image,
//!    derived from the dimensions rather than from the switch.
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

use rawler::rawimage::{RawImageData, RawPhotometricInterpretation};
use rawler::RawImage;

use crate::calibrate::{calibrate, WorkingSpace};
use crate::decode::decode_to_rawimage;
use crate::dehaze::dehaze;
use crate::demosaic::{demosaic, DemosaicAlgorithm};
use crate::denoise::denoise;
use crate::exposure::apply_exposure;
use crate::RawlerFotlabError;

/// The product of the develop pipeline: a linear RGB image (no gamma applied).
///
/// `rgb` is row-major linear RGB float, length `width * height * 3`.
///
/// **Path-independent by contract.** There is one of these regardless of which
/// demosaic path ran — full-resolution PPG/bilinear/X-Trans, or the
/// quarter-resolution superpixel switch. `width`/`height` are the dimensions of
/// the buffer actually produced (post-crop), nothing else records the choice, and
/// no consumer may infer or branch on it: calibrate, crop, the PNG encoder and
/// the rawalchemy grade all see the same structure with the same invariants and
/// simply process fewer pixels when the switch is on
/// (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`).
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
  /// Quarter-resolution preview switch — the Studio drawer's persisted downsampling
  /// preference. `true` replaces the demosaic stage with rawler's superpixel debayer: each
  /// 2×2 CFA block collapses into one RGB(E) pixel, so every later stage (calibrate, crop,
  /// PNG encode, rawalchemy grade) runs on a quarter of the pixels, i.e. the same picture
  /// at half the linear dimensions. This is a *different demosaic*, not a post-demosaic
  /// resize (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`).
  ///
  /// Ignored — the requested [`DemosaicAlgorithm`] runs at full resolution instead — when
  /// the sensor cannot use superpixel: X-Trans, a CFA that is neither the RGGB family nor
  /// four-colour, a Fuji-rotated sensor, or pre-coloured (non-CFA) input.
  /// `RawlerImageLoaded::supports_downsample` answers the capability in advance so the UI
  /// can disable the switch rather than let it silently do nothing.
  #[uniffi(default = false)]
  pub downsample: bool,
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
  /// Impulse denoise strength for the **impulse** sub-stage of the pre-demosaic
  /// mosaic denoise (`denoise_impulse.rs`), applied **before** exposure on the
  /// normalised 0..1 mosaic. `None` = skip (identity); `0` also collapses to
  /// identity. A RawTherapee-style CFA impulse denoise (hot/dead-pixel /
  /// salt-and-pepper removal): `strength` is a *sensitivity multiplier* on the
  /// detection threshold (`≈1.0` = mild, higher = more aggressive). Supplied from
  /// Kotlin when the Studio denoise impulse control is enabled. Non-2×2-periodic
  /// CFAs (e.g. X-Trans) are handled (per-colour grouping), not skipped.
  #[uniffi(default = None)]
  pub denoise_strength: Option<f32>,
  /// BM3D-CFA denoise strength for the **BM3D** sub-stage of the pre-demosaic
  /// mosaic denoise (`denoise_bm3d_cfa.rs`), applied **before** exposure on the
  /// normalised 0..1 mosaic, *after* the impulse sub-stage. `None` = skip
  /// (identity); `0` also collapses to identity. A from-scratch BM3D-style
  /// collaborative filter that runs directly on the CFA mosaic
  /// (`sigma = 0.02 · strength`); higher strength = more aggressive Gaussian /
  /// shot-noise reduction. Supplied from Kotlin when the Studio BM3D denoise
  /// control is enabled.
  #[uniffi(default = None)]
  pub denoise_bm3d_strength: Option<f32>,
  /// Dehaze strength (0..1) for the pre-demosaic mosaic dehaze stage (`dehaze.rs`),
  /// applied **before** exposure on the normalised 0..1 mosaic. `None` = skip
  /// (identity). Supplied from Kotlin when the Studio dehaze control is enabled; 0
  /// also collapses to identity.
  #[uniffi(default = None)]
  pub dehaze_strength: Option<f32>,
  /// Dehaze haze-floor percentile (0..1) for the pre-demosaic mosaic dehaze
  /// stage (`dehaze.rs`). The haze floor is estimated as this quantile of each
  /// CFA colour plane's histogram; lower is more conservative (closer to a pure
  /// minimum), higher lifts more of the low-tail signal. Arbitrary floats from
  /// Kotlin are clamped to `[0,1]` internally. `None` → the default tail
  /// (`0.01`). Supplied from Kotlin when the Studio dehaze control is enabled.
  #[uniffi(default = None)]
  pub dehaze_percentile: Option<f32>,
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

  // Pre-demosaic mosaic stages, composed as pure functions (`denoise.rs` /
  // `dehaze.rs` / `exposure.rs`). Each consumes the mosaic buffer and returns it;
  // `None` (or a zero strength) is the identity, so an unconfigured stage is free.
  // Order: **Denoise → Dehaze → Exposure**. Denoise and dehaze run on the *raw
  // normalised* 0..1 mosaic — before any gain — so each solves its own source
  // value problem first:
  //   * denoise is scale-invariant under a uniform linear gain (median + neighbour
  //     range scale together), so its result is identical on either side of
  //     exposure;
  //   * dehaze bins a 0..1 histogram, so it MUST see the normalised mosaic — a
  //     positive EV would push values >1.0 into the top bin and bias the floor.
  // Exposure is applied last among the mosaic stages as the channel-uniform
  // linear `2^exposure_ev` gain; because it is linear it commutes with demosaic.
  let cfa = match &image.photometric {
    RawPhotometricInterpretation::Cfa(config) => Some(config),
    _ => None,
  };
  // Denoise (pre-demosaic mosaic): orchestrates two composed sub-stages in order
  // — (1) RT-style CFA impulse / hot-dead-pixel removal on `denoise_strength`,
  // then (2) BM3D-CFA collaborative filtering on the raw mosaic on
  // `denoise_bm3d_strength`. Each is independently `None`/zero = identity, so
  // enabling either alone is free. See `denoise.rs`.
  let pixels = denoise(
    pixels,
    image.width,
    image.height,
    params.denoise_strength,
    params.denoise_bm3d_strength,
    cfa,
  );
  // Dehaze: separate haze floor per CFA colour plane, as a configurable
  // `dehaze_percentile` of each plane's 0..1 histogram, DCP-style contrast
  // restore, blended by `dehaze_strength`; histograms are restricted to the
  // active area so masked borders do not bias the estimate.
  let pixels = dehaze(
    pixels,
    image.width,
    image.height,
    params.dehaze_strength,
    params.dehaze_percentile,
    cfa,
    image.active_area.map(|r| (r.p.x, r.p.y, r.d.w, r.d.h)),
  );
  // Exposure last: the `2^exposure_ev` linear gain on the cleaned, normalised
  // mosaic. Channel-uniform and linear, so it commutes with demosaic.
  let pixels = apply_exposure(pixels, params.exposure_ev);

  // Demosaic stage — its ROI is already active_area, exactly like rawler's
  // Demosaic + FujiRotate + CropActiveArea steps. `params.downsample` picks the superpixel
  // producer instead of the selected algorithm; both return one `Intermediate`, which is the
  // convergence point of the two paths: from here on nothing knows which one ran, and every
  // pixel-count-dependent number (`pixels` above is the only full-resolution buffer left) is
  // simply whatever the intermediate's dimensions say.
  let intermediate = demosaic(&image, pixels, params.demosaic_algorithm, params.downsample)?;

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
  crop_default(&image, linear)
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
/// step, applied after calibrate.
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
///
/// SCALE fix (the downsample switch's own trap): the intermediate can be a
/// **decimated** view of that ROI — the downsampling switch runs rawler's
/// superpixel debayer, which emits one output pixel per 2×2 CFA block, so a
/// half-size buffer carries the ROI's coordinates at half scale. The crop
/// rectangle has to be brought into the buffer's coordinate space before it is
/// sliced.
///
/// The factor is **derived from the dimensions that actually came back** — never
/// from a "was superpixel used" flag threaded down from the caller, and never
/// from a hardcoded `0.5` — so the rectangle and the buffer it slices cannot
/// disagree, and a future decimation ratio needs no change here. The mapping is
/// `out = in / factor` in **integer** arithmetic, which is exactly how the
/// decimator itself truncates (`roi.d.w >> 1` discards the odd last column);
/// multiplying by the real ratio `buf/roi` would instead drift by a pixel
/// whenever the ROI is odd (`3664 * (1833/3667) = 1831`, but the true answer is
/// `1832`). When the dimensions do not describe a clean integer decimation the
/// factor stays `1` and the bounds check below reports the mismatch with its
/// numbers, rather than letting the slice panic and resurface as the same bogus
/// "Unsupported Format".
fn crop_default(
  image: &RawImage,
  mut linear: RawlerImageDeveloped,
) -> Result<RawlerImageDeveloped, RawlerFotlabError> {
  let Some(mut crop) = image.crop_area.or(image.active_area) else {
    return Ok(linear);
  };
  // The demosaic ROI is `active_area` (the whole frame when there is none), so that — not
  // `RawImage.width` — is the full-scale space both the crop rectangle and the intermediate are
  // measured in once the rectangle has been re-based.
  let (roi_w, roi_h) = match image.active_area {
    Some(active_area) => {
      crop = crop.adapt(&active_area);
      (active_area.d.w, active_area.d.h)
    }
    None => (image.width, image.height),
  };

  let factor = decimation_factor(roi_w, roi_h, linear.width as usize, linear.height as usize);
  if factor > 1 {
    crop.p.x /= factor;
    crop.p.y /= factor;
    crop.d.w /= factor;
    crop.d.h /= factor;
  }

  let (buf_w, buf_h) = (linear.width as usize, linear.height as usize);
  let (cw, ch) = (crop.width(), crop.height());
  let (x, y) = (crop.x(), crop.y());
  if cw == buf_w && ch == buf_h {
    return Ok(linear);
  }
  if cw == 0 || ch == 0 {
    return Ok(RawlerImageDeveloped { width: cw as u32, height: ch as u32, rgb: Vec::new() });
  }
  // The rectangle has to sit inside the buffer it is about to slice. Falling outside means the
  // intermediate's dimensions did not describe a decimation this stage recognises — report the
  // numbers that disagree instead of letting the slice panic.
  if x + cw > buf_w || y + ch > buf_h {
    return Err(RawlerFotlabError::Decode(format!(
      "crop rect {cw}x{ch}+{x}+{y} does not fit the {buf_w}x{buf_h} developed buffer \
       (roi {roi_w}x{roi_h}, decimation factor {factor})"
    )));
  }

  let row_len = cw * 3;
  // Row-wise copy, so each row is an independent contiguous memcpy — parallelised
  // with rayon instead of being walked sequentially (`OPTIMZ-PERFRM-000007`).
  let mut rgb = vec![0f32; cw * ch * 3];
  rgb.par_chunks_mut(row_len).enumerate().for_each(|(row, dst)| {
    let start = ((y + row) * buf_w + x) * 3;
    dst.copy_from_slice(&linear.rgb[start..start + row_len]);
  });
  linear.width = cw as u32;
  linear.height = ch as u32;
  linear.rgb = rgb;
  Ok(linear)
}

/// Integer decimation factor between the demosaic ROI and the intermediate that came back: `1`
/// when they are the same size, `n` when the buffer is that ROI reduced by `n` on both axes.
///
/// Only clean integer decimation is recognised. The quotient is checked by *truncating back*
/// (`roi / n == buf`) rather than by exact divisibility, because that is how the decimator itself
/// works — superpixel emits `roi.d.w >> 1`, so a 3667-wide ROI yields a 1833-wide buffer and
/// `1833 * 2 != 3667` even though the factor is unambiguously 2. Both axes must agree. Anything
/// unrecognised returns `1`, leaving the caller's bounds check to report the mismatch instead of
/// guessing a rectangle.
fn decimation_factor(roi_w: usize, roi_h: usize, buf_w: usize, buf_h: usize) -> usize {
  if buf_w == 0 || buf_h == 0 || buf_w > roi_w || buf_h > roi_h {
    return 1;
  }
  if buf_w == roi_w && buf_h == roi_h {
    return 1;
  }
  let (fw, fh) = (roi_w / buf_w, roi_h / buf_h);
  if fw == fh && fw > 1 && roi_w / fw == buf_w && roi_h / fh == buf_h {
    fw
  } else {
    1
  }
}
