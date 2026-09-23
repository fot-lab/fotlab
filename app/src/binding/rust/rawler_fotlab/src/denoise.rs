//! Denoise stage — pure-functional impulse (hot/dead-pixel) denoise on the scaled
//! mosaic, applied *before* demosaic (the exposure slot).
//!
//! ## Algorithm: RawTherapee's CFA-stage impulse denoise, generalised to any CFA
//!
//! RawTherapee's **Impulse Denoise** (the "hot/dead pixel" removal in RT's *Raw*
//! tab) runs on the single-channel CFA mosaic, *before* demosaic. It treats
//! photosite defects — hot pixels, dead pixels, stuck sensors, salt-and-pepper
//! impulse noise — as *isolated outliers* and replaces each with the median of its
//! same-colour neighbours. We port that idea here, on the same 0..1 scaled mosaic
//! the exposure slot hands us.
//!
//! Unlike the original port (which hard-coded the 2×2 Bayer sublattices and was
//! therefore skipped on X-Trans), this version groups pixels by
//! **`CFA::color_at(row, col)`** and so works on **every periodic CFA** —
//! 2×2 Bayer (RGGB / four-colour), 6×6 X-Trans, and anything else rawler
//! describes — as well as on single-channel (non-CFA, monochrome) input, with no
//! 2×2-only gating. The same-colour neighbour set of a pixel is the fixed list of
//! `(Δrow, Δcol)` offsets (within a `RADIUS × RADIUS` window, default 2 → a 5×5
//! mosaic neighbourhood) whose CFA colour matches the pixel's own; because the
//! CFA is periodic, this list depends only on `(row mod period_h, col mod
//! period_w)` and is **precomputed once**, so the per-pixel cost is a constant
//! gather + a tiny median — independent of the CFA family.
//!
//! Two enhancements over the textbook RT median test, kept from the original:
//!
//! 1. **Same-colour-only comparison, never a box blur.** A defect is detected and
//!    corrected only against same-colour neighbours, so no colour bleeds across
//!    planes and the median estimate is never contaminated by a neighbouring
//!    colour.
//! 2. **Beyond-neighbour-range test + soft knee.** RT flags a pixel when it
//!    deviates from the neighbour *median* by more than a threshold, which can
//!    nick genuine high-contrast edges. We instead require the pixel to sit
//!    *outside the neighbour range* by the threshold — a true isolated spike — and
//!    blend smoothly across a knee so the keep↔replace transition is continuous.
//!
//! ## Contract
//!
//! `denoise(pixels, width, height, strength, cfa) -> pixels`. `strength = None`
//! (or `0`) is identity; multi-channel (`cpp > 1`) input is also left untouched
//! by the length check. The orchestrator passes the `CFAConfig` so the stage is
//! colour-aware; `None` (non-CFA) falls back to a single-colour impulse filter.
//! `strength` is a sensitivity multiplier on the detection threshold
//! (`≈1.0` = mild, higher = more aggressive). Swapping this for a stronger model
//! does not touch the caller.

use rayon::prelude::*;
use rawler::rawimage::CFAConfig;

/// Base detection threshold in the normalised [0,1] mosaic. A pixel must exceed
/// the surrounding same-colour neighbour *range* by this much to be touched; the
/// soft knee then spans one further threshold of excess before it is fully
/// replaced by the neighbour median.
const BASE_THRESHOLD: f32 = 0.05;

/// Impulse-detection window radius (in mosaic pixels). `RADIUS = 2` is a 5×5
/// neighbourhood; for Bayer this recovers the original 8 same-colour neighbours
/// (the 3×3 sublattice), and for X-Trans it yields ~5–14 same-colour samples
/// depending on the colour.
const RADIUS: usize = 2;

/// Minimum number of same-colour neighbours required before a pixel may be
/// corrected; fewer (e.g. at awkward parities) leaves it untouched.
const MIN_NEIGH: usize = 4;

/// Largest possible same-colour neighbour count within the window (all 24
/// off-centre positions). Used for a stack-allocated gather buffer.
const MAX_NEIGH: usize = (2 * RADIUS + 1) * (2 * RADIUS + 1) - 1;

/// Denoise the scaled mosaic, treated as a `width × height` grid of single-channel
/// CFA samples. `strength` is a sensitivity multiplier on [`BASE_THRESHOLD]
/// (`None` or `0` → identity). `cfa` enables per-colour grouping; `None` falls
/// back to a single-colour impulse filter (monochrome / non-CFA input).
/// Multi-channel (pre-coloured, `cpp > 1`) input is left untouched by the length
/// check.
pub(crate) fn denoise(
    mut pixels: Vec<f32>,
    width: usize,
    height: usize,
    strength: Option<f32>,
    cfa: Option<&CFAConfig>,
) -> Vec<f32> {
    let Some(strength) = strength else {
        return pixels;
    };
    let strength = strength.clamp(0.0, 8.0);
    if strength == 0.0
        || width <= 2 * RADIUS
        || height <= 2 * RADIUS
        || pixels.len() != width * height
    {
        return pixels;
    }

    // Precompute, for every CFA parity, the same-colour neighbour offsets inside
    // the window. For `cfa = None` we treat every pixel as one colour, so the
    // table has a single entry holding all off-centre offsets.
    let period_w = cfa.map(|c| c.cfa.width).unwrap_or(1);
    let period_h = cfa.map(|c| c.cfa.height).unwrap_or(1);
    let color_of = |r: i64, c: i64| -> usize {
        match cfa {
            // `color_at` is periodic; rem_euclid keeps the parity index in range.
            Some(cfg) => {
                let rr = r.rem_euclid(period_h as i64);
                let cc = c.rem_euclid(period_w as i64);
                cfg.cfa.color_at(rr as usize, cc as usize)
            }
            None => 0,
        }
    };
    let offsets = build_offset_table(period_w, period_h, color_of);

    let thr = BASE_THRESHOLD * strength;
    let soft = thr;

    // Read neighbours from the original grid (`src`); write corrections to `dst`.
    // Both are full-resolution; `dst` starts as a copy so untouched pixels (and
    // the `RADIUS`-pixel border, which we skip) are preserved unchanged.
    let src = pixels.clone();
    let mut dst = pixels;

    dst.par_chunks_mut(width)
        .enumerate()
        .for_each(|(row, row_out)| {
            // Leave a `RADIUS` border untouched so every gathered neighbour is
            // in-bounds (matches the original interior-only behaviour).
            if row < RADIUS || row + RADIUS >= height {
                return;
            }
            let row = row as i64;
            for col in RADIUS..width - RADIUS {
                let col = col as i64;
                let pr = (row.rem_euclid(period_h as i64)) as usize;
                let pc = (col.rem_euclid(period_w as i64)) as usize;
                let offs = &offsets[pr * period_w + pc];
                let mut vals = [0.0f32; MAX_NEIGH];
                let mut n = 0usize;
                let center = src[(row as usize) * width + (col as usize)];
                for &(dr, dc) in offs {
                    if n >= vals.len() {
                        break;
                    }
                    let v = src[((row + dr as i64) as usize) * width + ((col + dc as i64) as usize)];
                    vals[n] = v;
                    n += 1;
                }
                if n < MIN_NEIGH {
                    row_out[col as usize] = center;
                    continue;
                }
                let (lo, hi, med) = min_max_median(&mut vals[..n]);
                let lo_t = lo - thr;
                let hi_t = hi + thr;
                if center > hi_t {
                    // Hot-pixel / impulse above the neighbour range: pull down
                    // toward the median, fully once the excess exceeds `soft`.
                    let excess = center - hi_t;
                    let frac = (excess / soft).min(1.0);
                    row_out[col as usize] = hi_t + (med - hi_t) * frac;
                } else if center < lo_t {
                    // Dead-pixel / impulse below the neighbour range: pull up
                    // toward the median.
                    let excess = lo_t - center;
                    let frac = (excess / soft).min(1.0);
                    row_out[col as usize] = lo_t + (med - lo_t) * frac;
                } else {
                    row_out[col as usize] = center;
                }
            }
        });

    dst
}

/// Build, for each CFA parity `(pr, pc)`, the list of `(dr, dc)` window offsets
/// whose CFA colour equals the parity's own colour. Because the CFA is periodic,
/// these offsets are identical for every pixel sharing that parity.
fn build_offset_table(
    period_w: usize,
    period_h: usize,
    color_of: impl Fn(i64, i64) -> usize,
) -> Vec<Vec<(i32, i32)>> {
    let mut table = Vec::with_capacity(period_w * period_h);
    for pr in 0..period_h as i64 {
        for pc in 0..period_w as i64 {
            let color = color_of(pr, pc);
            let mut offs: Vec<(i32, i32)> = Vec::new();
            for dr in -(RADIUS as i64)..=RADIUS as i64 {
                for dc in -(RADIUS as i64)..=RADIUS as i64 {
                    if dr == 0 && dc == 0 {
                        continue;
                    }
                    if color_of(pr + dr, pc + dc) == color {
                        offs.push((dr as i32, dc as i32));
                    }
                }
            }
            table.push(offs);
        }
    }
    table
}

/// Compute `(min, max, median)` of a small slice. `NaN`s (which should not occur
/// in scaled mosaic data) sort as equal rather than panicking.
fn min_max_median(vals: &mut [f32]) -> (f32, f32, f32) {
    vals.sort_unstable_by(|x, y| x.partial_cmp(y).unwrap_or(std::cmp::Ordering::Equal));
    let n = vals.len();
    let lo = vals[0];
    let hi = vals[n - 1];
    let med = if n % 2 == 1 {
        vals[n / 2]
    } else {
        (vals[n / 2 - 1] + vals[n / 2]) * 0.5
    };
    (lo, hi, med)
}
