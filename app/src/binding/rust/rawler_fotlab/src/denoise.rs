//! Denoise stage — pure-functional noise reduction on the scaled mosaic, applied
//! *before* demosaic (the exposure slot).
//!
//! ## Algorithm: RawTherapee's CFA-stage impulse denoise
//!
//! RawTherapee's **Impulse Denoise** (the "hot/dead pixel" removal in RT's *Raw*
//! tab) runs on the single-channel Bayer mosaic, *before* demosaic. It treats
//! photosite defects — hot pixels, dead pixels, stuck sensors, salt-and-pepper
//! impulse noise — as *isolated outliers* and replaces each with the median of its
//! same-colour neighbours. We port that idea here, working on the same 0..1
//! scaled mosaic the exposure slot hands us.
//!
//! Two enhancements over the textbook RT median test, while keeping the same
//! contract:
//!
//! 1. **Per-colour planes, never a box blur.** Like RT we split the RGGB (or any
//!    2×2-periodic) CFA into its four same-colour sublattices and detect only
//!    against same-colour neighbours at mosaic distance 2. A defect is never
//!    averaged with a neighbouring colour and no colour bleeds across planes — the
//!    old box-blur baseline could not make that guarantee.
//! 2. **Beyond-neighbour-range test + soft knee.** RT flags a pixel when it
//!    deviates from the neighbour *median* by more than a threshold; that can nick
//!    genuine high-contrast edges (where the median sits mid-edge and the edge
//!    pixel legitimately deviates from it). We instead require the pixel to sit
//!    *outside the neighbour range* by the threshold — a true isolated spike — and
//!    blend smoothly across a knee so the keep↔replace transition is continuous
//!    and seam-free. This is strictly fewer false positives than a pure
//!    median-deviation test for the same sensitivity.
//!
//! The stage is gated to **2×2-periodic CFAs** by the orchestrator (`develop.rs`):
//! X-Trans (6×6) is not 2×2-periodic, so the parity grouping would compare
//! against mismatched colours and is skipped there — matching
//! `bayer_cfa_desc`'s `cfa.width == 2 && cfa.height == 2` predicate.
//!
//! The pure signature `denoise(pixels, width, height, strength) -> pixels` is the
//! contract the develop pipeline depends on; swap this for a stronger model
//! without changing any caller. `strength = None` (or `0`) is the identity stage,
//! so the default pipeline output is unchanged until a strength is supplied.
//! `strength` is a sensitivity multiplier on the detection threshold
//! (`≈1.0` = mild, higher = more aggressive).

use rayon::prelude::*;

/// Base detection threshold in the normalised [0,1] mosaic. A pixel must exceed
/// the surrounding same-colour neighbour *range* by this much to be touched; the
/// soft knee then spans one further threshold of excess before it is fully
/// replaced by the neighbour median.
const BASE_THRESHOLD: f32 = 0.05;

/// Denoise the scaled mosaic, treated as a `width × height` grid of single-channel
/// CFA samples. `strength` is a sensitivity multiplier on [`BASE_THRESHOLD]
/// (`None` or `0` → identity). Multi-channel (pre-coloured, `cpp > 1`) input is
/// left untouched — the stage only understands a 2×2-periodic single-channel
/// grid, and the orchestrator already skips it for non-2×2 CFAs (e.g. X-Trans).
pub(crate) fn denoise(
    mut pixels: Vec<f32>,
    width: usize,
    height: usize,
    strength: Option<f32>,
) -> Vec<f32> {
    let Some(strength) = strength else {
        return pixels;
    };
    let strength = strength.clamp(0.0, 8.0);
    if strength == 0.0 || width == 0 || height == 0 || pixels.len() != width * height {
        return pixels;
    }
    let thr = BASE_THRESHOLD * strength;
    // Impulse-denoise each same-colour sublattice of the RGGB/Bayer grid
    // independently (offset 0/1 on each axis).
    for row_off in 0..2 {
        for col_off in 0..2 {
            impulse_plane(&mut pixels, width, height, row_off, col_off, thr);
        }
    }
    pixels
}

/// Run the impulse detector over the `(row_off, col_off)` Bayer sublattice of the
/// `width × height` grid, in place. The sublattice is `⌈width/2⌉ × ⌈height/2⌉`
/// samples at `(2*r + row_off, 2*c + col_off)`. Each output depends only on the
/// *original* sublattice (read from `src`, written to `dst`), so the row-parallel
/// pass is race-free and deterministic.
fn impulse_plane(
    buf: &mut [f32],
    width: usize,
    height: usize,
    row_off: usize,
    col_off: usize,
    thr: f32,
) {
    let sw = (width + 1) / 2;
    let sh = (height + 1) / 2;
    // Need a 3×3 interior to have orthogonal+diagonal same-colour neighbours; a
    // sublattice smaller than that has no interior pixels to test.
    if sw < 3 || sh < 3 {
        return;
    }

    // Extract the sublattice into a dense buffer; `dst` starts as a copy of `src`
    // so every pixel we do not explicitly rewrite is preserved untouched.
    let mut src: Vec<f32> = vec![0.0; sw * sh];
    let mut dst: Vec<f32> = vec![0.0; sw * sh];
    for r in 0..sh {
        let src_row = 2 * r + row_off;
        if src_row >= height {
            break;
        }
        for c in 0..sw {
            let src_col = 2 * c + col_off;
            if src_col >= width {
                break;
            }
            let v = buf[src_row * width + src_col];
            src[r * sw + c] = v;
            dst[r * sw + c] = v;
        }
    }

    // Knee width == threshold: a pixel is fully replaced by the neighbour median
    // once it overshoots the neighbour range by `thr` beyond the detection margin.
    let soft = thr;

    // Process interior rows in parallel. Border rows/cols are left equal to `src`.
    dst.par_chunks_mut(sw).enumerate().for_each(|(r, row_out)| {
        if r == 0 || r + 1 >= sh {
            return;
        }
        for c in 1..sw - 1 {
            // 8 same-colour neighbours in the sublattice = a 5×5-in-mosaic
            // neighbourhood, all the same photosite colour (distance 2 in the mosaic).
            let ns: [f32; 8] = [
                src[(r - 1) * sw + c],
                src[(r + 1) * sw + c],
                src[r * sw + c - 1],
                src[r * sw + c + 1],
                src[(r - 1) * sw + c - 1],
                src[(r + 1) * sw + c - 1],
                src[(r - 1) * sw + c + 1],
                src[(r + 1) * sw + c + 1],
            ];
            let mut lo = ns[0];
            let mut hi = ns[0];
            for &n in ns.iter().skip(1) {
                if n < lo {
                    lo = n;
                }
                if n > hi {
                    hi = n;
                }
            }
            let med = median8(&ns);
            let lo_t = lo - thr;
            let hi_t = hi + thr;
            let v = src[r * sw + c];
            if v > hi_t {
                // Hot-pixel / impulse above the neighbour range: pull down toward
                // the median, fully once the excess exceeds `soft`.
                let excess = v - hi_t;
                let frac = (excess / soft).min(1.0);
                row_out[c] = hi_t + (med - hi_t) * frac;
            } else if v < lo_t {
                // Dead-pixel / impulse below the neighbour range: pull up toward
                // the median.
                let excess = lo_t - v;
                let frac = (excess / soft).min(1.0);
                row_out[c] = lo_t + (med - lo_t) * frac;
            } else {
                row_out[c] = v;
            }
        }
    });

    // Write the corrected sublattice back into the mosaic.
    for r in 0..sh {
        let src_row = 2 * r + row_off;
        if src_row >= height {
            break;
        }
        for c in 0..sw {
            let src_col = 2 * c + col_off;
            if src_col >= width {
                break;
            }
            buf[src_row * width + src_col] = dst[r * sw + c];
        }
    }
}

/// Median of 8 values: the mean of the two middle samples after sorting. `NaN`s
/// (which should not occur in scaled mosaic data) sort as equal rather than
/// panicking.
fn median8(ns: &[f32; 8]) -> f32 {
    let mut a = *ns;
    a.sort_unstable_by(|x, y| x.partial_cmp(y).unwrap_or(std::cmp::Ordering::Equal));
    (a[3] + a[4]) * 0.5
}
