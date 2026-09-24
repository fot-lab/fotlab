//! Dehaze algorithm core, moved out of `dehaze.rs`.
//!
//! This module owns the actual pixel work:
//!
//! 1. **Global per-plane haze floor** (`plane_haze_floors`) — the histogram
//!    percentile floor from the original `dehaze.rs`, kept as the magnitude
//!    anchor and as the fallback for non-regular CFAs.
//! 2. **Fast guided filter** (`guided_filter`, He & Sun 2015) — a box-blur
//!    implementation on a single-channel regular grid, reused per colour plane.
//! 3. **Spatial dehaze** (`dehaze`) — restores the 2D awareness the original
//!    scalar floor lacked: for every *regular* plane it builds a
//!    sub-lattice dark channel (local box-min), normalises it against the
//!    global floor, and refines it with a guided filter whose **guide is the
//!    plane's own mosaic values** (per-plane own-guide, see `FOTLAB-RAWLER-000010`).
//!    The result is a smooth, edge-aware, spatially-varying per-plane haze
//!    field `h(x)`; non-regular planes keep the global scalar floor.
//!
//! Pipeline per regular plane `p`:
//! ```text
//! guide  = plane sub-lattice values (0..1)
//! dark   = box_min(guide)                         // local dark channel
//! src    = dark / h0[p]                           // normalised local haze proxy
//! g      = guided_filter(guide, src, r, eps)      // edge-aware, own guide
//! h(x)   = clamp(h0[p] * g, 0, tail)             // refined spatially-varying floor; ceiling = user percentile
//! ```
//! In uniform haze `dark ≈ h0` everywhere, so `g ≈ 1` and `h ≈ h0` — the old
//! behaviour is recovered. In thick-haze patches `dark > h0` raises `h`, in
//! clear patches it lowers `h`, so location is respected.
//!
//! Apply (per pixel): `cleared = (v − h) / (1 − h)`, clamped at 0, blended by
//! `strength`.

use std::collections::VecDeque;

use rayon::prelude::*;

use crate::cfa::CfaPlanes;

/// Histogram bins over the normalised [0,1] mosaic.
const BINS: usize = 256;

/// Default haze-floor percentile of each colour plane's histogram.
const DEFAULT_TAIL: f32 = 0.01;

/// Dehaze the scaled CFA mosaic (algorithm core; orchestration lives in `dehaze.rs`).
///
/// See `crate::dehaze` for the public contract. `dark_radius` / `guide_radius`
/// are the sub-lattice dark-channel box radius and guided-filter radius (in
/// sub-lattice pixels); `guide_eps` is the guided-filter epsilon.
pub(crate) fn dehaze(
    mut pixels: Vec<f32>,
    width: usize,
    height: usize,
    strength: Option<f32>,
    percentile: Option<f32>,
    planes: &CfaPlanes,
    active: Option<(usize, usize, usize, usize)>,
    dark_radius: usize,
    guide_radius: usize,
    guide_eps: f32,
) -> Vec<f32> {
    let strength = match strength {
        Some(s) => s.clamp(0.0, 1.0),
        None => return pixels,
    };
    if strength == 0.0 || width == 0 || height == 0 || pixels.len() != width * height {
        return pixels;
    }

    // Global per-plane floor (existing histogram algorithm; percentile honored).
    // Anchors magnitude and backs the non-regular fallback.
    let tail = percentile.unwrap_or(DEFAULT_TAIL).clamp(0.0, 1.0);
    let nplanes = planes.nplanes();
    let h0 = plane_haze_floors(&pixels, width, height, planes, active, tail);

    // Full-resolution haze field, filled plane by plane.
    let mut hfield = vec![0.0f32; width * height];
    let period = planes.period();

    for p in 0..nplanes {
        if planes.is_regular() {
            // Regular plane -> sub-lattice guided path.
            let (dr, dc) = planes.offset(p);
            let (gw, gh) = planes.sublattice_dims(width, height, p);
            if gw == 0 || gh == 0 {
                continue;
            }

            // Extract the plane's sub-lattice values as the guided-filter guide.
            let mut guide = vec![0.0f32; gw * gh];
            for i in 0..gh {
                for j in 0..gw {
                    let r = dr + period * i;
                    let c = dc + period * j;
                    guide[i * gw + j] = pixels[r * width + c];
                }
            }

            // Local dark channel (box-min) on the sub-lattice.
            let dark = box_min(&guide, gw, gh, dark_radius);

            // Normalised local haze proxy relative to the global floor. The
            // guided filter is linear in `src`, so `h = h0 * GF(dark/h0) =
            // GF(dark)` and `h0` cancels; below the floor the field simply tracks
            // the local dark channel. The dehaze ceiling is applied on the final
            // floor (the `clamp(.., tail)` at scatter time), where `tail` is the
            // user percentile: in guided mode the percentile caps how much haze
            // floor any region may claim — i.e. the maximum over-dehaze.
            let anchor = h0[p].max(1e-4);
            let mut src = vec![0.0f32; gw * gh];
            for k in 0..gw * gh {
                src[k] = (dark[k] / anchor).max(0.0);
            }

            // Refine with the plane's OWN guide.
            let g = guided_filter(&guide, &src, gw, gh, guide_radius, guide_eps);

            // Scatter the refined field back to the plane's pixels.
            for i in 0..gh {
                for j in 0..gw {
                    let r = dr + period * i;
                    let c = dc + period * j;
                    let val = (anchor * g[i * gw + j]).clamp(0.0, tail.min(1.0));
                    hfield[r * width + c] = val;
                }
            }
        } else {
            // Non-regular plane -> global scalar floor (old behaviour).
            let h = h0[p];
            for r in 0..height {
                for c in 0..width {
                    if planes.plane_at(r, c) == p {
                        hfield[r * width + c] = h;
                    }
                }
            }
        }
    }

    // Apply the cleared value, blended by strength.
    // (`active` was already consumed by `plane_haze_floors` for the global floor.)
    for (idx, p) in pixels.iter_mut().enumerate() {
        let h = hfield[idx];
        let denom = (1.0 - h).max(1e-6);
        let cleared = ((*p - h) / denom).max(0.0);
        *p = *p * (1.0 - strength) + cleared * strength;
    }
    pixels
}

/// Estimate a haze floor per colour plane as the `tail` quantile of each plane's
/// 256-bin 0..1 histogram. Accumulation is restricted to `active` when set.
fn plane_haze_floors(
    pixels: &[f32],
    width: usize,
    height: usize,
    planes: &CfaPlanes,
    active: Option<(usize, usize, usize, usize)>,
    tail: f32,
) -> Vec<f32> {
    let nplanes = planes.nplanes();

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
                if let Some((ax, ay, aw, ah)) = active {
                    if col < ax || col >= ax + aw || row >= ay + ah {
                        continue;
                    }
                }
                let plane = planes.plane_at(row, col);
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

/// Fast guided filter (He & Sun 2015) on a single-channel regular grid.
///
/// `guide` and `src` must both have length `w * h`. `radius` is in pixels and
/// `eps` the regularisation (smaller = more edge-preserving). The guide and src
/// may be the same buffer (self-guided).
pub(crate) fn guided_filter(
    guide: &[f32],
    src: &[f32],
    w: usize,
    h: usize,
    radius: usize,
    eps: f32,
) -> Vec<f32> {
    let n = w * h;
    if n == 0 || guide.len() != n || src.len() != n {
        return vec![0.0f32; n];
    }

    let mean_i = box_mean(guide, w, h, radius);
    let mean_p = box_mean(src, w, h, radius);

    let mut corr_i = vec![0.0f32; n];
    let mut corr_ip = vec![0.0f32; n];
    for k in 0..n {
        corr_i[k] = guide[k] * guide[k];
        corr_ip[k] = guide[k] * src[k];
    }
    let mean_ii = box_mean(&corr_i, w, h, radius);
    let mean_ip = box_mean(&corr_ip, w, h, radius);

    let mut a = vec![0.0f32; n];
    let mut b = vec![0.0f32; n];
    for k in 0..n {
        let var_i = mean_ii[k] - mean_i[k] * mean_i[k];
        let cov_ip = mean_ip[k] - mean_i[k] * mean_p[k];
        a[k] = cov_ip / (var_i + eps);
        b[k] = mean_p[k] - a[k] * mean_i[k];
    }

    let mean_a = box_mean(&a, w, h, radius);
    let mean_b = box_mean(&b, w, h, radius);

    let mut out = vec![0.0f32; n];
    for k in 0..n {
        out[k] = mean_a[k] * guide[k] + mean_b[k];
    }
    out
}

/// Separable box mean with window radius `radius` (window = `2*radius + 1`),
/// implemented with running sums (O(N)).
fn box_mean(src: &[f32], w: usize, h: usize, radius: usize) -> Vec<f32> {
    let n = w * h;
    if n == 0 {
        return Vec::new();
    }
    let r = radius.max(1).min((w.max(h) / 2).saturating_add(1));

    // Horizontal pass.
    let mut tmp = vec![0.0f32; n];
    for y in 0..h {
        let row = y * w;
        let mut acc = 0.0f32;
        let mut q = 0usize; // left edge of the window (exclusive)
        for x in 0..w {
            acc += src[row + x];
            while q < x.saturating_sub(r) + 1 {
                acc -= src[row + q];
                q += 1;
            }
            let l = x.saturating_sub(r);
            let rr = (x + r).min(w - 1);
            let cnt = (rr - l + 1) as f32;
            tmp[row + x] = acc / cnt;
        }
    }

    // Vertical pass.
    let mut out = vec![0.0f32; n];
    for x in 0..w {
        let mut acc = 0.0f32;
        let mut q = 0usize;
        for y in 0..h {
            acc += tmp[y * w + x];
            while q < y.saturating_sub(r) + 1 {
                acc -= tmp[q * w + x];
                q += 1;
            }
            let l = y.saturating_sub(r);
            let rr = (y + r).min(h - 1);
            let cnt = (rr - l + 1) as f32;
            out[y * w + x] = acc / cnt;
        }
    }
    out
}

/// Separable box minimum over window radius `radius` (centred). Implemented with
/// a monotonic deque (O(N)) via forward+backward passes on each axis.
fn box_min(src: &[f32], w: usize, h: usize, radius: usize) -> Vec<f32> {
    let n = w * h;
    if n == 0 {
        return Vec::new();
    }
    let r = radius.max(1);

    let mut fwd = vec![0.0f32; n];
    let mut bwd = vec![0.0f32; n];

    // Horizontal: fwd[x] = min over [0, x+r], bwd[x] = min over [x-r, w-1];
    // combined min covers the centred window [x-r, x+r].
    for y in 0..h {
        let row = y * w;
        let mut dq: VecDeque<(usize, f32)> = VecDeque::new();
        for x in 0..w {
            while let Some(&(idx, _)) = dq.front() {
                if idx + r < x {
                    dq.pop_front();
                } else {
                    break;
                }
            }
            while let Some(&(_, v)) = dq.back() {
                if v >= src[row + x] {
                    dq.pop_back();
                } else {
                    break;
                }
            }
            dq.push_back((x, src[row + x]));
            fwd[row + x] = dq.front().unwrap().1;
        }
        let mut dq: VecDeque<(usize, f32)> = VecDeque::new();
        for x in (0..w).rev() {
            while let Some(&(idx, _)) = dq.front() {
                if idx > x + r {
                    dq.pop_front();
                } else {
                    break;
                }
            }
            while let Some(&(_, v)) = dq.back() {
                if v >= src[row + x] {
                    dq.pop_back();
                } else {
                    break;
                }
            }
            dq.push_back((x, src[row + x]));
            bwd[row + x] = dq.front().unwrap().1;
        }
        for x in 0..w {
            fwd[row + x] = fwd[row + x].min(bwd[row + x]);
        }
    }

    // Vertical, same scheme, reading from `fwd` and writing the result.
    let mut out = vec![0.0f32; n];
    for x in 0..w {
        let mut dq: VecDeque<(usize, f32)> = VecDeque::new();
        for y in 0..h {
            let idx = y * w + x;
            while let Some(&(iy, _)) = dq.front() {
                if iy + r < y {
                    dq.pop_front();
                } else {
                    break;
                }
            }
            while let Some(&(_, v)) = dq.back() {
                if v >= fwd[idx] {
                    dq.pop_back();
                } else {
                    break;
                }
            }
            dq.push_back((y, fwd[idx]));
            bwd[idx] = dq.front().unwrap().1;
        }
        let mut dq: VecDeque<(usize, f32)> = VecDeque::new();
        for y in (0..h).rev() {
            let idx = y * w + x;
            while let Some(&(iy, _)) = dq.front() {
                if iy > y + r {
                    dq.pop_front();
                } else {
                    break;
                }
            }
            while let Some(&(_, v)) = dq.back() {
                if v >= fwd[idx] {
                    dq.pop_back();
                } else {
                    break;
                }
            }
            dq.push_back((y, fwd[idx]));
            out[idx] = dq.front().unwrap().1.min(bwd[idx]);
        }
    }
    out
}
