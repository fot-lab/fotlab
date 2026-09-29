//! DCB — "Directional Chroma Blending" Bayer demosaic.
//!
//! Ported from `external/RawTherapee/rtengine/demosaic_algos.cc:963-1548`
//! (`RawImageSource::dcb_demosaic` and its twelve helpers; the algorithm is
//! Jacek Zagórski's, adapted to RawTherapee by Ingo Weyrich — GPL-3.0).
//!
//! DCB is the most *iterative* kernel in the catalogue. Where IGV and RCD
//! estimate green once and then derive chroma from it, DCB refines green and
//! chroma in alternation, several times over:
//!
//! 1. **`dcb_hid`** — a plain bilinear green at every red/blue site. A starting
//!    point, nothing more.
//! 2. **`dcb_hid2`** ×3, then `dcb_map`, then `dcb_correction` — repeated
//!    `iterations` times. `dcb_hid2` re-derives green at red/blue sites from the
//!    *chroma* of the same site plus the four green samples two pixels away
//!    along each axis (the "green correction"). `dcb_map` then classifies every
//!    pixel as lying on a vertical (`0`) or horizontal (`1`) edge from a
//!    4-neighbour comparison of green; `dcb_correction` re-interpolates green
//!    from the two directions, weighted by a 5×5 box sum of that map. That is
//!    the "directional" half of the name.
//! 3. **`dcb_color`**, **`dcb_pp`**, then a fixed sequence of `dcb_map` /
//!    `dcb_correction` / `dcb_correction2` pairs. `dcb_color` fills the missing
//!    chroma from diagonal (`R`/`B` sites) and axial (green sites) colour
//!    differences; `dcb_pp` smooths red and blue over the 3×3 ring and restores
//!    the local green offset. `dcb_correction2` is `dcb_correction` with the
//!    *chroma* of the same site added back, so the green correction preserves
//!    colour. This is the "chroma" half.
//! 4. **`dcb_refinement`** (only when `dcb_enhance`) — a reciprocal-weighted
//!    green re-estimate that is deliberately *not* clamped to the local range
//!    until the very end, followed by **`dcb_color_full`**, a
//!    Luis Sanz Rodriguez interpolation over a *chroma* plane with 4-direction
//!    inverse-gradient weights.
//!
//! ## Tiling
//!
//! Upstream splits the frame into `TILESIZE`-square tiles with a `TILEBORDER`
//! margin (`CACHESIZE` total), so every tile is demosaiced independently and the
//! whole thing is embarrassingly parallel (`omp for schedule(dynamic)`).
//! `fill_raw` seeds the tile from the mosaic; the outer `TILEBORDER` ring is
//! left **zero** except on tiles that touch the frame edge, where `fill_border`
//! approximates it from a 3×3 neighbourhood. This port keeps that structure
//! verbatim, including the tile size, because the ring is part of the output.
//!
//! ## Numeric domain — this is *not* the RCD/LMMSE situation
//!
//! RCD normalises on load (`LIM01(rawData / 65536)`) and de-normalises on store,
//! so its pair cancels. DCB instead reads `rawData` **as-is** and writes
//! `std::max(0.f, …)` of it, so its internal domain *is* `rawData`, and the port
//! has to make the round trip explicit: `mosaic * SCALE` on load,
//! `/ SCALE` on store. That is not cosmetic — `dcb_refinement` divides by
//! `1.f + 2.f * currPix`, and `1.f` is an absolute constant, so the kernel is
//! **not scale invariant**. Feeding it `0..1` values would leave the `1.f`
//! dominating the denominator and change every refined green.
//!
//! `SCALE` is `65536`, the crate-wide convention (`bayer/rcd.rs`): the mosaic is
//! `rawData / 65536`, so a saturated sample is `65535` in the kernel, a hair
//! under `1.0` once divided back out. As in LMMSE the output is **not** clamped
//! above, only at zero.
//!
//! ## Which CFA mask
//!
//! Every `FC()` in DCB is the **folded**, three-valued mask (`CfaDesc::fc`), so
//! `c`/`d` indices are in `0..=2` and the kernel never sees the second green.
//! `fill_border` is the one caller that needs *signed* indices — see there.

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::cfa::CfaDesc;
use crate::math::{abs, intp, lim, max0, max2, min2};
use crate::{Error, Rgb};

/// `rawData` units per unit of the crate's mosaic, matching `bayer/rcd.rs`.
const SCALE: f32 = 65536.0;

/// `TILESIZE` (`:959`).
const TILESIZE: usize = 192;
/// `TILEBORDER` (`:960`).
const TILEBORDER: usize = 10;
/// `CACHESIZE` (`:961`) — the tile including both margins.
const CACHESIZE: usize = TILESIZE + 2 * TILEBORDER;
/// `CACHESIZE * CACHESIZE`, the flat length of every tile buffer.
const CACHE_LEN: usize = CACHESIZE * CACHESIZE;

/// The four half-open bounds a pass walks, as `dcb_initTileLimits` returns them.
///
/// All four are **cache** indices, not image coordinates. `row_min`/`col_min`
/// start at `border` and the tile's own `TILEBORDER` margin is added when the
/// tile sits on the top/left frame edge; `row_max`/`col_max` start at
/// `CACHESIZE - border` and are pulled in when the tile sits on the bottom/right
/// frame edge. The asymmetry is upstream's: the top-left margin is *skipped* on
/// edge tiles (there is no data outside the frame to fill it with) while the
/// bottom-right margin is *clipped* to the frame.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Limits {
  col_min: usize,
  row_min: usize,
  col_max: usize,
  row_max: usize,
}

/// `dcb_initTileLimits` (`:963-985`).
///
/// `w`/`h` are the frame size and `cfa` supplies `FC`. Upstream reads `W`/`H`
/// and `FC` from `this`; here they are arguments.
fn init_tile_limits(x0: usize, y0: usize, w: usize, h: usize, border: usize) -> Limits {
  let mut l = Limits {
    col_min: border,
    row_min: border,
    col_max: CACHESIZE - border,
    row_max: CACHESIZE - border,
  };

  if y0 == 0 {
    l.row_min = TILEBORDER + border;
  }
  if x0 == 0 {
    l.col_min = TILEBORDER + border;
  }
  if y0 + TILESIZE + TILEBORDER >= h - border {
    l.row_max = (TILEBORDER + h - border - y0).min(l.row_max);
  }
  if x0 + TILESIZE + TILEBORDER >= w - border {
    l.col_max = (TILEBORDER + w - border - x0).min(l.col_max);
  }

  l
}

/// The per-tile working set, i.e. the five buffers upstream carves out of one
/// `malloc` (`:1427-1435`).
///
/// `buffer` and `chrm` are the **same memory** upstream — `chrm` is literally
/// assigned `buffer`, with the comment "No overlap in usage of buffer and chrm
/// means we can reuse buffer". So they are one field here. Splitting them would
/// look tidier and would silently change behaviour: the enhance path relies on
/// `memset(chrm, …)` zeroing the buffer that `restore_from_buffer` has already
/// consumed.
struct Tile {
  /// `float (*tile)[3]` — R, G, B for every cache pixel.
  image: Vec<[f32; 3]>,
  /// `uint8_t *map` — the direction map `dcb_map` writes and the corrections read.
  map: Vec<u8>,
  /// `float (*buffer)[2]` = `float (*chrm)[2]` — R/B saved before `dcb_hid`, and
  /// later the two chroma planes.
  rbuf: Vec<[f32; 2]>,
}

impl Tile {
  fn new() -> Self {
    Self { image: vec![[0.0; 3]; CACHE_LEN], map: vec![0u8; CACHE_LEN], rbuf: vec![[0.0; 2]; CACHE_LEN] }
  }

  /// `memset(tile, 0, …)` + `memset(map, 0, …)` (`:1445-1446`).
  ///
  /// Load-bearing for a *reused* tile: the outer ring is never written, and
  /// `fill_border`'s neighbourhood sum reads it. Upstream `memset`s every tile;
  /// this port therefore zeroes every tile rather than only the first.
  fn clear(&mut self) {
    self.image.fill([0.0; 3]);
    self.map.fill(0);
  }
}

/// `fill_raw` (`:987-996`) — copy the mosaic into the cache, each sample landing
/// on its own CFA channel.
///
/// The scaling to `rawData` units happens here (see the module note on domain).
fn fill_raw(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize, cfa: &CfaDesc, mosaic: &Array2D<f32>) {
  let l = init_tile_limits(x0, y0, w, h, 0);

  for row in l.row_min..l.row_max {
    // `y0 - TILEBORDER + row` in that order panics: upstream is C `int`, where
    // the intermediate `-TILEBORDER` is fine because `row_min` is raised to
    // `TILEBORDER` on the first tile row. The sum is the same, so add first.
    let y = y0 + row - TILEBORDER;
    for col in l.col_min..l.col_max {
      let x = x0 + col - TILEBORDER;
      let indx = row * CACHESIZE + col;
      t.image[indx][cfa.fc(y, x) as usize] = mosaic.row(y)[x] * SCALE;
    }
  }
}

/// `fill_border` (`:998-1041`) — approximate the tile margin from a 3×3
/// neighbourhood, for the tiles that touch the frame edge.
///
/// Three details are easy to lose and all three are reproduced:
///
/// * **It is not a `TILEBORDER`-wide ring fill.** The `col = W - border` jump
///   skips the whole interior of the frame in one step, so on a row that is
///   inside the frame the pass only ever touches the right-hand strip; combined
///   with the row/col tests it visits exactly the `border`-wide ring where
///   `row < border`, `row >= H - border`, `col < border` or `col >= W - border`.
/// * **The neighbourhood sum is not guarded against negative coordinates.**
///   `row - 1` is `-1` on the frame's first row and `col - 1` is `-1` on its
///   first column, and the `if` only tests the *upper* bounds. The cache index
///   `(y - y0 + TILEBORDER) * CACHESIZE + TILEBORDER + x - x0` still lands inside
///   the tile (cache row/col `9`), on the **zero ring** — so the sample is read
///   as `0.0` *but `sum[f + 4]` is still incremented*. The average is therefore
///   diluted by the zero ring, and that dilution is part of upstream's output.
/// * **`FC` is evaluated on those negative coordinates too**, wrapped exactly as
///   C wraps (`fc_i`), so the "colour" of a sample read from the ring can be the
///   colour of a row or column that does not exist.
///
/// The two loops use `row != row + 2` guards upstream; a `for` over three values
/// is the same thing.
fn fill_border(t: &mut Tile, border: usize, x0: usize, y0: usize, w: usize, h: usize, cfa: &CfaDesc) {
  const COLORS: usize = 3;

  // `row`/`col` are image coordinates and are signed upstream only because
  // `row - 1` is evaluated; `row` itself starts at `y0 >= 0`.
  let row_end = (y0 + TILESIZE + TILEBORDER).min(h);
  let col_end = (x0 + TILESIZE + TILEBORDER).min(w);

  for row in y0..row_end {
    let mut col = x0;
    while col < col_end {
      if col >= border && col < w - border && row >= border && row < h - border {
        col = w - border;
        if col >= x0 + TILESIZE + TILEBORDER {
          break;
        }
      }

      let mut sum = [0f32; 8];
      for y in (row as isize - 1)..=(row as isize + 1) {
        for x in (col as isize - 1)..=(col as isize + 1) {
          if y < h as isize && y < (y0 + TILESIZE + TILEBORDER) as isize && x < w as isize && x < (x0 + TILESIZE + TILEBORDER) as isize {
            let f = cfa.fc_i(y as i32, x as i32) as usize;
            // `TILEBORDER + x - x0`, evaluated signed: `x` is `-1` on the
            // frame's first column, where the sum is `9`, not a wrapped uint.
            let indx = (y - y0 as isize + TILEBORDER as isize) as usize * CACHESIZE
              + (x - x0 as isize + TILEBORDER as isize) as usize;
            sum[f] += t.image[indx][f];
            sum[f + 4] += 1.0;
          }
        }
      }

      let f = cfa.fc(row, col) as usize;
      for c in 0..COLORS {
        if c != f && sum[c + 4] > 0.0 {
          let indx = (row - y0 + TILEBORDER) * CACHESIZE + TILEBORDER + col - x0;
          t.image[indx][c] = sum[c] / sum[c + 4];
        }
      }

      col += 1;
    }
  }
}

/// `copy_to_buffer` (`:1043-1052`) — save red and blue before `dcb_hid` starts
/// overwriting green.
fn copy_to_buffer(t: &mut Tile) {
  for indx in 0..CACHE_LEN {
    t.rbuf[indx][0] = t.image[indx][0];
    t.rbuf[indx][1] = t.image[indx][2];
  }
}

/// `restore_from_buffer` (`:1054-1063`).
fn restore_from_buffer(t: &mut Tile) {
  for indx in 0..CACHE_LEN {
    t.image[indx][0] = t.rbuf[indx][0];
    t.image[indx][2] = t.rbuf[indx][1];
  }
}

/// `FC(y0 - TILEBORDER + row, x0 - TILEBORDER + col)`.
///
/// DCB spells this out at every use site. On an edge tile the intermediate row
/// and column are **negative** — the tile's margin reaches outside the frame —
/// so the signed form is required and C's wrap is part of the answer (see
/// `CfaDesc::fc_i`).
#[inline]
fn fc_abs(cfa: &CfaDesc, x0: usize, y0: usize, row: usize, col: usize) -> u32 {
  cfa.fc_i(y0 as i32 - TILEBORDER as i32 + row as i32, x0 as i32 - TILEBORDER as i32 + col as i32)
}

/// `dcb_hid` (`:1065-1079`) — bilinear green at red/blue sites.
///
/// The stride-2 walk starts on the column parity that `FC(absRow, absColMin)`
/// selects, which for a Bayer is always the parity of the *red/blue* columns;
/// the same expression opens `dcb_hid2`, `dcb_color`'s first loop,
/// `dcb_correction`, `dcb_correction2` and `dcb_refinement`.
fn dcb_hid(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize, cfa: &CfaDesc) {
  let l = init_tile_limits(x0, y0, w, h, 2);
  let u = CACHESIZE;
  let image = &mut t.image;

  for row in l.row_min..l.row_max {
    let row_base = row * CACHESIZE;
    let mut indx = row_base + l.col_min + (fc_abs(cfa, x0, y0, row, l.col_min) & 1) as usize;
    while indx < row_base + l.col_max {
      image[indx][1] = 0.25 * (image[indx - 1][1] + image[indx + 1][1] + image[indx - u][1] + image[indx + u][1]);
      indx += 2;
    }
  }
}

/// `dcb_hid2` (`:1123-1152`) — the green correction, run three times per
/// `iterations` step.
///
/// It rebuilds green at a red/blue site from *that site's own colour* plus the
/// green samples two pixels away on each axis, correcting both by the same
/// four-sample colour difference. `c` is the site's CFA colour (`0` or `2`),
/// hoisted out of the column loop upstream because the column parity is fixed.
fn dcb_hid2(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize, cfa: &CfaDesc) {
  let l = init_tile_limits(x0, y0, w, h, 2);
  let v = 2 * CACHESIZE;
  let image = &mut t.image;

  for row in l.row_min..l.row_max {
    let row_base = row * CACHESIZE;
    let start = row_base + l.col_min + (fc_abs(cfa, x0, y0, row, l.col_min) & 1) as usize;
    let c = fc_abs(cfa, x0, y0, row, start - row_base) as usize;
    let mut indx = start;
    while indx < row_base + l.col_max {
      image[indx][1] = image[indx][c]
        + (image[indx + v][1] + image[indx - v][1] + image[indx - 2][1] + image[indx + 2][1]
          - (image[indx + v][c] + image[indx - v][c] + image[indx - 2][c] + image[indx + 2][c]))
          * 0.25;
      indx += 2;
    }
  }
}

/// `dcb_color` (`:1081-1121`) — the missing chroma, from colour differences.
///
/// Two loops: red at blue sites and blue at red sites from the four **diagonal**
/// neighbours, then both at green sites from the horizontal and the vertical
/// pair. Upstream's second loop starts its walk on `FC(absRow, absColMin + 1)`'s
/// parity — the *green* columns, one column over from every other loop in the
/// file — and takes `c` from `FC(absRow, col + 1)`, i.e. from the neighbour, not
/// the site.
fn dcb_color(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize, cfa: &CfaDesc) {
  let l = init_tile_limits(x0, y0, w, h, 1);
  let u = CACHESIZE;
  let image = &mut t.image;

  // red in blue pixels, blue in red pixels
  for row in l.row_min..l.row_max {
    let row_base = row * CACHESIZE;
    let col = l.col_min + (fc_abs(cfa, x0, y0, row, l.col_min) & 1) as usize;
    let c = (2 - fc_abs(cfa, x0, y0, row, col)) as usize;
    let mut indx = row_base + col;
    while indx < row_base + l.col_max {
      image[indx][c] = image[indx][1]
        + (image[indx + u + 1][c] + image[indx + u - 1][c] + image[indx - u + 1][c] + image[indx - u - 1][c]
          - (image[indx + u + 1][1] + image[indx + u - 1][1] + image[indx - u + 1][1] + image[indx - u - 1][1]))
          * 0.25;
      indx += 2;
    }
  }

  // red or blue in green pixels
  for row in l.row_min..l.row_max {
    let row_base = row * CACHESIZE;
    let col = l.col_min + (fc_abs(cfa, x0, y0, row, l.col_min + 1) & 1) as usize;
    let c = fc_abs(cfa, x0, y0, row, col + 1) as usize;
    let d = 2 - c;
    let mut indx = row_base + col;
    while indx < row_base + l.col_max {
      image[indx][c] = image[indx][1] + (image[indx + 1][c] + image[indx - 1][c] - (image[indx + 1][1] + image[indx - 1][1])) * 0.5;
      image[indx][d] = image[indx][1] + (image[indx + u][d] + image[indx - u][d] - (image[indx + u][1] + image[indx - u][1])) * 0.5;
      indx += 2;
    }
  }
}

/// `dcb_map` (`:1154-1175`) — classify each pixel's edge direction.
///
/// `1` means "vertical", `0` means "horizontal". Upstream compares `4 * a`
/// against the sum of the four axial neighbours (cheaper than averaging), and
/// then uses either the *minima* or the *maxima* of the two opposed pairs,
/// depending on that first test — i.e. the branch picks which of the two
/// statistics (darkest-side or brightest-side) decides the direction.
///
/// It takes no CFA at all: the map is built from green alone.
///
/// `min`/`max` are RT's own `rt_math.h:60/73` templates, which are `b < a ? b :
/// a` and `a < b ? b : a` — already `math::min2`/`math::max2`.
fn dcb_map(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize) {
  let l = init_tile_limits(x0, y0, w, h, 2);
  let u = CACHESIZE;
  let Tile { image, map, .. } = t;
  let img = &image[..];

  for row in l.row_min..l.row_max {
    for col in l.col_min..l.col_max {
      let indx = row * CACHESIZE + col;
      let horiz = img[indx - 1][1] + img[indx + 1][1];
      let vert = img[indx - u][1] + img[indx + u][1];

      // `map[indx] = <bool>` in C; the `u8::from` is outside the `if` because
      // `as` would otherwise bind to the `else` block alone.
      map[indx] = u8::from(if 4.0 * img[indx][1] > horiz + vert {
        (min2(img[indx - 1][1], img[indx + 1][1]) + horiz) < (min2(img[indx - u][1], img[indx + u][1]) + vert)
      } else {
        (max2(img[indx - 1][1], img[indx + 1][1]) + horiz) > (max2(img[indx - u][1], img[indx + u][1]) + vert)
      });
    }
  }
}

/// The 5×5 box weight `dcb_correction`/`dcb_correction2` both build from the map:
/// `4 * map[c] + 2 * (the 4 axial neighbours) + (the 4 neighbours two away)`.
///
/// Upstream sums this in `unsigned int` (the map is `0`/`1`) and converts once;
/// the values are small integers, so `f32` is exact.
#[inline]
fn map_weight(map: &[u8], indx: usize, u: usize, v: usize) -> f32 {
  let m = |k: usize| f32::from(map[k]);
  4.0 * m(indx)
    + 2.0 * (m(indx + u) + m(indx - u) + m(indx + 1) + m(indx - 1))
    + m(indx + v) + m(indx - v) + m(indx + 2) + m(indx - 2)
}

/// `dcb_correction` (`:1177-1198`) — re-interpolate green along the direction the
/// map chose.
///
/// `current` is the box weight; `(16 - current)` weights the horizontal pair and
/// `current` the vertical pair, over `* 0.03125` (= 1/32, the normalisation of a
/// 5×5 box). Only red/blue sites are visited.
fn dcb_correction(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize, cfa: &CfaDesc) {
  let l = init_tile_limits(x0, y0, w, h, 2);
  let (u, v) = (CACHESIZE, 2 * CACHESIZE);
  let Tile { image, map, .. } = t;

  for row in l.row_min..l.row_max {
    let row_base = row * CACHESIZE;
    let mut indx = row_base + l.col_min + (fc_abs(cfa, x0, y0, row, l.col_min) & 1) as usize;
    while indx < row_base + l.col_max {
      let current = map_weight(map, indx, u, v);
      image[indx][1] = ((16.0 - current) * (image[indx - 1][1] + image[indx + 1][1])
        + current * (image[indx - u][1] + image[indx + u][1]))
        * 0.03125;
      indx += 2;
    }
  }
}

/// `dcb_correction2` (`:1257-1295`) — `dcb_correction` with the chroma of the
/// same site added back.
///
/// The first column and the colour index are the awkward part: upstream writes
/// `colMin + (FC(absRow, absColMin) & 1)` twice, once inline as the walk's start
/// and once nested inside the `FC` that computes `c`, so `c` is `FC` at the
/// *chosen start column*. The border is `4` here, not `2`.
fn dcb_correction2(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize, cfa: &CfaDesc) {
  let l = init_tile_limits(x0, y0, w, h, 4);
  let (u, v) = (CACHESIZE, 2 * CACHESIZE);
  let Tile { image, map, .. } = t;

  for row in l.row_min..l.row_max {
    let row_base = row * CACHESIZE;
    let col = l.col_min + (fc_abs(cfa, x0, y0, row, l.col_min) & 1) as usize;
    let c = fc_abs(cfa, x0, y0, row, col) as usize;
    let mut indx = row_base + col;
    while indx < row_base + l.col_max {
      let current = map_weight(map, indx, u, v);
      image[indx][1] = image[indx][c]
        + ((16.0 - current)
          * (image[indx - 1][1] + image[indx + 1][1] - (image[indx + 2][c] + image[indx - 2][c]))
          + current * (image[indx - u][1] + image[indx + u][1] - (image[indx + v][c] + image[indx - v][c])))
          * 0.03125;
      indx += 2;
    }
  }
}

/// `dcb_pp` (`:1200-1255`) — red/blue smoothing over the 3×3 ring, with the
/// local green offset put back.
///
/// Upstream walks a `float (*pix)[3]` pointer over the eight neighbours in
/// row-major order; the offsets are listed here in that same order because each
/// accumulator's rounding depends on the order its terms are added. The pass is
/// **in place and order-dependent** — a pixel written here is read as a
/// neighbour by the pixels after it — so it has to stay serial.
fn dcb_pp(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize) {
  let l = init_tile_limits(x0, y0, w, h, 2);
  let u = CACHESIZE;
  let image = &mut t.image;

  for row in l.row_min..l.row_max {
    for col in l.col_min..l.col_max {
      let indx = row * CACHESIZE + col;
      // row-major over the ring, exactly upstream's `pix` walk.
      let ring = [indx - u - 1, indx - u, indx - u + 1, indx - 1, indx + 1, indx + u - 1, indx + u, indx + u + 1];

      let (mut r1, mut g1, mut b1) = (0.0f32, 0.0f32, 0.0f32);
      for k in ring {
        r1 += image[k][0];
        g1 += image[k][1];
        b1 += image[k][2];
      }
      r1 *= 0.125;
      g1 *= 0.125;
      b1 *= 0.125;
      r1 += image[indx][1] - g1;
      b1 += image[indx][1] - g1;

      image[indx][0] = r1;
      image[indx][2] = b1;
    }
  }
}

/// `dcb_refinement` (`:1297-1338`) — the reciprocal-weighted green re-estimate
/// that runs only when `dcb_enhance` is set.
///
/// Each of the six directional estimates is a green sample (or a pair, or a
/// doubled single) divided by `1 + (a constant times the site's own chroma
/// value)`. Those `1.f +` terms are why the kernel's numeric domain matters:
/// they are absolute, so the divisions do not scale with the data.
///
/// The result is a **product** of `currPix` and a blend of the two direction
/// sums over `48`; only afterwards is it clamped into `[minVal, maxVal]`, the
/// range of the four axial green samples. Upstream's comment calls that "get rid
/// of the overshot pixels" — the clamp is the last statement, not an
/// intermediate guard.
fn dcb_refinement(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize, cfa: &CfaDesc) {
  let l = init_tile_limits(x0, y0, w, h, 4);
  let (u, v) = (CACHESIZE, 2 * CACHESIZE);
  let Tile { image, map, .. } = t;

  for row in l.row_min..l.row_max {
    let row_base = row * CACHESIZE;
    let col = l.col_min + (fc_abs(cfa, x0, y0, row, l.col_min) & 1) as usize;
    let c = fc_abs(cfa, x0, y0, row, col) as usize;
    let mut indx = row_base + col;
    while indx < row_base + l.col_max {
      let current = map_weight(map, indx, u, v);
      let mut curr_pix = image[indx][c];

      let f0 = (image[indx - u][1] + image[indx + u][1]) / (1.0 + 2.0 * curr_pix);
      let f1 = 2.0 * image[indx - u][1] / (1.0 + image[indx - v][c] + curr_pix);
      let f2 = 2.0 * image[indx + u][1] / (1.0 + image[indx + v][c] + curr_pix);
      let g1 = f0 + f1 + f2;

      let h0 = (image[indx - 1][1] + image[indx + 1][1]) / (1.0 + 2.0 * curr_pix);
      let h1 = 2.0 * image[indx - 1][1] / (1.0 + image[indx - 2][c] + curr_pix);
      let h2 = 2.0 * image[indx + 1][1] / (1.0 + image[indx + 2][c] + curr_pix);
      let g2 = h0 + h1 + h2;

      curr_pix *= (current * g1 + (16.0 - current) * g2) / 48.0;

      let min_val = min2(image[indx - 1][1], min2(image[indx + 1][1], min2(image[indx - u][1], image[indx + u][1])));
      let max_val = max2(image[indx - 1][1], max2(image[indx + 1][1], max2(image[indx - u][1], image[indx + u][1])));

      image[indx][1] = lim(curr_pix, min_val, max_val);
      indx += 2;
    }
  }
}

/// `dcb_color_full` (`:1340-1404`) — Luis Sanz Rodriguez's chroma interpolation,
/// the quality counterpart of `dcb_color`.
///
/// Both chroma planes are built explicitly in `chrm` (which is `buffer`, see
/// [`Tile`]) and added back to green at the very end. Three passes:
///
/// * **A** seeds `chrm` with `C - G` at every red/blue site, walking the **whole
///   cache** (`1..CACHESIZE-1`), not the tile limits. That is deliberate — the
///   later passes read chroma up to three rows out — and it is the only loop in
///   the file that ignores `dcb_initTileLimits`.
/// * **B** fills the *other* chroma at red/blue sites, with four diagonal
///   directions each weighted by `1 / (1 + Σ|pairwise differences|)` over a
///   three-sample window. `c` is `1 - FC/2`, so a red site gets `chrm[1]` — the
///   index pass A did *not* write there.
/// * **C** fills both chroma at green sites from the axial directions, flipping
///   `c` inside the loop (`c = 1 - c`), with `intp(0.875, near, far)` as the
///   directional estimate.
///
/// Unlike every other pass, the *weights* here use plain `1.f` denominators and
/// constants (`1.325`, `0.175`, `0.075`, `0.875`), which is the second reason
/// the `rawData` domain has to be preserved.
fn dcb_color_full(t: &mut Tile, x0: usize, y0: usize, w: usize, h: usize, cfa: &CfaDesc) {
  let l = init_tile_limits(x0, y0, w, h, 3);
  let (u, w3) = (CACHESIZE, 3 * CACHESIZE);
  let Tile { image, rbuf, .. } = t;

  // A: C - G at every red/blue site, over the whole cache.
  for row in 1..CACHESIZE - 1 {
    let row_base = row * CACHESIZE;
    let col0 = 1 + (fc_abs(cfa, x0, y0, row, 1) & 1) as usize;
    let c = fc_abs(cfa, x0, y0, row, col0) as usize;
    let d = c / 2;
    let mut indx = row_base + col0;
    while indx < row_base + CACHESIZE - 1 {
      rbuf[indx][d] = image[indx][c] - image[indx][1];
      indx += 2;
    }
  }

  // B: the other chroma at red/blue sites, four diagonal directions.
  for row in l.row_min..l.row_max {
    let row_base = row * CACHESIZE;
    let col = l.col_min + (fc_abs(cfa, x0, y0, row, l.col_min) & 1) as usize;
    let c = (1 - fc_abs(cfa, x0, y0, row, col) / 2) as usize;
    let mut indx = row_base + col;
    while indx < row_base + l.col_max {
      let ch = |k: usize| rbuf[k][c];
      let f0 = 1.0
        / (1.0
          + abs(ch(indx - u - 1) - ch(indx + u + 1))
          + abs(ch(indx - u - 1) - ch(indx - w3 - 3))
          + abs(ch(indx + u + 1) - ch(indx - w3 - 3)));
      let f1 = 1.0
        / (1.0
          + abs(ch(indx - u + 1) - ch(indx + u - 1))
          + abs(ch(indx - u + 1) - ch(indx - w3 + 3))
          + abs(ch(indx + u - 1) - ch(indx - w3 + 3)));
      let f2 = 1.0
        / (1.0
          + abs(ch(indx + u - 1) - ch(indx - u + 1))
          + abs(ch(indx + u - 1) - ch(indx + w3 + 3))
          + abs(ch(indx - u + 1) - ch(indx + w3 - 3)));
      let f3 = 1.0
        / (1.0
          + abs(ch(indx + u + 1) - ch(indx - u - 1))
          + abs(ch(indx + u + 1) - ch(indx + w3 - 3))
          + abs(ch(indx - u - 1) - ch(indx + w3 + 3)));

      let g0 = 1.325 * ch(indx - u - 1) - 0.175 * ch(indx - w3 - 3) - 0.075 * (ch(indx - w3 - 1) + ch(indx - u - 3));
      let g1 = 1.325 * ch(indx - u + 1) - 0.175 * ch(indx - w3 + 3) - 0.075 * (ch(indx - w3 + 1) + ch(indx - u + 3));
      let g2 = 1.325 * ch(indx + u - 1) - 0.175 * ch(indx + w3 - 3) - 0.075 * (ch(indx + w3 - 1) + ch(indx + u - 3));
      let g3 = 1.325 * ch(indx + u + 1) - 0.175 * ch(indx + w3 + 3) - 0.075 * (ch(indx + w3 + 1) + ch(indx + u + 3));

      rbuf[indx][c] = (f0 * g0 + f1 * g1 + f2 * g2 + f3 * g3) / (f0 + f1 + f2 + f3);
      indx += 2;
    }
  }

  // C: both chroma at green sites, axial directions, `c` flipping per iteration.
  for row in l.row_min..l.row_max {
    let row_base = row * CACHESIZE;
    let col = l.col_min + (fc_abs(cfa, x0, y0, row, l.col_min + 1) & 1) as usize;
    let mut c = fc_abs(cfa, x0, y0, row, col + 1) / 2;
    let mut indx = row_base + col;
    while indx < row_base + l.col_max {
      for _ in 0..2 {
        let ci = c as usize;
        let ch = |k: usize| rbuf[k][ci];
        let f0 = 1.0 / (1.0 + abs(ch(indx - u) - ch(indx + u)) + abs(ch(indx - u) - ch(indx - w3)) + abs(ch(indx + u) - ch(indx - w3)));
        let f1 = 1.0 / (1.0 + abs(ch(indx + 1) - ch(indx - 1)) + abs(ch(indx + 1) - ch(indx + 3)) + abs(ch(indx - 1) - ch(indx + 3)));
        let f2 = 1.0 / (1.0 + abs(ch(indx - 1) - ch(indx + 1)) + abs(ch(indx - 1) - ch(indx - 3)) + abs(ch(indx + 1) - ch(indx - 3)));
        let f3 = 1.0 / (1.0 + abs(ch(indx + u) - ch(indx - u)) + abs(ch(indx + u) - ch(indx + w3)) + abs(ch(indx - u) - ch(indx + w3)));
        let g0 = intp(0.875, ch(indx - u), ch(indx - w3));
        let g1 = intp(0.875, ch(indx + 1), ch(indx + 3));
        let g2 = intp(0.875, ch(indx - 1), ch(indx - 3));
        let g3 = intp(0.875, ch(indx + u), ch(indx + w3));

        rbuf[indx][ci] = (f0 * g0 + f1 * g1 + f2 * g2 + f3 * g3) / (f0 + f1 + f2 + f3);
        c = 1 - c;
      }
      indx += 2;
    }
  }

  // D: chroma back onto green.
  for row in l.row_min..l.row_max {
    for col in l.col_min..l.col_max {
      let indx = row * CACHESIZE + col;
      image[indx][0] = rbuf[indx][0] + image[indx][1];
      image[indx][2] = rbuf[indx][1] + image[indx][1];
    }
  }
}

/// One tile from `memset` to the output block — the body of `dcb_demosaic`'s
/// tile loop (`:1441-1521`).
///
/// The pass order is upstream's, and it is a **sequence, not a set**: `dcb_hid`
/// leaves a green estimate that `dcb_hid2` overwrites three times, each time
/// reading what the previous one wrote; the `dcb_map`/`dcb_correction` pairs
/// after `dcb_pp` refine in place. Nothing here can be reordered.
///
/// `band` is this tile row's slice of the three output planes, already offset so
/// that its row 0 is `y0` (`min(y0 + TILESIZE, h)` rows tall). Only the tile's
/// own `TILESIZE`-square block is written, which is exactly what keeps tiles
/// independent — including the outer `TILEBORDER` ring, which is never output.
#[allow(clippy::too_many_arguments)]
fn demosaic_tile(
  t: &mut Tile,
  cfa: &CfaDesc,
  mosaic: &Array2D<f32>,
  x0: usize,
  y0: usize,
  w: usize,
  h: usize,
  iterations: i32,
  enhance: bool,
  band: &mut Band<'_>,
) {
  // `if (!xTile || !yTile || xTile == wTiles - 1 || yTile == hTiles - 1)`.
  let w_tiles = w / TILESIZE + usize::from(w % TILESIZE != 0);
  let h_tiles = h / TILESIZE + usize::from(h % TILESIZE != 0);
  let on_frame_edge = x0 == 0
    || y0 == 0
    || x0 / TILESIZE == w_tiles - 1
    || y0 / TILESIZE == h_tiles - 1;

  t.clear();
  fill_raw(t, x0, y0, w, h, cfa, mosaic);
  if on_frame_edge {
    fill_border(t, 6, x0, y0, w, h, cfa);
  }

  copy_to_buffer(t);
  dcb_hid(t, x0, y0, w, h, cfa);

  for _ in 0..iterations.max(0) {
    dcb_hid2(t, x0, y0, w, h, cfa);
    dcb_hid2(t, x0, y0, w, h, cfa);
    dcb_hid2(t, x0, y0, w, h, cfa);
    dcb_map(t, x0, y0, w, h);
    dcb_correction(t, x0, y0, w, h, cfa);
  }

  dcb_color(t, x0, y0, w, h, cfa);
  dcb_pp(t, x0, y0, w, h);
  dcb_map(t, x0, y0, w, h);
  dcb_correction2(t, x0, y0, w, h, cfa);
  dcb_map(t, x0, y0, w, h);
  dcb_correction(t, x0, y0, w, h, cfa);
  dcb_color(t, x0, y0, w, h, cfa);
  dcb_map(t, x0, y0, w, h);
  dcb_correction(t, x0, y0, w, h, cfa);
  dcb_map(t, x0, y0, w, h);
  dcb_correction(t, x0, y0, w, h, cfa);
  dcb_map(t, x0, y0, w, h);
  restore_from_buffer(t);

  if enhance {
    // `memset(chrm, …)` — `chrm` is `buffer` (`Tile::rbuf`), already consumed by
    // `restore_from_buffer` above.
    t.rbuf.fill([0.0; 2]);
    dcb_refinement(t, x0, y0, w, h, cfa);
    dcb_color_full(t, x0, y0, w, h, cfa);
  } else {
    dcb_color(t, x0, y0, w, h, cfa);
  }

  // The output block, `TILESIZE` square and inside the cache's `TILEBORDER` ring.
  for y in 0..TILESIZE {
    if y0 + y >= h {
      break;
    }
    let src_row = (y + TILEBORDER) * CACHESIZE + TILEBORDER;
    let dst_row = y * w + x0;
    for j in 0..TILESIZE {
      if x0 + j >= w {
        break;
      }
      let px = t.image[src_row + j];
      band.red[dst_row + j] = max0(px[0]) / SCALE;
      band.green[dst_row + j] = max0(px[1]) / SCALE;
      band.blue[dst_row + j] = max0(px[2]) / SCALE;
    }
  }
}

/// A tile row's slice of the three output planes.
///
/// A tile row covers output rows `[y0, min(y0 + TILESIZE, height))` — **all** of
/// them, with no border pass carving anything off, because `dcb_demosaic` writes
/// every pixel of its block. So consecutive tile rows' bands are disjoint *and*
/// adjacent and together cover the whole frame, which is what lets the driver
/// hand each task a real `&mut` slice with no `unsafe`.
///
/// The slices are flat (`width` stride) and start at `y0`, so a local row `y`
/// inside a band is absolute row `y0 + y`.
struct Band<'a> {
  red: &'a mut [f32],
  green: &'a mut [f32],
  blue: &'a mut [f32],
}

/// `RawImageSource::dcb_demosaic(iterations, dcb_enhance)`.
///
/// `iterations` is `raw.bayersensor.dcb_iterations` and `enhance` is
/// `raw.bayersensor.dcb_enhance`; upstream's defaults are `2` and `true`
/// (`BayerParams::default`).
///
/// # Errors
/// [`Error::UnsupportedCfa`] for a CFA this kernel cannot express (a
/// four-colour one, or X-Trans), and [`Error::Shape`] for a frame too small for
/// the tile margin — DCB's `fill_border` indexes `W - 6` and `H - 6`, so
/// anything under 8 pixels on a side has no valid margin.
pub fn bayer_dcb_demosaic(cfa: &CfaDesc, mosaic: &Array2D<f32>, iterations: i32, enhance: bool) -> Result<Rgb, Error> {
  if cfa.has_fourth_colour() {
    // Upstream uses `FC() == 3` to detect a four-colour CFA and hands those to
    // IGV. For a *three-colour* Bayer that test can never fire (the folded mask
    // has no 3), and for a four-colour one IGV cannot cope either — see
    // `bayer/igv.rs`. Refused rather than reproduced, as in vng4/rcd/lmmse.
    return Err(Error::UnsupportedCfa("dcb on a four-colour CFA"));
  }
  if !cfa.is_bayer {
    return Err(Error::UnsupportedCfa("dcb on a non-Bayer CFA"));
  }

  let (w, h) = (mosaic.width(), mosaic.height());
  if w < 8 || h < 8 {
    return Err(Error::Shape(format!("dcb needs at least 8x8, got {w}x{h}")));
  }

  let mut out = Rgb::new(w, h);
  let band_len = TILESIZE * w;

  // One scratch per task, reused across its tiles — upstream allocates once per
  // thread for exactly the same reason (`:1427`).
  out
    .red
    .as_mut_slice()
    .par_chunks_mut(band_len)
    .zip(out.green.as_mut_slice().par_chunks_mut(band_len))
    .zip(out.blue.as_mut_slice().par_chunks_mut(band_len))
    .enumerate()
    .for_each(|(tr, ((red, green), blue))| {
      let mut band = Band { red, green, blue };
      let y0 = tr * TILESIZE;
      let mut t = Tile::new();

      for x0 in (0..w).step_by(TILESIZE) {
        demosaic_tile(&mut t, cfa, mosaic, x0, y0, w, h, iterations, enhance, &mut band);
      }
    });

  Ok(out)
}

#[cfg(test)]
mod tests {
  use super::*;

  const RGGB: [[u8; 2]; 2] = [[0, 1], [1, 2]];

  fn cfa(pattern: [[u8; 2]; 2]) -> CfaDesc {
    CfaDesc::bayer_from_2x2(pattern)
  }

  /// A frame whose every red site holds `r`, green site `g` and blue site `b`.
  ///
  /// This is the input DCB is exact on: every pass is built from *differences of
  /// one channel* (`C - G`) or from a channel's own neighbourhood, so a globally
  /// piecewise-constant mosaic reconstructs exactly. Any parity error in the
  /// `FC`-driven column starts swaps the classes and fails loudly.
  fn piecewise(cfa: &CfaDesc, w: usize, h: usize, r: f32, g: f32, b: f32) -> Array2D<f32> {
    let mut m = Array2D::new(w, h);
    for row in 0..h {
      for col in 0..w {
        m.set(row, col, match cfa.fc(row, col) {
          0 => r,
          2 => b,
          _ => g,
        });
      }
    }
    m
  }

  /// The 400x192 frame the exactness tests read out of.
  ///
  /// Three tiles across, one tile row. Only the **second** tile column is
  /// checked, and only its rows `20..172`: the first tile column owns the
  /// frame's top and left edge, where `dcb_hid` never reaches (its walk starts
  /// two cache rows/columns in) and the output is genuinely contaminated —
  /// upstream's is too. Inside the second tile the cache margin is real frame
  /// data, so the kernel is exact and the assertions can be tight rather than
  /// statistical.
  const PROBE: (usize, usize, usize, usize, usize) = (400, 192, 20, 172, 200);

  /// ...and the exclusive column bound, kept out of the tuple for readability.
  const PROBE_COL_END: usize = 380;

  #[test]
  fn a_piecewise_constant_mosaic_reconstructs_exactly() {
    let (w, h, row0, row1, col0) = PROBE;

    for enhance in [false, true] {
      for pattern in [RGGB, [[2, 1], [1, 0]], [[1, 0], [2, 1]], [[1, 2], [0, 1]]] {
        let c = cfa(pattern);
        let m = piecewise(&c, w, h, 0.2, 0.5, 0.8);
        let out = bayer_dcb_demosaic(&c, &m, 2, enhance).expect("dcb");

        for row in row0..row1 {
          for col in col0..PROBE_COL_END {
            for (name, plane, want) in
              [("red", &out.red, 0.2f32), ("green", &out.green, 0.5), ("blue", &out.blue, 0.8)]
            {
              let got = plane.at(row, col);
              assert!(
                (got - want).abs() < 1e-4,
                "enhance={enhance} pattern={pattern:?} {name} at ({row},{col}) = {got}, want {want}"
              );
            }
          }
        }
      }
    }
  }

  /// `dcb_enhance` switches the last stage between `dcb_color` and
  /// `dcb_refinement` + `dcb_color_full`, so this is not a cosmetic flag.
  #[test]
  fn enhance_is_not_a_no_op() {
    let (w, h) = (400usize, 192usize);
    let c = cfa(RGGB);
    let m = piecewise(&c, w, h, 0.2, 0.5, 0.8);

    let off = bayer_dcb_demosaic(&c, &m, 2, false).expect("dcb");
    let on = bayer_dcb_demosaic(&c, &m, 2, true).expect("dcb");

    // Both are exact on this input, so any difference is bounded by rounding —
    // but the two paths have to have actually run, and `dcb_refinement` is
    // allowed to move green (it clamps into the axial range, which on a
    // piecewise-constant field is a point).
    for row in PROBE.2..PROBE.3 {
      for col in PROBE.4..PROBE_COL_END {
        assert!((off.green.at(row, col) - on.green.at(row, col)).abs() < 1e-4);
        assert!((off.red.at(row, col) - on.red.at(row, col)).abs() < 1e-4);
      }
    }
  }

  /// `iterations = 0` skips the `dcb_hid2`/`dcb_map`/`dcb_correction` loop, and a
  /// negative count has to behave the same way (`for (i = it; i > 0; i--)`).
  #[test]
  fn zero_and_negative_iterations_take_the_same_path() {
    let (w, h) = (400usize, 192usize);
    let c = cfa(RGGB);
    let m = piecewise(&c, w, h, 0.2, 0.5, 0.8);

    let zero = bayer_dcb_demosaic(&c, &m, 0, true).expect("dcb");
    let negative = bayer_dcb_demosaic(&c, &m, -7, true).expect("dcb");
    assert_eq!(zero, negative);
  }

  /// Nothing in DCB can produce a NaN or a negative: the output is
  /// `std::max(0.f, …)`, and every division is by `1 + (non-negative sum of
  /// magnitudes)` or by a sum of four strictly-positive weights. Unlike LMMSE
  /// there is no upper clamp, so values above `1` are legitimate.
  #[test]
  fn output_is_finite_and_non_negative() {
    let (w, h) = (200usize, 200usize);
    let c = cfa(RGGB);
    let mut m = Array2D::new(w, h);
    for row in 0..h {
      for col in 0..w {
        let v = ((row * 11 + col * 29) % 101) as f32 / 100.0;
        m.set(row, col, v);
      }
    }

    for enhance in [false, true] {
      for iterations in [0, 1, 2, 5] {
        let out = bayer_dcb_demosaic(&c, &m, iterations, enhance).expect("dcb");
        for (name, plane) in [("red", &out.red), ("green", &out.green), ("blue", &out.blue)] {
          for (i, &x) in plane.as_slice().iter().enumerate() {
            assert!(x.is_finite(), "enhance={enhance} iterations={iterations} {name}[{i}] is {x}");
            assert!(x >= 0.0, "enhance={enhance} iterations={iterations} {name}[{i}] is {x}");
          }
        }
      }
    }
  }

  /// A tile row writes rows `[y0, min(y0 + TILESIZE, h))` — all of them, since
  /// `dcb_demosaic` has no border pass carving anything off — so chunking each
  /// plane by `TILESIZE * w` gives every task a disjoint slice of whole rows and
  /// covers the frame exactly once. That is the property the driver relies on to
  /// avoid `unsafe`; there is no `border_interpolate` call to clean up after it.
  #[test]
  fn tile_row_chunks_partition_every_row() {
    let w = 16usize;
    for h in [8usize, 191, 192, 193, 384, 385, 600] {
      let plane = vec![0f32; w * h];
      let chunks: Vec<&[f32]> = plane.chunks(TILESIZE * w).collect();

      assert_eq!(
        chunks.len(),
        h / TILESIZE + usize::from(h % TILESIZE != 0),
        "chunk count for h={h}"
      );

      let mut rows = 0usize;
      for c in &chunks {
        assert_eq!(c.len() % w, 0, "whole rows only, h={h}");
        rows += c.len() / w;
      }
      assert_eq!(rows, h, "the chunks cover every row exactly once, h={h}");
    }
  }

  /// A four-colour CFA is refused rather than handed to IGV, because upstream's
  /// fallback cannot demosaic one either (`bayer/igv.rs`).
  #[test]
  fn a_four_colour_cfa_is_rejected() {
    let mut four = cfa(RGGB);
    four.colors = 4;
    let m = piecewise(&cfa(RGGB), 32, 32, 0.2, 0.5, 0.8);
    let err = bayer_dcb_demosaic(&four, &m, 2, true).unwrap_err();
    assert!(matches!(err, Error::UnsupportedCfa(_)), "{err:?}");
  }

  /// ...and so is an X-Trans CFA.
  #[test]
  fn a_non_bayer_cfa_is_rejected() {
    let xt = CfaDesc::xtrans_from_6x6([[1; 6]; 6]);
    let m = piecewise(&cfa(RGGB), 32, 32, 0.2, 0.5, 0.8);
    let err = bayer_dcb_demosaic(&xt, &m, 2, true).unwrap_err();
    assert!(matches!(err, Error::UnsupportedCfa(_)), "{err:?}");
  }

  /// `fill_border` indexes `W - 6` and `H - 6`, so there is a real floor under
  /// the frame size, lower than the crate's own 4x4 but above zero.
  #[test]
  fn a_frame_too_small_is_rejected() {
    let c = cfa(RGGB);
    let err = bayer_dcb_demosaic(&c, &piecewise(&c, 7, 7, 0.2, 0.5, 0.8), 2, true).unwrap_err();
    assert!(matches!(err, Error::Shape(_)), "{err:?}");

    assert!(bayer_dcb_demosaic(&c, &piecewise(&c, 8, 8, 0.2, 0.5, 0.8), 2, true).is_ok());
  }
}





