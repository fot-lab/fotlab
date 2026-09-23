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

/// Tuning knobs the ported Bayer kernels need, mirroring the RawTherapee
/// `procparams::RAWParams` fields they read.
///
/// Only DCB, LMMSE and the `dual_demosaic_RT` hybrids read anything; the rest
/// ignore it.
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
}

impl Default for BayerParams {
  fn default() -> Self {
    // Upstream defaults: `dcb_iterations = 2`, `dcb_enhance = true`,
    // `lmmse_iterations = 2` (`rtengine/params/raw.cc:87`),
    // `dualDemosaicContrast = 0`, `dualDemosaicAutoContrast = true`
    // (`rtengine/params/raw.h`). The contrast value only matters once a hybrid is
    // selected, and 0 means "no blending", which is the conservative default.
    Self { dcb_iterations: 2, dcb_enhance: true, lmmse_iterations: 2, dual_contrast: 0.0, dual_auto_contrast: false }
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

  // `params` is read by the DCB and dual-hybrid arms; the simple kernels ignore
  // it, exactly as upstream's do.
  let _ = params;

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
  let _ = params;
  Err(Error::UnsupportedAlgo(algo.original_name()))
}
