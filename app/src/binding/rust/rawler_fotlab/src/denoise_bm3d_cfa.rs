//! BM3D-CFA denoise — a from-scratch BM3D-style collaborative filter that runs
//! directly on the CFA mosaic (single-channel, before demosaic).
//!
//! ## Why on the mosaic
//!
//! Operating before demosaic means one correction per photosite and, because
//! demosaic is linear, the result is statistically identical to denoising after
//! it — but we avoid the demosaic step colouring / aliasing the noise. Every
//! accepted input here is the raw 0..1 mosaic (packed Bayer / X-Trans /
//! monochrome), never an RGB or Lab buffer. This is the **collaborative** (Gaussian
//! / shot-noise) half of the composed mosaic denoise; the impulse (hot/dead-pixel)
//! half is `denoise_impulse.rs`. The orchestrator is `denoise.rs`.
//!
//! ## Algorithm (Dabov et al. BM3D, made CFA-aware)
//!
//! 1. **Block matching on the mosaic.** Reference `N×N` patches are grouped with
//!    other patches whose CFA *phase* matches (top-left pixel has the same
//!    colour), so every compared pixel in the two patches carries the same colour
//!    and the SSD is colour-honest. This generalises to any periodic CFA (Bayer,
//!    X-Trans) and to monochrome (all phases identical) at once, and — for Bayer —
//!    halves the candidate set versus unrestricted search.
//! 2. **3-D transform.** Each patch is 2-D DCT-transformed, the patches are
//!    stacked into an `N×N×K` volume, and a 1-D DCT is applied along the `K`
//!    (group) axis — collaborative filtering in a transform domain. (A 1-D DCT is
//!    used rather than Haar so the group size `K` need not be a power of two.)
//! 3. **Hard-thresholding stage** estimates a basic image; a **Wiener stage**
//!    re-groups on that estimate and applies energy-based shrinkage, which is
//!    markedly better at preserving detail than a single hard-threshold pass.
//! 4. **Aggregation** averages the overlapping, collaboratively-filtered patches,
//!    weighted (Wiener weights in stage 2).
//!
//! ## Parameterisation
//!
//! `strength` is a noise-level multiplier: `sigma = STRENGTH_TO_SIGMA * strength`.
//! `None`/`0` → identity. The CFA-aware grouping is the CFA-domain analogue of
//! BM3D-CFA / CBM3D. **Performance note:** this is a faithful, unoptimised
//! reference implementation (full block-match search + two transform passes). The
//! tunable constants at the top trade speed for quality; for production on
//! high-megapixel phone RAWs you will want a smaller `SEARCH`/`STEP_REF`, GPU
//! offload, or to run at a reduced resolution.

use std::sync::OnceLock;

use rayon::prelude::*;
use rawler::rawimage::CFAConfig;

/// Patch size (N×N mosaic samples). 8×8 is the classic BM3D patch size — small
/// enough that the 2-D DCT is cheap, large enough to capture texture.
const N: usize = 8;
/// Block-match search half-window (in mosaic pixels). Larger = better matches,
/// slower.
const SEARCH: i64 = 16;
/// Reference grid step (overlap factor = `N / STEP_REF`). Smaller = more overlap,
/// smoother aggregation, slower.
const STEP_REF: usize = 4;
/// Maximum blocks per group. Capped, not required to be a power of two (we use a
/// 1-D DCT along K).
const K_MAX: usize = 16;
/// Maps the user `strength` to a noise standard deviation in the normalised 0..1
/// mosaic. `strength = 1` → `sigma ≈ 0.02`, a moderate read-noise level.
const STRENGTH_TO_SIGMA: f32 = 0.02;
/// Hard-threshold multiplier on `sigma` (BM3D's `2.7·sigma`).
const HARD_K: f32 = 2.7;
/// Block-match similarity threshold multiplier on `sigma`. A candidate patch is
/// grouped only if its raw SSD is ≤ `(MATCH_C·sigma)²·N²`.
const MATCH_C: f32 = 2.5;

/// Denoise the scaled mosaic with BM3D-CFA. `strength = None`/`0` is identity;
/// `cpp > 1` (pre-coloured) and size-mismatched input is left untouched.
pub(crate) fn denoise_bm3d_cfa(
    pixels: Vec<f32>,
    width: usize,
    height: usize,
    strength: Option<f32>,
    cfa: Option<&CFAConfig>,
) -> Vec<f32> {
    let Some(strength) = strength else {
        return pixels;
    };
    let strength = strength.clamp(0.0, 8.0);
    if strength == 0.0 || width < N || height < N || pixels.len() != width * height {
        return pixels;
    }

    let sigma = STRENGTH_TO_SIGMA * strength;
    // Per-pixel CFA colour id (0 for monochrome / non-CFA). Drives phase-matched
    // block matching so every compared pixel is the same colour.
    let color_img = build_color_img(width, height, cfa);

    // Stage 1 — hard-thresholding on the noisy image → basic estimate.
    let basic = bm3d_stage(&pixels, &pixels, width, height, &color_img, sigma, true);

    // Stage 2 — Wiener, matching on the basic estimate, filtering the original.
    bm3d_stage(&pixels, &basic, width, height, &color_img, sigma, false)
}

/// Run one BM3D stage. `src` is the image to filter; `match_img` is the image
/// block-matching runs on (the noisy image for the hard-threshold stage, the
/// basic estimate for the Wiener stage). Returns the aggregated image.
fn bm3d_stage(
    src: &[f32],
    match_img: &[f32],
    width: usize,
    height: usize,
    color_img: &[u8],
    sigma: f32,
    hard: bool,
) -> Vec<f32> {
    let w = width as i64;
    let h = height as i64;
    let n = width * height;

    // Reference top-left patch positions on a grid; every patch fits in-bounds.
    let r_end = h - N as i64;
    let c_end = w - N as i64;
    if r_end <= 0 || c_end <= 0 {
        return src.to_vec();
    }
    // Lock-free aggregation by 2-D tiles — the "tile + discard-core" scheme.
    //
    // A reference block at (r0, c0) contributes to a (2*SEARCH + N)-wide square
    // footprint: matched blocks can sit up to SEARCH away, and each then spreads
    // an N*N patch. We split the image into TILE x TILE *core* squares and, per
    // core, process every reference block whose footprint can reach it. A
    // contribution that lands in the surrounding halo is discarded — the
    // neighbouring tile whose core actually owns that pixel re-processes the same
    // block and keeps it. Cores are pairwise disjoint (different tile-row *or*
    // column band), so each tile writes only its own core into `out`: no mutex,
    // no merge step. The redundant re-processing of boundary blocks is pure
    // algorithm work (no contention) and is bounded by the halo/area ratio.
    //
    // Sharding: `par_chunks_mut` one band per TILE rows of `out`; within a band
    // the column tiles are processed in turn, each scattering its disjoint column
    // range. This keeps every write into `out` on a single thread and therefore
    // data-race free, while still parallelising across tile-row bands.
    const TILE: i64 = 256;
    let halo: i64 = SEARCH + N as i64 - 1; // 23: matches may land SEARCH away

    let num_tile_cols = ((w + TILE - 1) / TILE) as usize;

    let mut out = vec![0.0f32; n];
    let row_band = (TILE as usize) * width;
    out.par_chunks_mut(row_band).enumerate().for_each(|(tr0, band)| {
        let tr = (tr0 as i64) * TILE;
        let th = (band.len() / width) as i64;
        for tc0 in 0..num_tile_cols {
            let tc = (tc0 as i64) * TILE;
            let tw = ((w - tc).min(TILE)) as i64;

            let core = (th * tw) as usize;
            let mut acc = vec![0.0f32; core];
            let mut wsum = vec![0.0f32; core];

            // Reference blocks whose (2*SEARCH + N) footprint can reach this core.
            //
            // Why the two cutoffs differ by exactly N-1: a block is anchored at its
            // top-left and hangs *downward* N-1 rows, so relative to the reference
            // position r0 the contributed rows span
            //     [r0 - SEARCH,  r0 + SEARCH + N - 1]
            // i.e. SEARCH rows above r0 but SEARCH + N - 1 rows below. Inverting
            // that relation for "which r0 can touch rows [tr, tr+th-1]" swaps the
            // two numbers: the *lower* cutoff takes the wide halo (SEARCH + N - 1),
            // the *upper* cutoff only SEARCH. It is not a symmetric ±halo.
            //
            // `tr - halo` need not sit on the STEP_REF lattice, and stepping
            // STEP_REF from an off-lattice start would walk a *shifted* grid — a
            // different set of blocks, hence a silently different result. Snap
            // each start *up* to the next lattice point so we process exactly the
            // same grid the single-pass version used.
            //
            // Both ends are capped to keep the original's exclusive grid range
            // `(0..r_end).step_by(STEP_REF)`, i.e. the top row `r_end` itself is
            // NOT a reference position. (r_end > 0 is guaranteed by the early
            // return above, so `r_end - 1` never underflows.)
            let step = STEP_REF as i64;
            let r_lo = (((tr - halo).max(0) + step - 1) / step) * step;
            let r_hi = (tr + th - 1 + SEARCH).min(r_end - 1);
            let c_lo = (((tc - halo).max(0) + step - 1) / step) * step;
            let c_hi = (tc + tw - 1 + SEARCH).min(c_end - 1);
            let mut r0 = r_lo;
            while r0 <= r_hi {
                let mut c0 = c_lo;
                while c0 <= c_hi {
                    for (pix, val, wt) in
                        process_ref(r0, c0, src, match_img, width, height, color_img, sigma, hard)
                    {
                        let pr = (pix as i64) / w;
                        let pc = (pix as i64) % w;
                        if pr >= tr && pr < tr + th && pc >= tc && pc < tc + tw {
                            let li = ((pr - tr) * tw + (pc - tc)) as usize;
                            acc[li] += val;
                            wsum[li] += wt;
                        }
                    }
                    c0 += STEP_REF as i64;
                }
                r0 += STEP_REF as i64;
            }

            // Scatter this core (disjoint columns per row) into the band.
            for i in 0..th as usize {
                let row_global = (tr as usize + i) * width;
                let band_row = i * width;
                for j in 0..tw as usize {
                    let gi = row_global + tc as usize + j;
                    let li = i * tw as usize + j;
                    let v = if wsum[li] > 0.0 { acc[li] / wsum[li] } else { src[gi] };
                    band[band_row + tc as usize + j] = v;
                }
            }
        }
    });
    out
}

/// Process one reference block end-to-end: match, forward-transform, shrink,
/// inverse-transform, and emit per-pixel aggregation contributions
/// `(pixel_index, value · weight, weight)`.
fn process_ref(
    r0: i64,
    c0: i64,
    src: &[f32],
    match_img: &[f32],
    width: usize,
    height: usize,
    color_img: &[u8],
    sigma: f32,
    hard: bool,
) -> Vec<(usize, f32, f32)> {
    let w = width as i64;
    let h = height as i64;
    let phase = color_img[(r0 * w + c0) as usize];

    let tau = (MATCH_C * sigma).powi(2) * (N * N) as f32;
    let mut group: Vec<(i64, i64)> = vec![(r0, c0)]; // self always grouped
    let mut cand: Vec<(f32, (i64, i64))> = Vec::new();
    for dr in -SEARCH..=SEARCH {
        let rr = r0 + dr;
        if rr < 0 || rr + N as i64 > h {
            continue;
        }
        for dc in -SEARCH..=SEARCH {
            let cc = c0 + dc;
            if cc < 0 || cc + N as i64 > w {
                continue;
            }
            if dr == 0 && dc == 0 {
                continue;
            }
            // Phase match → same colour layout → colour-honest full-patch SSD.
            if color_img[(rr * w + cc) as usize] != phase {
                continue;
            }
            cand.push((patch_ssd(match_img, width, r0, c0, rr, cc), (rr, cc)));
        }
    }
    cand.sort_by(|a, b| a.0.partial_cmp(&b.0).unwrap_or(std::cmp::Ordering::Equal));
    for (d, pos) in cand {
        if group.len() >= K_MAX {
            break;
        }
        if d <= tau {
            group.push(pos);
        }
    }

    let k = group.len();
    let mut coeff_src = vec![0.0f32; N * N * k];
    forward_group(&group, src, width, &mut coeff_src);
    let mut coeff_match = vec![0.0f32; N * N * k];
    // 1-D DCT along the group axis (collaborative filtering domain).
    // Cached: only K_MAX distinct group sizes exist, see dct1_cached.
    let c = dct1_cached(k);
    apply_axis(&mut coeff_src, k, c, false);
    if !hard {
        // Wiener stage: the pilot (basic estimate) is 3-D transformed too, so its
        // energy at each 3-D coefficient location drives the shrinkage weight.
        forward_group(&group, match_img, width, &mut coeff_match);
        apply_axis(&mut coeff_match, k, c, false);
    }
    // Aggregation weight per block: uniform (1) for the hard-threshold stage,
    // total Wiener weight for the Wiener stage.
    let mut w_block = if hard { 1.0f32 } else { 0.0f32 };
    if hard {
        let lambda = HARD_K * sigma;
        for x in coeff_src.iter_mut() {
            if x.abs() < lambda {
                *x = 0.0;
            }
        }
    } else {
        let sig2 = sigma * sigma;
        for i in 0..coeff_src.len() {
            let e = coeff_match[i] * coeff_match[i];
            let w = e / (e + sig2);
            coeff_src[i] *= w;
            w_block += w;
        }
    }
    apply_axis(&mut coeff_src, k, c, true);

    // Inverse 2-D DCT per patch, emit aggregation contributions.
    let dct = dct_matrix();
    let mut contribs = Vec::with_capacity(N * N * k);
    for ki in 0..k {
        let (r, c) = group[ki];
        let mut dc = [0.0f32; N * N];
        for ij in 0..N * N {
            dc[ij] = coeff_src[ij * k + ki];
        }
        let patch = dct2_inverse(&dc, dct);
        for i in 0..N {
            for j in 0..N {
                let p = ((r + i as i64) * w + (c + j as i64)) as usize;
                contribs.push((p, patch[i * N + j] * w_block, w_block));
            }
        }
    }
    contribs
}

/// Extract the `N×N` patch at `(r0, c0)` and write its 2-D DCT coefficients into
/// `coeff` at `[(i*N+j)*k + ki]`.
fn forward_group(group: &[(i64, i64)], img: &[f32], width: usize, coeff: &mut [f32]) {
    let k = group.len();
    let c = dct_matrix();
    for (ki, &(r0, c0)) in group.iter().enumerate() {
        let mut patch = [0.0f32; N * N];
        for i in 0..N {
            let row = (r0 + i as i64) * width as i64;
            for j in 0..N {
                patch[i * N + j] = img[(row + (c0 + j as i64)) as usize];
            }
        }
        let dct = dct2_forward(&patch, c);
        for ij in 0..N * N {
            coeff[ij * k + ki] = dct[ij];
        }
    }
}

/// Apply a 1-D orthonormal DCT along the `K` axis at every `(i,j)` of the
/// `N×N×K` coefficient volume (row-major `[ij*k + ki]`). `inverse` selects the
/// forward transform `C` or its transpose `Cᵀ` (the inverse of the orthonormal
/// DCT-II).
fn apply_axis(coeff: &mut [f32], k: usize, c: &[Vec<f32>], inverse: bool) {
    for ij in 0..N * N {
        let base = ij * k;
        let slice: Vec<f32> = coeff[base..base + k].to_vec();
        for out in 0..k {
            let mut s = 0.0;
            if !inverse {
                for q in 0..k {
                    s += c[out][q] * slice[q];
                }
            } else {
                for p in 0..k {
                    s += c[p][out] * slice[p];
                }
            }
            coeff[base + out] = s;
        }
    }
}

/// Sum of squared per-pixel differences between two `N×N` patches. Phase already
/// matched by the caller, so every compared pixel shares a colour.
fn patch_ssd(img: &[f32], width: usize, r0: i64, c0: i64, r1: i64, c1: i64) -> f32 {
    let w = width as i64;
    let mut s = 0.0;
    for i in 0..N {
        let row_a = (r0 + i as i64) * w;
        let row_b = (r1 + i as i64) * w;
        for j in 0..N {
            let a = img[(row_a + (c0 + j as i64)) as usize];
            let b = img[(row_b + (c1 + j as i64)) as usize];
            let d = a - b;
            s += d * d;
        }
    }
    s
}

/// Per-pixel CFA colour id, `0` where there is no CFA (monochrome / non-CFA).
fn build_color_img(width: usize, height: usize, cfa: Option<&CFAConfig>) -> Vec<u8> {
    match cfa {
        Some(cfg) => {
            let period_w = cfg.cfa.width;
            let period_h = cfg.cfa.height;
            let mut v = vec![0u8; width * height];
            // Every output cell depends only on its own (row, col), so this is a
            // purely separable fill — sharded by **row band** (`par_chunks_mut`),
            // never per element, per the crate's rayon discipline. The row's
            // vertical CFA phase is hoisted out of the column loop for the same
            // reason it was worth hoisting before: it is constant along the row.
            v.par_chunks_mut(width).enumerate().for_each(|(r, row)| {
                let pr = (r as i64).rem_euclid(period_h as i64) as usize;
                for c in 0..width {
                    let pc = (c as i64).rem_euclid(period_w as i64) as usize;
                    row[c] = cfg.cfa.color_at(pr, pc) as u8;
                }
            });
            v
        }
        None => vec![0u8; width * height],
    }
}

/// 2-D orthonormal DCT-II forward: `T = C·B·Cᵀ`.
fn dct2_forward(b: &[f32; N * N], c: &[[f32; N]; N]) -> [f32; N * N] {
    let mut t = [0.0f32; N * N];
    for i in 0..N {
        for j in 0..N {
            let mut s = 0.0;
            for kk in 0..N {
                for ll in 0..N {
                    s += c[i][kk] * b[kk * N + ll] * c[j][ll];
                }
            }
            t[i * N + j] = s;
        }
    }
    t
}

/// 2-D orthonormal DCT-II inverse: `B = Cᵀ·T·C`.
fn dct2_inverse(t: &[f32; N * N], c: &[[f32; N]; N]) -> [f32; N * N] {
    let mut b = [0.0f32; N * N];
    for kk in 0..N {
        for ll in 0..N {
            let mut s = 0.0;
            for i in 0..N {
                for j in 0..N {
                    s += c[i][kk] * t[i * N + j] * c[j][ll];
                }
            }
            b[kk * N + ll] = s;
        }
    }
    b
}

/// Orthonormal 1-D DCT-II matrix `C` of size `m×m`, `C[p][q] = α_p·cos(…)`.
fn dct1_matrix(m: usize) -> Vec<Vec<f32>> {
    let mm = m as f32;
    let mut c = vec![vec![0.0f32; m]; m];
    for p in 0..m {
        let alpha = if p == 0 {
            (1.0 / mm).sqrt()
        } else {
            (2.0 / mm).sqrt()
        };
        for q in 0..m {
            c[p][q] = alpha
                * (std::f32::consts::PI * (p as f32) * (2.0 * (q as f32) + 1.0) / (2.0 * mm)).cos();
        }
    }
    c
}

/// Memoised `dct1_matrix(k)` for every reachable group size `1..=K_MAX`.
///
/// `process_ref` asks for one `k×k` transformer per reference block — on a 24 MP
/// frame that is ~1.5M builds, each paying `k²` cosines plus `k + 1` heap
/// allocations. Only `K_MAX` distinct sizes actually occur, so each is built once
/// and reused: `dct1_matrix` is a pure function of `k`, so handing back the
/// stored result is numerically identical to rebuilding it — the numbers do not
/// move. Tile aggregation made this proportionately more worth doing, since
/// boundary blocks are now re-processed and `process_ref` runs ~1.33× more often.
///
/// One `OnceLock` slot per size keeps the table lazy (sizes that never occur are
/// never built) and safe to hit concurrently from every rayon worker; after
/// warm-up the hot path is a single `get()`.
fn dct1_cached(k: usize) -> &'static Vec<Vec<f32>> {
    // `k` is `group.len()`, and a group always contains at least the reference
    // block itself, so `1 <= k <= K_MAX` holds at every call site.
    static CACHE: [OnceLock<Vec<Vec<f32>>>; K_MAX] = [const { OnceLock::new() }; K_MAX];
    CACHE[k - 1].get_or_init(|| dct1_matrix(k))
}

/// The 8×8 orthonormal DCT-II matrix, built once.
fn dct_matrix() -> &'static [[f32; N]; N] {
    static M: OnceLock<[[f32; N]; N]> = OnceLock::new();
    M.get_or_init(|| {
        let nn = N as f32;
        let mut m = [[0.0f32; N]; N];
        for i in 0..N {
            let alpha = if i == 0 {
                (1.0 / nn).sqrt()
            } else {
                (2.0 / nn).sqrt()
            };
            for j in 0..N {
                m[i][j] = alpha
                    * (std::f32::consts::PI * (i as f32) * (2.0 * (j as f32) + 1.0) / (2.0 * nn))
                        .cos();
            }
        }
        m
    })
}
