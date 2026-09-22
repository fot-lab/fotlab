//! Demosaic glue — calls rawler's own demosaic algorithms and assembles the
//! debayer stage of the develop pipeline.
//!
//! This is the "demosaic" half of our hand-built develop pipeline
//! (`FOTLAB-RAWLER-000003`). It intentionally reuses rawler's *building blocks*
//! (`rawler::imgop::sensor::Demosaic` implementations) rather than calling
//! `RawDevelop::develop_intermediate`, so that the algorithm is selectable and
//! the pipeline can later be extended. The algorithm choice is constrained by
//! the sensor CFA: a Bayer-RGB mosaic only has PPG available upstream, a
//! 4-colour Bayer mosaic uses bilinear-4, and X-Trans uses the X-Trans bilinear
//! demosaic. A user selection that is incompatible with the CFA falls back to
//! the CFA-appropriate default (and logs).
//!
//! The stage also owns the **quarter-resolution** switch: when `downsample` is
//! set, rawler's superpixel debayer (a different `Demosaic` implementation, not
//! a resize) replaces the selected algorithm and this stage returns an
//! intermediate at *half* the linear dimensions. Everything downstream —
//! calibrate, crop, PNG encode, the rawalchemy grade — sees only that smaller
//! `Intermediate` / `RawlerImageDeveloped`, so the branch is invisible to them
//! (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`).
//!
//! Fuji rotation and active-area crop are part of this stage because they are
//! tightly coupled to the demosaic ROI (rawler's own pipeline interleaves them).
//! `fuji_normalize_rotation` is `pub(crate)` upstream, so it is replicated here
//! verbatim from `external/dnglab/rawler/src/imgop/fuji_rotate.rs`.

use rawler::imgop::develop::Intermediate;
use rawler::imgop::sensor::bayer::{
  bilinear::Bilinear4Channel,
  ppg::PPGDemosaic,
  superpixel::{Superpixel3Channel, Superpixel4Channel},
};
use rawler::imgop::sensor::xtrans::bilinear::XTransBilinearDemosaic;
use rawler::imgop::sensor::{Demosaic as RawlerDemosaic, SensorType};
use rawler::imgop::Dim2;
use rawler::pixarray::{Color2D, PixF32};
use rawler::rawimage::{CFAConfig, RawImage, RawPhotometricInterpretation};

use crate::RawlerFotlabError;

/// Selectable demosaic algorithm exposed to Kotlin.
///
/// `Default` reproduces rawler's CFA-driven choice (PPG for Bayer-RGB,
/// bilinear-4 for 4-colour Bayer, X-Trans bilinear for X-Trans). The other
/// variants request a specific algorithm; an incompatible request falls back to
/// the CFA default.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum DemosaicAlgorithm {
  #[default]
  Default,
  Ppg,
  Bilinear4Channel,
  XTransBilinear,
}

/// Internal algorithm selection after CFA compatibility is resolved.
enum Algo {
  Ppg,
  Bilinear4,
  XTrans,
  /// Quarter-resolution 2×2 combine for a three-colour Bayer mosaic
  /// (`Superpixel3Channel`). Output is half the linear dimensions.
  Superpixel,
  /// The same combine for a four-colour mosaic (`Superpixel4Channel`).
  Superpixel4,
}

/// Debayer the scaled mosaic into a colour intermediate, applying the selected
/// demosaic algorithm, Fuji rotation and active-area crop. Mirrors the demosaic
/// block of rawler's `RawDevelop::develop_intermediate` (minus the later
/// calibrate / SRgb steps).
///
/// `downsample` swaps the resolved algorithm for rawler's superpixel debayer —
/// a quarter-resolution *demosaic*, not a resize — when the sensor supports it
/// (see [`superpixel_algo`]); otherwise it has no effect and `algo` runs at full
/// resolution. Either way the return value is one `Intermediate`, which is what
/// makes the switch invisible to the calibrate stage and beyond.
///
/// `data` is the scaled f32 pixel buffer, MOVED OUT of the `RawImage` by the
/// caller (zero-copy) so it is not duplicated: on a 50 MP frame this buffer is
/// ~210 MB and the colour intermediate it produces is ~630 MB, so a copy here
/// pushed peak RSS over the device/emulator budget and got the process
/// low-memory-killed mid-develop. `image` is still borrowed for its geometry,
/// photometric/CFA config and Fuji hints.
pub(crate) fn demosaic(
  image: &RawImage,
  data: Vec<f32>,
  algo: DemosaicAlgorithm,
  downsample: bool,
) -> Result<Intermediate, RawlerFotlabError> {
  let (w, h) = (image.width, image.height);

  // 1. Project the scaled mosaic (or pre-coloured) data into an Intermediate.
  let intermediate = match image.cpp {
    1 => Intermediate::Monochrome(PixF32::new_with(data, w, h)),
    3 => {
      let d: Vec<[f32; 3]> = data.chunks_exact(3).map(|c| [c[0], c[1], c[2]]).collect();
      Intermediate::ThreeColor(Color2D::<f32, 3>::new_with(d, w, h))
    }
    4 => {
      let d: Vec<[f32; 4]> = data.chunks_exact(4).map(|c| [c[0], c[1], c[2], c[3]]).collect();
      Intermediate::FourColor(Color2D::<f32, 4>::new_with(d, w, h))
    }
    other => return Err(RawlerFotlabError::Decode(format!("unsupported cpp: {other}"))),
  };

  // 2. Demosaic only the single-channel (CFA) case, exactly like rawler.
  if let RawPhotometricInterpretation::Cfa(config) = &image.photometric {
    if let Intermediate::Monochrome(pixels) = &intermediate {
      let roi = if image.active_area.is_some() && image.fuji_rotation_width.is_some() {
        // rawler panics here; keep the same guard.
        return Err(RawlerFotlabError::Decode(
          "ActiveArea not possible when rotation is not normalized".to_string(),
        ));
      } else if let Some(area) = image.active_area {
        area
      } else {
        pixels.rect()
      };

      // The downsampling switch takes precedence over the algorithm selection, so that the
      // drawer's preference and the Demosaic dropdown stay independent knobs: with the switch
      // ON the image is quarter-resolution; with it OFF the picked algorithm runs. A sensor that
      // cannot use superpixel keeps the picked algorithm — the switch degrades, it never fails.
      // (Pre-coloured input never reaches here: it has no CFA and is returned untouched below.)
      let chosen = match downsample.then(|| superpixel_algo(image, config)).flatten() {
        Some(superpixel) => superpixel,
        None => effective_algorithm(config, algo),
      };
      let next = match chosen {
        Algo::Ppg => {
          let rgb = PPGDemosaic::new().demosaic(pixels, &config.cfa, &config.colors, roi);
          Intermediate::ThreeColor(fuji_rotate_if_needed(rgb, image))
        }
        Algo::Bilinear4 => {
          let rgb = Bilinear4Channel::new().demosaic(pixels, &config.cfa, &config.colors, roi);
          Intermediate::FourColor(rgb)
        }
        Algo::XTrans => {
          let rgb = XTransBilinearDemosaic::new().demosaic(pixels, &config.cfa, &config.colors, roi);
          Intermediate::ThreeColor(fuji_rotate_if_needed(rgb, image))
        }
        // Superpixel needs no Fuji rotation here: `superpixel_algo` refuses Fuji-rotated
        // sensors, because `rotate_45cw` mixes the absolute `fuji_rotation_width` with the
        // source width and the source is half-scale on this path.
        Algo::Superpixel => {
          let rgb = Superpixel3Channel::new().demosaic(pixels, &config.cfa, &config.colors, roi);
          Intermediate::ThreeColor(rgb)
        }
        Algo::Superpixel4 => {
          let rgb = Superpixel4Channel::new().demosaic(pixels, &config.cfa, &config.colors, roi);
          Intermediate::FourColor(rgb)
        }
      };
      return Ok(next);
    }
  }

  Ok(intermediate)
}

/// Resolve the superpixel (quarter-resolution) variant for this sensor, or `None` when the
/// quarter-resolution path cannot run and the caller must fall back to a full-resolution
/// algorithm.
///
/// The guards are hard requirements of the upstream primitives, not policy:
/// * `Superpixel3Channel` matches the CFA **name** against the four RGGB-family patterns and
///   `unreachable!()`s on anything else — so a three-colour X-Trans name (and any malformed 2×2
///   string) must never reach it.
/// * `Superpixel4Channel` requires four colour planes and panics otherwise.
/// * A Fuji-rotated sensor is excluded because the rotation step is a *later* stage of this same
///   pipeline and mixes the absolute `fuji_rotation_width` with the source width — which is
///   half-scale on this path, so the rotated crop would be wrong.
fn superpixel_algo(image: &RawImage, config: &CFAConfig) -> Option<Algo> {
  if config.sensor != SensorType::Bayer || image.fuji_rotation_width.is_some() {
    return None;
  }
  match config.colors.plane_count() {
    3 if matches!(config.cfa.name.as_str(), "RGGB" | "BGGR" | "GBRG" | "GRBG") => Some(Algo::Superpixel),
    4 => Some(Algo::Superpixel4),
    _ => None,
  }
}

/// Whether this decoded image can be developed at quarter resolution — the question the Studio
/// drawer's downsampling switch asks before offering itself. Same guard as [`superpixel_algo`], so
/// the answer and the pipeline's behaviour cannot drift apart: `false` also covers pre-coloured
/// (non-CFA) input, which has no mosaic to combine and is passed through untouched.
pub(crate) fn supports_downsample(image: &RawImage) -> bool {
  match &image.photometric {
    RawPhotometricInterpretation::Cfa(config) => superpixel_algo(image, config).is_some(),
    _ => false,
  }
}

/// Resolve a user selection against the sensor CFA, falling back to the
/// CFA-appropriate default when the request is incompatible.
fn effective_algorithm(config: &CFAConfig, algo: DemosaicAlgorithm) -> Algo {
  let is_bayer = config.sensor == SensorType::Bayer;
  let is_xtrans = config.sensor == SensorType::Xtrans;
  let four_color = config.cfa.unique_colors() == 4;

  match algo {
    DemosaicAlgorithm::Default => {
      if four_color && is_bayer {
        Algo::Bilinear4
      } else if is_xtrans {
        Algo::XTrans
      } else {
        Algo::Ppg
      }
    }
    DemosaicAlgorithm::Ppg => Algo::Ppg,
    DemosaicAlgorithm::Bilinear4Channel => {
      if four_color && is_bayer {
        Algo::Bilinear4
      } else {
        Algo::Ppg
      }
    }
    DemosaicAlgorithm::XTransBilinear => {
      if is_xtrans {
        Algo::XTrans
      } else {
        Algo::Ppg
      }
    }
  }
}

/// Apply Fuji 45° sensor rotation when `fuji_rotation_width` is set.
fn fuji_rotate_if_needed(rgb: Color2D<f32, 3>, image: &RawImage) -> Color2D<f32, 3> {
  if let Some(fuji_rotation_width) = image.fuji_rotation_width {
    let extra_rotate = image.camera.find_hint("fuji_rotate_90cw");
    fuji_normalize_rotation(&rgb, fuji_rotation_width, extra_rotate)
  } else {
    rgb
  }
}

// ---------------------------------------------------------------------------
// Fuji rotation (replicated from rawler's `imgop/fuji_rotate.rs`; it is
// `pub(crate)` so we cannot call it from this crate).
// ---------------------------------------------------------------------------

/// Rotates a Fujifilm sensor image by 45° CW to correct the 45° CCW rotation of
/// the Super CCD / X-Trans sensor layout.
fn fuji_normalize_rotation(src: &Color2D<f32, 3>, fuji_rotation_width: usize, extra_rotate: bool) -> Color2D<f32, 3> {
  let src = if extra_rotate {
    &src.rotate_90cw()
  } else {
    src
  };
  rotate_45cw(src, fuji_rotation_width)
}

/// Calculate the final image dimension after correcting rotation.
fn fuji_calc_dimension(width: usize, fuji_rotation_width: usize) -> Dim2 {
  let t = fuji_rotation_width as f64;
  let s = width as f64;
  let crop_w = (t * std::f64::consts::SQRT_2).floor() as usize;
  let crop_h = ((s - t) * std::f64::consts::SQRT_2).floor() as usize;
  if crop_w > crop_h {
    Dim2::new(crop_w, crop_h)
  } else {
    Dim2::new(crop_h, crop_w) // Fuji rotate_alt
  }
}

/// Rotate a Color2D<f32, 3> image 45 degrees clockwise using bilinear interpolation,
/// and crop to the inscribed rectangle.
fn rotate_45cw(src: &Color2D<f32, 3>, fuji_rotation_width: usize) -> Color2D<f32, 3> {
  let src_w = src.width;
  let src_h = src.height;
  let inv_sqrt2: f64 = std::f64::consts::FRAC_1_SQRT_2;

  let src_cx = src_w as f64 / 2.0;
  let src_cy = src_h as f64 / 2.0;

  let Dim2 { w: crop_w, h: crop_h } = fuji_calc_dimension(src_w, fuji_rotation_width);

  let crop_cx = crop_w as f64 / 2.0;
  let crop_cy = crop_h as f64 / 2.0;

  let mut dst = Color2D::<f32, 3>::new(crop_w, crop_h);

  for row in 0..crop_h {
    for col in 0..crop_w {
      let dx = col as f64 - crop_cx;
      let dy = row as f64 - crop_cy;

      let src_x = (dx + dy) * inv_sqrt2 + src_cx;
      let src_y = (dy - dx) * inv_sqrt2 + src_cy;

      let sx = src_x.round() as isize;
      let sy = src_y.round() as isize;
      if sx < 0 || sx >= src_w as isize || sy < 0 || sy >= src_h as isize {
        continue;
      }

      let x0 = src_x.floor() as isize;
      let y0 = src_y.floor() as isize;
      let x1 = x0 + 1;
      let y1 = y0 + 1;

      let pixel = dst.at_mut(row, col);
      if x0 < 0 || y0 < 0 || x1 >= src_w as isize || y1 >= src_h as isize {
        *pixel = *src.at(sy as usize, sx as usize);
      } else {
        let fx = (src_x - x0 as f64) as f32;
        let fy = (src_y - y0 as f64) as f32;

        let p00 = src.at(y0 as usize, x0 as usize);
        let p10 = src.at(y0 as usize, x1 as usize);
        let p01 = src.at(y1 as usize, x0 as usize);
        let p11 = src.at(y1 as usize, x1 as usize);

        for ch in 0..3 {
          pixel[ch] = p00[ch] * (1.0 - fx) * (1.0 - fy) + p10[ch] * fx * (1.0 - fy) + p01[ch] * (1.0 - fx) * fy + p11[ch] * fx * fy;
        }
      }
    }
  }

  dst
}
