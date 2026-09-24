//! Dehaze algorithm core, moved out of `dehaze.rs`.
//!
//! This module owns the actual pixel work:
//!
//! 1. **Global per-plane haze floor** (`plane_haze_floors`) — the histogram
//!    percentile floor from the original `dehaze.rs`, kept as the scalar
//!    fallback for non-regular CFAs (and skipped entirely when every plane
//!    takes the guided path).
//! 2. **Fast guided filter** (`guided_filter`, He & Sun 2015) — a box-blur
//!    implementation on a single-channel regular grid, reused per colour plane.
//! 3. **Spatial dehaze** (`dehaze`) — restores the 2D awareness the original
//!    scalar floor lacked: for every *regular* plane it builds a
//!    sub-lattice dark channel (local box-min) and refines it with a guided
//!    filter whose **guide is the plane's own mosaic values** (per-plane
//!    own-guide, see `FOTLAB-RAWLER-000010`). The result is a smooth,
//!    edge-aware, spatially-varying per-plane haze field `h(x)`; non-regular
//!    planes keep the global scalar floor.
//!
//! Pipeline per regular plane `p`:
//! ```text
//! guide  = plane sub-lattice values (0..1)
//! dark   = box_min(guide)                         // local dark channel
//! g      = guided_filter(guide, dark, r, eps)     // edge-aware, own guide
//! h(x)   = clamp(g, 0, cap_tail)                  // spatially-varying floor; cap_tail = `ceiling` param
//! ```
//! In uniform haze `dark ≈ h0` everywhere and the guided filter passes such a
//! smooth field through, so `h ≈ h0` — the old behaviour is recovered. In
//! thick-haze patches `dark > h0` raises `h`, in clear patches it lowers `h`,
//! so location is respected. (An earlier revision normalised `dark` by `h0`
//! before filtering and multiplied it back after; the guided filter is linear
//! in `src`, so that anchor cancelled exactly and has been removed.)
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
    // The scalar branch is the only consumer of the global per-plane floors;
    // skip the histogram pass entirely when every plane takes the guided path.
    let h0 = if guided && planes.is_regular() {
        Vec::new()
    } else {
        plane_haze_floors(&pixels, width, height, planes, active, floor_tail)
    };

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

            // Refine the local dark channel with the plane's OWN guide. The
            // guided filter is linear in `src`, so the old `anchor = h0[p]`
            // normalisation cancelled exactly between `src = dark/h0` and
            // `h = h0·g` (verified to machine epsilon) — feeding `dark`
            // directly is the same field with one less buffer and one less
            // divide/multiply pass. The dehaze ceiling `cap_tail` is applied on
            // the final floor (the `clamp(.., cap_tail)` at scatter time): it
            // caps how much haze floor any region may claim — i.e. the maximum
            // over-dehaze, independent of the floor quantile `floor_tail`.
            let g = guided_filter(&guide, &dark, gw, gh, guide_radius, guide_eps);

            // Scatter the refined field back to the plane's pixels.
            for i in 0..gh {
                for j in 0..gw {
                    let r = dr + period * i;
                    let c = dc + period * j;
                    let val = g[i * gw + j].clamp(0.0, cap_tail.min(1.0));
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

/// Box mean with window radius `radius` (window = `2*radius + 1`, clamped at the
/// borders, divided by the *actual* sample count), computed from a summed-area
/// table (integral image): O(N) build + O(1) per-pixel query.
///
/// The table is `f64`, not `f32`: on a half-resolution Bayer sub-lattice
/// (~4000×3000 for a 50 MP frame) the running sums reach ~1.2e7, where an `f32`
/// ulp is already 1.0 — larger than the window sums being recovered by
/// differencing. `f64` keeps that subtraction exact.
///
/// The previous running-sum implementation was subtly wrong: a single left-to-
/// right pass can never see the `r` samples ahead of `x`, so it divided the
/// trailing-only partial sum by the full centred-window count (a constant-1
/// field came back as `(r/(2r+1))²` in the interior instead of 1.0). The
/// integral image evaluates the true centred window directly, so there is no
/// running window to get wrong. `box_min` below already used the correct
/// forward+backward scheme; this function now matches its semantics.
fn box_mean(src: &[f32], w: usize, h: usize, radius: usize) -> Vec<f32> {
    let n = w * h;
    if n == 0 {
        return Vec::new();
    }
    let r = radius.max(1).min((w.max(h) / 2).saturating_add(1));

    // Summed-area table with a zero row/column border: sat[(y+1)*iw + (x+1)] is
    // the sum of src over [0..=y] × [0..=x].
    let iw = w + 1;
    let mut sat = vec![0.0f64; iw * (h + 1)];
    // Row prefix sums (each output row independent -> parallel over rows).
    sat[iw..]
        .par_chunks_mut(iw)
        .zip(src.par_chunks(w))
        .for_each(|(srow, src_row)| {
            let mut acc = 0.0f64;
            for x in 0..w {
                acc += src_row[x] as f64;
                srow[x + 1] = acc;
            }
        });
    // Column prefix sums: sat[y][x] += sat[y-1][x]. Sequential over y, but
    // strictly row-major streaming (two passes of linear traffic), which beats a
    // parallel strided column walk on cache.
    for y in 1..=h {
        let (above, below) = sat.split_at_mut(y * iw);
        let prev = &above[(y - 1) * iw..y * iw];
        let cur = &mut below[..iw];
        for x in 0..iw {
            cur[x] += prev[x];
        }
    }

    // O(1) window queries, each output row independent -> parallel over rows.
    let mut out = vec![0.0f32; n];
    out.par_chunks_mut(w)
        .enumerate()
        .for_each(|(y, out_row)| {
            let y1 = y.saturating_sub(r);
            let y2 = (y + r).min(h - 1);
            let rows = (y2 - y1 + 1) as f64;
            let (top, bot) = (y1 * iw, (y2 + 1) * iw);
            for x in 0..w {
                let x1 = x.saturating_sub(r);
                let x2 = (x + r).min(w - 1);
                let sum = sat[bot + x2 + 1] - sat[top + x2 + 1] - sat[bot + x1] + sat[top + x1];
                out_row[x] = (sum / (rows * (x2 - x1 + 1) as f64)) as f32;
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

#[cfg(test)]
mod tests {
    use super::*;

    /// Brute-force centred-window mean: the definition `box_mean` must satisfy.
    /// Applies the same radius clamp as `box_mean` so the windows match.
    fn reference_box_mean(src: &[f32], w: usize, h: usize, radius: usize) -> Vec<f32> {
        let r = radius.max(1).min((w.max(h) / 2).saturating_add(1));
        let mut out = vec![0.0f32; w * h];
        for y in 0..h {
            let y1 = y.saturating_sub(r);
            let y2 = (y + r).min(h - 1);
            for x in 0..w {
                let x1 = x.saturating_sub(r);
                let x2 = (x + r).min(w - 1);
                let mut sum = 0.0f64;
                for yy in y1..=y2 {
                    for xx in x1..=x2 {
                        sum += src[yy * w + xx] as f64;
                    }
                }
                out[y * w + x] = (sum / ((y2 - y1 + 1) * (x2 - x1 + 1)) as f64) as f32;
            }
        }
        out
    }

    /// Deterministic pseudo-random field (no rand dependency).
    fn pseudo_random(w: usize, h: usize) -> Vec<f32> {
        let mut s = 0x12345678u32;
        (0..w * h)
            .map(|_| {
                // xorshift32
                s ^= s << 13;
                s ^= s >> 17;
                s ^= s << 5;
                (s as f32) / (u32::MAX as f32)
            })
            .collect()
    }

    fn assert_close(a: &[f32], b: &[f32], tol: f32) {
        assert_eq!(a.len(), b.len());
        for (i, (x, y)) in a.iter().zip(b.iter()).enumerate() {
            assert!(
                (x - y).abs() <= tol,
                "mismatch at {i}: {x} vs {y} (tol {tol})"
            );
        }
    }

    /// The regression this rewrite fixes: a constant field must come back
    /// constant. The old running-sum version returned `(r/(2r+1))²` in the
    /// interior (e.g. ≈0.22 for r=8), silently flattening every guided-filter
    /// mean it fed.
    #[test]
    fn box_mean_preserves_constant_field() {
        let (w, h) = (40, 40);
        let src = vec![1.0f32; w * h];
        for r in [1, 3, 8] {
            let out = box_mean(&src, w, h, r);
            assert_close(&out, &src, 1e-6);
        }
    }

    /// Non-constant field, odd and even radii, including a radius that exceeds
    /// half the frame (exercising the radius clamp): match the brute-force
    /// centred-window definition everywhere, borders included.
    #[test]
    fn box_mean_matches_brute_force() {
        let (w, h) = (37, 23);
        let src = pseudo_random(w, h);
        for r in [1, 2, 5, 30] {
            let want = reference_box_mean(&src, w, h, r);
            let got = box_mean(&src, w, h, r);
            assert_close(&got, &want, 1e-4);
        }
    }

    /// A constant `src` must pass through the guided filter unchanged
    /// (cov = 0 ⇒ a = 0, b = mean_p). End-to-end check that the box_mean
    /// rewrite keeps the filter's basic invariant.
    #[test]
    fn guided_filter_reproduces_constant_src() {
        let (w, h) = (32, 24);
        let guide = pseudo_random(w, h);
        let src = vec![0.4f32; w * h];
        let out = guided_filter(&guide, &src, w, h, 4, 0.01);
        assert_close(&out, &src, 1e-5);
    }

    /// Guided-filter linearity in `src`: GF(g, k·p) = k·GF(g, p). This
    /// invariant is what allowed the `anchor = h0` normalisation to be removed
    /// from `dehaze` without changing its output — keep it pinned.
    #[test]
    fn guided_filter_is_linear_in_src() {
        let (w, h) = (32, 24);
        let guide = pseudo_random(w, h);
        let src: Vec<f32> = pseudo_random(w, h).iter().map(|v| v * 0.5).collect();
        let k = 37.0f32;
        let scaled: Vec<f32> = src.iter().map(|v| v * k).collect();
        let a = guided_filter(&guide, &src, w, h, 4, 0.01);
        let b = guided_filter(&guide, &scaled, w, h, 4, 0.01);
        let expect: Vec<f32> = a.iter().map(|v| v * k).collect();
        assert_close(&b, &expect, 1e-2);
    }
}
