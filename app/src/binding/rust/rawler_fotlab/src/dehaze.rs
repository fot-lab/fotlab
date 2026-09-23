//! Dehaze stage — pure-functional haze / fog reduction on the scaled mosaic,
//! applied *before* demosaic (the exposure slot).
//!
//! Baseline: a single-image dehazing in the classic dark-channel spirit — estimate
//! a haze floor from the low tail of the mosaic histogram and lift it out per
//! pixel, then blend back toward the original by `strength`.
//!
//! The pure signature `dehaze(pixels, width, height, strength) -> pixels` is the
//! contract the develop pipeline depends on; replace the baseline with a stronger
//! model without touching the pipeline. `strength = None` is the identity stage,
//! so the default pipeline output is unchanged until a strength is supplied.

use rayon::prelude::*;

/// Dehaze the scaled mosaic. `strength` (0..1) blends the dehazed result with the
/// original; `None` returns the buffer unchanged (identity stage). Multi-channel
/// (`cpp > 1`) input is left untouched — the baseline only understands a
/// single-channel grid.
pub(crate) fn dehaze(
    mut pixels: Vec<f32>,
    _width: usize,
    _height: usize,
    strength: Option<f32>,
) -> Vec<f32> {
    let Some(strength) = strength else {
        return pixels;
    };
    let strength = strength.clamp(0.0, 1.0);
    if strength == 0.0 || pixels.len() != _width * _height {
        return pixels;
    }
    // Haze floor: the value at the ~1% percentile of the histogram.
    let haze = haze_floor(&pixels);
    if haze <= 0.0 {
        return pixels;
    }
    let denom = (1.0 - haze).max(1e-3);
    pixels.par_chunks_mut(64 * 1024).for_each(|chunk| {
        for p in chunk {
            let cleared = ((*p - haze) / denom).max(0.0);
            *p = *p * (1.0 - strength) + cleared * strength;
        }
    });
    pixels
}

/// Coarse percentile estimate: the value at the `TAIL` fraction of the histogram,
/// computed over 256 bins in [0,1] with a parallel reduction.
fn haze_floor(pixels: &[f32]) -> f32 {
    const BINS: usize = 256;
    const TAIL: f32 = 0.01;
    let hist = pixels
        .par_chunks(64 * 1024)
        .map(|chunk| {
            let mut local = vec![0u64; BINS];
            for &v in chunk {
                let b = ((v.clamp(0.0, 1.0) * (BINS as f32 - 1.0)).round() as usize).min(BINS - 1);
                local[b] += 1;
            }
            local
        })
        .reduce(
            || vec![0u64; BINS],
            |mut a, b| {
                for i in 0..BINS {
                    a[i] += b[i];
                }
                a
            },
        );
    let total: u64 = hist.iter().sum();
    let threshold = (total as f32 * TAIL) as u64;
    let mut cum = 0u64;
    for (i, &count) in hist.iter().enumerate() {
        cum += count;
        if cum >= threshold {
            return i as f32 / (BINS as f32 - 1.0);
        }
    }
    0.0
}
