//! RCD — Ratio Corrected Demosaicing (Bayer).
//!
//! Ported from `external/RawTherapee/rtengine/rcd_demosaic.cc`
//! (`RawImageSource::rcd_demosaic`, Copyright (c) 2017-2020 Luis Sanz Rodriguez
//! and Ingo Weyrich, GPL-3.0); the algorithm is release 2.3 of
//! <https://github.com/LuisSR/RCD-Demosaicing>, tiled and tuned by Ingo Weyrich.
//!
//! Green is interpolated first, from two directional estimates weighted by a
//! high-pass colour-difference statistic (`VH_Dir`). Red and blue then follow as
//! **colour differences**: at an R pixel the blue estimate is `G + (B - G)`
//! measured at the *diagonal* neighbours, which is where a blue sample natively
//! sits. That detail is what makes step 4.2's reads land on real samples instead
//! of interpolated ones, and it is also why the scratch has to start zeroed.
//!
//! ## What the port changes, and what it must not
//!
//! * **Numeric domain.** Upstream loads `LIM01(rawData / 65536)` and writes back
//!   `rgb * 65536` — it works on `[0,1]` and converts at the edges. This crate's
//!   mosaic is *already* normalised, so both conversions are elided, and they are
//!   exact because `65536 = 2^16`. The arithmetic therefore still runs in
//!   upstream's own domain, which is what gives `EPS`/`EPSSQ` their meaning, and
//!   the border pass stays in the same units as the interior.
//! * **The tiling is load-bearing.** Upstream works in 194x194 tiles stepped by
//!   176 with a 9-pixel margin because the per-pixel working set (~6.5 planes)
//!   would otherwise have to exist at image size. The port keeps the tiling for
//!   the same reason — it is a memory bound, not a cache trick.
//! * **Parallelism.** Upstream's `#pragma omp for collapse(2)` is over tiles. The
//!   port shards over **tile rows**: a tile row alone owns the output rows
//!   `[row_start + 9, row_end - 9)`, those bands are disjoint and contiguous
//!   ([`tile_row_bands`] and a test pin it), so every rayon task gets a real
//!   `&mut` slice of all three planes and no `unsafe` is needed. Inside a tile
//!   the port stays serial, exactly like upstream.
//! * **`tileBorder == rcdBorder == 9`** (`rcd_demosaic.cc:81-82`), so upstream's
//!   four `(tr == 0) ? rcdBorder : tileBorder` ternaries are no-ops: the region a
//!   tile may write is always its own inset by 9.
//! * **`lpf` aliases `PQ_Dir`** upstream to save one buffer. The port keeps them
//!   apart — their lifetimes are only disjoint by statement order, the saving is
//!   a sixteenth of the tile, and a separate buffer cannot be perturbed by a
//!   future reordering.
//! * **`bufferV`'s three rotating pointers** become a rotating index over the
//!   same three rows; the visit order is identical.
//! * **No SIMD to port.** `rcd_demosaic.cc` contains no intrinsics at all — it
//!   relies on `-ftree-vectorize`, i.e. on the same auto-vectorisation the Rust
//!   compiler is asked for. Nothing hand-written was dropped.
//! * **The four-colour guard is live — here and in vng4 alike.** Upstream tests
//!   `FC(i, j) == 3` on the *folded* mask, and `FC` really can return `3`:
//!   `set_prefilters()` folds only when `isBayer() && get_colors() == 3`
//!   (`rawimage.h:50-56`), so a sensor with a fourth colour keeps its `3` and the
//!   guard fires. (So does a Bayer under dcraw's `four_color_rgb` / `half_size`,
//!   which raises `colors` to 4 before the same test — `dcraw.cc:5025-5034`.)
//!   Upstream then falls back to `igv_interpolate`. IGV is not ported yet, so this
//!   port returns [`Error::UnsupportedCfa`], through
//!   [`CfaDesc::has_fourth_colour`] — which agrees with the literal test for every
//!   CFA `CfaDesc` can describe. When `bayer/igv.rs` lands, this arm should call
//!   it: a behaviour *addition* the port owes upstream.
//!
//! Everything else — every constant, every loop bound, every parenthesis in a
//! float expression — is upstream's, transcribed rather than tidied.

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::border::border_interpolate;
use crate::cfa::CfaDesc;
use crate::math::{abs, intp, lim01, max0, max2};
use crate::{Error, Rgb};

// ---------------------------------------------------------------------------
// Upstream tile geometry (rcd_demosaic.cc:81-91)
// ---------------------------------------------------------------------------

/// `tileSize` — the edge of a tile's scratch buffers.
const TILE_SIZE: usize = 194;
/// `tileBorder` — the margin between a tile's edge and the region it may write.
const TILE_BORDER: usize = 9;
/// `rcdBorder` — upstream spells it as a second constant, but it is the same 9,
/// which is why the write-region ternaries collapse. Kept for cross-reading.
const RCD_BORDER: usize = 9;
/// `tileSizeN` — how far the tile origin advances between neighbours.
const TILE_STEP: usize = TILE_SIZE - 2 * TILE_BORDER;
/// Elements in a full tile buffer.
const TILE_LEN: usize = TILE_SIZE * TILE_SIZE;
/// Elements in a half-resolution tile buffer (`lpf`, `PQ_Dir`, `P/Q_CDiff_Hpf`).
const TILE_HALF: usize = TILE_LEN / 2;

/// Upstream's `w1`..`w4`: row strides inside the flat tile buffers. Note these
/// stay `tileSize` even for the last column tile, where `tileCols < tileSize`.
const W1: usize = TILE_SIZE;
const W2: usize = 2 * TILE_SIZE;
const W3: usize = 3 * TILE_SIZE;
const W4: usize = 4 * TILE_SIZE;

/// Tolerance to avoid dividing by zero (`rcd_demosaic.cc:89-90`).
const EPS: f32 = 1e-5;
const EPSSQ: f32 = 1e-10;

/// `SQR(x)` (`rt_math.h:36`).
#[inline(always)]
fn sqr(x: f32) -> f32 {
  x * x
}

/// One tile's scratch, sized like upstream's per-thread allocation.
///
/// Every field upstream allocates with `calloc` is zeroed once per tile by
/// [`Self::reset`], because a fresh tile must *see* zeros wherever upstream would
/// have read an uninitialised neighbour. That is not defensive coding: step 4.3
/// at row `r` reads `rgb[c]` at row `r - 3`, so for `r` near the top of the tile
/// it reads a plane element no stage has written yet, and the value it finds
/// there propagates into the rows the tile *does* write. Upstream's `calloc`
/// makes it a `0`; reusing a scratch buffer would make it whatever the previous
/// tile left behind, which is a silent per-tile-placing dependency.
///
/// `cfa` is the one exception — the load loop rewrites every element the kernel
/// can reach — and `buffer_v`/`buffer_h` are stack arrays upstream rather than
/// `calloc`, written before they are read.
struct TileScratch {
  /// `cfa` — the loaded mosaic, normalised.
  cfa: Vec<f32>,
  /// `rgb[3]` — the three planes under construction.
  rgb: [Vec<f32>; 3],
  /// `VH_Dir` — vertical/horizontal discrimination strength.
  vh_dir: Vec<f32>,
  /// `lpf` — low-pass incorporating all three sampled colours.
  lpf: Vec<f32>,
  /// `PQ_Dir` — P/Q diagonal discrimination strength.
  pq_dir: Vec<f32>,
  /// `P_CDiff_Hpf`, `Q_CDiff_Hpf` — the diagonal colour-difference high pass.
  p_cdiff: Vec<f32>,
  q_cdiff: Vec<f32>,
  /// `bufferV[3]` — three rows of `SQR(...)` in vertical orientation.
  buffer_v: [Vec<f32>; 3],
  /// `bufferH` — one row of `SQR(...)` in horizontal orientation.
  buffer_h: Vec<f32>,
}

impl TileScratch {
  fn new() -> Self {
    Self {
      cfa: vec![0.0; TILE_LEN],
      rgb: [vec![0.0; TILE_LEN], vec![0.0; TILE_LEN], vec![0.0; TILE_LEN]],
      vh_dir: vec![0.0; TILE_LEN],
      lpf: vec![0.0; TILE_HALF],
      pq_dir: vec![0.0; TILE_HALF],
      p_cdiff: vec![0.0; TILE_HALF],
      q_cdiff: vec![0.0; TILE_HALF],
      buffer_v: [vec![0.0; TILE_SIZE - 8], vec![0.0; TILE_SIZE - 8], vec![0.0; TILE_SIZE - 8]],
      buffer_h: vec![0.0; TILE_SIZE - 6],
    }
  }

  /// Restore the state a fresh tile starts from — upstream's `calloc`.
  fn reset(&mut self) {
    // `self.rgb` goes through `iter_mut` rather than three `[0]`/`[1]`/`[2]`
    // entries in the array below: an indexed element borrow is not tracked
    // per-index, so the three would alias each other. Distinct fields are fine.
    for plane in &mut self.rgb {
      plane.fill(0.0);
    }
    for b in [
      &mut self.vh_dir,
      &mut self.lpf,
      &mut self.pq_dir,
      &mut self.p_cdiff,
      &mut self.q_cdiff,
    ] {
      b.fill(0.0);
    }
    // `cfa`, `buffer_v` and `buffer_h` are written before read: see the type doc.
  }
}

/// The per-tile-row output bands for an image `height` rows tall.
///
/// Returns `(tile_row_start, band_start, band_end)` for each tile row that writes
/// anything, in ascending order. Each tile row writes rows
/// `[row_start + RCD_BORDER, row_end - RCD_BORDER)` and nothing else, so the bands
/// are disjoint; consecutive bands are also adjacent, because `tileStep` is
/// exactly `tileSize - 2 * border`. Together they cover `[RCD_BORDER, height -
/// RCD_BORDER)`, the rectangle the kernel owns once the border pass has taken the
/// frame. Those three properties are what let the driver hand out `&mut` slices.
fn tile_row_bands(height: usize) -> Vec<(usize, usize, usize)> {
  let num_th = height / TILE_STEP + usize::from(height % TILE_STEP != 0);
  let mut out = Vec::with_capacity(num_th);

  for tr in 0..num_th {
    let row_start = tr * TILE_STEP;
    let row_end = (row_start + TILE_SIZE).min(height);

    // Upstream's degenerate-tile skip (`rcd_demosaic.cc:113`). The test is an
    // equality, not a comparison, because that is what upstream wrote.
    if row_start + TILE_BORDER == row_end.saturating_sub(TILE_BORDER) {
      continue;
    }

    let (band_start, band_end) = (row_start + RCD_BORDER, row_end.saturating_sub(RCD_BORDER));
    if band_start >= band_end {
      // Upstream computes this tile and then discards it (`firstVertical >=
      // lastVertical` makes the write loop empty). Skipping it is the same
      // result without the work, since writing is all a tile does.
      continue;
    }

    out.push((row_start, band_start, band_end));
  }

  out
}

/// A tile row's band: the rows it alone writes, plus the three plane slices
/// covering exactly those rows.
struct Band<'a> {
  /// First row of the tile row whose region this band is; the tile row's end is
  /// `min(tile_row_start + TILE_SIZE, height)`, which the caller knows.
  tile_row_start: usize,
  /// First output row covered — the slices' own row 0.
  row0: usize,
  red: &'a mut [f32],
  green: &'a mut [f32],
  blue: &'a mut [f32],
}

/// Split the three output planes into the bands [`tile_row_bands`] describes.
fn split_bands<'a>(
  red: &'a mut [f32],
  green: &'a mut [f32],
  blue: &'a mut [f32],
  width: usize,
  bands: &[(usize, usize, usize)],
) -> Vec<Band<'a>> {
  /// Take the next `n` elements, leaving the rest behind.
  fn take<'a>(s: &mut &'a mut [f32], n: usize) -> &'a mut [f32] {
    let (head, tail) = core::mem::take(s).split_at_mut(n);
    *s = tail;
    head
  }

  let (mut r, mut g, mut b) = (red, green, blue);
  let mut cursor = 0usize;
  let mut out = Vec::with_capacity(bands.len());

  for &(tile_row_start, start, end) in bands {
    if start > cursor {
      let gap = (start - cursor) * width;
      let _ = take(&mut r, gap);
      let _ = take(&mut g, gap);
      let _ = take(&mut b, gap);
    }
    let len = (end - start) * width;
    out.push(Band {
      tile_row_start,
      row0: start,
      red: take(&mut r, len),
      green: take(&mut g, len),
      blue: take(&mut b, len),
    });
    cursor = end;
  }

  out
}

/// Demosaic one tile: upstream's whole `#pragma omp for` body, minus the tiling
/// bookkeeping the driver does.
///
/// `row_start`/`row_end`/`col_start`/`col_end` are global image coordinates, but
/// the tile-local row/column of upstream's compute loops is what indexes the flat
/// scratch, so the two are kept apart on purpose. `col_start` is always even
/// (multiples of `TILE_STEP`), which is what makes the tile-local CFA phase agree
/// with the global one.
#[allow(clippy::too_many_arguments)]
fn demosaic_tile(
  raw: &Array2D<f32>,
  cfarray: &[[u32; 2]; 2],
  row_start: usize,
  row_end: usize,
  col_start: usize,
  col_end: usize,
  sc: &mut TileScratch,
  band: &mut Band<'_>,
  width: usize,
) {
  let tile_rows = row_end - row_start;
  let tile_cols = col_end - col_start;

  sc.reset();

  // Disjoint field borrows, so the transcription below reads like upstream's.
  let TileScratch { cfa, rgb, vh_dir, lpf, pq_dir, p_cdiff, q_cdiff, buffer_v, buffer_h } = sc;

  // --- load (upstream 125-131) --------------------------------------------
  for row in row_start..row_end {
    let c0 = cfarray[row & 1][col_start & 1] as usize;
    let c1 = cfarray[row & 1][(col_start + 1) & 1] as usize;
    let mut indx = (row - row_start) * TILE_SIZE;
    for col in col_start..col_end {
      // Upstream: `LIM01(rawData[row][col] / scale)`, `scale = 65536.f`. This
      // crate's mosaic is already in the [0,1] domain that division produces, so
      // the division is elided — exactly, because 65536 is a power of two. The
      // interior therefore stays in the same units as `border_interpolate`, which
      // reads `raw` directly, and `EPS`/`EPSSQ` keep upstream's meaning.
      let v = lim01(raw.at(row, col));
      cfa[indx] = v;
      rgb[c0][indx] = v;
      rgb[c1][indx] = v;
      indx += 1;
    }
  }

  // --- step 1.1 (upstream 137-141) ----------------------------------------
  // Seeds the two vertical rows the rolling window below starts from.
  let v_seed = core::cmp::min(tile_rows.saturating_sub(3), 5);
  for row in 3..v_seed {
    for col in 4..tile_cols.saturating_sub(4) {
      let indx = row * TILE_SIZE + col;
      buffer_v[row - 3][col - 4] = sqr(
        (cfa[indx - W3] - cfa[indx - W1] - cfa[indx + W1] + cfa[indx + W3]) - 3.0 * (cfa[indx - W2] + cfa[indx + W2])
          + 6.0 * cfa[indx],
      );
    }
  }

  // --- step 1.2 (upstream 144-165) ----------------------------------------
  // Upstream rotates three `float*` (V0, V1, V2) across `bufferV`; the port
  // rotates an index instead. `swap(V0, V2); swap(V0, V1)` is exactly a
  // rotate-left by one, so V0/V1/V2 are `rot`, `rot+1`, `rot+2` (mod 3).
  let mut rot = 0usize;
  for row in 4..tile_rows.saturating_sub(4) {
    // Horizontal orientation for this row — refreshed every iteration.
    for col in 3..tile_cols.saturating_sub(3) {
      let indx = row * TILE_SIZE + col;
      buffer_h[col - 3] = sqr(
        (cfa[indx - 3] - cfa[indx - 1] - cfa[indx + 1] + cfa[indx + 3]) - 3.0 * (cfa[indx - 2] + cfa[indx + 2])
          + 6.0 * cfa[indx],
      );
    }

    let (i0, i1, i2) = (rot % 3, (rot + 1) % 3, (rot + 2) % 3);

    // Vertical orientation for the row below, landing on V2.
    for col in 4..tile_cols.saturating_sub(4) {
      let indx = (row + 1) * TILE_SIZE + col;
      buffer_v[i2][col - 4] = sqr(
        (cfa[indx - W3] - cfa[indx - W1] - cfa[indx + W1] + cfa[indx + W3]) - 3.0 * (cfa[indx - W2] + cfa[indx + W2])
          + 6.0 * cfa[indx],
      );
    }

    for col in 4..tile_cols.saturating_sub(4) {
      let indx = row * TILE_SIZE + col;
      let v_stat = max2(EPSSQ, buffer_v[i0][col - 4] + buffer_v[i1][col - 4] + buffer_v[i2][col - 4]);
      let h_stat = max2(EPSSQ, buffer_h[col - 4] + buffer_h[col - 3] + buffer_h[col - 2]);
      vh_dir[indx] = v_stat / (v_stat + h_stat);
    }

    rot += 1;
  }

  // --- step 2 (upstream 168-174) ------------------------------------------
  // `lpf` exists only at the non-green positions — `2 + (fc & 1)` steps by two
  // through exactly those — and is indexed at half resolution, so its row stride
  // is `TILE_SIZE / 2`. That is why step 3's vertical lookups subtract the full
  // `W1` (two rows, the next cell of the *same* CFA class) while its horizontal
  // ones subtract 1 (one column, likewise the same class).
  for row in 2..tile_rows.saturating_sub(2) {
    let mut col = 2 + (cfarray[row & 1][0] & 1) as usize;
    let mut indx = row * TILE_SIZE + col;
    let mut lpindx = indx / 2;
    while col < tile_cols.saturating_sub(2) {
      lpf[lpindx] = cfa[indx]
        + 0.5 * (cfa[indx - W1] + cfa[indx + W1] + cfa[indx - 1] + cfa[indx + 1])
        + 0.25 * (cfa[indx - W1 - 1] + cfa[indx - W1 + 1] + cfa[indx + W1 - 1] + cfa[indx + W1 + 1]);
      col += 2;
      indx += 2;
      lpindx += 1;
    }
  }

  // --- step 3 (upstream 177-205) ------------------------------------------
  // Green at the red and blue positions.
  for row in 4..tile_rows.saturating_sub(4) {
    let mut col = 4 + (cfarray[row & 1][0] & 1) as usize;
    let mut indx = row * TILE_SIZE + col;
    let mut lpindx = indx / 2;
    while col < tile_cols.saturating_sub(4) {
      let cfai = cfa[indx];

      // Cardinal gradients. The parenthesisation is upstream's: each pair of
      // absolute differences is summed first, then the three terms are added.
      let n_grad = EPS + (abs(cfa[indx - W1] - cfa[indx + W1]) + abs(cfai - cfa[indx - W2]))
        + (abs(cfa[indx - W1] - cfa[indx - W3]) + abs(cfa[indx - W2] - cfa[indx - W4]));
      let s_grad = EPS + (abs(cfa[indx - W1] - cfa[indx + W1]) + abs(cfai - cfa[indx + W2]))
        + (abs(cfa[indx + W1] - cfa[indx + W3]) + abs(cfa[indx + W2] - cfa[indx + W4]));
      let w_grad = EPS + (abs(cfa[indx - 1] - cfa[indx + 1]) + abs(cfai - cfa[indx - 2]))
        + (abs(cfa[indx - 1] - cfa[indx - 3]) + abs(cfa[indx - 2] - cfa[indx - 4]));
      let e_grad = EPS + (abs(cfa[indx - 1] - cfa[indx + 1]) + abs(cfai - cfa[indx + 2]))
        + (abs(cfa[indx + 1] - cfa[indx + 3]) + abs(cfa[indx + 2] - cfa[indx + 4]));

      // Cardinal estimates: the neighbouring green sample scaled by the `lpf`
      // ratio between this cell and the same-class neighbour in that direction.
      let lpfi = lpf[lpindx];
      let n_est = cfa[indx - W1] * (lpfi + lpfi) / (EPS + lpfi + lpf[lpindx - W1]);
      let s_est = cfa[indx + W1] * (lpfi + lpfi) / (EPS + lpfi + lpf[lpindx + W1]);
      let w_est = cfa[indx - 1] * (lpfi + lpfi) / (EPS + lpfi + lpf[lpindx - 1]);
      let e_est = cfa[indx + 1] * (lpfi + lpfi) / (EPS + lpfi + lpf[lpindx + 1]);

      let v_est = (s_grad * n_est + n_grad * s_est) / (n_grad + s_grad);
      let h_est = (w_grad * e_est + e_grad * w_est) / (e_grad + w_grad);

      // Refined vertical/horizontal local discrimination.
      let vh_central = vh_dir[indx];
      let vh_neigh = 0.25
        * ((vh_dir[indx - W1 - 1] + vh_dir[indx - W1 + 1]) + (vh_dir[indx + W1 - 1] + vh_dir[indx + W1 + 1]));
      let vh_disc = if abs(0.5 - vh_central) < abs(0.5 - vh_neigh) { vh_neigh } else { vh_central };

      rgb[1][indx] = intp(vh_disc, h_est, v_est);

      col += 2;
      indx += 2;
      lpindx += 1;
    }
  }

  // --- step 4.0 (upstream 212-217) ----------------------------------------
  for row in 3..tile_rows.saturating_sub(3) {
    let mut col = 3;
    let mut indx = row * TILE_SIZE + col;
    let mut indx2 = indx / 2;
    while col < tile_cols.saturating_sub(3) {
      p_cdiff[indx2] = sqr(
        (cfa[indx - W3 - 3] - cfa[indx - W1 - 1] - cfa[indx + W1 + 1] + cfa[indx + W3 + 3])
          - 3.0 * (cfa[indx - W2 - 2] + cfa[indx + W2 + 2])
          + 6.0 * cfa[indx],
      );
      q_cdiff[indx2] = sqr(
        (cfa[indx - W3 + 3] - cfa[indx - W1 + 1] - cfa[indx + W1 - 1] + cfa[indx + W3 - 3])
          - 3.0 * (cfa[indx - W2 + 2] + cfa[indx + W2 - 2])
          + 6.0 * cfa[indx],
      );
      col += 2;
      indx += 2;
      indx2 += 1;
    }
  }

  // --- step 4.1 (upstream 220-226) ----------------------------------------
  for row in 4..tile_rows.saturating_sub(4) {
    let mut col = 4 + (cfarray[row & 1][0] & 1) as usize;
    // `indx` only seeds the three half-resolution cursors; upstream still
    // advances it in the loop head but never reads it again, so this port keeps
    // it immutable and drops that dead increment.
    let indx = row * TILE_SIZE + col;
    let mut indx2 = indx / 2;
    let mut indx3 = (indx - W1 - 1) / 2;
    let mut indx4 = (indx + W1 - 1) / 2;
    while col < tile_cols.saturating_sub(4) {
      let p_stat = max2(EPSSQ, p_cdiff[indx3] + p_cdiff[indx2] + p_cdiff[indx4 + 1]);
      let q_stat = max2(EPSSQ, q_cdiff[indx3 + 1] + q_cdiff[indx2] + q_cdiff[indx4]);
      pq_dir[indx2] = p_stat / (p_stat + q_stat);
      col += 2;
      indx2 += 1;
      indx3 += 1;
      indx4 += 1;
    }
  }

  // --- step 4.2 (upstream 229-257) ----------------------------------------
  // Red and blue at the blue and red positions. Every `rgb[c]` read here lands on
  // a *native* sample: the diagonal neighbours of a red position are blue
  // positions, where the blue plane holds the measured value and the green plane
  // holds step 3's estimate. The rows are visited in order on purpose — the
  // `rgb[1][indx ± W2 ± 2]` terms read green at positions this very loop writes.
  for row in 4..tile_rows.saturating_sub(4) {
    let mut col = 4 + (cfarray[row & 1][0] & 1) as usize;
    let mut indx = row * TILE_SIZE + col;
    let mut pqindx = indx / 2;
    let mut pqindx2 = (indx - W1 - 1) / 2;
    let mut pqindx3 = (indx + W1 - 1) / 2;
    while col < tile_cols.saturating_sub(4) {
      let c = (2 - cfarray[row & 1][col & 1]) as usize;

      // Refined P/Q diagonal local discrimination.
      let pq_central = pq_dir[pqindx];
      let pq_neigh = 0.25 * (pq_dir[pqindx2] + pq_dir[pqindx2 + 1] + pq_dir[pqindx3] + pq_dir[pqindx3 + 1]);
      let pq_disc = if abs(0.5 - pq_central) < abs(0.5 - pq_neigh) { pq_neigh } else { pq_central };

      // Diagonal gradients.
      let nw_grad = EPS + abs(rgb[c][indx - W1 - 1] - rgb[c][indx + W1 + 1])
        + abs(rgb[c][indx - W1 - 1] - rgb[c][indx - W3 - 3])
        + abs(rgb[1][indx] - rgb[1][indx - W2 - 2]);
      let ne_grad = EPS + abs(rgb[c][indx - W1 + 1] - rgb[c][indx + W1 - 1])
        + abs(rgb[c][indx - W1 + 1] - rgb[c][indx - W3 + 3])
        + abs(rgb[1][indx] - rgb[1][indx - W2 + 2]);
      let sw_grad = EPS + abs(rgb[c][indx - W1 + 1] - rgb[c][indx + W1 - 1])
        + abs(rgb[c][indx + W1 - 1] - rgb[c][indx + W3 - 3])
        + abs(rgb[1][indx] - rgb[1][indx + W2 - 2]);
      let se_grad = EPS + abs(rgb[c][indx - W1 - 1] - rgb[c][indx + W1 + 1])
        + abs(rgb[c][indx + W1 + 1] - rgb[c][indx + W3 + 3])
        + abs(rgb[1][indx] - rgb[1][indx + W2 + 2]);

      // Diagonal colour differences.
      let nw_est = rgb[c][indx - W1 - 1] - rgb[1][indx - W1 - 1];
      let ne_est = rgb[c][indx - W1 + 1] - rgb[1][indx - W1 + 1];
      let sw_est = rgb[c][indx + W1 - 1] - rgb[1][indx + W1 - 1];
      let se_est = rgb[c][indx + W1 + 1] - rgb[1][indx + W1 + 1];

      let p_est = (nw_grad * se_est + se_grad * nw_est) / (nw_grad + se_grad);
      let q_est = (ne_grad * sw_est + sw_grad * ne_est) / (ne_grad + sw_grad);

      rgb[c][indx] = rgb[1][indx] + intp(pq_disc, q_est, p_est);

      col += 2;
      indx += 2;
      pqindx += 1;
      pqindx2 += 1;
      pqindx3 += 1;
    }
  }

  // --- step 4.3 (upstream 260-301) ----------------------------------------
  // Red and blue at the green positions. Note the CFA lookup is on column **1**
  // here, not 0: that is what starts the walk on a green cell.
  for row in 4..tile_rows.saturating_sub(4) {
    let mut col = 4 + (cfarray[row & 1][1] & 1) as usize;
    let mut indx = row * TILE_SIZE + col;
    while col < tile_cols.saturating_sub(4) {
      let vh_central = vh_dir[indx];
      let vh_neigh = 0.25
        * ((vh_dir[indx - W1 - 1] + vh_dir[indx - W1 + 1]) + (vh_dir[indx + W1 - 1] + vh_dir[indx + W1 + 1]));
      let vh_disc = if abs(0.5 - vh_central) < abs(0.5 - vh_neigh) { vh_neigh } else { vh_central };

      let g_centre = rgb[1][indx];
      let g_n = EPS + abs(g_centre - rgb[1][indx - W2]);
      let g_s = EPS + abs(g_centre - rgb[1][indx + W2]);
      let g_w = EPS + abs(g_centre - rgb[1][indx - 2]);
      let g_e = EPS + abs(g_centre - rgb[1][indx + 2]);

      let g_nw1 = rgb[1][indx - W1];
      let g_sw1 = rgb[1][indx + W1];
      let g_w1 = rgb[1][indx - 1];
      let g_e1 = rgb[1][indx + 1];

      for c in [0usize, 2] {
        let sn_abs = abs(rgb[c][indx - W1] - rgb[c][indx + W1]);
        let ew_abs = abs(rgb[c][indx - 1] - rgb[c][indx + 1]);
        let n_grad = g_n + sn_abs + abs(rgb[c][indx - W1] - rgb[c][indx - W3]);
        let s_grad = g_s + sn_abs + abs(rgb[c][indx + W1] - rgb[c][indx + W3]);
        let w_grad = g_w + ew_abs + abs(rgb[c][indx - 1] - rgb[c][indx - 3]);
        let e_grad = g_e + ew_abs + abs(rgb[c][indx + 1] - rgb[c][indx + 3]);

        let n_est = rgb[c][indx - W1] - g_nw1;
        let s_est = rgb[c][indx + W1] - g_sw1;
        let w_est = rgb[c][indx - 1] - g_w1;
        let e_est = rgb[c][indx + 1] - g_e1;

        let v_est = (n_grad * s_est + s_grad * n_est) / (n_grad + s_grad);
        let h_est = (e_grad * w_est + w_grad * e_est) / (e_grad + w_grad);

        rgb[c][indx] = g_centre + intp(vh_disc, h_est, v_est);
      }

      col += 2;
      indx += 2;
    }
  }

  // --- write out (upstream 303-315) ---------------------------------------
  // Upstream's `(tr == 0) ? rcdBorder : tileBorder` ternaries both select 9, so
  // the region is always the tile inset by the border.
  let first_v = row_start + RCD_BORDER;
  let last_v = row_end.saturating_sub(RCD_BORDER);
  let first_h = col_start + RCD_BORDER;
  let last_h = col_end.saturating_sub(RCD_BORDER);

  for row in first_v..last_v {
    let mut idx = (row - row_start) * TILE_SIZE + first_h - col_start;
    let mut out_idx = (row - band.row0) * width + first_h;
    for _col in first_h..last_h {
      // Upstream: `std::max(0.f, rgb[c][idx] * scale)`. `scale` is elided on the
      // way out exactly as it was on the way in, so the [0,1] domain is kept.
      band.red[out_idx] = max0(rgb[0][idx]);
      band.green[out_idx] = max0(rgb[1][idx]);
      band.blue[out_idx] = max0(rgb[2][idx]);
      idx += 1;
      out_idx += 1;
    }
  }
}

/// `RawImageSource::rcd_demosaic` — ratio corrected demosaicing.
///
/// `mosaic` is the normalised Bayer mosaic; the result is the three planes the
/// kernel wrote plus the `border_interpolate` frame upstream fills last.
///
/// # Errors
/// [`Error::UnsupportedCfa`] when the CFA has a fourth colour, which upstream
/// hands to `igv_interpolate` (not ported yet).
pub fn bayer_rcd_demosaic(cfa: &CfaDesc, raw: &Array2D<f32>) -> Result<Rgb, Error> {
  // Upstream's guard, read off the folded mask (`rcd_demosaic.cc:56-65`). It can
  // never fire for a CFA built by `CfaDesc::bayer_from_2x2`, whose fold removed
  // every `3`; it fires for a genuine four-colour CFA, whose mask was never
  // folded, and upstream then falls back to IGV.
  for i in 0..2 {
    for j in 0..2 {
      if cfa.fc(i, j) == 3 {
        return Err(Error::UnsupportedCfa(
          "rcd: a four-colour CFA falls back to igv_interpolate, which is not ported yet",
        ));
      }
    }
  }

  let (w, h) = (raw.width(), raw.height());
  let cfarray = [[cfa.fc(0, 0), cfa.fc(0, 1)], [cfa.fc(1, 0), cfa.fc(1, 1)]];
  let num_tw = w / TILE_STEP + usize::from(w % TILE_STEP != 0);

  let mut out = Rgb::new(w, h);
  let spec = tile_row_bands(h);

  // The bands borrow the three planes mutably; the scopes end before the border
  // pass, which needs the whole planes back.
  {
    let mut bands = split_bands(out.red.as_mut_slice(), out.green.as_mut_slice(), out.blue.as_mut_slice(), w, &spec);

    bands.par_iter_mut().for_each(|band| {
      let row_start = band.tile_row_start;
      let row_end = (row_start + TILE_SIZE).min(h);

      // One scratch per tile row, reused across that row's tiles — upstream
      // allocates once per thread and reuses it for every tile the thread takes.
      let mut sc = TileScratch::new();

      for tc in 0..num_tw {
        let col_start = tc * TILE_STEP;
        let col_end = (col_start + TILE_SIZE).min(w);

        // Upstream's per-tile skip, this time on the column axis.
        if col_start + TILE_BORDER == col_end.saturating_sub(TILE_BORDER) {
          continue;
        }

        demosaic_tile(raw, &cfarray, row_start, row_end, col_start, col_end, &mut sc, band, w);
      }
    });
  }

  border_interpolate(cfa, raw, &mut out.red, &mut out.green, &mut out.blue, RCD_BORDER);

  Ok(out)
}

#[cfg(test)]
mod tests {
  use super::*;

  fn rggb() -> CfaDesc {
    CfaDesc::bayer_from_2x2([[0, 1], [1, 2]])
  }

  /// The whole parallel scheme rests on this: the tile rows' bands must be
  /// disjoint, ascending and adjacent, and must together cover exactly the
  /// rectangle the kernel owns before the border pass runs.
  #[test]
  fn tile_row_bands_partition_the_kernel_region() {
    for height in [1usize, 17, 18, 19, 20, 100, 176, 177, 194, 195, 352, 353, 1000, 6000] {
      let bands = tile_row_bands(height);

      for (tile_row_start, start, end) in &bands {
        assert!(start < end, "height {height}: empty band");
        assert_eq!(tile_row_start % TILE_STEP, 0, "height {height}: tile row origin");
      }

      let first = RCD_BORDER;
      let last = height.saturating_sub(RCD_BORDER);
      if last > first {
        assert_eq!(bands.first().expect("non-empty interior").1, first, "height {height}: starts");
        assert_eq!(bands.last().expect("non-empty interior").2, last, "height {height}: ends");
        for pair in bands.windows(2) {
          assert_eq!(pair[0].2, pair[1].1, "height {height}: gap or overlap at {}", pair[0].2);
        }
      }
    }
  }

  /// Every plane's *sampled* channel must come back as the raw value, bit for
  /// bit — the load writes it and no later stage touches it. This is the cheapest
  /// check that catches the classic port errors: swapping two planes, indexing
  /// the wrong CFA phase, or letting a later pass overwrite a measured sample.
  #[test]
  fn sampled_channels_are_preserved_exactly() {
    let (w, h) = (64usize, 64usize);
    let cfa = rggb();
    let mut raw = Array2D::new(w, h);
    for i in 0..h {
      for j in 0..w {
        raw.set(i, j, 0.1 + 0.001 * i as f32 + 0.002 * j as f32);
      }
    }

    let rgb = bayer_rcd_demosaic(&cfa, &raw).expect("demosaic");

    for i in 0..h {
      for j in 0..w {
        let want = lim01(raw.at(i, j));
        let got = match cfa.fc(i, j) {
          0 => rgb.red.at(i, j),
          1 => rgb.green.at(i, j),
          _ => rgb.blue.at(i, j),
        };
        assert_eq!(got, want, "sampled channel at {i},{j}");
      }
    }
  }

  /// A flat mosaic must come back flat on all three planes, frame included.
  ///
  /// Not *bit*-exact: `EPS` floors the gradients and the `lpf` ratio, so the
  /// surviving error is `v * EPS / (EPS + 8v)` ≈ `EPS / 8` = 1.25e-6 at
  /// `v = 0.5`. The tolerance is set well inside that, and any real indexing fault
  /// produces an error of order `v`, so the check still has all its teeth.
  #[test]
  fn flat_field_comes_back_flat() {
    let (w, h) = (64usize, 64usize);
    let level = 0.5f32;
    let raw = Array2D::filled(w, h, level);

    let rgb = bayer_rcd_demosaic(&rggb(), &raw).expect("demosaic");

    for i in 0..h {
      for j in 0..w {
        for (name, plane) in [("R", &rgb.red), ("G", &rgb.green), ("B", &rgb.blue)] {
          let got = plane.at(i, j);
          assert!((got - level).abs() < 1e-5, "{name} at {i},{j}: {got} vs {level}");
        }
      }
    }
  }

  /// A four-colour CFA must be refused rather than demosaiced: upstream's
  /// `FC(i,j) == 3` guard is live here (the fold skipped such a mask), and its
  /// fallback, IGV, is not ported yet.
  #[test]
  fn a_four_colour_cfa_is_rejected() {
    // RGGB's four-colour original, i.e. a mask `set_prefilters` never folded.
    let cfa = CfaDesc {
      is_bayer: true,
      colors: 4,
      filters: 0xb4b4_b4b4,
      prefilters: 0xb4b4_b4b4,
      xtrans: [[0; 6]; 6],
    };
    assert!(cfa.has_fourth_colour());
    assert_eq!(cfa.fc(1, 0), 3, "the guard must be able to see the second green");

    let raw = Array2D::filled(32, 32, 0.5);
    assert!(matches!(bayer_rcd_demosaic(&cfa, &raw), Err(Error::UnsupportedCfa(_))));
  }

  /// Including on a mosaic built to stress every branch: a checkerboard, a black
  /// region that makes the `lpf` ratios and gradients degenerate, and a row of
  /// full scale. Nothing here may panic or produce a non-finite sample.
  #[test]
  fn hostile_mosaic_stays_finite() {
    let (w, h) = (60usize, 60usize);
    let mut raw = Array2D::new(w, h);
    for i in 0..h {
      for j in 0..w {
        let v = match (i / 7 + j / 5) % 3 {
          0 => 0.0,
          1 => 1.0,
          _ => if (i + j) % 2 == 0 { 0.25 } else { 0.75 },
        };
        raw.set(i, j, v);
      }
    }

    let rgb = bayer_rcd_demosaic(&rggb(), &raw).expect("demosaic");

    for (name, plane) in [("R", &rgb.red), ("G", &rgb.green), ("B", &rgb.blue)] {
      for (i, row) in plane.rows().enumerate() {
        for (j, &v) in row.iter().enumerate() {
          assert!(v.is_finite(), "{name} at {i},{j} is {v}");
          assert!(v >= 0.0, "{name} at {i},{j} is negative: {v}");
        }
      }
    }
  }
}
