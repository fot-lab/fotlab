//! Exposure stage — pure-functional exposure compensation with an input clip.
//!
//! Extracted out of `develop.rs` so the develop pipeline is a composition of pure
//! stage functions instead of one inline block. Exposure is applied to the scaled
//! mosaic *before* demosaic; demosaic is a linear operation and the gain is
//! channel-uniform, so applying it earlier is numerically identical to applying it
//! after (`FOTLAB-RAWLER-000004` §as-shot).

use rayon::prelude::*;

/// Apply the exposure stage to the scaled mosaic: an optional min/max clip of the
/// normalised 0..1 values, immediately followed by the linear gain `2^exposure_ev`.
///
/// Pure: consumes `pixels` and returns the (possibly) transformed buffer — no
/// shared state, no side effects, same input → same output. `None` (the Kotlin
/// enable switch's stage-off state) is the identity for the *whole* stage — the
/// clip is gated behind the same switch, so a stage-off render never clips
/// either — and the no-compensation path pays nothing, matching rawler's
/// `RawDevelop::default()` (the pipeline dnglab uses to render its DNG thumbnail,
/// which applies no exposure step at all; `FOTLAB-RAWLER-000004` §as-shot).
///
/// The clip runs **fused into the same rayon chunk pass as the gain** — clamp
/// first, then scale — so it adds no separate sequential sweep over the buffer
/// (user directive 2026-09-26: nest the clip inside the parallel iteration).
/// Semantics: values strictly below `clip_lower` are forced up to it; values at
/// or above `clip_upper` are forced down to it (`f32::clamp`). The defaults
/// `[0, 1]` are a no-op on the already black/white-level-normalised mosaic, and
/// that identity configuration short-circuits without touching the pixels.
pub(crate) fn apply_exposure(
    mut pixels: Vec<f32>,
    exposure_ev: Option<f32>,
    clip_lower: f32,
    clip_upper: f32,
) -> Vec<f32> {
    // Stage-off (`None`, the Kotlin enable switch) is the identity for the whole
    // stage — clip included.
    let ev_scale = match exposure_ev {
        None => return pixels,
        Some(ev) => 2f32.powf(ev),
    };
    // Defensive: `f32::clamp` panics when min > max; order the bounds so a swapped
    // UI value can never crash a render.
    let clip_lo = clip_lower.min(clip_upper);
    let clip_hi = clip_lower.max(clip_upper);
    // Identity configuration (as-shot EV with the default [0,1] bounds on a
    // normalised mosaic): short-circuit, as the pre-clip version did.
    if ev_scale == 1.0 && clip_lo <= 0.0 && clip_hi >= 1.0 {
        return pixels;
    }
    // One clamp + multiply per photosite, chunked so a rayon task processes a whole
    // slice instead of a single float (`OPTIMZ-PERFRM-000007`). A raw `par_iter_mut`
    // here would be dominated by per-element scheduling overhead. The clip is fused
    // into this same pass — clamp, then scale — rather than a separate sweep.
    pixels.par_chunks_mut(64 * 1024).for_each(|chunk| {
        for p in chunk {
            *p = (*p).clamp(clip_lo, clip_hi) * ev_scale;
        }
    });
    pixels
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stage_off_is_identity_even_with_tight_clip() {
        let px = vec![0.0f32, 0.5, 1.0];
        let out = apply_exposure(px.clone(), None, 0.2, 0.8);
        assert_eq!(out, px);
    }

    #[test]
    fn identity_config_is_free() {
        // EV 0 with the default [0,1] bounds short-circuits without touching pixels.
        let px = vec![0.0f32, 0.25, 1.0];
        let out = apply_exposure(px.clone(), Some(0.0), 0.0, 1.0);
        assert_eq!(out, px);
    }

    #[test]
    fn clip_runs_before_gain() {
        // 0.9 >= upper → 0.8, then ×2 → 1.6. Clamping *after* the gain would give 0.8,
        // so this pins the clamp-then-scale order.
        let out = apply_exposure(vec![0.1, 0.5, 0.9], Some(1.0), 0.2, 0.8);
        assert_eq!(out, vec![0.4, 1.0, 1.6]);
    }

    #[test]
    fn swapped_bounds_do_not_panic() {
        // lo/hi arrive swapped → silently ordered, no `clamp` panic.
        let out = apply_exposure(vec![0.1, 0.5, 0.9], Some(1.0), 0.8, 0.2);
        assert_eq!(out, vec![0.4, 1.0, 1.6]);
    }
}
