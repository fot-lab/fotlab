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
//!    own-guide, see `FOTLAB-RAWLER-000010`). Irregular CFAs have no decimated
//!    grid to filter on, so those planes keep the global scalar floor.
//!
//! ## Estimation, merge and application are deliberately separate steps
//!
//! Estimating a haze floor is per plane; *using* one is per pixel, and the two
//! stay in separate stages so a single shared field is built once and applied
//! identically to every colour plane of a CFA period. The earlier folded version
//! produced a colour cast in two distinct ways (now understood and accepted):
//!
//! 1. **Per-channel transmission.** With one field per plane the apply step
//!    became `cleared_c = (v_c − h_c) / (1 − h_c)`, i.e. a different
//!    denominator per channel — a per-channel transmission, which the haze
//!    model does not contain (`t` is a property of the medium, only the
//!    airlight `A_c` is per channel). A neutral surface's four photosites then
//!    came out scaled differently.
//! 2. **An absolute offset still breaks ratios even when shared.** Applying the
//!    *shared* field as `cleared = (v − h) / (1 − h)` subtracts the same
//!    absolute `h` from every channel. The channels carry different magnitudes,
//!    so an equal absolute offset shifts their ratios — a neutral block still
//!    casts. That is exactly the behaviour we now *accept*: the apply step uses
//!    the classical recovery `cleared = (v − A) / (1 − strength·h) + A` (see
//!    [`apply_mask`]) in exchange for a physically-correct, 2-D-aware dehaze; the
//!    field stays shared per CFA period so the cast is bounded and spatially
//!    coherent rather than per-photosite noise.
//!
//! So the pipeline is three stages, with a shared field inserted in the middle:
//!
//! ```text
//! estimate  ->  per-plane fields      PlaneMask::{Grid, Uniform}
//! merge     ->  shared OR per-plane   Avg / Min / Blue (one field) or Each (per-plane)
//! apply     ->  pixels                cleared = (v − A) / (1 − strength·h) + A   (classical; chroma NOT preserved)
//! ```
//!
//! Per regular plane `p` the estimate is
//! ```text
//! guide  = plane sub-lattice values (0..1)
//! dark   = box_min(guide)                         // local dark channel
//! g      = guided_filter(guide, dark, r, eps)     // edge-aware, own guide
//! mask   = clamp(g, 0, cap_tail)                  // cap_tail = `ceiling` param
//! ```
//! In uniform haze `dark ≈ h0` everywhere and the guided filter passes such a
//! smooth field through, so `h ≈ h0` — the old behaviour is recovered. In
//! thick-haze patches `dark > h0` raises `h`, in clear patches it lowers `h`,
//! so location is respected. (An earlier revision normalised `dark` by `h0`
//! before filtering and multiplied it back after; the guided filter is linear
//! in `src`, so that anchor cancelled exactly and has been removed.)
//!
//! See [`merge_masks`] for how the per-plane fields are resampled onto one
//! shared grid before averaging — that is where the "non-Bayer arrays have
//! different pixel counts" problem lands.

use std::collections::VecDeque;

use rayon::prelude::*;

use crate::cfa::CfaPlanes;

/// How the per-plane haze estimates are combined into the field applied to pixels.
///
/// * `Each` — no merge: every colour plane applies the filter it estimated for
///   itself (the pre-"shared field" behaviour).
/// * `Blue` — every plane uses the **blue** channel plane's estimated field
///   (`CfaPlanes::blue_plane`). Blue scatters most in haze, so its dark channel
///   is typically the lowest — applying it everywhere is the most conservative
///   single-plane choice.
/// * `Min` — per pixel, the minimum haze across the planes: a pixel is only
///   dehazed where *every* plane agrees it is hazy. This is the **default**.
/// * `Avg` — per-pixel mean across the planes (the original shared-field merge).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, uniffi::Enum)]
pub enum DehazeMergeMode {
    /// No merge — each plane applies its own estimated field.
    Each,
    /// Every plane uses the blue channel plane's field.
    Blue,
    /// Per-pixel minimum of the plane fields (default).
    #[default]
    Min,
    /// Per-pixel mean of the plane fields (the original shared-field merge).
    Avg,
}

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
    merge_mode: DehazeMergeMode,
    blue_plane: Option<usize>,
    atmospheric_light: f32,
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
    // The scalar branch is the only consumer of the global per-plane floors;
    // skip the histogram pass entirely when every plane takes the guided path.
    let floors = if guided && planes.is_regular() {
        Vec::new()
    } else {
        plane_haze_floors(&pixels, width, height, planes, active, floor_tail)
    };

    // 1. estimate (per plane) -> 2. merge (one shared field, or keep per-plane)
    // -> 3. apply.
    let masks = estimate_masks(
        &pixels, width, height, planes, guided, cap_tail, &floors,
        dark_radius, guide_radius, guide_eps,
    );
    match merge_mode {
        DehazeMergeMode::Each => {
            // No merge: every colour plane applies the field it estimated for itself.
            let plane_cells = build_plane_cells(&masks, width, height, planes.period());
            apply_each(&mut pixels, &plane_cells, planes, width, height, strength, atmospheric_light);
        }
        DehazeMergeMode::Blue => {
            // Every plane uses the blue channel plane's estimated field.
            let blue = blue_plane.unwrap_or(0);
            let mask = reduce_cells(&masks, &Reduction::Blue(blue), width, height, planes.period());
            apply_mask(&mut pixels, &mask, strength, atmospheric_light);
        }
        DehazeMergeMode::Min => {
            let mask = reduce_cells(&masks, &Reduction::Min, width, height, planes.period());
            apply_mask(&mut pixels, &mask, strength, atmospheric_light);
        }
        DehazeMergeMode::Avg => {
            // The historical shared-field merge: per-cell mean across the planes.
            let mask = merge_masks(&masks, width, height, planes.period());
            apply_mask(&mut pixels, &mask, strength, atmospheric_light);
        }
    }
    pixels
}

/// A single colour plane's haze estimate, still living on that plane's own grid.
///
/// This type is the reason estimation and application are separate: it is a
/// value the caller can combine with the other planes' estimates *before* any
/// pixel is touched. See the module docs for why that matters.
pub(crate) enum PlaneMask {
    /// Decimated field on a plane's own sub-lattice. Index `(i, j)` is the
    /// photosite at `(dr + period*i, dc + period*j)` for that plane's period
    /// offset `(dr, dc)`.
    Grid {
        gw: usize,
        gh: usize,
        values: Vec<f32>,
    },
    /// No usable sub-lattice — either the CFA is irregular (X-Trans and friends)
    /// or the scalar branch was selected. The plane contributes one global
    /// number, extended over the whole shared grid by [`merge_masks`].
    Uniform(f32),
}

/// Stage 1: one haze estimate per colour plane.
///
/// Regular planes take the sub-lattice guided path (`box_min` dark channel, then
/// `guided_filter` with the plane's own values as guide) and the result is
/// clamped to `cap_tail`, which caps how much haze floor any plane may claim.
/// Everything else falls back to that plane's global histogram floor.
fn estimate_masks(
    pixels: &[f32],
    width: usize,
    height: usize,
    planes: &CfaPlanes,
    guided: bool,
    cap_tail: f32,
    floors: &[f32],
    dark_radius: usize,
    guide_radius: usize,
    guide_eps: f32,
) -> Vec<PlaneMask> {
    let nplanes = planes.nplanes();
    let period = planes.period();
    let use_grids = guided && planes.is_regular();
    let mut masks = Vec::with_capacity(nplanes);

    for p in 0..nplanes {
        let fallback = || PlaneMask::Uniform(floors.get(p).copied().unwrap_or(0.0));
        if !use_grids {
            masks.push(fallback());
            continue;
        }
        let (dr, dc) = planes.offset(p);
        let (gw, gh) = planes.sublattice_dims(width, height, p);
        if gw == 0 || gh == 0 {
            masks.push(fallback());
            continue;
        }

        // Extract the plane's sub-lattice values as the guided-filter guide.
        let mut guide = vec![0.0f32; gw * gh];
        for i in 0..gh {
            let r = dr + period * i;
            if r >= height {
                break;
            }
            for j in 0..gw {
                let c = dc + period * j;
                if c >= width {
                    break;
                }
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
        // divide/multiply pass. `cap_tail` caps the maximum over-dehaze,
        // independent of the floor quantile `floor_tail`.
        let mut values = guided_filter(&guide, &dark, gw, gh, guide_radius, guide_eps);
        for v in values.iter_mut() {
            *v = v.clamp(0.0, cap_tail.min(1.0));
        }
        masks.push(PlaneMask::Grid { gw, gh, values });
    }
    masks
}

/// Stage 2 reduction: how the per-plane estimates collapse onto the common
/// *period grid* before being expanded to full resolution.
enum Reduction {
    /// Per-cell mean of the planes that reached the cell (the historical `Avg`).
    Avg,
    /// Per-cell minimum of the planes that reached the cell.
    Min,
    /// The blue channel plane's estimate for every cell (`Blue` mode).
    Blue(usize),
}

/// The value plane `mask` contributes at common-grid cell `(i, j)`, or `None`
/// when the plane does not cover that cell (a regular grid plane whose sub-lattice
/// stops short of the frame edge at a partial tail row/column). `Uniform` planes
/// always cover every cell.
fn plane_value_at(mask: &PlaneMask, i: usize, j: usize) -> Option<f32> {
    match mask {
        PlaneMask::Grid { gw: pw, gh: ph, values } => {
            if i < *ph && j < *pw {
                Some(values[i * (*pw) + j])
            } else {
                None
            }
        }
        PlaneMask::Uniform(v) => Some(*v),
    }
}

/// Collapse the per-plane estimates into **one** shared full-resolution field
/// (`Min` / `Blue` / `Avg` modes). Mirrors the old `merge_masks` cell-wise
/// accumulation, but replaces the fixed mean with the requested [`Reduction`].
///
/// Every field is resampled onto the common period grid
/// `(ceil(width/period), ceil(height/period))` because that is the only grid where
/// all planes are comparable: a grid plane at offset `(dr, dc)` seeds its sample
/// `(i, j)` at photosite `(dr + period*i, dc + period*j)`, whose common-grid cell
/// is `(i, j)` whenever `dr, dc < period` (always true). Uniform planes contribute
/// one number to every cell. Cells no plane reached get `0` (no extra haze).
///
/// The reduced cells are then expanded to full resolution: every photosite in a
/// cell receives that cell's value, so the applied field is identical across the
/// colours within a CFA period.
fn reduce_cells(
    masks: &[PlaneMask],
    reduction: &Reduction,
    width: usize,
    height: usize,
    period: usize,
) -> Vec<f32> {
    if width == 0 || height == 0 {
        return Vec::new();
    }
    let period = period.max(1);
    let gw = (width + period - 1) / period;
    let gh = (height + period - 1) / period;

    let mut cell = vec![0.0f32; gw * gh];
    for ci in 0..gh {
        for cj in 0..gw {
            let k = ci * gw + cj;
            cell[k] = match reduction {
                Reduction::Avg => {
                    let mut s = 0.0f32;
                    let mut n = 0u32;
                    for m in masks {
                        if let Some(v) = plane_value_at(m, ci, cj) {
                            s += v;
                            n += 1;
                        }
                    }
                    if n > 0 {
                        s / n as f32
                    } else {
                        0.0
                    }
                }
                Reduction::Min => {
                    let mut m = f32::INFINITY;
                    let mut any = false;
                    for mm in masks {
                        if let Some(v) = plane_value_at(mm, ci, cj) {
                            m = m.min(v);
                            any = true;
                        }
                    }
                    if any {
                        m
                    } else {
                        0.0
                    }
                }
                Reduction::Blue(b) => plane_value_at(&masks[*b], ci, cj).unwrap_or(0.0),
            };
        }
    }

    // Expand cell -> photosites. Row-independent, so parallelised with rayon
    // (`OPTIMZ-PERFRM-000007`).
    let mut out = vec![0.0f32; width * height];
    out.par_chunks_mut(width).enumerate().for_each(|(r, row)| {
        let bi = r / period;
        for c in 0..width {
            row[c] = cell[bi * gw + c / period];
        }
    });
    out
}

/// `Each` mode: per-plane cell grids, one per plane, expanded to the whole frame
/// so the apply step can look each pixel's field up by `plane_at(r, c)`. A grid
/// plane seeds `plane_cells[p][cell]` from its sub-lattice (only the cells it
/// reaches); a uniform plane fills every cell with its constant.
fn build_plane_cells(
    masks: &[PlaneMask],
    width: usize,
    height: usize,
    period: usize,
) -> Vec<Vec<f32>> {
    let period = period.max(1);
    let gw = (width + period - 1) / period;
    let gh = (height + period - 1) / period;
    let nplanes = masks.len();
    let mut plane_cells = vec![vec![0.0f32; gw * gh]; nplanes];
    for p in 0..nplanes {
        match &masks[p] {
            PlaneMask::Grid { gw: pw, gh: ph, values } => {
                let rows = (*ph).min(gh);
                let cols = (*pw).min(gw);
                for i in 0..rows {
                    for j in 0..cols {
                        plane_cells[p][i * gw + j] = values[i * (*pw) + j];
                    }
                }
            }
            PlaneMask::Uniform(v) => {
                for cell in plane_cells[p].iter_mut() {
                    *cell = *v;
                }
            }
        }
    }
    plane_cells
}

/// Apply the classical recovery per pixel, each colour plane using **its own**
/// field from `plane_cells` (the `Each` merge mode). `plane_cells[plane][cell]`
/// is plane `plane`'s haze value at the pixel's CFA-period cell.
fn apply_each(
    pixels: &mut [f32],
    plane_cells: &[Vec<f32>],
    planes: &CfaPlanes,
    width: usize,
    height: usize,
    strength: f32,
    atmospheric_light: f32,
) {
    const EPS: f32 = 1e-3;
    let period = planes.period().max(1);
    let gw = (width + period - 1) / period;
    pixels.par_chunks_mut(width).enumerate().for_each(|(r, row)| {
        let bi = r / period;
        for c in 0..width {
            let plane = planes.plane_at(r, c);
            let h = plane_cells[plane][bi * gw + c / period];
            let t = (1.0 - strength * h).max(EPS);
            row[c] = ((row[c] - atmospheric_light) / t + atmospheric_light).max(0.0);
        }
    });
}

/// The historical shared-field merge: per-cell mean across the planes. Kept as a
/// thin wrapper so callers/tests that only need the average keep reading
/// `merge_masks`.
pub(crate) fn merge_masks(masks: &[PlaneMask], width: usize, height: usize, period: usize) -> Vec<f32> {
    reduce_cells(masks, &Reduction::Avg, width, height, period)
}

/// Stage 3: apply one shared field to every pixel with the **classical
/// atmospheric-scattering recovery** — the exact inverse of `I = J·(1 − h) + A·h`
/// for a spatially-varying `h`:
///
/// ```text
/// t       = max(1 − strength·h, EPS)   # effective transmission (strength = user amount)
/// cleared  = (v − A) / t + A           # = (v − A·strength·h) / (1 − strength·h)
/// cleared  = max(cleared, 0)
/// ```
///
/// `strength` (0..1) scales how much haze is removed: `strength = 0` ⇒ `t = 1` ⇒
/// identity; `strength = 1` ⇒ full classical recovery. `A` is the atmospheric light
/// (constant 1.0 at the FFI boundary today; a parameter here for later per-channel /
/// non-unity extension). Per-pixel and order-free, so parallelised over the
/// full-resolution buffer (`OPTIMZ-PERFRM-000007`).
///
/// Unlike the old multiplicative-gain form this subtracts the **absolute** airlight
/// `A·strength·h`, so channel ratios are *not* preserved — a neutral block stays
/// neutral but a coloured block casts by `A·strength·h`. That is the deliberate
/// trade for a physically-correct, 2-D-aware dehaze (see the module header and
/// `dehaze_classical_allows_coloured_cast`). `h` is shared across the CFA period so
/// the cast is spatially coherent and bounded.
fn apply_mask(pixels: &mut [f32], mask: &[f32], strength: f32, atmospheric_light: f32) {
    const EPS: f32 = 1e-3;
    pixels
        .par_iter_mut()
        .zip(mask.par_iter())
        .for_each(|(px, &h)| {
            let t = (1.0 - strength * h).max(EPS); // effective transmission, floored to avoid /0
            let recovered = ((*px - atmospheric_light) / t + atmospheric_light).max(0.0);
            *px = recovered;
        });
}

/// Estimate a haze floor per colour plane as the `tail` quantile of each plane's
/// 256-bin 0..1 histogram. Accumulation is restricted to `active` when set.
fn plane_haze_floors(
    pixels: &[f32],
    width: usize,
    _height: usize,
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

    /// The four Bayer planes: distinct grids seeded at the four period offsets.
    fn bayer_planes() -> CfaPlanes {
        CfaPlanes::from_offsets(
            vec![vec![(0, 0)], vec![(0, 1)], vec![(1, 0)], vec![(1, 1)]],
            2,
        )
    }

    fn grid_values(mask: &PlaneMask) -> &Vec<f32> {
        match mask {
            PlaneMask::Grid { values, .. } => values,
            PlaneMask::Uniform(_) => panic!("expected a sub-lattice grid, got a uniform plane"),
        }
    }

    /// A Bayer mosaic whose four planes carry clearly different levels, so any
    /// per-plane leakage is visible, plus texture so the dark channel is not
    /// degenerate.
    fn bayer_frame(w: usize, h: usize) -> Vec<f32> {
        let tex = pseudo_random(w, h);
        (0..w * h)
            .map(|k| {
                let (r, c) = (k / w, k % w);
                let level = match (r % 2, c % 2) {
                    (0, 0) => 0.60, // R
                    (0, 1) => 0.40, // G1
                    (1, 0) => 0.45, // G2
                    _ => 0.20,      // B
                };
                level + 0.08 * tex[k]
            })
            .collect()
    }

    /// The property the stage split exists for: after merging, every photosite
    /// in a CFA cell receives the **same** haze value, because no pixel consults
    /// its own colour plane any more. Before decoupling each plane applied its
    /// own `(v − h_c)/(1 − h_c)`, i.e. four different denominators per cell.
    #[test]
    fn merged_mask_is_one_value_per_cfa_cell() {
        let (w, h) = (32, 24);
        let planes = bayer_planes();
        let px = bayer_frame(w, h);
        let masks = estimate_masks(&px, w, h, &planes, true, 1.0, &[], 3, 4, 0.01);
        assert_eq!(masks.len(), 4);

        // Guard against a vacuous pass: the per-plane estimates really do differ.
        let first = grid_values(&masks[0]);
        let last = grid_values(&masks[3]);
        let spread = first
            .iter()
            .zip(last.iter())
            .fold(0.0f32, |acc, (a, b)| acc.max((a - b).abs()));
        assert!(spread > 1e-3, "plane estimates should differ, got {spread}");

        let mask = merge_masks(&masks, w, h, planes.period());
        let grids: Vec<&Vec<f32>> = masks.iter().map(grid_values).collect();
        for bi in 0..h / 2 {
            for bj in 0..w / 2 {
                let want: f32 = grids.iter().map(|g| g[bi * (w / 2) + bj]).sum::<f32>() / 4.0;
                let idx = (2 * bi) * w + 2 * bj;
                // All four photosites of the cell, not just the sampled one.
                for (dr, dc) in [(0, 0), (0, 1), (1, 0), (1, 1)] {
                    let got = mask[idx + dr * w + dc];
                    assert!(
                        (got - want).abs() < 1e-5,
                        "cell ({bi},{bj}) at +({dr},{dc}): {got} vs mean {want}"
                    );
                }
            }
        }
    }

    /// Planes do not all have the same extent: a plane covers fewer cells than
    /// the frame does, so the divisor has to be the number of planes that
    /// actually reached each cell rather than the plane count.
    #[test]
    fn merge_masks_divides_by_the_planes_that_reached_each_cell() {
        let (w, h, period) = (8, 6, 2);
        // One 1x1 grid (covers cell (0,0) only) and one uniform plane (covers all).
        let masks = vec![
            PlaneMask::Grid { gw: 1, gh: 1, values: vec![0.4] },
            PlaneMask::Uniform(0.2),
        ];
        let mask = merge_masks(&masks, w, h, period);
        assert!((mask[0] - 0.3).abs() < 1e-6, "cell (0,0) averages both: {mask:?}");
        let elsewhere = mask[3 * w + 5]; // cell (1,2)
        assert!(
            (elsewhere - 0.2).abs() < 1e-6,
            "covered by the uniform plane alone, got {elsewhere}"
        );
    }

    /// End-to-end through the three stages: the guided branch must move pixels,
    /// and never brighten them — `cleared = v · (1 − strength·h) ≤ v` for
    /// `h ≥ 0`, `strength ∈ [0,1]`, which is an invariant worth pinning now that
    /// `h` comes from a mean and is applied multiplicatively (as a ratio).
    #[test]
    fn dehaze_moves_bayer_pixels_without_brightening_them() {
        let (w, h) = (32, 24);
        let planes = bayer_planes();
        let px = bayer_frame(w, h);
        let out = dehaze(
            px.clone(), w, h, Some(1.0), Some(0.01), Some(1.0),             &planes, None, 3, 4, 0.01,
            DehazeMergeMode::Avg, None, 1.0,
        );
        let moved = out
            .iter()
            .zip(px.iter())
            .filter(|(o, i)| (*o - *i).abs() > 1e-3)
            .count();
        assert!(moved > 0, "the guided branch must change pixels");
        for (o, i) in out.iter().zip(px.iter()) {
            assert!(*o <= *i + 1e-6, "dehaze brightened {i} to {o}");
        }
    }

    /// Channel-ratio semantics of the classical (additive) apply form.
    ///
    /// Neutral (equal-R/G/B) mosaic: the additive `-A·strength·h` term is identical
    /// for every channel, so the four photosites of a cell keep their input ratio —
    /// a neutral block does NOT cast (same as the old gain form for neutral input).
    ///
    /// Coloured mosaic: the same absolute offset shifts channels of different
    /// magnitude by different *relative* amounts, so ratios change. That cast is the
    /// deliberate trade for a physically-correct dehaze and is pinned here so it is
    /// never accidentally "fixed" back to a ratio-preserving form.
    #[test]
    fn dehaze_classical_allows_coloured_cast() {
        let (w, h, _period) = (32, 24, 2);
        let planes = bayer_planes();

        // Neutral (uniform) frame: every photosite identical => the shared mask h is
        // the same for all, and the classical offset `-A*strength*h` is identical for
        // every channel, so the four photosites of a cell keep the SAME value. That is
        // "no relative cast" (the R:G:B ratio is identical before and after).
        //
        // NOTE (accepted DCP artifact, not a bug): for a *uniform* frame the dark
        // channel equals the level, so h == level and the classical map crushes the
        // level to 0 — DCP conflates brightness with haze. The pin here is the
        // *relative* invariant (all channels equal), not the absolute output.
        let c = 0.5f32;
        let neutral: Vec<f32> = (0..w * h).map(|_| c).collect();
        let out_n = dehaze(
            neutral.clone(), w, h, Some(1.0), Some(0.01), Some(1.0),             &planes, None, 3, 4, 0.01,
            DehazeMergeMode::Avg, None, 1.0,
        );
        for bi in 0..h / 2 {
            for bj in 0..w / 2 {
                let base = (2 * bi) * w + 2 * bj;
                let cell = [
                    out_n[base],
                    out_n[base + 1],
                    out_n[base + w],
                    out_n[base + w + 1],
                ];
                let lo = cell.iter().cloned().fold(f32::INFINITY, f32::min);
                let hi = cell.iter().cloned().fold(f32::NEG_INFINITY, f32::max);
                assert!(
                    (hi - lo) < 1e-5,
                    "neutral cell ({bi},{bj}) should keep all channels equal (no cast): {cell:?}"
                );
            }
        }

        // Coloured frame: distinct R/G/B levels + texture. The shared offset
        // `-A*strength*h` is one EQUAL absolute amount, so it shifts channels of
        // different magnitude by different *relative* amounts: the per-channel
        // absolute change `(out - in)` differs between R and G. A cast-free (ratio
        // preserving) transform would give every channel the same `(out - in)`. Pin
        // the deliberate cast with a division-free check (robust to the darkest
        // channel hitting the zero crossing, where `out == 0`).
        let coloured = bayer_frame(w, h);
        let out_c = dehaze(
            coloured.clone(), w, h, Some(1.0), Some(0.01), Some(1.0),             &planes, None, 3, 4, 0.01,
            DehazeMergeMode::Avg, None, 1.0,
        );
        let mut max_change_diff = 0.0f32;
        for bi in 0..h / 2 {
            for bj in 0..w / 2 {
                let base = (2 * bi) * w + 2 * bj;
                let r = base; // R photosite
                let g = base + 1; // G1 photosite
                let d_r = out_c[r] - coloured[r];
                let d_g = out_c[g] - coloured[g];
                max_change_diff = max_change_diff.max((d_r - d_g).abs());
            }
        }
        assert!(
            max_change_diff > 1e-3,
            "coloured channels must NOT share one (out-in) offset (cast-free); got {max_change_diff}"
        );
    }

    /// Two equal-extent Grid planes with distinct values: the `Avg` reduction must
    /// be their per-cell mean. Pins the historical shared-field merge arithmetic
    /// at the cell level (independent of expansion to full resolution).
    #[test]
    fn reduce_cells_avg_is_mean_of_planes() {
        let period = 2;
        let (w, h) = (4, 4); // cell grid is 2×2
        let masks = vec![
            PlaneMask::Grid { gw: w / period, gh: h / period, values: vec![0.4; 4] },
            PlaneMask::Grid { gw: w / period, gh: h / period, values: vec![0.2; 4] },
        ];
        let reduced = reduce_cells(&masks, &Reduction::Avg, w, h, period);
        // Every photosite of every cell gets the same 0.3 mean.
        for &v in reduced.iter() {
            assert!((v - 0.3).abs() < 1e-6, "Avg cell should be 0.3, got {v}");
        }
    }

    /// Same planes, `Min` reduction must be the per-cell minimum.
    #[test]
    fn reduce_cells_min_is_min_of_planes() {
        let period = 2;
        let (w, h) = (4, 4);
        let masks = vec![
            PlaneMask::Grid { gw: w / period, gh: h / period, values: vec![0.4; 4] },
            PlaneMask::Grid { gw: w / period, gh: h / period, values: vec![0.2; 4] },
        ];
        let reduced = reduce_cells(&masks, &Reduction::Min, w, h, period);
        for &v in reduced.iter() {
            assert!((v - 0.2).abs() < 1e-6, "Min cell should be 0.2, got {v}");
        }
    }

    /// `Blue(b)` reduction must return plane `b`'s estimated field verbatim — not
    /// the mean, not the min. Use a third plane so blue (index 1) differs from
    /// both the average and the min, making the selection unambiguous.
    #[test]
    fn reduce_cells_blue_selects_blue_plane() {
        let period = 2;
        let (w, h) = (4, 4);
        let masks = vec![
            PlaneMask::Grid { gw: w / period, gh: h / period, values: vec![0.4; 4] },
            PlaneMask::Grid { gw: w / period, gh: h / period, values: vec![0.7; 4] }, // blue plane
            PlaneMask::Grid { gw: w / period, gh: h / period, values: vec![0.0; 4] },
        ];
        let reduced = reduce_cells(&masks, &Reduction::Blue(1), w, h, period);
        for &v in reduced.iter() {
            assert!((v - 0.7).abs() < 1e-6, "Blue mode must copy the blue plane (0.7), got {v}");
        }
    }

    /// The defining property of `Each`: every plane applies **its own** field, so
    /// a uniform input spreads differently per plane. Build four uniform planes
    /// with distinct haze (only plane 1 strong), neutral input, and assert that
    /// the plane-1 photosite collapses to 0 while the others are untouched —
    /// proof the field was *not* merged into one shared value.
    #[test]
    fn apply_each_applies_per_plane_field() {
        let (w, h, period) = (4, 4, 2);
        let planes = bayer_planes();
        // Uniform planes: floor only as haze (scalar-style), plane 1 strong.
        let masks = vec![
            PlaneMask::Uniform(0.0),
            PlaneMask::Uniform(0.6),
            PlaneMask::Uniform(0.0),
            PlaneMask::Uniform(0.0),
        ];
        let plane_cells = build_plane_cells(&masks, w, h, period);
        let mut px = vec![0.5f32; w * h]; // neutral frame
        apply_each(&mut px, &plane_cells, &planes, w, h, 1.0, 1.0);

        // plane_at(0,0)=0 → untouched 0.5; plane_at(0,1)=1 → h=0.6 ⇒ cleared 0.
        let r00 = px[0 * w + 0];
        let r01 = px[0 * w + 1];
        assert!((r00 - 0.5).abs() < 1e-6, "plane 0 should be untouched, got {r00}");
        assert!((r01 - 0.0).abs() < 1e-6, "plane 1 should be dehazed to 0, got {r01}");
        assert!((r00 - r01).abs() > 0.4, "Each must apply distinct per-plane fields");
    }

    /// Regression pin: `Avg` (the default) must reproduce the historical shared-
    /// field merge byte-for-byte. Re-derive the masks the engine used, reduce them
    /// with the old `merge_masks` mean, apply with `apply_mask`, and assert the
    /// end-to-end `dehaze(.. Avg ..)` output matches. If Avg ever silently diverges
    /// from the pre-merge-mode behaviour this fails.
    #[test]
    fn dehaze_avg_recovers_historical_merge() {
        let (w, h) = (32, 24);
        let planes = bayer_planes();
        let px = bayer_frame(w, h);
        let out = dehaze(
            px.clone(), w, h, Some(1.0), Some(0.01), Some(1.0),
            &planes, None, 3, 4, 0.01,
            DehazeMergeMode::Avg, None, 1.0,
        );

        // Manual reconstruction of the historical Avg path.
        let masks = estimate_masks(&px, w, h, &planes, true, 1.0, &[], 3, 4, 0.01);
        let field = merge_masks(&masks, w, h, planes.period());
        let mut manual = px.clone();
        apply_mask(&mut manual, &field, 1.0, 1.0);

        assert_close(&out, &manual, 1e-6);
    }
}
