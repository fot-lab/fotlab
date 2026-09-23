//! 1-pass X-Trans demosaic — Frank Markesteijn's algorithm as
//! `RawImageSource::xtrans_interpolate(1, false)` (March 2014 version by Ingo
//! Weyrich, `xtrans_demosaic.cc:181-967`).
//!
//! This is the **1-pass, non-CIELab** path only: `passes = 1` (so `ndir = 4`
//! interpolation directions, no second-pass green recalculation, no `rgb += 4`
//! plane shift) and `useCieLab = false` (so the direction-difference statistic
//! is plain BT.2020 YPbPr, `xtrans_demosaic.cc:682-739`, not the Lab conversion
//! that would need the camera colour matrix — which is exactly why this kernel
//! is ported and `three_pass` is not, `FOTLAB-NATIVE-000004` rev 12).
//!
//! Structure, per 114-square tile (16-pixel overlap, tiles start at (3,3)):
//! green min/max bounds per non-green pixel, seed all four direction planes
//! with the raw mosaic, interpolate green four ways along the hexagonal
//! neighbourhoods (`allhex`, clamped to the per-pixel min/max), interpolate R/B
//! at solitary greens, R/B for blue/red pixels and for 2x2 green blocks, then
//! YPbPr derivatives → per-direction homogeneity → 5x5 homogeneity sums →
//! average the most homogeneous directions. A border pass of 11 pixels closes
//! the frame.
//!
//! ## Fidelity notes
//!
//! * **`allhex` is kept as (col, row) pairs, not `h + v*width` flats.** Upstream
//!   stores two copies of the same hexagon differing only in memory stride
//!   (`allhex[0] = h + v*width` for the raw frame, `allhex[1] = h + v*ts` for
//!   the tile planes); one (h, v) table serves both.
//! * **The hexagon triggers are reproduced verbatim**, including the
//!   overwrite order when a pixel's `%3` walk fires more than once (the last
//!   triggering direction wins for overlapping slots) and the solitary-green
//!   anchor `sgrow`/`sgcol` defaulting to (0, 0) if no solitary green exists.
//! * **The `hex[d] + hex[d+1]` test in the 2x2-green fill** is a flat-offset
//!   sum; with `|h| ≤ 2 < ts` it is zero exactly when both the column pair and
//!   the row pair cancel, which is how this port spells it.
//! * **Upstream's 1-pass leaves the R/B channels of the two diagonal planes
//!   un-filled at 2x2 green blocks** (the fill loop runs `d < ndir; d += 2`
//!   over planes 0..ndir, i.e. only the first two planes for `ndir = 4`). The
//!   homogeneity statistic then almost never selects them there, which is why
//!   the gap is invisible in upstream output — it is reproduced, not repaired.
//! * **The min/max slot sharing is approximate upstream.** Adjacent non-green
//!   columns of the same row can share a `(col-left)>>1` slot, and the slot is
//!   written from the first column's hexagon while the second column reads it
//!   with its own — upstream's behaviour, kept as-is (the bounds only need to
//!   be plausible).
//! * **Buffers are zeroed where upstream reuses uninitialized malloc memory**
//!   (the `homo`/`homosum` rows below row 6 of the first tiles, and everything
//!   past `mrow - 6` on edge tiles after the `mrow = height - top + 2`
//!   expansion). Every output pixel those reads could influence is overwritten
//!   by the trailing border pass, so zeroing changes nothing visible while
//!   making the port deterministic.
//! * **Row bands are the parallel unit** (one buffer set per band, tiles
//!   sequential left-to-right), the same trade as every tiled kernel here;
//!   upstream hands each OpenMP thread a buffer for a stripe of tiles.
//! * **Upstream's interior-skip jump cannot hang here** — see
//!   `border.rs` — because the border pass runs last over the whole frame.

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::cfa::CfaDesc;
use crate::math::{lim, max0};
use crate::xtrans::border::xtrans_border_interpolate;
use crate::xtrans::is_green;
use crate::{Error, Rgb};

/// Tile size (`xtrans_demosaic.cc:191`).
const TS: usize = 114;
/// Half tile size — the green min/max slot row stride is `tsh`.
const TSH: usize = 57;
/// Tile overlap: tiles advance by `ts - 16`.
const STEP: usize = TS - 16;
/// YPbPr plane stride (`ts - 8`).
const YUV_STRIDE: usize = TS - 8;
/// Derivative plane stride (`ts - 10`).
const DRV_STRIDE: usize = TS - 10;

/// `orth` — the four cardinal directions as (row, col) multiplier pairs, plus
/// the wrap-around fifth entry (`xtrans_demosaic.cc:205`).
const ORTH: [i32; 12] = [1, 0, 0, 1, -1, 0, 0, -1, 1, 0, 0, 1];
/// `patt` — the hexagon offset patterns for non-green (`[0]`) and green (`[1]`)
/// centres (`xtrans_demosaic.cc:206-208`).
const PATT: [[i32; 16]; 2] = [
  [0, 1, 0, -1, 2, 0, -1, 0, 1, 1, 1, -1, 0, 0, 0, 0],
  [0, 1, 0, -2, 1, 0, -2, 0, 1, 1, -2, -2, 1, -1, -1, 1],
];
/// Direction strides inside the `ts`-stride tile planes, folded to the
/// `(ts-8)`-stride YPbPr planes by `f == 1 ? 1 : f - 8`
/// (`xtrans_demosaic.cc:727-728`): horizontal, vertical, both diagonals.
const DIR: [i32; 4] = [1, (TS - 8) as i32, (TS + 1 - 8) as i32, (TS - 1 - 8) as i32];

/// The `allhex` hexagon tables plus the solitary-green anchor.
struct HexTables {
  /// `(col offset, row offset)` per `(row % 3, col % 3, slot)` — one table for
  /// both upstream copies (see module docs).
  hex: [[[(i32, i32); 8]; 3]; 3],
  /// Sensor-matrix position of the solitary green pixel.
  sgrow: usize,
  sgcol: usize,
}

/// Build `allhex` and `sgrow`/`sgcol` (`xtrans_demosaic.cc:231-264`).
fn build_tables(cfa: &CfaDesc) -> HexTables {
  let mut hex = [[[(0i32, 0i32); 8]; 3]; 3];
  let (mut sgrow, mut sgcol) = (0usize, 0usize);

  for row in 0..3usize {
    for col in 0..3usize {
      let gint = usize::from(is_green(cfa, row, col));
      let mut ng = 0usize;
      for d in (0..10).step_by(2) {
        // The walk visits (row+orth[d], col+orth[d+2]) — order S, W, N, E —
        // on the 3-periodic sensor matrix (the `+6` keeps C's `%` positive).
        let nb_row = ((row as i32 + ORTH[d] + 6) % 3) as usize;
        let nb_col = ((col as i32 + ORTH[d + 2] + 6) % 3) as usize;
        if cfa.xtrans[nb_row][nb_col] == 1 {
          ng = 0;
        } else {
          ng += 1;
        }
        if ng == 4 {
          // Four non-green cardinal neighbours: the solitary green pixel.
          sgrow = row;
          sgcol = col;
        }
        if ng == gint + 1 {
          for c in 0..8usize {
            let p1 = PATT[gint][c * 2];
            let p2 = PATT[gint][c * 2 + 1];
            let v = ORTH[d] * p1 + ORTH[d + 1] * p2;
            let h = ORTH[d + 2] * p1 + ORTH[d + 3] * p2;
            // `c ^ (gint * 2 & d)`: the green-centre mask alternates the slot
            // base between the two trigger directions.
            hex[row][col][c ^ (gint * 2 & d)] = (h, v);
          }
        }
      }
    }
  }
  HexTables { hex, sgrow, sgcol }
}

/// Demosaic an X-Trans mosaic with Markesteijn's 1-pass algorithm.
///
/// # Errors
/// [`Error::UnsupportedCfa`] for a Bayer CFA (the dispatcher already refuses,
/// this is the kernel's own last line of defence).
pub fn xtrans_one_pass_demosaic(cfa: &CfaDesc, raw: &Array2D<f32>) -> Result<Rgb, Error> {
  if cfa.is_bayer {
    return Err(Error::UnsupportedCfa("xtrans one-pass on a Bayer CFA"));
  }
  let (w, h) = (raw.width(), raw.height());
  let tables = build_tables(cfa);

  // `RightShift[row]`: rows of the 3x3 sensor matrix with exactly two greens
  // take the 3-step column walk (`xtrans_demosaic.cc:280-291`).
  let mut right_shift = [0usize; 3];
  for (row, slot) in right_shift.iter_mut().enumerate() {
    let greens = (0..3).filter(|&col| is_green(cfa, row, col)).count();
    *slot = usize::from(greens == 2);
  }

  let mut out = Rgb::new(w, h);

  let tops: Vec<usize> = (3..h.saturating_sub(19)).step_by(STEP).collect();
  let lefts: Vec<usize> = (3..w.saturating_sub(19)).step_by(STEP).collect();

  let ts = TS;
  let tsh = TSH;

  // Bands over tile rows; tiles sequential within a band.
  //
  // Each band also records the frame rows its final-averaging step writes —
  // tile-relative `row` runs `[MIN(top, 8), mrow2 - 8)` and maps to frame row
  // `row + top` (`xtrans_demosaic.cc:912-947`; `mrow2` depends on `top` alone,
  // so it is the same for every tile of the band). The last band is always an
  // edge band, so its range ends at `h - 6` — the border pass overwrites
  // `[h - 11, h)` afterwards anyway.
  let bands: Vec<(Vec<(usize, usize)>, usize, usize)> = tops
    .iter()
    .map(|&top| {
      let mrow = (top + ts).min(h - 3);
      let mrow2 = if h - top < ts + 4 { h - top + 2 } else { mrow - top };
      let tiles: Vec<(usize, usize)> = lefts.iter().map(|&left| (top, left)).collect();
      let lo = top + top.min(8);
      let hi = top + mrow2 - 8;
      (tiles, lo, hi)
    })
    .collect();

  // Split each plane into one mutable slice per band. The bands' written row
  // ranges are contiguous — band k+1 starts exactly where band k ends — so a
  // running split over the whole plane partitions it; the leading border rows
  // ride in the first band's slice and the trailing ones in the last band's,
  // both left untouched here (the border pass fills them at the end). Each
  // band records `row0`, the frame row its slice *starts* at — that is the
  // running partition boundary, not `lo` (the first band's slice begins at
  // row 0).
  let mut sizes: Vec<usize> = Vec::with_capacity(bands.len());
  let mut prev = 0usize;
  let bands: Vec<(Vec<(usize, usize)>, usize, usize)> = bands
    .into_iter()
    .map(|(tiles, _lo, hi)| {
      let hi = hi.max(prev).min(h);
      sizes.push((hi - prev) * w);
      let row0 = prev;
      prev = hi;
      (tiles, row0, hi)
    })
    .collect();
  if let Some(last) = sizes.last_mut() {
    // The trailing border rows (`[hi_last, h)`) ride in the last band's
    // slice — appended, not substituted: the pushed size above is the band's
    // own written rows.
    *last += (h - prev) * w;
  }
  let red_parts = split_slices(out.red.as_mut_slice(), &sizes);
  let green_parts = split_slices(out.green.as_mut_slice(), &sizes);
  let blue_parts = split_slices(out.blue.as_mut_slice(), &sizes);

  red_parts
    .into_par_iter()
    .zip(green_parts)
    .zip(blue_parts)
    .zip(bands)
    .for_each(|(((red, green), blue), (tiles, row0, _))| {
    // Per-band scratch (upstream: one malloc per OpenMP thread).
    let mut gminmax = vec![(f32::MAX, 0.0f32); ts * tsh];
    let mut rgb = vec![0.0f32; 4 * ts * ts * 3];
    let mut yuv = vec![0.0f32; 3 * YUV_STRIDE * YUV_STRIDE];
    let mut drv = vec![0.0f32; 4 * DRV_STRIDE * DRV_STRIDE];
    // Upstream reuses the lab/drv memory for these and reads rows that were
    // never written this tile; zeroed here (module docs).
    let mut homo = vec![0u8; 4 * ts * ts];
    let mut homosum = vec![0u8; 4 * ts * ts];
    let mut homosummax = vec![0u8; ts * ts];

    for &(top, left) in &tiles {
      let mrow = (top + ts).min(h - 3);
      let mcol = (left + ts).min(w - 3);

      // --- Green min/max bounds per non-green pixel (upstream 318-406).
      for row in top..mrow {
        let leftstart = first_non_green(cfa, row, left, mcol);
        let coloffset = if right_shift[row % 3] == 1 {
          3
        } else {
          1 + usize::from(cfa.xtrans_color(row, leftstart + 1) & 1)
        };
        if coloffset == 3 {
          let hex = &tables.hex[row % 3][leftstart % 3];
          let mut col = leftstart;
          while col < mcol {
            gminmax[((row - top) * tsh) + ((col - left) >> 1)] = hex_minmax(raw, row, col, hex);
            col += 3;
          }
        } else {
          let mut col = leftstart;
          if coloffset == 2 {
            gminmax[((row - top) * tsh) + ((col - left) >> 1)] =
              hex_minmax(raw, row, col, &tables.hex[row % 3][col % 3]);
            col += 2;
          }
          // `col % 3` is invariant under `col += 3`, so one hex serves the loop.
          let hex = &tables.hex[row % 3][col % 3];
          while col < mcol.saturating_sub(1) {
            let mm = hex_minmax(raw, row, col, hex);
            gminmax[((row - top) * tsh) + ((col - left) >> 1)] = mm;
            gminmax[((row - top) * tsh) + ((col + 1 - left) >> 1)] = mm;
            col += 3;
          }
          if col < mcol {
            gminmax[((row - top) * tsh) + ((col - left) >> 1)] = hex_minmax(raw, row, col, hex);
          }
        }
      }

      // --- Seed all four direction planes with the raw mosaic
      // (upstream 408-417: memset + fill plane 0 + three memcpy).
      for row in top..mrow {
        for col in left..mcol {
          let px = ((row - top) * ts + (col - left)) * 3;
          rgb[px + cfa.xtrans_color(row, col) as usize] = raw.at(row, col);
        }
      }
      for d in 1..4 {
        rgb.copy_within(0..ts * ts * 3, d * ts * ts * 3);
      }

      // --- Interpolate green along the four hexagonal directions
      // (upstream 420-473).
      for row in top..mrow {
        let leftstart = first_non_green(cfa, row, left, mcol);
        let coloffset0 = if right_shift[row % 3] == 1 {
          3
        } else {
          1 + usize::from(cfa.xtrans_color(row, leftstart + 1) & 1)
        };
        if coloffset0 == 3 {
          let hex = &tables.hex[row % 3][leftstart % 3];
          let mut col = leftstart;
          while col < mcol {
            let colors = green_hex_colors(raw, row, col, hex);
            let (glo, ghi) = gminmax[((row - top) * tsh) + ((col - left) >> 1)];
            for (c, &value) in colors.iter().enumerate() {
              rgb[(c * ts * ts + (row - top) * ts + (col - left)) * 3 + 1] = lim(value, glo, ghi);
            }
            col += 3;
          }
        } else {
          let hexmod = [
            &tables.hex[row % 3][leftstart % 3],
            &tables.hex[row % 3][(leftstart + coloffset0) % 3],
          ];
          let mut col = leftstart;
          let mut coloffset = coloffset0;
          let mut hexindex = 0usize;
          while col < mcol {
            let colors = green_hex_colors(raw, row, col, hexmod[hexindex]);
            let (glo, ghi) = gminmax[((row - top) * tsh) + ((col - left) >> 1)];
            for (c, &value) in colors.iter().enumerate() {
              // The alternating hexagons swap the plane order (`c ^ 1`).
              rgb[((c ^ 1) * ts * ts + (row - top) * ts + (col - left)) * 3 + 1] = lim(value, glo, ghi);
            }
            col += coloffset;
            coloffset ^= 3;
            hexindex ^= 1;
          }
        }
      }

      // --- Interpolate R/B at solitary green pixels (upstream 524-558).
      {
        let sgstartcol = (left as i32 - tables.sgcol as i32 + 4) / 3 * 3 + tables.sgcol as i32;
        let mut row = (top as i32 - tables.sgrow as i32 + 4) / 3 * 3 + tables.sgrow as i32;
        while row < mrow as i32 - 2 {
          let mut col = sgstartcol;
          let mut hcol = cfa.xtrans_color(row as usize, (col + 1) as usize) as usize;
          while col < mcol as i32 - 2 {
            // `rix` walks the four direction planes of one pixel.
            let mut rix = ((row as usize - top) * ts + (col as usize - left)) * 3;
            let mut diff = [0.0f32; 6];
            let mut color = [[0.0f32; 6]; 3];
            let mut i = 1usize;
            let mut d = 0usize;
            while d < 6 {
              for _c in 0..2usize {
                let off = i << _c;
                let g = 2.0 * rgb[rix + 1] - rgb[rix + off * 3 + 1] - rgb[rix - off * 3 + 1];
                color[hcol][d] = g + rgb[rix + off * 3 + hcol] + rgb[rix - off * 3 + hcol];
                if d > 1 {
                  let t = rgb[rix + off * 3 + 1]
                    - rgb[rix - off * 3 + 1]
                    - rgb[rix + off * 3 + hcol]
                    + rgb[rix - off * 3 + hcol];
                  diff[d] += t * t + g * g;
                }
                hcol ^= 2;
              }
              if d > 2 && (d & 1) == 1 && diff[d - 1] < diff[d] {
                color[0][d] = color[0][d - 1];
                color[2][d] = color[2][d - 1];
              }
              if (d & 1) == 1 || d < 2 {
                // `CLIP` is the identity here (upstream redefines it).
                rgb[rix] = 0.5 * color[0][d];
                rgb[rix + 2] = 0.5 * color[2][d];
                rix += ts * ts * 3;
              }
              d += 1;
              i ^= ts ^ 1;
              hcol ^= 2;
            }
            col += 3;
            hcol ^= 2;
          }
          row += 3;
        }
      }

      // --- Interpolate R for blue pixels and vice versa (upstream 561-601).
      for row in top + 3..mrow - 3 {
        let leftstart = first_non_green(cfa, row, left + 3, mcol - 1);
        let coloffset0 = if right_shift[row % 3] == 1 { 3 } else { 1 };
        // Axis choice per pixel: `c_axis` is the near axis, `h_axis` = 3x the
        // other one — the far pair is only used when the near-axis gradients
        // say the structure runs across it.
        let c_axis = if (row - tables.sgrow) % 3 != 0 { ts } else { 1 };
        let h_axis = 3 * (c_axis ^ ts ^ 1);

        if coloffset0 == 3 {
          let mut f = 2 - cfa.xtrans_color(row, leftstart) as usize;
          let mut col = leftstart;
          while col < mcol - 3 {
            let mut rix = ((row - top) * ts + (col - left)) * 3;
            for d in 0..4usize {
              let i = pick_axis(&rgb, c_axis, h_axis, rix, d);
              rgb[rix + f] = rgb[rix + 1]
                + 0.5 * (rgb[rix + i * 3 + f] + rgb[rix - i * 3 + f] - rgb[rix + i * 3 + 1] - rgb[rix - i * 3 + 1]);
              rix += ts * ts * 3;
            }
            col += 3;
            f ^= 2;
          }
        } else {
          let mut coloffset = usize::from(cfa.xtrans_color(row, leftstart + 1) == 1) + 1; // 2 if next is green
          let mut f = 2 - cfa.xtrans_color(row, leftstart) as usize;
          let mut col = leftstart;
          while col < mcol - 3 {
            let mut rix = ((row - top) * ts + (col - left)) * 3;
            for d in 0..4usize {
              let i = pick_axis(&rgb, c_axis, h_axis, rix, d);
              rgb[rix + f] = rgb[rix + 1]
                + 0.5 * (rgb[rix + i * 3 + f] + rgb[rix - i * 3 + f] - rgb[rix + i * 3 + 1] - rgb[rix - i * 3 + 1]);
              rix += ts * ts * 3;
            }
            col += coloffset;
            coloffset ^= 3;
            f ^= coloffset & 2;
          }
        }
      }

      // --- Fill in R/B for 2x2 blocks of green (upstream 603-648).
      {
        let mut topstart = top + 2;
        while topstart < mrow - 2 && (topstart - tables.sgrow) % 3 == 0 {
          topstart += 1;
        }
        let mut leftstart = left + 2;
        while leftstart < mcol - 2 && (leftstart - tables.sgcol) % 3 == 0 {
          leftstart += 1;
        }
        let coloffsetstart = 2 - usize::from(cfa.xtrans_color(topstart, leftstart + 1) & 1);

        for row in topstart..mrow - 2 {
          if (row - tables.sgrow) % 3 == 0 {
            continue;
          }
          let hexmod = [
            &tables.hex[row % 3][leftstart % 3],
            &tables.hex[row % 3][(leftstart + coloffsetstart) % 3],
          ];
          let mut col = leftstart;
          let mut coloffset = coloffsetstart;
          let mut hexindex = 0usize;
          while col < mcol - 2 {
            let hex = hexmod[hexindex];
            let mut rix = ((row - top) * ts + (col - left)) * 3;
            let mut d = 0usize;
            while d < 4 {
              // `hex[d] + hex[d+1]` in flat tile offsets is zero exactly when
              // both the column pair and the row pair cancel (module docs).
              // Tile-plane pixel offsets of the two hexagon samples — signed,
              // the hexagon reaches two pixels in every direction.
              let o1 = (hex[d].0 + hex[d].1 * ts as i32) as isize * 3;
              let o2 = (hex[d + 1].0 + hex[d + 1].1 * ts as i32) as isize * 3;
              if hex[d].0 + hex[d + 1].0 != 0 || hex[d].1 + hex[d + 1].1 != 0 {
                let g = 3.0 * rgb[rix + 1] - 2.0 * rgb[(rix as isize + o1) as usize + 1]
                  - rgb[(rix as isize + o2) as usize + 1];
                for c in [0usize, 2] {
                  rgb[rix + c] = (g
                    + 2.0 * rgb[(rix as isize + o1) as usize + c]
                    + rgb[(rix as isize + o2) as usize + c])
                    * 0.333_333_33;
                }
              } else {
                let g = 2.0 * rgb[rix + 1] - rgb[(rix as isize + o1) as usize + 1]
                  - rgb[(rix as isize + o2) as usize + 1];
                for c in [0usize, 2] {
                  rgb[rix + c] =
                    (g + rgb[(rix as isize + o1) as usize + c] + rgb[(rix as isize + o2) as usize + c]) * 0.5;
                }
              }
              rix += ts * ts * 3;
              d += 2;
            }
            col += coloffset;
            coloffset ^= 3;
            hexindex ^= 1;
          }
        }
      }

      // --- YPbPr derivative statistic and homogeneity (upstream 652-909),
      // with `mrow`/`mcol` now tile-local.
      let (mrow_l, mcol_l) = (mrow - top, mcol - left);

      for d in 0..4usize {
        let plane = &rgb[d * ts * ts * 3..(d + 1) * ts * ts * 3];
        for row in 4..mrow_l - 4 {
          for col in 4..mcol_l - 4 {
            let px = (row * ts + col) * 3;
            let y = 0.262_7 * plane[px] + 0.678 * plane[px + 1] + 0.059_3 * plane[px + 2];
            let base = (row - 4) * YUV_STRIDE + (col - 4);
            yuv[base] = y;
            yuv[YUV_STRIDE * YUV_STRIDE + base] = (plane[px + 2] - y) * 0.564_33;
            yuv[2 * YUV_STRIDE * YUV_STRIDE + base] = (plane[px] - y) * 0.678_15;
          }
        }
        // `f = dir[d] ; f == 1 ? 1 : f - 8` — already folded into `DIR`.
        let f = DIR[d];
        for row in 5..mrow_l - 5 {
          for col in 5..mcol_l - 5 {
            let idx = (row - 4) * YUV_STRIDE + (col - 4);
            let mut acc = 0.0f32;
            for k in 0..3usize {
              let p = k * YUV_STRIDE * YUV_STRIDE;
              let t = 2.0 * yuv[p + idx] - yuv[p + (idx as i32 + f) as usize] - yuv[p + (idx as i32 - f) as usize];
              acc += t * t;
            }
            drv[d * DRV_STRIDE * DRV_STRIDE + (row - 5) * DRV_STRIDE + (col - 5)] = acc;
          }
        }
      }

      // Homogeneity map: 3x3 count of directions below 8x the minimum.
      for row in 6..mrow_l - 6 {
        for col in 6..mcol_l - 6 {
          let mut tr = f32::MAX;
          for d in 0..4usize {
            tr = tr.min(drv[d * DRV_STRIDE * DRV_STRIDE + (row - 5) * DRV_STRIDE + (col - 5)]);
          }
          tr *= 8.0;
          for d in 0..4usize {
            let mut t = 0u8;
            for v in -1i32..=1 {
              for hh in -1i32..=1 {
                let idx = ((row as i32 + v - 5) * DRV_STRIDE as i32 + (col as i32 + hh - 5)) as usize;
                if drv[d * DRV_STRIDE * DRV_STRIDE + idx] <= tr {
                  t += 1;
                }
              }
            }
            homo[d * ts * ts + row * ts + col] = t;
          }
        }
      }

      // Edge tiles re-extend their write range to the frame (upstream 811-817).
      let mrow2 = if h - top < ts + 4 { h - top + 2 } else { mrow_l };
      let mcol2 = if w - left < ts + 4 { w - left + 2 } else { mcol_l };

      // 5x5 homogeneity sums with the sliding column window
      // (upstream 843-864, scalar branch).
      let startcol = left.min(8);
      for d in 0..4usize {
        for row in top.min(8)..mrow2.saturating_sub(8) {
          let mut col = startcol;
          if col < mcol2.saturating_sub(8) {
            let mut v5sum = [0i32; 5];
            for v in -2i32..=2 {
              for hh in -2i32..=2 {
                v5sum[(2 + hh) as usize] +=
                  homo[d * ts * ts + ((row as i32 + v) * ts as i32 + col as i32 + hh) as usize] as i32;
              }
            }
            let mut blocksum: i32 = v5sum.iter().sum();
            homosum[d * ts * ts + row * ts + col] = blocksum as u8;
            col += 1;
            let mut voffset = 0usize;
            while col < mcol2.saturating_sub(8) {
              let mut colsum = 0i32;
              for v in -2i32..=2 {
                colsum += homo[d * ts * ts + ((row as i32 + v) * ts as i32 + col as i32 + 2) as usize] as i32;
              }
              voffset = if voffset == 5 { 0 } else { voffset };
              blocksum -= v5sum[voffset];
              blocksum += colsum;
              v5sum[voffset] = colsum;
              homosum[d * ts * ts + row * ts + col] = blocksum as u8;
              col += 1;
              voffset += 1;
            }
          }
        }
      }

      // Per-pixel maximum of the homogeneity sums, discounted by an eighth
      // (upstream 899-908, scalar branch).
      for row in top.min(8)..mrow2.saturating_sub(8) {
        for col in startcol..mcol2.saturating_sub(8) {
          let mut maxval = 0u8;
          for d in 0..4usize {
            maxval = maxval.max(homosum[d * ts * ts + row * ts + col]);
          }
          maxval -= maxval >> 3;
          homosummax[row * ts + col] = maxval;
        }
      }

      // Average the most homogeneous directions into the frame
      // (upstream 912-947). `ndir == 4`, so the diagonal-pairing loop that
      // zeroes weaker directions for the 3-pass kernel does not run.
      for row in top.min(8)..mrow2.saturating_sub(8) {
        let rowbase = (row + top - row0) * w;
        for col in left.min(8)..mcol2.saturating_sub(8) {
          let maxval = homosummax[row * ts + col];
          let mut avg = [0.0f32; 4];
          for d in 0..4usize {
            if homosum[d * ts * ts + row * ts + col] >= maxval {
              let px = (row * ts + col) * 3;
              for c in 0..3usize {
                avg[c] += rgb[d * ts * ts * 3 + px + c];
              }
              avg[3] += 1.0;
            }
          }
          red[rowbase + col + left] = max0(avg[0] / avg[3]);
          green[rowbase + col + left] = max0(avg[1] / avg[3]);
          blue[rowbase + col + left] = max0(avg[2] / avg[3]);
        }
      }
    }
  });

  // One-pass frames take an 11-pixel border (upstream 966).
  xtrans_border_interpolate(cfa, raw, &mut out, 11);
  Ok(out)
}

/// Split `s` into consecutive pieces of the given lengths (the same helper
/// `bayer/amaze.rs` uses for its row bands).
fn split_slices<'a>(s: &'a mut [f32], sizes: &[usize]) -> Vec<&'a mut [f32]> {
  let mut out = Vec::with_capacity(sizes.len());
  let mut rest = s;
  for &n in sizes {
    let (head, tail) = rest.split_at_mut(n);
    out.push(head);
    rest = tail;
  }
  out
}

/// The near/far axis choice of the R-for-blue pass: the far pair wins only
/// when `d` is past the first pair, the parity test fails, or the near-axis
/// green gradients are less than twice the far-axis ones
/// (`xtrans_demosaic.cc:580-581`).
#[inline]
fn pick_axis(rgb: &[f32], c_axis: usize, h_axis: usize, rix: usize, d: usize) -> usize {
  let grad_near = (rgb[rix + 1] - rgb[rix + c_axis * 3 + 1]).abs()
    + (rgb[rix + 1] - rgb[rix - c_axis * 3 + 1]).abs();
  let grad_far = (rgb[rix + 1] - rgb[rix + h_axis * 3 + 1]).abs()
    + (rgb[rix + 1] - rgb[rix - h_axis * 3 + 1]).abs();
  if d > 1 || ((d ^ c_axis) & 1) == 1 || grad_near < 2.0 * grad_far {
    c_axis
  } else {
    h_axis
  }
}

/// First non-green column in `[from, limit)` on `row` — upstream's `leftstart`
/// search. Callers rely on X-Trans guaranteeing one exists before `limit`.
#[inline]
fn first_non_green(cfa: &CfaDesc, row: usize, from: usize, limit: usize) -> usize {
  let mut col = from;
  while col < limit && is_green(cfa, row, col) {
    col += 1;
  }
  col
}

/// Min/max of the six green samples around `(row, col)`'s hexagon
/// (`xtrans_demosaic.cc:337-342` et al).
#[inline]
fn hex_minmax(raw: &Array2D<f32>, row: usize, col: usize, hex: &[(i32, i32); 8]) -> (f32, f32) {
  let mut minv = f32::MAX;
  let mut maxv = 0.0f32;
  for &(dh, dv) in &hex[..6] {
    let val = raw.at(((row as i32) + dv) as usize, ((col as i32) + dh) as usize);
    minv = minv.min(val);
    maxv = maxv.max(val);
  }
  (minv, maxv)
}

/// The four directional green estimates at `(row, col)`
/// (`xtrans_demosaic.cc:437-444` — identical in both walk branches).
#[inline]
fn green_hex_colors(raw: &Array2D<f32>, row: usize, col: usize, hex: &[(i32, i32); 8]) -> [f32; 4] {
  let at = |k: i32, sign: i32| -> f32 {
    let (dh, dv) = hex[k as usize];
    raw.at(((row as i32) + sign * dv) as usize, ((col as i32) + sign * dh) as usize)
  };
  let centre = raw.at(row, col);
  let color0 = 0.679_687_5 * (at(1, 1) + at(0, 1)) - 0.179_687_5 * (at(1, 2) + at(0, 2));
  let color1 = 0.871_093_75 * at(3, 1) + at(2, 1) * 0.128_906_25 + 0.359_375 * (centre - at(2, -1));
  let color2 = 0.640_625 * at(4, 1) + 0.359_375 * at(4, -2) + 0.128_906_25 * (2.0 * centre - at(4, 3) - at(4, -3));
  let color3 = 0.640_625 * at(5, 1) + 0.359_375 * at(5, -2) + 0.128_906_25 * (2.0 * centre - at(5, 3) - at(5, -3));
  [color0, color1, color2, color3]
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::xtrans::border::test_support::std_cfa;

  #[test]
  fn a_flat_field_is_a_fixed_point() {
    let c = std_cfa();
    // 410x403 puts four tile rows/columns in play, so the tile origins hit
    // every `%3` phase class — the allhex-driven access pattern depends only
    // on (matrix, geometry), never on pixel values, and a flat field
    // therefore exercises every read the kernel can make.
    for v in [0.2_f32, 0.5, 0.7] {
      let out = xtrans_one_pass_demosaic(&c, &Array2D::filled(410, 403, v)).expect("one-pass");
      for plane in [&out.red, &out.green, &out.blue] {
        for &x in plane.as_slice() {
          assert!((x - v).abs() < 1e-6, "v {v}: got {x}");
        }
      }
    }
  }

  #[test]
  fn the_solitary_green_anchor_matches_the_standard_matrix() {
    // The standard matrix has exactly one solitary green, at (2, 2) of the
    // 3x3 period (see the hand-check in FOTLAB-NATIVE-000004 rev 14).
    let t = build_tables(&std_cfa());
    assert_eq!((t.sgrow, t.sgcol), (2, 2));
  }

  #[test]
  fn green_samples_survive_at_green_sites() {
    let c = std_cfa();
    let mut raw = Array2D::filled(410, 403, 0.25);
    for row in 0..403 {
      for col in 0..410 {
        if c.xtrans_color(row, col) == 1 {
          raw.set(row, col, 0.75);
        }
      }
    }
    let out = xtrans_one_pass_demosaic(&c, &raw).expect("one-pass");
    for row in 0..403 {
      for col in 0..410 {
        if c.xtrans_color(row, col) == 1 {
          assert_eq!(out.green.at(row, col), 0.75, "green site ({row},{col})");
        }
      }
    }
  }

  #[test]
  fn every_pixel_is_written_and_finite_across_tile_boundaries() {
    // 149 = 3 + 146: exercises full tiles, an edge tile, and the mrow2/mcol2
    // re-expansion; a ramp makes every estimate value-carrying.
    let c = std_cfa();
    let mut raw = Array2D::filled(149, 121, 0.0);
    for row in 0..121 {
      for col in 0..149 {
        raw.set(row, col, ((row * 31 + col * 17) % 23) as f32 / 22.0);
      }
    }
    let out = xtrans_one_pass_demosaic(&c, &raw).expect("one-pass");
    for plane in [&out.red, &out.green, &out.blue] {
      for &x in plane.as_slice() {
        assert!(x.is_finite() && x >= 0.0, "bad pixel {x}");
      }
    }
  }

  #[test]
  fn a_frame_smaller_than_the_tile_grip_still_completes() {
    // Below the tile-loop threshold the kernel is just the border pass.
    let c = std_cfa();
    let out = xtrans_one_pass_demosaic(&c, &Array2D::filled(21, 20, 0.5)).expect("one-pass");
    for plane in [&out.red, &out.green, &out.blue] {
      for &x in plane.as_slice() {
        assert!((x - 0.5).abs() < 1e-6, "got {x}");
      }
    }
  }
}
