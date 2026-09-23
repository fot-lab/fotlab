//! `rawtrp_demos` — first-party pure-Rust port of the RawTherapee demosaic kernels.
//!
//! One module per algorithm, each ported from a named upstream file (see the file
//! header) and parallelised with rayon at the **same** sharding points where
//! RawTherapee used `#pragma omp for` over rows.
//!
//! The crate takes the minimal contract established in
//! `rules/STRUCT/detail/RAWTRP-DECODE-000003.md` §3.1 — **an array plus a CFA
//! description** — so it needs no `RawImageSource`, no `RawImage`, and no C++:
//!
//! ```text
//!   mosaic: &Array2D<f32>  (w x h, single channel, 0..1 linear)  ┐
//!   cfa:    &CfaDesc       (filters u32 / xtrans 6x6)            ┘ -> Rgb { red, green, blue }
//! ```
//!
//! and [`bridge::to_intermediate`] hands the result back as the exact
//! `rawler::imgop::develop::Intermediate::ThreeColor` the rest of the develop
//! pipeline already consumes, so *calibrate and everything after it are
//! unchanged* (`rules/DESIGN/detail/FOTLAB-NATIVE-000004.md` R2/R3).
//!
//! ## Selecting an algorithm
//!
//! [`demosaic_bayer`] / [`demosaic_xtrans`] take the ported-algorithm enums from
//! [`algo`]. The UI never builds a list by hand: [`algo::candidates`] returns the
//! concatenation of the two decoupling dictionaries (RAWLER originals wrapped as
//! `RAWLER …`, ported kernels kept under their upstream names as `RAWTRP …`).
//!
//! Algorithms that are not yet ported return [`Error::UnsupportedAlgo`] and are
//! **not** advertised by [`algo::candidates`], so the UI can never offer a path
//! that would fail (`algo::IMPLEMENTED_BAYER` / `IMPLEMENTED_XTRANS`).

pub mod algo;
pub mod array2d;
pub mod bayer;
pub mod border;
pub mod bridge;
pub mod cfa;
pub mod math;
pub mod xtrans;

pub use algo::{candidates, BayerAlgo, Candidate, SensorKind, XTransAlgo};
pub use array2d::Array2D;
pub use cfa::CfaDesc;

/// Errors a demosaic entry point can return.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
  /// The requested algorithm exists in the catalogue but its kernel is not
  /// ported yet. Never returned for an algorithm [`candidates`] advertises.
  UnsupportedAlgo(&'static str),
  /// The mosaic's CFA is outside what this kernel supports; the caller must fall
  /// back (upstream does the same, e.g. VNG4 -> IGV for a 4-colour CFA).
  UnsupportedCfa(&'static str),
  /// Geometry mismatch between the mosaic and the CFA / output planes.
  Shape(String),
}

impl core::fmt::Display for Error {
  fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
    match self {
      Self::UnsupportedAlgo(a) => write!(f, "demosaic algorithm not ported yet: {a}"),
      Self::UnsupportedCfa(a) => write!(f, "CFA not supported by {a}"),
      Self::Shape(m) => write!(f, "shape error: {m}"),
    }
  }
}

impl std::error::Error for Error {}

/// The three demosaiced colour planes, each `width x height`, `0..1` linear —
/// RawTherapee's `red`/`green`/`blue` `array2D<float>`s.
#[derive(Clone, Debug, PartialEq)]
pub struct Rgb {
  pub red: Array2D<f32>,
  pub green: Array2D<f32>,
  pub blue: Array2D<f32>,
}

impl Rgb {
  /// Allocate three zeroed planes of `width x height`.
  #[must_use]
  pub fn new(width: usize, height: usize) -> Self {
    Self {
      red: Array2D::new(width, height),
      green: Array2D::new(width, height),
      blue: Array2D::new(width, height),
    }
  }

  /// Planes as `(width, height)`.
  #[must_use]
  pub fn dims(&self) -> (usize, usize) {
    (self.red.width(), self.red.height())
  }
}

/// Camera → normalised-XYZ, for the kernels that judge pixels in CIELab.
///
/// Upstream builds this per image as
/// `xyz_cam[i][j] = Σ_k xyz_rgb[i][k] * imatrices.rgb_cam[k][j] / d65_white[i]`
/// (`ahd_demosaic_RT.cc:75-82`) from the camera's own colour matrix. This crate
/// never sees that matrix — it is handed a mosaic and a `CFA` — so the caller
/// supplies the finished 3x3 and this is what its default means.
///
/// The convention the kernels rely on is that a **neutral** (1,1,1) camera
/// triple maps to XYZ (1,1,1): both upstream's derivation and
/// `rawler::rawimage::RawImage::cam_to_xyz_normalized()` satisfy it, and it is
/// what puts white at the top of the `cbrt` table a Lab conversion indexes.
pub const XYZ_CAM_FROM_SRGB: [[f32; 3]; 3] = [
  // `xyz_rgb` row 0, divided by `d65_white[0]`.
  [0.412453 / 0.950456, 0.357580 / 0.950456, 0.180423 / 0.950456],
  // Row 1; `d65_white[1]` is 1.
  [0.212671, 0.715160, 0.072169],
  // Row 2, divided by `d65_white[2]`.
  [0.019334 / 1.088754, 0.119193 / 1.088754, 0.950227 / 1.088754],
];

/// Tuning knobs the ported Bayer kernels need, mirroring the RawTherapee
/// `procparams::RAWParams` fields they read.
///
/// Only DCB, LMMSE, AHD/EAHD and the `dual_demosaic_RT` hybrids read anything;
/// the rest ignore it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct BayerParams {
  /// `raw.bayersensor.dcb_iterations` — DCB refinement passes.
  pub dcb_iterations: i32,
  /// `raw.bayersensor.dcb_enhance` — DCB post-processing.
  pub dcb_enhance: bool,
  /// `raw.bayersensor.lmmse_iterations` — LMMSE's median/refinement passes.
  /// Upstream's GUI offers `0..=6` and defaults to `2`; values `7` and `8` are
  /// still honoured (`bayer/lmmse.rs` documents what they do), and anything
  /// else falls off the end of the state machine.
  pub lmmse_iterations: i32,
  /// `dualDemosaicContrast` — 0 means "base algorithm only" (the RT default).
  pub dual_contrast: f64,
  /// `autoContrast` — derive the blend threshold instead of using the above.
  pub dual_auto_contrast: bool,
  /// Camera → normalised-XYZ for AHD and EAHD. See [`XYZ_CAM_FROM_SRGB`] for the
  /// convention and for what the default (the camera's channels *are* sRGB)
  /// costs. Not an RT `procparams` field — it is per-image data RT reads from
  /// `imatrices`, threaded here because this crate has no other way to see it.
  pub xyz_cam: [[f32; 3]; 3],
}

impl Default for BayerParams {
  fn default() -> Self {
    // Upstream defaults: `dcb_iterations = 2`, `dcb_enhance = true`,
    // `lmmse_iterations = 2` (`rtengine/params/raw.cc:87`),
    // `dualDemosaicContrast = 0`, `dualDemosaicAutoContrast = true`
    // (`rtengine/params/raw.h`). The contrast value only matters once a hybrid is
    // selected, and 0 means "no blending", which is the conservative default.
    Self {
      dcb_iterations: 2,
      dcb_enhance: true,
      lmmse_iterations: 2,
      dual_contrast: 0.0,
      dual_auto_contrast: false,
      xyz_cam: XYZ_CAM_FROM_SRGB,
    }
  }
}

/// Tuning knobs the ported X-Trans kernels need.
#[derive(Clone, Copy, Debug, PartialEq, Default)]
pub struct XTransParams {
  /// `dualDemosaicContrast` for the `two_pass` / `four_pass` hybrids.
  pub dual_contrast: f64,
  /// `autoContrast` for the same.
  pub dual_auto_contrast: bool,
}

/// Demosaic a Bayer mosaic with a ported kernel.
///
/// # Errors
/// [`Error::Shape`] if `mosaic`/CFA geometry disagrees, [`Error::UnsupportedCfa`]
/// if the CFA has a fourth colour and the kernel cannot handle it, and
/// [`Error::UnsupportedAlgo`] for a kernel that is catalogued but not ported yet.
pub fn demosaic_bayer(algo: BayerAlgo, cfa: &CfaDesc, mosaic: &Array2D<f32>, params: &BayerParams) -> Result<Rgb, Error> {
  if !cfa.is_bayer {
    return Err(Error::UnsupportedCfa("bayer demosaic on a non-Bayer CFA"));
  }
  let (w, h) = (mosaic.width(), mosaic.height());
  if w < 4 || h < 4 {
    return Err(Error::Shape(format!("mosaic too small: {w}x{h}")));
  }

  // `params` is read by the DCB, LMMSE and dual-hybrid arms; the simple kernels
  // ignore it, exactly as upstream's do.

  // Ported kernels land one at a time (FOTLAB-NATIVE-000004 C6); an unported
  // algorithm stays out of `algo::candidates` until its arm exists here.
  match algo {
    BayerAlgo::Bilinear => bayer::bilinear::bayer_bilinear_demosaic(cfa, None, mosaic),
    // VNG4 reads the *unfolded* CFA mask and needs three-colour RGB, so it
    // returns `UnsupportedCfa` for a four-colour CFA — the same case upstream
    // falls back to IGV for (see `bayer/vng4.rs`).
    BayerAlgo::Vng4 => bayer::vng4::bayer_vng4_demosaic(cfa, mosaic),
    // RCD computes inside a 194x194 per-tile scratch — upstream's own tiling, kept
    // because the alternative is a working set of ~6.5 full-resolution planes. It
    // refuses a four-colour CFA, which upstream hands to IGV.
    BayerAlgo::Rcd => bayer::rcd::bayer_rcd_demosaic(cfa, mosaic),
    // IGV is the kernel `vng4`/`rcd` name as their fallback for a four-colour CFA,
    // but upstream's IGV indexes `rgb[3]` for one, so it cannot serve as that
    // fallback either — see `bayer/igv.rs`.
    BayerAlgo::Igv => bayer::igv::bayer_igv_demosaic(cfa, mosaic),
    // LMMSE works in a different numeric domain from the other kernels (its tone
    // curve is indexed in `rawData` units), and it is the only one that takes a
    // parameter — upstream's `lmmse_iterations`.
    BayerAlgo::Lmmse => bayer::lmmse::bayer_lmmse_demosaic(cfa, mosaic, params.lmmse_iterations),
    // DCB tiles the frame itself (192-square with a 10-pixel margin, upstream's
    // own geometry) and takes both of its parameters.
    BayerAlgo::Dcb => bayer::dcb::bayer_dcb_demosaic(cfa, mosaic, params.dcb_iterations, params.dcb_enhance),
    // HPHD takes no parameter upstream.
    BayerAlgo::Hphd => bayer::hphd::bayer_hphd_demosaic(cfa, mosaic),
    // AMAZE is the longest kernel in the catalogue and the only one that tiles
    // the frame with its own 16-pixel mirrored border, so it needs no border
    // pass afterwards; it takes no parameter (`bayer/amaze.rs`).
    BayerAlgo::Amaze => bayer::amaze::bayer_amaze_demosaic(cfa, mosaic),
    // FAST is the speed floor of the catalogue: a gradient-weighted green pass
    // plus two colour-difference passes over 224-square tiles. It reads no
    // per-image data and no parameter; the only absolute constant it carries
    // (the `clip_pt` highlight guard) is 4.0 in the mosaic's 0..1 domain
    // (`bayer/fast.rs`).
    BayerAlgo::Fast => bayer::fast::bayer_fast_demosaic(cfa, mosaic),
    // AHD is the first kernel that needs per-image data rather than a tuning
    // knob: its homogeneity test is a Lab comparison, so it takes the camera's
    // colour matrix (`bayer/ahd.rs` explains the convention and the default).
    // The arm works, but `algo::IMPLEMENTED_BAYER` deliberately omits `"ahd"`, so
    // nothing UI-facing reaches it yet (`FOTLAB-NATIVE-000004` rev 12).
    BayerAlgo::Ahd => bayer::ahd::bayer_ahd_demosaic(cfa, mosaic, &params.xyz_cam),
    other => Err(Error::UnsupportedAlgo(other.original_name())),
  }
}

/// Demosaic an X-Trans mosaic with a ported kernel.
///
/// # Errors
/// As [`demosaic_bayer`].
pub fn demosaic_xtrans(algo: XTransAlgo, cfa: &CfaDesc, mosaic: &Array2D<f32>, params: &XTransParams) -> Result<Rgb, Error> {
  if cfa.is_bayer {
    return Err(Error::UnsupportedCfa("x-trans demosaic on a Bayer CFA"));
  }
  let (w, h) = (mosaic.width(), mosaic.height());
  if w < 4 || h < 4 {
    return Err(Error::Shape(format!("mosaic too small: {w}x{h}")));
  }
  match algo {
    // Markesteijn 1-pass: the full homogeneity-directed kernel on four
    // directions, with the YPbPr difference statistic that needs no camera
    // colour matrix (`xtrans/one_pass.rs`).
    XTransAlgo::OnePass => xtrans::one_pass::xtrans_one_pass_demosaic(cfa, mosaic),
    // Fast X-Trans: one weighted cross-kernel pass (`xtrans/fast.rs`).
    XTransAlgo::Fast => xtrans::fast::xtrans_fast_demosaic(cfa, mosaic),
    // three_pass needs the CIELab statistic, hence the camera colour matrix
    // (rev 12); two_pass/four_pass are dual_demosaic_RT hybrids that also
    // need the blend-mask machinery. Catalogued, parked — the dispatcher
    // never hands them out while `IMPLEMENTED_XTRANS` omits them.
    other => {
      let _ = params;
      Err(Error::UnsupportedAlgo(other.original_name()))
    }
  }
}
