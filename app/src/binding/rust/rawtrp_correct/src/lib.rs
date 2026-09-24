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
//! ## Scope of this port
//!
//! Both passes of `CA_correct_RT` are ported:
//!   * **pass 1** — auto-fit measurement [`ca_correct::detect_ca`] (per-tile
//!     colour-difference correlation + 2-D polynomial regression solved with
//!     [`lin_eq_solve`]); exposed publicly as [`fit_ca_bayer`].
//!   * **pass 2** — shift application [`correct_ca_bayer`], for both the manual
//!     (`ca_red`/`ca_blue` radial) and auto paths (auto either measures via pass
//!     1 or reuses a supplied [`FitParams`], the `fitParamsIn` path).
//!
//! The port works in the `0..1` linear mosaic domain; the upstream `/65535`
//! round-trip is omitted and all thresholds are unchanged.

pub mod ca_correct;
pub mod gauss;
pub mod lin_eq;

pub use ca_correct::{correct_ca_bayer, fit_ca_bayer, CaParams, Error, FitParams};
pub use lin_eq::lin_eq_solve;
