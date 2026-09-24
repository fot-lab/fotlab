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
//!
//! # Three producers, one `Intermediate`
//!
//! The stage can be driven by any of three kinds of producer, and nothing
//! downstream can tell which ran (`FOTLAB-NATIVE-000004` R2/R3):
//!
//! * rawler's own Bayer/X-Trans demosaics — [`RawlerAlgo`];
//! * rawler's superpixel combine, which is a *demosaic*, not a resize, and
//!   returns a half-linear-size intermediate;
//! * the RawTherapee kernels ported in `rawtrp_demos` — [`Algo::RawtrpBayer`],
//!   driven through `rawtrp_demos::bridge`, which hands back the very same
//!   `Intermediate::ThreeColor` calibrate consumes.
//!
//! The third one is the odd one out mechanically: ported kernels are not
//! `rawler::imgop::sensor::Demosaic` impls. They read the mosaic as an `Array2D`
//! plus a `CfaDesc` and own their border handling, so [`run_rawtrp_bayer`]
//! materialises the ROI mosaic and shifts the CFA to the ROI origin instead of
//! handing over a `Pix2D`/`CFA` pair. The user-visible choice still comes from one
//! place: [`crate::demosaic_candidates`] is the concatenated RAWLER ⧺ RAWTRP list,
//! and [`DemosaicAlgorithm`] only transports it.

use rawler::imgop::develop::Intermediate;
use rawler::imgop::sensor::bayer::{
  bilinear::Bilinear4Channel,
  ppg::PPGDemosaic,
  superpixel::{Superpixel3Channel, Superpixel4Channel},
};
use rawler::imgop::sensor::xtrans::bilinear::XTransBilinearDemosaic;
use rawler::imgop::sensor::{Demosaic as RawlerDemosaic, SensorType};
use rawler::imgop::{Dim2, Rect};
use rawler::pixarray::{Color2D, PixF32};
use rawler::rawimage::{CFAConfig, RawImage, RawPhotometricInterpretation};
use rawler::CFA;

use crate::RawlerFotlabError;

/// Selectable demosaic algorithm exposed to Kotlin.
///
/// `Default` reproduces rawler's CFA-driven choice (PPG for Bayer-RGB,
/// bilinear-4 for 4-colour Bayer, X-Trans bilinear for X-Trans). The other
/// variants request a specific algorithm; an incompatible request falls back to
/// the CFA default.
///
/// # Two families, one enum (list concatenation)
///
/// The variants below the rawler four are the **RAWTRP** half: the first-party
/// RawTherapee kernels ported in `rawtrp_demos`. They are appended, never
/// interleaved — rawler's four keep their positions so an existing selection
/// keeps its meaning — and one variant is appended per ported kernel as its arm
/// lands (`FOTLAB-NATIVE-000004` D5/C6, `rawtrp_demos::algo`).
///
/// The list the UI shows is **not** written here: it comes from
/// [`crate::demosaic_candidates`], which reads `rawtrp_demos::algo::candidates()` (the
/// concatenation of the two decoupling dictionaries, filtered to the kernels
/// that are actually wired). This enum is only the *transport* for the choice —
/// the two must agree, and
/// `demosaic_candidates_maps_every_advertised_id_to_a_variant` pins that.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum DemosaicAlgorithm {
  #[default]
  Default,
  Ppg,
  Bilinear4Channel,
  XTransBilinear,
  // ---- RAWTRP — appended in the order the kernels were ported ----
  /// `bilinear` — RawTherapee's plain bilinear debayer.
  RawtrpBilinear,
  /// `vng4` — Variable Number of Gradients.
  RawtrpVng4,
  /// `rcd` — Ratio Corrected Demosaicing.
  RawtrpRcd,
  /// `igv` — Improved Green and Variance.
  RawtrpIgv,
  /// `lmmse` — Linear Minimum Mean Square Error.
  RawtrpLmmse,
  /// `dcb` — Directional Cubic-spline Bayer.
  RawtrpDcb,
  /// `hphd` — High Pass Horizontal/Vertical Direction.
  RawtrpHphd,
  /// `ahd` — Adaptive Homogeneity-Directed.
  ///
  /// Ported and dispatchable, but intentionally not advertised: the kernel needs
  /// the camera's colour matrix, so `rawtrp_demos::algo::IMPLEMENTED_BAYER`
  /// leaves `"ahd"` out and the UI never offers the id. Nothing here changes when
  /// it is wired back up (`FOTLAB-NATIVE-000004` rev 12).
  RawtrpAhd,
  /// `amaze` — Aliasing Minimization and Zipper Elimination.
  ///
  /// Advertised: unlike AHD/EAHD it reads no camera colour matrix, only the
  /// scalar `1 / initialGain` highlight threshold (`FOTLAB-NATIVE-000004` rev 12).
  RawtrpAmaze,
  /// `fast` — Emil Martinec's fast Bayer demosaic.
  RawtrpFast,
  /// X-Trans Markesteijn 1-pass (`xtrans_interpolate(1, false)`).
  RawtrpXTransOnePass,
  /// X-Trans fast (`fast_xtrans_interpolate`).
  RawtrpXTransFast,
}

impl DemosaicAlgorithm {
  /// The RAWTRP kernel this variant selects, or `None` for a rawler variant.
  ///
  /// A kernel that `rawtrp_demos` catalogues but has not ported yet has no
  /// variant, so it can never be selected — the same invariant
  /// `rawtrp_demos::algo::IMPLEMENTED_*` enforces on the menu
  /// (`FOTLAB-NATIVE-000004` C6).
  fn rawtrp_bayer(self) -> Option<rawtrp_demos::BayerAlgo> {
    Some(match self {
      Self::RawtrpBilinear => rawtrp_demos::BayerAlgo::Bilinear,
      Self::RawtrpVng4 => rawtrp_demos::BayerAlgo::Vng4,
      Self::RawtrpRcd => rawtrp_demos::BayerAlgo::Rcd,
      Self::RawtrpIgv => rawtrp_demos::BayerAlgo::Igv,
      Self::RawtrpLmmse => rawtrp_demos::BayerAlgo::Lmmse,
      Self::RawtrpDcb => rawtrp_demos::BayerAlgo::Dcb,
      Self::RawtrpHphd => rawtrp_demos::BayerAlgo::Hphd,
      Self::RawtrpAhd => rawtrp_demos::BayerAlgo::Ahd,
      Self::RawtrpAmaze => rawtrp_demos::BayerAlgo::Amaze,
      Self::RawtrpFast => rawtrp_demos::BayerAlgo::Fast,
      _ => return None,
    })
  }

  /// The RAWTRP X-Trans kernel this variant selects, or `None` for the rest.
  ///
  /// As [`Self::rawtrp_bayer`]: `three_pass`/`four_pass` stay parked (colour
  /// matrix; rev 12) and the dual hybrids stay unported, so they have no
  /// variant and can never be selected.
  fn rawtrp_xtrans(self) -> Option<rawtrp_demos::XTransAlgo> {
    Some(match self {
      Self::RawtrpXTransOnePass => rawtrp_demos::XTransAlgo::OnePass,
      Self::RawtrpXTransFast => rawtrp_demos::XTransAlgo::Fast,
      _ => return None,
    })
  }

  /// The RAWTRP variant wrapping `algo`, or `None` while that kernel is
  /// unported — used by [`crate::demosaic_candidates`] to keep an advertised id
  /// and a dispatchable variant in step.
  pub(crate) fn from_rawtrp_bayer(algo: rawtrp_demos::BayerAlgo) -> Option<Self> {
    Some(match algo {
      rawtrp_demos::BayerAlgo::Bilinear => Self::RawtrpBilinear,
      rawtrp_demos::BayerAlgo::Vng4 => Self::RawtrpVng4,
      rawtrp_demos::BayerAlgo::Rcd => Self::RawtrpRcd,
      rawtrp_demos::BayerAlgo::Igv => Self::RawtrpIgv,
      rawtrp_demos::BayerAlgo::Lmmse => Self::RawtrpLmmse,
      rawtrp_demos::BayerAlgo::Dcb => Self::RawtrpDcb,
      rawtrp_demos::BayerAlgo::Hphd => Self::RawtrpHphd,
      rawtrp_demos::BayerAlgo::Ahd => Self::RawtrpAhd,
      rawtrp_demos::BayerAlgo::Amaze => Self::RawtrpAmaze,
      rawtrp_demos::BayerAlgo::Fast => Self::RawtrpFast,
      _ => return None,
    })
  }

  /// As [`Self::from_rawtrp_bayer`], for the X-Trans half.
  pub(crate) fn from_rawtrp_xtrans(algo: rawtrp_demos::XTransAlgo) -> Option<Self> {
    Some(match algo {
      rawtrp_demos::XTransAlgo::OnePass => Self::RawtrpXTransOnePass,
      rawtrp_demos::XTransAlgo::Fast => Self::RawtrpXTransFast,
      _ => return None,
    })
  }
}

/// rawler's own demosaic implementations — the producers reached through the
/// `rawler::imgop::sensor::Demosaic` trait.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum RawlerAlgo {
  Ppg,
  Bilinear4,
  XTrans,
  /// Quarter-resolution 2×2 combine for a three-colour Bayer mosaic
  /// (`Superpixel3Channel`). Output is half the linear dimensions.
  Superpixel,
  /// The same combine for a four-colour mosaic (`Superpixel4Channel`).
  Superpixel4,
}

/// Internal algorithm selection after CFA compatibility is resolved.
///
/// The variants are *different kinds of producer*, not settings of one:
/// `Rawler` arms are `Demosaic` impls that take a `Pix2D` + `CFA` + ROI, while
/// the `Rawtrp*` arms are kernels ported in `rawtrp_demos` that take
/// an `Array2D` + `CfaDesc` and bring their own border handling. Keeping them
/// apart is what makes the RAWTRP fallback total: [`cfa_default_algo`] can only
/// return a `RawlerAlgo`, so the "degrade to the CFA default" path has no
/// unreachable case to paper over.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Algo {
  Rawler(RawlerAlgo),
  /// A Bayer kernel ported in `rawtrp_demos` (`FOTLAB-NATIVE-000004`).
  RawtrpBayer(rawtrp_demos::BayerAlgo),
  /// An X-Trans kernel ported in `rawtrp_demos` (`FOTLAB-NATIVE-000004` B4).
  RawtrpXTrans(rawtrp_demos::XTransAlgo),
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
        Some(superpixel) => Algo::Rawler(superpixel),
        None => effective_algorithm(config, algo),
      };
      let next = match chosen {
        Algo::Rawler(rawler) => run_rawler_algo(rawler, pixels, config, roi, image),
        Algo::RawtrpBayer(kernel) => run_rawtrp_bayer(kernel, pixels, config, roi, image),
        Algo::RawtrpXTrans(kernel) => run_rawtrp_xtrans(kernel, pixels, config, roi, image),
      };
      return Ok(next);
    }
  }

  Ok(intermediate)
}

/// Run one of rawler's own demosaic implementations over `roi`.
///
/// Every arm returns an `Intermediate` shaped like `roi` — the convergence point
/// of the whole stage: `calibrate` and everything after it see only the
/// intermediate's dimensions, never which producer wrote it.
fn run_rawler_algo(chosen: RawlerAlgo, pixels: &PixF32, config: &CFAConfig, roi: Rect, image: &RawImage) -> Intermediate {
  match chosen {
    RawlerAlgo::Ppg => {
      let rgb = PPGDemosaic::new().demosaic(pixels, &config.cfa, &config.colors, roi);
      Intermediate::ThreeColor(fuji_rotate_if_needed(rgb, image))
    }
    RawlerAlgo::Bilinear4 => {
      let rgb = Bilinear4Channel::new().demosaic(pixels, &config.cfa, &config.colors, roi);
      Intermediate::FourColor(rgb)
    }
    RawlerAlgo::XTrans => {
      let rgb = XTransBilinearDemosaic::new().demosaic(pixels, &config.cfa, &config.colors, roi);
      Intermediate::ThreeColor(fuji_rotate_if_needed(rgb, image))
    }
    // Superpixel needs no Fuji rotation here: `superpixel_algo` refuses Fuji-rotated
    // sensors, because `rotate_45cw` mixes the absolute `fuji_rotation_width` with the
    // source width and the source is half-scale on this path.
    RawlerAlgo::Superpixel => {
      let rgb = Superpixel3Channel::new().demosaic(pixels, &config.cfa, &config.colors, roi);
      Intermediate::ThreeColor(rgb)
    }
    RawlerAlgo::Superpixel4 => {
      let rgb = Superpixel4Channel::new().demosaic(pixels, &config.cfa, &config.colors, roi);
      Intermediate::FourColor(rgb)
    }
  }
}

/// Run a kernel ported in `rawtrp_demos` over `roi`.
///
/// Ported kernels are **not** rawler `Demosaic` impls: they take the mosaic as an
/// `Array2D<f32>` plus a `CfaDesc` (`RAWTRP-DECODE-000003` §3.1) and demosaic
/// inside their own borders, so the mosaic is materialised for the ROI and the
/// pattern is shifted to the ROI origin — the same `cfa.shift(roi)` step every
/// rawler demosaic performs (`ppg.rs:46`, `superpixel.rs:40`, `xtrans/bilinear.rs:73`).
///
/// The ROI is the very same `active_area`-or-full-rect the rawler arms get, so
/// the result is ROI-sized and this stage's output contract is unchanged; Fuji
/// rotation is applied here exactly as it is on the PPG arm (`FOTLAB-NATIVE-000004` D4).
/// The camera → normalised-XYZ matrix AHD and EAHD judge homogeneity in.
///
/// RawTherapee derives it from `imatrices.rgb_cam`, which is per-image data this
/// binding is the only place that can reach; `rawler` carries the camera's
/// `xyz_to_cam` and will invert and normalise it into the same convention (a
/// neutral triple maps to XYZ (1,1,1)), which is why this is threaded rather
/// than left at the sRGB default.
///
/// It is still guarded, because the inversion is only as good as the tag that fed
/// it: a camera with no colour matrix — or a degenerate one — can invert to
/// something non-finite or all-zero, and a Lab plane built from that would make
/// every pixel equally homogeneous and silently turn AHD into "the mean of both
/// directions". Falling back to `XYZ_CAM_FROM_SRGB` costs directional accuracy
/// and nothing else.
fn xyz_cam_for(image: &RawImage) -> [[f32; 3]; 3] {
  let m = image.cam_to_xyz_normalized();
  let mut out = [[0.0f32; 3]; 3];
  for (row, src) in out.iter_mut().zip(m.iter()) {
    row.copy_from_slice(&src[..3]);
  }
  let usable = out.iter().flatten().all(|v| v.is_finite()) && out.iter().flatten().any(|v| *v != 0.0);
  if usable {
    out
  } else {
    log::warn!("no usable camera colour matrix for this RAW; AHD/EAHD will assume sRGB");
    rawtrp_demos::XYZ_CAM_FROM_SRGB
  }
}

fn run_rawtrp_bayer(kernel: rawtrp_demos::BayerAlgo, pixels: &PixF32, config: &CFAConfig, roi: Rect, image: &RawImage) -> Intermediate {
  // `effective_algorithm` only routes a three-colour 2x2 Bayer CFA here, so this
  // is `Some` for every request that arrives. It is still handled rather than
  // unwrapped because the two guards are written in different terms — the
  // resolver tests `unique_colors() == 4`, this one tests "the 2x2 tile is all
  // R/G/B" — and a future divergence should degrade, not panic across the FFI.
  let Some(cfa) = bayer_cfa_desc(&config.cfa, roi) else {
    log::warn!(
      "RAWTRP {} needs a 2x2 R/G/B CFA, but '{}' has none; using the CFA default instead",
      kernel.original_name(),
      config.cfa.name
    );
    return run_rawler_algo(cfa_default_algo(config), pixels, config, roi, image);
  };

  // RawTherapee's own defaults — `dcb_iterations = 2`, `dcb_enhance = true`,
  // `lmmse_iterations = 2` (`rtengine/params/raw.cc:87`). Studio has no drawer
  // for these three yet, so a RAWTRP DCB/LMMSE pick runs RT's defaults; this is
  // the single place to inject them once it does (`FOTLAB-NATIVE-000004` D4).
  let mut params = rawtrp_demos::BayerParams::default();
  // The camera's colour matrix is per-image data, not a knob, so it comes from
  // the decoded RAW rather than from a default — see `xyz_cam_for`.
  params.xyz_cam = xyz_cam_for(image);
  let mosaic = mosaic_from_roi(pixels, roi);

  match rawtrp_demos::bridge::demosaic_bayer_to_intermediate(kernel, &cfa, &mosaic, &params) {
    Ok(Intermediate::ThreeColor(rgb)) => Intermediate::ThreeColor(fuji_rotate_if_needed(rgb, image)),
    // Every ported Bayer kernel is three-colour by construction; a future
    // four-colour one would come back as `FourColor` and needs no rotation.
    Ok(other) => other,
    // A rawler `Demosaic` impl cannot report failure — it panics instead — while
    // ours returns `Err` (degenerate geometry, a CFA the kernel refuses). Degrade
    // rather than failing the whole develop, and say so, because the frame the
    // user then gets is *not* the kernel they picked.
    Err(e) => {
      log::warn!("RAWTRP {} failed ({e}); using the CFA default instead", kernel.original_name());
      run_rawler_algo(cfa_default_algo(config), pixels, config, roi, image)
    }
  }
}

/// Build the `CfaDesc` for an X-Trans 6x6 CFA **as seen from `roi`**, or `None`
/// when the pattern is not a 6x6 R/G/B X-Trans tile.
///
/// The `shift` matters for exactly the same reason as in [`bayer_cfa_desc`]:
/// the kernels ask `CfaDesc::xtrans_color(row, col)` about the mosaic's origin,
/// so the 6x6 must be the one at the ROI's top-left. (X-Trans sensors are
/// typically full-frame ROIs, but an active area is not required to start on a
/// multiple of 6, and rawler's own X-Trans demosaic shifts the same way,
/// `xtrans/bilinear.rs:73`.)
fn xtrans_cfa_desc(cfa: &CFA, roi: Rect) -> Option<rawtrp_demos::CfaDesc> {
  if cfa.width != 6 || cfa.height != 6 {
    return None;
  }

  let shifted = cfa.shift(roi.p.x, roi.p.y);
  let mut pattern = [[0u8; 6]; 6];
  for (row, line) in pattern.iter_mut().enumerate() {
    for (col, cell) in line.iter_mut().enumerate() {
      let color = shifted.color_at(row, col);
      if color > 2 {
        return None;
      }
      *cell = color as u8;
    }
  }

  Some(rawtrp_demos::CfaDesc::xtrans_from_6x6(pattern))
}

/// Run an X-Trans kernel ported in `rawtrp_demos` over `roi` — the X-Trans
/// mirror of [`run_rawtrp_bayer`] (same ROI materialisation, same Fuji
/// rotation, same degrade-not-fail contract).
fn run_rawtrp_xtrans(kernel: rawtrp_demos::XTransAlgo, pixels: &PixF32, config: &CFAConfig, roi: Rect, image: &RawImage) -> Intermediate {
  let Some(cfa) = xtrans_cfa_desc(&config.cfa, roi) else {
    log::warn!(
      "RAWTRP {} needs a 6x6 R/G/B X-Trans CFA, but '{}' has none; using the CFA default instead",
      kernel.original_name(),
      config.cfa.name
    );
    return run_rawler_algo(cfa_default_algo(config), pixels, config, roi, image);
  };

  // Neither ported X-Trans kernel reads a parameter yet (`dual_contrast`
  // belongs to the two_pass/four_pass hybrids, which are not ported).
  let params = rawtrp_demos::XTransParams::default();
  let mosaic = mosaic_from_roi(pixels, roi);

  match rawtrp_demos::bridge::demosaic_xtrans_to_intermediate(kernel, &cfa, &mosaic, &params) {
    Ok(Intermediate::ThreeColor(rgb)) => Intermediate::ThreeColor(fuji_rotate_if_needed(rgb, image)),
    Ok(other) => other,
    Err(e) => {
      log::warn!("RAWTRP {} failed ({e}); using the CFA default instead", kernel.original_name());
      run_rawler_algo(cfa_default_algo(config), pixels, config, roi, image)
    }
  }
}

/// Materialise the `roi` of the scaled mosaic as the `Array2D<f32>` the ported
/// kernels take.
///
/// Unlike the rawler arms, which read `pixels` in place, this copies the ROI
/// (`w * h * 4` bytes). That is the only allocation the RAWTRP path adds; it is
/// dwarfed by the kernels' own buffers and by the colour intermediate, and the
/// full-resolution `pixels` is released as soon as this stage returns.
fn mosaic_from_roi(pixels: &PixF32, roi: Rect) -> rawtrp_demos::Array2D<f32> {
  let mut mosaic = rawtrp_demos::Array2D::new(roi.d.w, roi.d.h);
  for row in 0..roi.d.h {
    let dst = mosaic.row_mut(row);
    for col in 0..roi.d.w {
      dst[col] = *pixels.at(roi.p.y + row, roi.p.x + col);
    }
  }
  mosaic
}

/// Build the `CfaDesc` for a 2x2 Bayer CFA **as seen from `roi`**, or `None` when
/// the pattern has no R/G/B-only 2x2 tile (an X-Trans 6x6, or a four-colour CFA
/// such as RGBE whose fourth level is not expressible as R/G/B).
///
/// The `shift` is the load-bearing part. A ported kernel asks
/// `CfaDesc::fc(row, col)` about *the mosaic's* origin, so the pattern must be
/// the one at the ROI's top-left, not the sensor's. Every rawler demosaic does
/// the same shift; dropping it silently swaps the red and blue planes on any
/// odd-offset active area — a "looks fine, is wrong" failure rather than a crash.
pub(crate) fn bayer_cfa_desc(cfa: &CFA, roi: Rect) -> Option<rawtrp_demos::CfaDesc> {
  if cfa.width != 2 || cfa.height != 2 {
    return None;
  }

  let shifted = cfa.shift(roi.p.x, roi.p.y);
  let mut pattern = [[0u8; 2]; 2];
  for (row, line) in pattern.iter_mut().enumerate() {
    for (col, cell) in line.iter_mut().enumerate() {
      // rawler's `CFAColor` is dcraw's numbering — R=0, G=1, B=2, then the
      // exotic levels (C/M/Y/E). Anything above 2 is not a Bayer RGB tile.
      let color = shifted.color_at(row, col);
      if color > 2 {
        return None;
      }
      *cell = color as u8;
    }
  }

  Some(rawtrp_demos::CfaDesc::bayer_from_2x2(pattern))
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
fn superpixel_algo(image: &RawImage, config: &CFAConfig) -> Option<RawlerAlgo> {
  if config.sensor != SensorType::Bayer || image.fuji_rotation_width.is_some() {
    return None;
  }
  match config.colors.plane_count() {
    3 if matches!(config.cfa.name.as_str(), "RGGB" | "BGGR" | "GBRG" | "GRBG") => Some(RawlerAlgo::Superpixel),
    4 => Some(RawlerAlgo::Superpixel4),
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
    DemosaicAlgorithm::Default => Algo::Rawler(cfa_default_algo(config)),
    DemosaicAlgorithm::Ppg => Algo::Rawler(RawlerAlgo::Ppg),
    DemosaicAlgorithm::Bilinear4Channel => {
      if four_color && is_bayer {
        Algo::Rawler(RawlerAlgo::Bilinear4)
      } else {
        Algo::Rawler(RawlerAlgo::Ppg)
      }
    }
    DemosaicAlgorithm::XTransBilinear => {
      if is_xtrans {
        Algo::Rawler(RawlerAlgo::XTrans)
      } else {
        Algo::Rawler(RawlerAlgo::Ppg)
      }
    }
    // RAWTRP Bayer kernels are RawTherapee's Bayer debayers: a three-colour
    // 2x2 CFA is their whole input contract. Upstream RT falls back to IGV on
    // a four-colour CFA, but its IGV indexes `rgb[3]` for one — that fallback
    // is not a usable path and our port refuses the CFA instead
    // (`rawtrp_demos::algo`, `FOTLAB-NATIVE-000004` rev 7 item 7). The RAWTRP
    // X-Trans kernels mirror this with their 6x6 contract. So a mismatched
    // CFA, the wrong sensor family, or a catalogued-but-unported kernel all
    // resolve to the CFA default — the same treatment an incompatible rawler
    // pick gets.
    other => {
      if let Some(kernel) = other.rawtrp_bayer() {
        if is_bayer && !four_color {
          return Algo::RawtrpBayer(kernel);
        }
      }
      if let Some(kernel) = other.rawtrp_xtrans() {
        if is_xtrans {
          return Algo::RawtrpXTrans(kernel);
        }
      }
      Algo::Rawler(cfa_default_algo(config))
    }
  }
}

/// Rawler's CFA-appropriate default — what [`DemosaicAlgorithm::Default`] means
/// and what every incompatible request falls back to.
///
/// The branch order is the one this had before the RAWTRP variants existed, so
/// the `DEFAULT` path stays pixel-identical (`FOTLAB-NATIVE-000004` B1
/// acceptance: "`DEFAULT` 逐像素不变").
fn cfa_default_algo(config: &CFAConfig) -> RawlerAlgo {
  let is_bayer = config.sensor == SensorType::Bayer;
  let is_xtrans = config.sensor == SensorType::Xtrans;
  let four_color = config.cfa.unique_colors() == 4;

  if four_color && is_bayer {
    RawlerAlgo::Bilinear4
  } else if is_xtrans {
    RawlerAlgo::XTrans
  } else {
    RawlerAlgo::Ppg
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
