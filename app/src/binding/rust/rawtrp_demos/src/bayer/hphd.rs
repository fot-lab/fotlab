//! HPHD — "High Pass Horizontal/Vertical Direction" Bayer demosaic.
//!
//! Ported from `external/RawTherapee/rtengine/hphd_demosaic_RT.cc`
//! (Copyright (c) 2004-2019 Gabor Horvath, GPL-3.0) — `hphd_vertical`,
//! `hphd_horizontal`, `hphd_green` and `RawImageSource::hphd_demosaic()`.
//!
//! The kernel is a *directional* interpolator: it builds a high-pass magnitude
//! for the vertical and the horizontal direction of every pixel, decides which
//! direction is the smoother one, and interpolates green along it. Red and blue
//! then come from [`interpolate_row_rb_mul_pp`] (`bayer/interp.rs`) on top of the
//! green plane, and the 4-pixel frame from `border_interpolate`.
//!
//! ## Fidelity notes
//!
//! * **The numeric domain is the mosaic's own (0..1).** HPHD is *almost*
//!   homogeneous of degree 1 in `rawData` — every term is a difference of samples
//!   or a weighted mean of them — but two absolute constants break that: the
//!   `0.001f` floor on `dev`, and the `eps = 0.001f` added to `dx`/`dy`. Both are
//!   therefore divided by `SCALE` on the way in (`dev`'s, being a sum of
//!   *squares*, by `SCALE²`), which is what makes them the same relative size as
//!   in RT. Nothing else is scaled, and no value is rescaled on the way out — so
//!   `border_interpolate`, which reads `rawData` directly, needs no adjustment.
//!   This follows `bayer/igv.rs`, which keeps the 0..1 domain and scales only the
//!   constants that need it, rather than `bayer/dcb.rs`, which makes the whole
//!   round trip explicit.
//! * **The vertical pass is serial.** Upstream splits it across OpenMP threads by
//!   *column* range (`blk = W / nthreads`), which a row-major `Array2D` cannot
//!   express as disjoint `&mut` slices without `unsafe`. The alternatives were a
//!   transposed `hpmap` (every later read becomes strided) or a rolling window
//!   over nine temp rows (more index bookkeeping than this kernel deserves), so
//!   the pass keeps RT's 8-column tiling — whose `temp`/`avg`/`dev` are only
//!   `8 x H` and so cost almost nothing — and runs sequentially. The other three
//!   passes are row-parallel and bit-identical to upstream.
//! * **The horizontal pass reuses its buffers the way upstream does.** RT
//!   allocates `temp`/`avg`/`dev` once per thread and does *not* re-zero them
//!   between rows, so `temp[0..5]` and `temp[W-5..W]` (which the 5-tap stencils
//!   read but nothing ever writes) hold the *previous row's* values rather than
//!   0. This port chunks rows into `current_num_threads()` blocks and gives each
//!   block one freshly-zeroed set, which reproduces that exactly: the reset
//!   points are the same block boundaries.
//! * **`hpmap` is a single `w x h` plane**, not two. RT's `hphd_vertical` writes a
//!   magnitude into it and `hphd_horizontal` overwrites columns `5 .. W-5` with
//!   the 0/1/2 decision, leaving the outer five columns holding a magnitude —
//!   which `hphd_green` then reads and, not being 0/1/2, treats as "no
//!   direction". That is upstream behaviour, not a bug introduced here.
//! * **The two `#pragma omp simd` / SSE2 branches are one code path.** RT ships a
//!   vectorised and a scalar spelling of the horizontal pass; they are the same
//!   nine-term sums in the same order, so this port has one, the scalar one.
//! * `#pragma omp parallel for` over rows becomes rayon over rows (or row blocks).

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::bayer::interp::interpolate_row_rb_mul_pp;
use crate::border::border_interpolate;
use crate::cfa::CfaDesc;
use crate::math::{abs, max0, max2, sqr};
use crate::{Error, Rgb};

/// RT's `rawData` is 0..65535 where this crate's mosaic is 0..1.
///
/// `65536` rather than `65535` so that the constant matches `bayer/dcb.rs` and
/// `bayer/lmmse.rs`; the two differ by 0.0015%, which is far below the precision
/// anything here depends on.
const SCALE: f32 = 65536.0;

/// Upstream's `eps` (`hphd_green`), in mosaic units. It is added to a *first
/// order* difference, so it scales by `SCALE`.
const EPS: f32 = 0.001 / SCALE;

/// Upstream's `std::max(0.001f, …)` floor on `dev`, in mosaic units. `dev` is a
/// sum of *squared* differences, so it scales by `SCALE²`.
const DEV_FLOOR: f32 = 0.001 / (SCALE * SCALE);

/// Upstream's `constexpr int numCols` — how many columns one vertical tile
/// covers. Chosen for L1 residency; kept verbatim.
const NUM_COLS: usize = 8;

/// Upstream's `0.8f` in the two "is one direction clearly smoother?" tests.
const DIR_RATIO: f32 = 0.8;

/// RT's `hphd_vertical` (`hphd_demosaic_RT.cc:39-116`).
///
/// Fills rows `5 .. h-5` of every column of `hp` with the vertical high-pass
/// magnitude; rows `0..5` and `h-5..h` stay 0, exactly as upstream's zero-filled
/// `hpmap` does. Serial — see the module note.
fn hphd_vertical(raw: &Array2D<f32>, hp: &mut Array2D<f32>) {
  let (w, h) = (raw.width(), raw.height());

  // RT's `JaggedArray<float> temp/avg/dev(numCols, H, true)`: `H` rows of
  // `numCols`, zero-filled, allocated once per call. Flat `NUM_COLS x h` here.
  let mut temp = vec![0.0f32; NUM_COLS * h];
  let mut avg = vec![0.0f32; NUM_COLS * h];
  let mut dev = vec![0.0f32; NUM_COLS * h];

  let mut k = 0usize;
  while k + NUM_COLS <= w {
    vertical_tile(raw, hp, &mut temp, &mut avg, &mut dev, k, NUM_COLS, h);
    k += NUM_COLS;
  }
  if k < w {
    // RT's scalar tail (`for (; k < col_to; k++)`) walks the last `w % 8`
    // columns one at a time. Running the same tile code with a shorter lane
    // count is identical: `n` only sizes the inner loops, and the lanes that
    // exist are the lanes that upstream would have walked.
    vertical_tile(raw, hp, &mut temp, &mut avg, &mut dev, k, w - k, h);
  }
}

/// One vertical tile: columns `col0 .. col0+n`, all rows.
///
/// `temp`/`avg`/`dev` are `n x h`, laid out `row * n + lane`, matching RT's
/// `temp[row][lane]` on a `JaggedArray(numCols, H)`. They are **not** cleared
/// between tiles: the rows outside `5 .. h-5` are never written by any tile, so
/// they keep the zeros they started with, which is what upstream's single
/// zero-filled allocation gives it too.
fn vertical_tile(
  raw: &Array2D<f32>,
  hp: &mut Array2D<f32>,
  temp: &mut [f32],
  avg: &mut [f32],
  dev: &mut [f32],
  col0: usize,
  n: usize,
  h: usize,
) {
  // Step 1 — a 5th-order vertical derivative of the raw samples.
  for i in 5..h.saturating_sub(5) {
    for lc in 0..n {
      let c = col0 + lc;
      temp[i * n + lc] = abs(
        (raw.at(i - 5, c) - raw.at(i + 5, c))
          - 8.0 * (raw.at(i - 4, c) - raw.at(i + 4, c))
          + 27.0 * (raw.at(i - 3, c) - raw.at(i + 3, c))
          - 48.0 * (raw.at(i - 2, c) - raw.at(i + 2, c))
          + 42.0 * (raw.at(i - 1, c) - raw.at(i + 1, c)),
      );
    }
  }

  // Step 2 — a 9-tap mean and variance of that derivative along the column.
  for j in 4..h.saturating_sub(4) {
    for lc in 0..n {
      let a = ((temp[(j - 4) * n + lc] + temp[(j - 3) * n + lc])
        + (temp[(j - 2) * n + lc] + temp[(j - 1) * n + lc])
        + (temp[j * n + lc] + temp[(j + 1) * n + lc])
        + (temp[(j + 2) * n + lc] + temp[(j + 3) * n + lc])
        + temp[(j + 4) * n + lc])
        / 9.0;
      avg[j * n + lc] = a;
      dev[j * n + lc] = max2(
        DEV_FLOOR,
        (sqr(temp[(j - 4) * n + lc] - a) + sqr(temp[(j - 3) * n + lc] - a))
          + (sqr(temp[(j - 2) * n + lc] - a) + sqr(temp[(j - 1) * n + lc] - a))
          + (sqr(temp[j * n + lc] - a) + sqr(temp[(j + 1) * n + lc] - a))
          + (sqr(temp[(j + 2) * n + lc] - a) + sqr(temp[(j + 3) * n + lc] - a))
          + sqr(temp[(j + 4) * n + lc] - a),
      );
    }
  }

  // Step 3 — blend the two neighbouring means, weighted by *this* pixel's
  // variance: a smooth neighbourhood defers to its smoother neighbour.
  for j in 5..h.saturating_sub(5) {
    let row = hp.row_mut(j);
    for lc in 0..n {
      let al = avg[(j - 1) * n + lc];
      let ar = avg[(j + 1) * n + lc];
      let dl = dev[(j - 1) * n + lc];
      let dr = dev[(j + 1) * n + lc];
      row[col0 + lc] = al + (ar - al) * dl / (dl + dr);
    }
  }
}

/// RT's `hphd_horizontal` (`hphd_demosaic_RT.cc:118-186`).
///
/// Overwrites columns `5 .. w-5` of every row of `hp` with the direction
/// decision: `2` = interpolate vertically, `1` = horizontally, `0` = use all
/// four directions.
fn hphd_horizontal(raw: &Array2D<f32>, hp: &mut Array2D<f32>) {
  let (w, h) = (raw.width(), raw.height());

  // Upstream gives each OpenMP thread a row range and one buffer set; mirroring
  // that means one block per thread, each with its own freshly-zeroed buffers —
  // see the module note about the buffers not being cleared between rows.
  let blocks = rayon::current_num_threads().max(1);
  let rows_per_block = ((h + blocks - 1) / blocks).max(1);

  hp.as_mut_slice()
    .par_chunks_mut(w * rows_per_block)
    .enumerate()
    .for_each(|(blk, chunk)| {
      let row0 = blk * rows_per_block;
      let mut temp = vec![0.0f32; w];
      let mut avg = vec![0.0f32; w];
      let mut dev = vec![0.0f32; w];

      for (li, hp_row) in chunk.chunks_mut(w).enumerate() {
        let i = row0 + li;
        let row = raw.row(i);

        // Step 1 — the same 5th-order derivative, along the row.
        for j in 5..w.saturating_sub(5) {
          temp[j] = abs(
            (row[j - 5] - row[j + 5]) - 8.0 * (row[j - 4] - row[j + 4]) + 27.0 * (row[j - 3] - row[j + 3])
              - 48.0 * (row[j - 2] - row[j + 2])
              + 42.0 * (row[j - 1] - row[j + 1]),
          );
        }

        // Step 2 — the same 9-tap mean and variance.
        for j in 4..w.saturating_sub(4) {
          let a = ((temp[j - 4] + temp[j - 3]) + (temp[j - 2] + temp[j - 1]) + (temp[j] + temp[j + 1])
            + (temp[j + 2] + temp[j + 3])
            + temp[j + 4])
            / 9.0;
          avg[j] = a;
          dev[j] = max2(
            DEV_FLOOR,
            (sqr(temp[j - 4] - a) + sqr(temp[j - 3] - a))
              + (sqr(temp[j - 2] - a) + sqr(temp[j - 1] - a))
              + (sqr(temp[j] - a) + sqr(temp[j + 1] - a))
              + (sqr(temp[j + 2] - a) + sqr(temp[j + 3] - a))
              + sqr(temp[j + 4] - a),
          );
        }

        // Step 3 — compare the horizontal magnitude with the vertical one the
        // previous pass left in `hp_row`.
        for j in 5..w.saturating_sub(5) {
          let al = avg[j - 1];
          let ar = avg[j + 1];
          let dl = dev[j - 1];
          let dr = dev[j + 1];
          let hpv = al + (ar - al) * dl / (dl + dr);
          let old = hp_row[j];
          hp_row[j] = if old < DIR_RATIO * hpv {
            2.0
          } else if hpv < DIR_RATIO * old {
            1.0
          } else {
            0.0
          };
        }
      }
    });
}

/// RT's `hphd_green` (`hphd_demosaic_RT.cc:188-283`).
///
/// Writes rows `3 .. h-3`, columns `3 .. w-3` of `green`. Green sites keep their
/// sample; the rest are interpolated along the direction `hp` picked.
fn hphd_green(cfa: &CfaDesc, raw: &Array2D<f32>, hp: &Array2D<f32>, green: &mut Array2D<f32>) {
  let (w, h) = (raw.width(), raw.height());

  green.par_rows_mut().enumerate().for_each(|(i, g_row)| {
    if i < 3 || i >= h.saturating_sub(3) {
      return;
    }

    for j in 3..w.saturating_sub(3) {
      if cfa.is_green(i, j) {
        g_row[j] = raw.at(i, j);
        continue;
      }

      let dir = hp.at(i, j);
      let g = if dir == 1.0 {
        // Horizontal: the two horizontal candidates g2 (right) and g4 (left).
        let g2 = raw.at(i, j + 1) - raw.at(i, j + 2) * 0.5;
        let g4 = raw.at(i, j - 1) - raw.at(i, j - 2) * 0.5;

        let dx = EPS + abs(raw.at(i, j + 1) - raw.at(i, j - 1));
        let mut d1 = raw.at(i, j + 3) - raw.at(i, j + 1);
        let mut d2 = raw.at(i, j + 2) - raw.at(i, j);
        let mut d3 = raw.at(i - 1, j + 2) - raw.at(i - 1, j);
        let mut d4 = raw.at(i + 1, j + 2) - raw.at(i + 1, j);
        let e2 = 1.0 / (dx + (abs(d1) + abs(d2)) + (abs(d3) + abs(d4)) * 0.5);

        d1 = raw.at(i, j - 3) - raw.at(i, j - 1);
        d2 = raw.at(i, j - 2) - raw.at(i, j);
        d3 = raw.at(i - 1, j - 2) - raw.at(i - 1, j);
        d4 = raw.at(i + 1, j - 2) - raw.at(i + 1, j);
        let e4 = 1.0 / (dx + (abs(d1) + abs(d2)) + (abs(d3) + abs(d4)) * 0.5);

        raw.at(i, j) * 0.5 + (e2 * g2 + e4 * g4) / (e2 + e4)
      } else if dir == 2.0 {
        // Vertical: g1 (up) and g3 (down).
        let g1 = raw.at(i - 1, j) - raw.at(i - 2, j) * 0.5;
        let g3 = raw.at(i + 1, j) - raw.at(i + 2, j) * 0.5;

        let dy = EPS + abs(raw.at(i + 1, j) - raw.at(i - 1, j));
        let mut d1 = raw.at(i - 1, j) - raw.at(i - 3, j);
        let mut d2 = raw.at(i, j) - raw.at(i - 2, j);
        let mut d3 = raw.at(i, j - 1) - raw.at(i - 2, j - 1);
        let mut d4 = raw.at(i, j + 1) - raw.at(i - 2, j + 1);
        let e1 = 1.0 / (dy + (abs(d1) + abs(d2)) + (abs(d3) + abs(d4)) * 0.5);

        d1 = raw.at(i + 1, j) - raw.at(i + 3, j);
        d2 = raw.at(i, j) - raw.at(i + 2, j);
        d3 = raw.at(i, j - 1) - raw.at(i + 2, j - 1);
        d4 = raw.at(i, j + 1) - raw.at(i + 2, j + 1);
        let e3 = 1.0 / (dy + (abs(d1) + abs(d2)) + (abs(d3) + abs(d4)) * 0.5);

        raw.at(i, j) * 0.5 + (e1 * g1 + e3 * g3) / (e1 + e3)
      } else {
        // No direction: all four candidates, each weighted by its own
        // smoothness estimate.
        let g1 = raw.at(i - 1, j) - raw.at(i - 2, j) * 0.5;
        let g2 = raw.at(i, j + 1) - raw.at(i, j + 2) * 0.5;
        let g3 = raw.at(i + 1, j) - raw.at(i + 2, j) * 0.5;
        let g4 = raw.at(i, j - 1) - raw.at(i, j - 2) * 0.5;

        let dx = EPS + abs(raw.at(i, j + 1) - raw.at(i, j - 1));
        let dy = EPS + abs(raw.at(i + 1, j) - raw.at(i - 1, j));

        let mut d1 = raw.at(i - 1, j) - raw.at(i - 3, j);
        let mut d2 = raw.at(i, j) - raw.at(i - 2, j);
        let mut d3 = raw.at(i, j - 1) - raw.at(i - 2, j - 1);
        let mut d4 = raw.at(i, j + 1) - raw.at(i - 2, j + 1);
        let e1 = 1.0 / (dy + (abs(d1) + abs(d2)) + (abs(d3) + abs(d4)) * 0.5);

        d1 = raw.at(i, j + 3) - raw.at(i, j + 1);
        d2 = raw.at(i, j + 2) - raw.at(i, j);
        d3 = raw.at(i - 1, j + 2) - raw.at(i - 1, j);
        d4 = raw.at(i + 1, j + 2) - raw.at(i + 1, j);
        let e2 = 1.0 / (dx + (abs(d1) + abs(d2)) + (abs(d3) + abs(d4)) * 0.5);

        d1 = raw.at(i + 1, j) - raw.at(i + 3, j);
        d2 = raw.at(i, j) - raw.at(i + 2, j);
        d3 = raw.at(i, j - 1) - raw.at(i + 2, j - 1);
        d4 = raw.at(i, j + 1) - raw.at(i + 2, j + 1);
        let e3 = 1.0 / (dy + (abs(d1) + abs(d2)) + (abs(d3) + abs(d4)) * 0.5);

        d1 = raw.at(i, j - 3) - raw.at(i, j - 1);
        d2 = raw.at(i, j - 2) - raw.at(i, j);
        d3 = raw.at(i - 1, j - 2) - raw.at(i - 1, j);
        d4 = raw.at(i + 1, j - 2) - raw.at(i + 1, j);
        let e4 = 1.0 / (dx + (abs(d1) + abs(d2)) + (abs(d3) + abs(d4)) * 0.5);

        raw.at(i, j) * 0.5 + ((e1 * g1 + e2 * g2) + (e3 * g3 + e4 * g4)) / (e1 + e2 + e3 + e4)
      };

      g_row[j] = max0(g);
    }
  });
}

/// `RawImageSource::hphd_demosaic()` (`hphd_demosaic_RT.cc:290-360`).
///
/// `raw` is the `width x height` mosaic; the result is three `width x height`
/// planes, with the 4-pixel frame filled by `border_interpolate(…, 4, …)`.
pub fn bayer_hphd_demosaic(cfa: &CfaDesc, raw: &Array2D<f32>) -> Result<Rgb, Error> {
  let (w, h) = (raw.width(), raw.height());
  // The vertical and horizontal passes need a 5-sample halo on each side and the
  // green pass a 3-sample one, and `border_interpolate(…, 4, …)` needs 4 rows on
  // each side to make sense; 11 is where the widest stencil still fits.
  if w < 11 || h < 11 {
    return Err(Error::Shape(format!("bayer_hphd: mosaic too small: {w}x{h}")));
  }
  if cfa.has_fourth_colour() {
    // Upstream has no guard (HPHD is only reachable with an RGB CFA), but a
    // fourth colour would silently produce wrong colours, so refuse it and let
    // the caller fall back.
    return Err(Error::UnsupportedCfa("bayer_hphd"));
  }

  let mut out = Rgb::new(w, h);
  let mut hp = Array2D::new(w, h);

  hphd_vertical(raw, &mut hp);
  hphd_horizontal(raw, &mut hp);
  hphd_green(cfa, raw, &hp, &mut out.green);

  // `#pragma omp parallel for` over `i in 4..H-4`.
  {
    let Rgb { red, green, blue } = &mut out;
    red
      .par_rows_mut()
      .zip(blue.par_rows_mut())
      .enumerate()
      .for_each(|(i, (r_row, b_row))| {
        if i < 4 || i >= h.saturating_sub(4) {
          return;
        }
        let pg = green.row(i - 1);
        let cg = green.row(i);
        let ng = green.row(i + 1);
        interpolate_row_rb_mul_pp(cfa, raw, r_row, b_row, Some(pg), cg, Some(ng), i, w, h);
      });
  }

  border_interpolate(cfa, raw, &mut out.red, &mut out.green, &mut out.blue, 4);
  Ok(out)
}

#[cfg(test)]
mod tests {
  use super::*;

  /// The four 2x2 Bayer rotations, as dcraw colour codes.
  const PATTERNS: [[[u8; 2]; 2]; 4] = [
    [[0, 1], [1, 2]], // RGGB
    [[2, 1], [1, 0]], // BGGR
    [[1, 0], [2, 1]], // GBRG
    [[1, 2], [0, 1]], // GRBG
  ];

  /// Build a mosaic from a per-pixel function. `Array2D` has no `from_fn`, and
  /// the tests below need three different surfaces.
  fn surface(w: usize, h: usize, f: impl Fn(usize, usize) -> f32) -> Array2D<f32> {
    let mut m = Array2D::new(w, h);
    for i in 0..h {
      for j in 0..w {
        m.set(i, j, f(i, j));
      }
    }
    m
  }

  fn ramp(w: usize, h: usize) -> Array2D<f32> {
    surface(w, h, |i, j| 0.1 + 0.01 * i as f32 + 0.005 * j as f32)
  }

  /// A flat mosaic is the kernel's fixed point: both high-pass derivatives are
  /// zero everywhere, so every variance sits on its floor, every direction
  /// decision is "no direction", and every candidate green is `0.5 * v` — which
  /// with the `0.5 * rawData` term gives `v` back. Red and blue then copy it.
  ///
  /// It must hold for all four rotations and every pixel, border ring included:
  /// that is what pins the CFA sampling, the direction branches and the border
  /// fill all at once.
  #[test]
  fn flat_field_is_flat() {
    for pat in PATTERNS {
      let cfa = CfaDesc::bayer_from_2x2(pat);
      let (w, h) = (24usize, 24usize);
      let raw = Array2D::filled(w, h, 0.5);
      let rgb = bayer_hphd_demosaic(&cfa, &raw).expect("demosaic");
      for i in 0..h {
        for j in 0..w {
          for (name, plane) in [("R", &rgb.red), ("G", &rgb.green), ("B", &rgb.blue)] {
            let v = plane.at(i, j);
            assert!((v - 0.5).abs() < 1e-5, "{name} at {i},{j} = {v} for {pat:?}");
          }
        }
      }
    }
  }

  /// A linear ramp must come out exactly: the 5th-order stencils annihilate it,
  /// so every pixel takes the "all four directions" branch, where each candidate
  /// `g` is `0.5 * raw(i,j)` and the `0.5 * rawData` term restores the value —
  /// whatever the weights are.
  ///
  /// This is the test that catches a CFA parity or an index offset error, which
  /// the flat one cannot: they make some of the `g`s read a neighbour's channel
  /// and the identity stops holding. Checked outside the 4-pixel ring, which
  /// `border_interpolate` overwrites with a clipped (and so inexact) average.
  #[test]
  fn a_linear_ramp_gives_the_exact_green() {
    for pat in PATTERNS {
      let cfa = CfaDesc::bayer_from_2x2(pat);
      let (w, h) = (24usize, 24usize);
      let raw = ramp(w, h);
      let rgb = bayer_hphd_demosaic(&cfa, &raw).expect("demosaic");
      for i in 4..h - 4 {
        for j in 4..w - 4 {
          let want = raw.at(i, j);
          let got = rgb.green.at(i, j);
          assert!((got - want).abs() < 1e-5, "G at {i},{j} = {got}, want {want} for {pat:?}");
        }
      }
    }
  }

  /// Green sites keep their sample verbatim — the one thing every branch of
  /// `hphd_green` agrees on, and the property `interpolate_row_rb_mul_pp` builds
  /// on.
  #[test]
  fn green_sites_keep_their_sample() {
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let (w, h) = (24usize, 24usize);
    let mut raw = Array2D::new(w, h);
    // RGGB: even row + odd column is a green site.
    raw.set(10, 11, 1.0);
    assert!(cfa.is_green(10, 11));

    let rgb = bayer_hphd_demosaic(&cfa, &raw).expect("demosaic");
    assert_eq!(rgb.green.at(10, 11), 1.0);
  }

  /// A curved surface is *not* a fixed point, so the kernel must actually move
  /// pixels — otherwise the two tests above would pass for a kernel that just
  /// copies the mosaic. The surface must curve along the sampling grid: HPHD
  /// averages the two neighbours along the smoother axis, which reproduces
  /// anything linear in that axis exactly, so `i * j` alone never moves.
  #[test]
  fn a_curved_surface_is_not_the_identity() {
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let (w, h) = (24usize, 24usize);
    let raw = surface(w, h, |i, j| 0.2 + 0.0008 * (i * i + j * j) as f32);
    let rgb = bayer_hphd_demosaic(&cfa, &raw).expect("demosaic");

    let moved = (4..h - 4)
      .flat_map(|i| (4..w - 4).map(move |j| (i, j)))
      .filter(|&(i, j)| !cfa.is_green(i, j))
      .filter(|&(i, j)| (rgb.green.at(i, j) - raw.at(i, j)).abs() > 1e-6)
      .count();
    assert!(moved > 0, "the kernel left every interpolated green untouched");
  }

  /// No NaN and no negative: the two ways a variance floor or a chroma
  /// difference can go wrong show up here rather than as a wrong-looking image.
  #[test]
  fn output_is_finite_and_non_negative() {
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let (w, h) = (32usize, 32usize);
    let raw = surface(w, h, |i, j| ((i * 7 + j * 13) % 17) as f32 * 0.05);
    let rgb = bayer_hphd_demosaic(&cfa, &raw).expect("demosaic");
    for (name, plane) in [("R", &rgb.red), ("G", &rgb.green), ("B", &rgb.blue)] {
      for &v in plane.as_slice() {
        assert!(v.is_finite() && v >= 0.0, "{name} = {v}");
      }
    }
  }

  /// A four-colour CFA is refused, not silently demosaiced.
  #[test]
  fn a_four_colour_cfa_is_rejected() {
    let mut four = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    four.colors = 4;
    let raw = Array2D::filled(24, 24, 0.5);
    assert!(matches!(
      bayer_hphd_demosaic(&four, &raw).unwrap_err(),
      Error::UnsupportedCfa(_)
    ));
  }

  /// Below 11 rows or columns the 5-tap stencils cannot be evaluated.
  #[test]
  fn a_frame_too_small_is_rejected() {
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    assert!(bayer_hphd_demosaic(&cfa, &Array2D::filled(10, 24, 0.5)).is_err());
    assert!(bayer_hphd_demosaic(&cfa, &Array2D::filled(24, 10, 0.5)).is_err());
    assert!(bayer_hphd_demosaic(&cfa, &Array2D::filled(11, 11, 0.5)).is_ok());
  }

  /// A width that is not a multiple of `NUM_COLS` exercises the scalar tail in
  /// `hphd_vertical`, which must agree with the tiled path.
  #[test]
  fn a_non_multiple_width_stays_flat() {
    for w in [11usize, 13, 19, 25] {
      let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
      let raw = Array2D::filled(w, 24, 0.5);
      let rgb = bayer_hphd_demosaic(&cfa, &raw).expect("demosaic");
      for i in 0..24 {
        for j in 0..w {
          assert!((rgb.green.at(i, j) - 0.5).abs() < 1e-5, "G at {i},{j} for w={w}");
        }
      }
    }
  }
}
