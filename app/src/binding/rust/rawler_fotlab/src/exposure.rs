//! Exposure stage — pure-functional exposure compensation.
//!
//! Extracted out of `develop.rs` so the develop pipeline is a composition of pure
//! stage functions instead of one inline block. Exposure is applied to the scaled
//! mosaic *before* demosaic; demosaic is a linear operation and the gain is
//! channel-uniform, so applying it earlier is numerically identical to applying it
//! after (`FOTLAB-RAWLER-000004` §as-shot).

use rayon::prelude::*;

/// Apply exposure compensation as the linear gain `2^exposure_ev` to the scaled
/// mosaic.
///
/// Pure: consumes `pixels` and returns the (possibly) scaled buffer — no shared
/// state, no side effects, same input → same output. `None` and `Some(0.0)` both
/// collapse to unity gain (as-shot), so the no-compensation path pays nothing and
/// matches rawler's `RawDevelop::default()` (the pipeline dnglab uses to render
/// its DNG thumbnail, which applies no exposure step at all).
pub(crate) fn apply_exposure(mut pixels: Vec<f32>, exposure_ev: Option<f32>) -> Vec<f32> {
    let ev_scale = exposure_ev.map_or(1.0, |ev| 2f32.powf(ev));
    if ev_scale == 1.0 {
        return pixels;
    }
    // One multiply per photosite, chunked so a rayon task processes a whole slice
    // instead of a single float (`OPTIMZ-PERFRM-000007`). A raw `par_iter_mut`
    // here would be dominated by per-element scheduling overhead.
    pixels.par_chunks_mut(64 * 1024).for_each(|chunk| {
        for p in chunk {
            *p *= ev_scale;
        }
    });
    pixels
}
