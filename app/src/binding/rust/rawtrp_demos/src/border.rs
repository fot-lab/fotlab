//! `border_interpolate` — the shared border fill every Bayer kernel ends with.
//!
//! Ported from `external/RawTherapee/rtengine/demosaic_algos.cc:46-199`
//! (`RawImageSource::border_interpolate`, Copyright (c) 2004-2010 Gabor Horvath,
//! GPL-3.0).
//!
//! Structure is reproduced verbatim: three passes — every row's first/last few
//! columns, then the first few rows' interior columns, then the last few rows'
//! interior columns. The `sum[6]` accumulator, the CFA-sampled channel keeping
//! its raw value, and the two *different* bounds tests (`j1 > -1` on the left
//! edges, `j1 < width` on the right ones) are all upstream quirks kept as-is;
//! their asymmetry is harmless in range but must not be "tidied".
//!
//! Upstream calls this inside `#pragma omp single` (e.g.
//! `vng4_demosaic_RT.cc:395-400`) — it is deliberately **not** parallel, and at
//! `lborders` = 1..8 it is a negligible share of the kernel, so this port keeps
//! it sequential too instead of inventing an unsafe row-parallel split.

use crate::array2d::Array2D;
use crate::cfa::CfaDesc;

/// Which edge test upstream applies when a neighbour column steps out of range.
#[derive(Clone, Copy, PartialEq, Eq)]
enum EdgeTest {
  /// Only `j1 > -1` is required (the left-hand quadrants).
  Left,
  /// Only `j1 < width` is required (the right-hand quadrants).
  Right,
}

/// Fill one output pixel: the CFA-sampled channel keeps the raw value, the other
/// two become the mean of that colour over the in-bounds 3x3 neighbourhood.
#[inline(always)]
fn fill_pixel(
  cfa: &CfaDesc,
  raw: &Array2D<f32>,
  red: &mut [f32],
  green: &mut [f32],
  blue: &mut [f32],
  i: usize,
  j: usize,
  width: usize,
  height: usize,
  edge: EdgeTest,
) {
  let mut sum = [0.0f32; 6];

  for i1 in (i as isize - 1)..(i as isize + 2) {
    for j1 in (j as isize - 1)..(j as isize + 2) {
      let in_bounds = i1 > -1
        && (i1 as usize) < height
        && match edge {
          EdgeTest::Left => j1 > -1,
          EdgeTest::Right => (j1 as usize) < width,
        };

      if in_bounds {
        let c = cfa.fc(i1 as usize, j1 as usize) as usize;
        sum[c] += raw.at(i1 as usize, j1 as usize);
        sum[c + 3] += 1.0;
      }
    }
  }

  let c = cfa.fc(i, j);

  if c == 1 {
    red[j] = sum[0] / sum[3];
    green[j] = raw.at(i, j);
    blue[j] = sum[2] / sum[5];
  } else {
    green[j] = sum[1] / sum[4];

    if c == 0 {
      red[j] = raw.at(i, j);
      blue[j] = sum[2] / sum[5];
    } else {
      red[j] = sum[0] / sum[3];
      blue[j] = raw.at(i, j);
    }
  }
}

/// `RawImageSource::border_interpolate(winw, winh, lborders, rawData, red, green, blue)`.
///
/// `red`/`green`/`blue` must be `width x height` and already hold the interior
/// result; only the border ring is written.
pub fn border_interpolate(
  cfa: &CfaDesc,
  raw: &Array2D<f32>,
  red: &mut Array2D<f32>,
  green: &mut Array2D<f32>,
  blue: &mut Array2D<f32>,
  lborders: usize,
) {
  let width = raw.width();
  let height = raw.height();
  let bord = lborders;

  // Pass 1 — every row, first few columns (left edge test).
  for i in 0..height {
    for j in 0..bord {
      let (r, g, b) = (red.row_mut(i), green.row_mut(i), blue.row_mut(i));
      fill_pixel(cfa, raw, r, g, b, i, j, width, height, EdgeTest::Left);
    }
  }

  // Pass 1b — every row, last few columns (right edge test).
  for i in 0..height {
    for j in width.saturating_sub(bord)..width {
      let (r, g, b) = (red.row_mut(i), green.row_mut(i), blue.row_mut(i));
      fill_pixel(cfa, raw, r, g, b, i, j, width, height, EdgeTest::Right);
    }
  }

  // Pass 2 — first few rows, interior columns (left edge test).
  for i in 0..bord {
    for j in bord..width.saturating_sub(bord) {
      let (r, g, b) = (red.row_mut(i), green.row_mut(i), blue.row_mut(i));
      fill_pixel(cfa, raw, r, g, b, i, j, width, height, EdgeTest::Left);
    }
  }

  // Pass 3 — last few rows, interior columns (right edge test).
  for i in height.saturating_sub(bord)..height {
    for j in bord..width.saturating_sub(bord) {
      let (r, g, b) = (red.row_mut(i), green.row_mut(i), blue.row_mut(i));
      fill_pixel(cfa, raw, r, g, b, i, j, width, height, EdgeTest::Right);
    }
  }
}
