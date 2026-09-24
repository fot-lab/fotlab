//! Denoise stage — orchestrates the pre-demosaic mosaic denoise as two composed
//! pure functions, both running *before* demosaic (the exposure slot):
//!
//!   1. [`denoise_impulse`] — RT-style CFA hot/dead-pixel / impulse removal.
//!   2. [`denoise_bm3d_cfa`] — BM3D collaborative filtering on the raw mosaic.
//!
//! Each is independently parameterised by its own `strength` (`None`/`0` =
//! identity), so an unconfigured sub-stage is free and the caller can enable
//! either or both, in that fixed order. Operating before demosaic means one
//! correction per photosite and, because demosaic is linear, a result identical
//! to denoising after it — without the demosaic step colouring / aliasing the
//! noise. Every accepted input is the raw 0..1 mosaic (packed Bayer / X-Trans /
//! monochrome), never an RGB or Lab buffer.
use crate::denoise_bm3d_cfa::denoise_bm3d_cfa;
use crate::denoise_impulse::denoise_impulse;
use rawler::rawimage::CFAConfig;

/// Compose the impulse and BM3D-CFA mosaic denoise sub-stages, in that order.
///
/// `impulse_strength` / `bm3d_strength` are independent `Option<f32>` multipliers
/// (`None`/`0` → identity for that sub-stage). `Multi-channel` (`cpp > 1`) and
/// size-mismatched input is returned untouched by the sub-stages' length checks.
pub(crate) fn denoise(
    pixels: Vec<f32>,
    width: usize,
    height: usize,
    impulse_strength: Option<f32>,
    bm3d_strength: Option<f32>,
    cfa: Option<&CFAConfig>,
) -> Vec<f32> {
    // Both unconfigured → skip entirely (no buffer churn).
    if impulse_strength.is_none() && bm3d_strength.is_none() {
        return pixels;
    }
    // Order: impulse (defects) first, then collaborative BM3D (smooth noise).
    let pixels = denoise_impulse(pixels, width, height, impulse_strength, cfa);
    let pixels = denoise_bm3d_cfa(pixels, width, height, bm3d_strength, cfa);
    pixels
}
