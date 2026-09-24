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
//! h(x)   = clamp(h0[p] * g, 0, cap_tail)         // refined spatially-varying floor; cap_tail = `ceiling` param
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
    ceiling: Option<f32>,
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

    // Mode routing: `ceiling` selects the guided (2D) branch; its absence selects
    // the scalar (global-floor) branch. The caller returns identity if both are
    // `None`, but guard anyway.
    let guided = ceiling.is_some();
    // Global per-plane floor quantile (`percentile`); anchors magnitude and backs
    // the scalar branch. Defaults to `DEFAULT_TAIL` when unset.
    let floor_tail = percentile.unwrap_or(DEFAULT_TAIL).clamp(0.0, 1.0);
    // Soft-mask ceiling (max over-dehaze) for the guided branch; defaults to the
    // floor tail when unset (defensive — guided implies `ceiling` is `Some`).
    let cap_tail = ceiling.unwrap_or(DEFAULT_TAIL).clamp(0.0, 1.0);
    let nplanes = planes.nplanes();
    let h0 = plane_haze_floors(&pixels, width, height, planes, active, floor_tail);

    // Full-resolution haze field, filled plane by plane.
    let mut hfield = vec![0.0f32; width * height];
    let period = planes.period();

    for p in 0..nplanes {
        if guided && planes.is_regular() {
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
            // the local dark channel. The dehaze ceiling `cap_tail` is applied on
            // the final floor (the `clamp(.., cap_tail)` at scatter time): it caps
            // how much haze floor any region may claim — i.e. the maximum
            // over-dehaze, independent of the floor quantile `floor_tail`.
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
                    let val = (anchor * g[i * gw + j]).clamp(0.0, cap_tail.min(1.0));
                    hfield[r * width + c] = val;
                }
            }
        } else {
            // Scalar (non-guided) branch: global per-plane floor (old behaviour),
            // whether the plane is non-regular or guided mode was not selected.
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

    // Apply the cleared value, blended by strength. Per-pixel and order-free, so
    // parallelised with rayon over the full-resolution buffer (`OPTIMZ-PERFRM-000007`).
    pixels
        .par_iter_mut()
        .enumerate()
        .for_each(|(idx, p)| {
            let h = hfield[idx];
            let denom = (1.0 - h).max(1e-6);
            let cleared = ((*p - h) / denom).max(0.0);
            *p = *p * (1.0 - strength) + cleared * strength;
        });
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
    corr_i
        .par_iter_mut()
        .zip(corr_ip.par_iter_mut())
        .enumerate()
        .for_each(|(k, (ci, cip))| {
            let g = guide[k];
            *ci = g * g;
            *cip = g * src[k];
        });
    let mean_ii = box_mean(&corr_i, w, h, radius);
    let mean_ip = box_mean(&corr_ip, w, h, radius);

    let mut a = vec![0.0f32; n];
    let mut b = vec![0.0f32; n];
    a.par_iter_mut()
        .zip(b.par_iter_mut())
        .enumerate()
        .for_each(|(k, (ak, bk))| {
            let var_i = mean_ii[k] - mean_i[k] * mean_i[k];
            let cov_ip = mean_ip[k] - mean_i[k] * mean_p[k];
            *ak = cov_ip / (var_i + eps);
            *bk = mean_p[k] - *ak * mean_i[k];
        });

    let mean_a = box_mean(&a, w, h, radius);
    let mean_b = box_mean(&b, w, h, radius);

    let mut out = vec![0.0f32; n];
    out.par_iter_mut().enumerate().for_each(|(k, ok)| {
        *ok = mean_a[k] * guide[k] + mean_b[k];
    });
    out
}

/// Separable box mean with window radius `radius` (window = `2*radius + 1`),
/// implemented with running sums (O(N)). Both axes are parallelised with rayon:
/// each row/column is independent, so the horizontal pass runs per-row and the
/// vertical pass per-column, each with distinct output indices (`OPTIMZ-PERFRM-000007`).
fn box_mean(src: &[f32], w: usize, h: usize, radius: usize) -> Vec<f32> {
    let n = w * h;
    if n == 0 {
        return Vec::new();
    }
    let r = radius.max(1).min((w.max(h) / 2).saturating_add(1));

    // Horizontal pass: each row is independent -> parallel over rows.
    let mut tmp = vec![0.0f32; n];
    tmp.par_chunks_mut(w)
        .zip(src.par_chunks(w))
        .for_each(|(tmp_row, srow)| {
            let mut acc = 0.0f32;
            let mut q = 0usize; // left edge of the window (exclusive)
            for x in 0..w {
                acc += srow[x];
                while q < x.saturating_sub(r) + 1 {
                    acc -= srow[q];
                    q += 1;
                }
                let l = x.saturating_sub(r);
                let rr = (x + r).min(w - 1);
                let cnt = (rr - l + 1) as f32;
                tmp_row[x] = acc / cnt;
            }
        });

    // Vertical pass: each column is independent. Compute every column in parallel
    // (reads `tmp` at stride `w`, no aliasing), then scatter back per-row in parallel.
    let mut out = vec![0.0f32; n];
    let cols: Vec<Vec<f32>> = (0..w)
        .into_par_iter()
        .map(|x| {
            let mut col = vec![0.0f32; h];
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
                col[y] = acc / cnt;
            }
            col
        })
        .collect();
    out.par_chunks_mut(w)
        .enumerate()
        .for_each(|(y, out_row)| {
            for x in 0..w {
                out_row[x] = cols[x][y];
            }
        });
    out
}

/// Separable box minimum over window radius `radius` (centred). Implemented with
/// a monotonic deque (O(N)) via forward+backward passes on each axis. Both axes
/// are parallelised with rayon: each row/column is independent with distinct
/// output indices (`OPTIMZ-PERFRM-000007`).
fn box_min(src: &[f32], w: usize, h: usize, radius: usize) -> Vec<f32> {
    let n = w * h;
    if n == 0 {
        return Vec::new();
    }
    let r = radius.max(1);

    let mut fwd = vec![0.0f32; n];
    let mut bwd = vec![0.0f32; n];

    // Horizontal: fwd[x] = min over [0, x+r], bwd[x] = min over [x-r, w-1];
    // combined min covers the centred window [x-r, x+r]. Each row is independent.
    fwd.par_chunks_mut(w)
        .zip(bwd.par_chunks_mut(w))
        .zip(src.par_chunks(w))
        .for_each(|((fwd_row, bwd_row), srow)| {
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
                    if v >= srow[x] {
                        dq.pop_back();
                    } else {
                        break;
                    }
                }
                dq.push_back((x, srow[x]));
                fwd_row[x] = dq.front().unwrap().1;
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
                    if v >= srow[x] {
                        dq.pop_back();
                    } else {
                        break;
                    }
                }
                dq.push_back((x, srow[x]));
                bwd_row[x] = dq.front().unwrap().1;
            }
            for x in 0..w {
                fwd_row[x] = fwd_row[x].min(bwd_row[x]);
            }
        });

    // Vertical, same scheme, reading from `fwd`. Each column is independent, so
    // gather every column in parallel (stride `w`, no aliasing) then scatter.
    let mut out = vec![0.0f32; n];
    let cols: Vec<Vec<f32>> = (0..w)
        .into_par_iter()
        .map(|x| {
            let mut bwd_col = vec![0.0f32; h];
            let mut dq: VecDeque<(usize, f32)> = VecDeque::new();
            for y in 0..h {
                let v = fwd[y * w + x];
                while let Some(&(iy, _)) = dq.front() {
                    if iy + r < y {
                        dq.pop_front();
                    } else {
                        break;
                    }
                }
                while let Some(&(_, vv)) = dq.back() {
                    if vv >= v {
                        dq.pop_back();
                    } else {
                        break;
                    }
                }
                dq.push_back((y, v));
                bwd_col[y] = dq.front().unwrap().1;
            }
            let mut dq: VecDeque<(usize, f32)> = VecDeque::new();
            let mut col = vec![0.0f32; h];
            for y in (0..h).rev() {
                let v = fwd[y * w + x];
                while let Some(&(iy, _)) = dq.front() {
                    if iy > y + r {
                        dq.pop_front();
                    } else {
                        break;
                    }
                }
                while let Some(&(_, vv)) = dq.back() {
                    if vv >= v {
                        dq.pop_back();
                    } else {
                        break;
                    }
                }
                dq.push_back((y, v));
                col[y] = dq.front().unwrap().1.min(bwd_col[y]);
            }
            col
        })
        .collect();
    out.par_chunks_mut(w)
        .enumerate()
        .for_each(|(y, out_row)| {
            for x in 0..w {
                out_row[x] = cols[x][y];
            }
        });
    out
}
