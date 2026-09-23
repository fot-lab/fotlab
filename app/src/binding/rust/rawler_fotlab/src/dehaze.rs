//! Dehaze stage — pure-functional haze / fog reduction on the scaled mosaic,
//! applied *before* demosaic (the exposure slot).
//!
//! ## Algorithm (enhanced: per-colour-plane histogram-floor dehaze)
//!
//! A single-image dehaze in the dark-channel spirit, adapted to the
//! single-channel CFA mosaic. Where the previous baseline estimated *one* haze
//! floor over the whole mosaic, this version estimates a **separate haze floor
//! per CFA colour plane** — R, G and B (the two Bayer greens share one plane, as
//! do all X-Trans greens) — exactly like RawTherapee's Raw Dehaze, which
//! subtracts the per-raw-channel minimum. The per-plane floor is the
//! user-configurable `percentile` of that plane's 0..1 histogram (default 1%);
//! a lower percentile is more conservative (closer to a pure minimum), a higher
//! one lifts more of the low-tail signal.
//!
//! Each photosite is then cleared against its own plane's floor:
//! `cleared = (v − haze[plane]) / (1 − haze[plane])`, clamped at 0, and the
//! result is blended back toward the original by `strength` (kept from the
//! baseline). The divide restores the contrast the haze washed out (the DCP
//! atmospheric form), so the stage both lifts the offset *and* un-compresses
//! the contrast lost to uniform haze.
//!
//! ## Why per-plane, and why it also covers X-Trans
//!
//! The haze floor is a property of *each colour*, not of the frame as a whole:
//! green typically carries more signal than red or blue, so a single global
//! floor would over-lift one plane and under-lift another. Grouping by
//! `CFA::color_at(row, col)` makes the stage colour-aware on **every** CFA —
//! 2×2 Bayer (3 planes: R / G / B) and 6×6 X-Trans (also R / G / B) — without
//! the 2×2-only gating that `denoise` uses. Non-CFA input (pre-coloured RGB,
//! `cpp > 1`, or monochrome) falls back to a single global plane, matching the
//! old behaviour; `cpp > 1` buffers are left untouched by the length check.
//!
//! The accumulation is also restricted to the sensor **active area** when one is
//! present, so masked / black borders cannot bias the percentile estimate.
//!
//! ## Contract
//!
//! `dehaze(pixels, width, height, strength, percentile, cfa, active) -> pixels`.
//! `strength = None` (or `0`) is identity; `percentile = None` defaults to 1%.
//! `percentile` is clamped to `[0, 1]` internally. Swapping this for a stronger
//! post-demosaic model (DCP / Haze-Line / fusion) does not touch the caller.

use rayon::prelude::*;
use rawler::rawimage::CFAConfig;

/// Histogram bins over the normalised [0,1] mosaic.
const BINS: usize = 256;

/// Default haze-floor percentile of each colour plane's histogram.
const DEFAULT_TAIL: f32 = 0.01;

/// Dehaze the scaled CFA mosaic.
///
/// * `strength` (0..1) blends the dehazed result with the original; `None` (or
///   `0`) returns the buffer unchanged (identity stage).
/// * `percentile` is the haze-floor quantile of each colour plane's histogram,
///   clamped to `[0,1]` internally; `None` → [`DEFAULT_TAIL`].
/// * `cfa` enables per-colour-plane estimation (`None` → a single global plane,
///   for non-CFA input).
/// * `active` is the sensor active-area `(x, y, w, h)`; when `Some`, the
///   percentile histograms are accumulated only inside it so masked borders do
///   not bias the estimate.
///
/// Multi-channel (`cpp > 1`) input is left untouched — the stage only
/// understands a single-channel grid.
pub(crate) fn dehaze(
    mut pixels: Vec<f32>,
    width: usize,
    height: usize,
    strength: Option<f32>,
    percentile: Option<f32>,
    cfa: Option<&CFAConfig>,
    active: Option<(usize, usize, usize, usize)>,
) -> Vec<f32> {
    let Some(strength) = strength else {
        return pixels;
    };
    let strength = strength.clamp(0.0, 1.0);
    if strength == 0.0 || width == 0 || height == 0 || pixels.len() != width * height {
        return pixels;
    }

    // Haze floor per colour plane, estimated as `percentile` of that plane's
    // 0..1 histogram (restricted to the active area when present).
    let tail = percentile.unwrap_or(DEFAULT_TAIL).clamp(0.0, 1.0);
    let haze = plane_haze_floors(&pixels, width, height, cfa, active, tail);

    // Denominators (1 − haze), floored so a near-1 haze does not divide by ~0.
    let denom: Vec<f32> = haze.iter().map(|&h| (1.0 - h).max(1e-3)).collect();

    let nplanes = haze.len();
    pixels
        .par_chunks_mut(width)
        .enumerate()
        .for_each(|(row, row_px)| {
            for (col, p) in row_px.iter_mut().enumerate() {
                let plane = match cfa {
                    Some(c) => c.cfa.color_at(row, col).min(nplanes - 1),
                    None => 0,
                };
                let h = haze[plane];
                let cleared = ((*p - h) / denom[plane]).max(0.0);
                *p = *p * (1.0 - strength) + cleared * strength;
            }
        });
    pixels
}

/// Estimate a haze floor per colour plane as the `tail` quantile of each plane's
/// 256-bin 0..1 histogram. Accumulation is restricted to `active` when set.
fn plane_haze_floors(
    pixels: &[f32],
    width: usize,
    _height: usize,
    cfa: Option<&CFAConfig>,
    active: Option<(usize, usize, usize, usize)>,
    tail: f32,
) -> Vec<f32> {
    let nplanes = match cfa {
        Some(c) => c.colors.plane_count().max(1),
        None => 1,
    };

    // Parallel histogram: each thread accumulates `nplanes` 256-bin histograms,
    // merged by addition. Only pixels inside `active` (when present) count.
    let acc = pixels
        .par_chunks(width)
        .enumerate()
        .map(|(row, row_px)| {
            let mut local = vec![0u64; nplanes * BINS];
            if let Some((_ax, ay, _aw, ah)) = active {
                if row < ay || row >= ay + ah {
                    return local;
                }
            }
            for (col, &v) in row_px.iter().enumerate() {
                if let Some((ax, _ay, aw, ah)) = active {
                    if col < ax || col >= ax + aw || row >= ay + ah {
                        continue;
                    }
                }
                let plane = match cfa {
                    Some(c) => c.cfa.color_at(row, col).min(nplanes - 1),
                    None => 0,
                };
                let b = ((v.clamp(0.0, 1.0) * (BINS as f32 - 1.0)).round() as usize).min(BINS - 1);
                local[plane * BINS + b] += 1;
            }
            local
        })
        .reduce(
            || vec![0u64; nplanes * BINS],
            |mut a, b| {
                for (i, x) in b.iter().enumerate() {
                    a[i] += x;
                }
                a
            },
        );

    // Per-plane quantile.
    let mut out = vec![0.0f32; nplanes];
    for p in 0..nplanes {
        let hist = &acc[p * BINS..(p + 1) * BINS];
        let total: u64 = hist.iter().sum();
        if total == 0 {
            out[p] = 0.0;
            continue;
        }
        let threshold = (total as f64 * tail as f64).max(0.0) as u64;
        let mut cum = 0u64;
        for (i, &count) in hist.iter().enumerate() {
            cum += count;
            if cum >= threshold {
                out[p] = i as f32 / (BINS as f32 - 1.0);
                break;
            }
        }
    }
    out
}
