//! `rawtrp_correct` — first-party pure-Rust port of RawTherapee's CFA-mosaic
//! chromatic-aberration correction.
//!
//! The ported kernel is `CA_correct_RT` (`rtengine/CA_correct_RT.cc`), the
//! *pre-demosaic* radial CA model by Emil Martinec / Ingo Weyrich. It rewrites
//! the R and B planes of a Bayer mosaic in place — a genuine "治本" (root-cause)
//! correction, distinct from the post-demosaic constant-R/B `cacorrection`
//! (see `rules/STRUCT/detail/RAWTRP-SURVEY-000004.md` §5).
//!
//! ## Contract
//!
//! Like the demosaic kernels in `rawtrp_demos`, this crate takes the minimal
//! contract from `rules/STRUCT/detail/RAWTRP-DECODE-000003.md` §3.1 — **an
//! array plus a CFA description** — so it needs no `RawImageSource` and no C++:
//!
//! ```text
//!   mosaic: &mut Array2D<f32>  (w x h, single channel, 0..1 linear)  ┐
//!   cfa:    &CfaDesc            (folded Bayer mask)                   ┘ -> mutates R/B
//! ```
//!
//! The mosaic is the **ROI-local** single-channel CFA buffer (D4: the pipeline
//! materialises only the ROI); the caller is responsible for running this
//! *before* any demosaic and for feeding the corrected mosaic onwards.
//!
//! ## Scope of this port (batch C1)
//!
//! This batch implements the **pass-2 shift-application** stage — the part that
//! actually rewrites the mosaic's R/B planes — for both the manual
//! (`cared`/`cablue` radial) and the auto path (shift parameters supplied via
//! `fit`). The pass-1 *auto-fit measurement* (per-tile colour-difference
//! correlation + 4th-order 2-D polynomial regression solved with
//! [`lin_eq_solve`]) is a follow-up batch; until it lands,
//! `auto_ca = true` without a `fit` returns [`Error::AutoCaNotYet`].

pub mod ca_correct;
pub mod gauss;
pub mod lin_eq;

pub use ca_correct::{correct_ca_bayer, CaParams, Error, FitParams};
pub use lin_eq::lin_eq_solve;
