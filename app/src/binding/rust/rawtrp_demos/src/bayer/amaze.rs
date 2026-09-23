//! AMAZE — Aliasing Minimization and Zipper Elimination Bayer demosaic.
//!
//! Ported from `external/RawTherapee/rtengine/amaze_demosaic_RT.cc`
//! (Copyright (c) 2008-2010 Emil Martinec; optimisations Copyright (c) Ingo
//! Weyrich — GPL-3.0), `RawImageSource::amaze_demosaic_RT()`.
//!
//! The kernel is the longest in the catalogue (~1600 upstream lines) and the only
//! one that keeps *four* generations of the same estimate around: a Hamilton-Adams
//! interpolation, an adaptive-ratio interpolation, a "which of the two varies
//! less" choice between them, and a Nyquist-texture area interpolation on top.
//! Green is estimated first (three times: cardinal, then via variance weights,
//! then once more from the interpolated R+B), then R/B come from green minus a
//! smoothed colour difference.
//!
//! ## Fidelity notes
//!
//! * **This is the scalar (`#else`) branch of upstream, not the SSE one.** Like
//!   LMMSE before it, the two branches are the same *algorithm* but not the same
//!   floating point: upstream's SSE path writes `cddiffsq` at every site while
//!   the scalar one writes it only at red/blue sites (`:681` sits inside the
//!   non-green branch), and the two compute the colour-difference variance by
//!   different identities — `3*Σx² - (Σx)²` here versus Σ(xᵢ-xⱼ)² there. The
//!   scalar branch is what upstream itself compiles on a non-SSE target, so it
//!   *is* RawTherapee, just not the build an x86-64 desktop happens to run.
//! * **`initialGain` is the one per-image input, and this crate cannot see it.**
//!   Upstream derives `clip_pt = 1 / initialGain` and `clip_pt8 = 0.8 /
//!   initialGain` from the white-balance gain it applied before demosaicing
//!   (`initialGain` is `max(scale_mul)/min(scale_mul)`,
//!   `rawimagesource.cc:1454`). This crate is handed a mosaic and a CFA and
//!   applies no gain at all, so the two thresholds take their neutral values
//!   (1.0 and 0.8) — i.e. "clipping begins where our 0..1 domain ends". A
//!   heavily-gained image would upstream trip the highlight path earlier.
//! * **The tile is fully self-initialising.** Every tile mirrors a 16-pixel
//!   border out of the frame (upper/lower/left/right strips plus the four corner
//!   blocks) and then only ever writes the *inner* 16-pixel-smaller rectangle,
//!   so consecutive tiles — stepping by `TS - 32` — tile the frame exactly, with
//!   no gap and no overlap. There is therefore **no trailing
//!   `border_interpolate`**, unlike every other Bayer kernel here: upstream's
//!   own one (`:1601-1603`) is guarded by the `border` member of
//!   `RawImageSource`, which does not exist in this crate, and it is not
//!   reachable anyway.
//! * **The scratch planes are not aliased here.** Upstream hands several names
//!   the same memory (`Dgrb` = `vcdalt`, `delp` = `cddiffsq`, `pmwt` =
//!   `delhvsqsum`, `rbm` = `vcd`, `nyquist2` = `cddiffsq`) on the comment "no
//!   overlap in buffer usage => share". That sharing is only safe because each
//!   stage overwrites everything it reads before the next one looks, which is
//!   another way of saying the aliasing is not load-bearing — so this port gives
//!   every plane its own allocation. The one place the aliasing *is* visible is
//!   `Dgrb[1]`, which upstream inherits stale `vcdalt` values at the red/blue
//!   coset rows: the fancy-chrominance pass overwrites those entries from the
//!   four diagonal neighbours and never reads the old value, so starting from
//!   zero is bit-identical.
//! * **Parallelism follows the tile *rows*, not the tiles.** Upstream is
//!   `omp for collapse(2)` over the 2-D tile grid with one scratch buffer per
//!   *thread*; two tiles in the same row-band therefore share a buffer and are
//!   processed one after another. That is reproduced here: one scratch set per
//!   row-band task, tiles of the band serial inside it. (Parallelising the whole
//!   grid would need a ~2 MB buffer per *tile*, which is why upstream did not.)
//! * **`nyquist2` is only zeroed when the tile has Nyquist pixels** — upstream
//!   `memset`s it inside `if (doNyquist)` (`:891`). Reproduced, because the
//!   stale flags it leaves behind are only read by `Dgrb2`, which is itself only
//!   read inside the same `doNyquist` guard.
//! * **Colour codes come from [`CfaDesc::fc`] in *tile* coordinates**, matching
//!   upstream's local `fc(cfarray, r, c)` macro. That is only the same as asking
//!   in image coordinates because every tile origin is even: `top` and `left`
//!   both start at `-16` and step by `TS - 32 = 128`.

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::cfa::CfaDesc;
use crate::math::{abs, intp, max0, median3, min2, sqr, xdiv2f, xdivf, xmul2f};
use crate::{Error, Rgb};

/// Tile size (`AMAZETS`, multiple of 32 in `[96, 992]`; `amaze:77`).
const TS: i32 = 160;
/// Half of [`TS`].
const TSH: i32 = TS / 2;

/// Shifts of the flat tile index into the vertical and diagonal directions
/// (`amaze:103`).
const V1: i32 = TS;
const V2: i32 = 2 * TS;
const V3: i32 = 3 * TS;
const P1: i32 = -TS + 1;
const P2: i32 = -2 * TS + 2;
const P3: i32 = -3 * TS + 3;
const M1: i32 = TS + 1;
const M2: i32 = 2 * TS + 2;
const M3: i32 = 3 * TS + 3;

/// Tolerance to avoid dividing by zero (`amaze:106`).
const EPS: f32 = 1e-5;
/// Its square — used wherever the kernel adds a floor to a *variance*.
const EPS_SQ: f32 = 1e-10;
/// Adaptive-ratio threshold: above it the ratio branch is not trusted
/// (`amaze:109`).
const ARTHRESH: f32 = 0.75;

/// Gaussian on a 5x5 quincunx, sigma = 1.2 (`amaze:112`).
const GAUSS_ODD: [f32; 4] = [0.14659727707323927, 0.103592713382435, 0.0732036125103057, 0.0365543548389495];
/// Nyquist texture test threshold, already folded into [`GAUSS_GRAD`]
/// (`amaze:114`).
const NYQ_THRESH: f32 = 0.5;
/// `nyqthresh *` the 5x5 sigma=1.2 gradient Gaussian (`amaze:117-119`).
const GAUSS_GRAD: [f32; 6] = [
  NYQ_THRESH * 0.07384411893421103,
  NYQ_THRESH * 0.06207511968171489,
  NYQ_THRESH * 0.0521818194747806,
  NYQ_THRESH * 0.03687419286733595,
  NYQ_THRESH * 0.03099732204057846,
  NYQ_THRESH * 0.018413194161458882,
];
/// Gaussian on a 5x5 alt quincunx, sigma = 1.5 (`amaze:121`).
const GAUSS_EVEN: [f32; 2] = [0.13719494435797422, 0.05640252782101291];
/// Gaussian on the quincunx grid (`amaze:123`).
const GQUINC: [f32; 4] = [0.169917, 0.108947, 0.069855, 0.0287182];

/// `clip_pt` / `clip_pt8` with `initialGain = 1` — see the module docs.
const CLIP_PT: f32 = 1.0;
const CLIP_PT8: f32 = 0.8;

/// Smallest frame the kernel can mirror a 16-pixel border out of: the corner
/// blocks reach `rawData[32][32]` (`amaze:321`), so 33 rows and 33 columns are
/// the floor. Upstream assumes it too — it reads those rows unconditionally for
/// the first tile.
const MIN_SIDE: usize = 33;

/// Per-tile scratch planes, one allocation each (upstream shares several of
/// these; see the module docs for why this port does not).
struct Tile {
  /// The tile's copy of the mosaic, `TS x TS`, 0..1.
  cfa: Vec<f32>,
  /// Green: starts as the mosaic and is overwritten at red/blue sites.
  rgbgreen: Vec<f32>,
  /// `Σ` of the squared horizontal and vertical gradients.
  delhvsqsum: Vec<f32>,
  /// Vertical / horizontal gradient weights.
  dirwts0: Vec<f32>,
  dirwts1: Vec<f32>,
  /// Vertical / horizontal colour differences (G-R or G-B).
  vcd: Vec<f32>,
  hcd: Vec<f32>,
  /// The Hamilton-Adams alternative to each of the above.
  vcdalt: Vec<f32>,
  hcdalt: Vec<f32>,
  /// Square of the difference between the two colour-difference estimates.
  cddiffsq: Vec<f32>,
  /// Square of the difference between the up/down and left/right green
  /// interpolations.
  dgintv: Vec<f32>,
  dginth: Vec<f32>,
  /// The half-resolution planes, indexed `indx >> 1`.
  hvwt: Vec<f32>,
  /// `Dgrb[0]` = G-R, `Dgrb[1]` = G-B, one entry per colour pair.
  dgrb0: Vec<f32>,
  dgrb1: Vec<f32>,
  /// Local curvature of the interpolated green, horizontal and vertical.
  dgrb2_h: Vec<f32>,
  dgrb2_v: Vec<f32>,
  dgrbsq1m: Vec<f32>,
  dgrbsq1p: Vec<f32>,
  delp: Vec<f32>,
  delm: Vec<f32>,
  /// Interpolated R+B at red/blue sites, minus and plus diagonals.
  rbm: Vec<f32>,
  rbp: Vec<f32>,
  rbint: Vec<f32>,
  pmwt: Vec<f32>,
  nyqutest: Vec<f32>,
  nyquist: Vec<u8>,
  nyquist2: Vec<u8>,
}

impl Tile {
  fn new() -> Self {
    let full = (TS * TS) as usize;
    let half = (TS * TSH) as usize;
    Self {
      cfa: vec![0.0; full],
      rgbgreen: vec![0.0; full],
      delhvsqsum: vec![0.0; full],
      dirwts0: vec![0.0; full],
      dirwts1: vec![0.0; full],
      vcd: vec![0.0; full],
      hcd: vec![0.0; full],
      vcdalt: vec![0.0; full],
      hcdalt: vec![0.0; full],
      cddiffsq: vec![0.0; full],
      dgintv: vec![0.0; full],
      dginth: vec![0.0; full],
      hvwt: vec![0.0; half],
      dgrb0: vec![0.0; half],
      dgrb1: vec![0.0; half],
      dgrb2_h: vec![0.0; half],
      dgrb2_v: vec![0.0; half],
      dgrbsq1m: vec![0.0; half],
      dgrbsq1p: vec![0.0; half],
      delp: vec![0.0; half],
      delm: vec![0.0; half],
      rbm: vec![0.0; half],
      rbp: vec![0.0; half],
      rbint: vec![0.0; half],
      pmwt: vec![0.0; half],
      nyqutest: vec![0.0; half],
      nyquist: vec![0; half],
      nyquist2: vec![0; half],
    }
  }
}

/// The slice of the three output planes one tile row-band owns.
struct Out<'a> {
  width: usize,
  /// Image row the first row of [`Self::red`] stands for.
  row0: usize,
  red: &'a mut [f32],
  green: &'a mut [f32],
  blue: &'a mut [f32],
}

impl Out<'_> {
  #[inline(always)]
  fn set_red(&mut self, row: i32, col: i32, v: f32) {
    self.red[(row as usize - self.row0) * self.width + col as usize] = max0(v);
  }

  #[inline(always)]
  fn set_green(&mut self, row: i32, col: i32, v: f32) {
    self.green[(row as usize - self.row0) * self.width + col as usize] = max0(v);
  }

  #[inline(always)]
  fn set_blue(&mut self, row: i32, col: i32, v: f32) {
    self.blue[(row as usize - self.row0) * self.width + col as usize] = max0(v);
  }
}

/// Demosaic a Bayer mosaic with AMAZE.
///
/// # Errors
/// [`Error::UnsupportedCfa`] for a non-Bayer or four-colour CFA, and
/// [`Error::Shape`] for a frame smaller than [`MIN_SIDE`] in either dimension.
pub fn bayer_amaze_demosaic(cfa: &CfaDesc, mosaic: &Array2D<f32>) -> Result<Rgb, Error> {
  if !cfa.is_bayer {
    return Err(Error::UnsupportedCfa("bayer_amaze on a non-Bayer CFA"));
  }
  if cfa.has_fourth_colour() {
    return Err(Error::UnsupportedCfa("bayer_amaze"));
  }
  let (w, h) = (mosaic.width(), mosaic.height());
  if w < MIN_SIDE || h < MIN_SIDE {
    return Err(Error::Shape(format!("bayer_amaze: mosaic too small: {w}x{h} (needs {MIN_SIDE}x{MIN_SIDE})")));
  }
  let (wi, hi) = (w as i32, h as i32);

  // Offset of the red site inside the 2x2 Bayer tile (`amaze:83-100`). Colour
  // codes are what `CfaDesc::fc` returns: 0 = R, 1 = G, 2 = B.
  let (ey, ex): (i32, i32) = if cfa.fc_i(0, 0) == 1 {
    if cfa.fc_i(0, 1) == 0 {
      (0, 1)
    } else {
      (1, 0)
    }
  } else if cfa.fc_i(0, 0) == 0 {
    (0, 0)
  } else {
    (1, 1)
  };

  let mut out = Rgb::new(w, h);

  // Row bands: the tiles whose `top` is the same share a scratch set, and they
  // write disjoint column ranges inside the same rows, so one band is one task.
  let mut tops: Vec<i32> = Vec::new();
  let mut top = -16;
  while top < hi {
    tops.push(top);
    top += TS - 32;
  }

  // Row range each band writes: `[top + 16, top + rr1 - 16)`. `rr1` depends on
  // `top` alone, so it is the same for every tile of the band (`amaze:200-206`).
  // The last `top` can sit above `hi - 16` (it only has to be `< hi`), and then
  // the tile's write loops — `rr in 16..rr1 - 16` — are empty, exactly as they
  // are upstream. Such a band owns zero output rows and is dropped here rather
  // than handed an empty slice: `hi_row - lo` would underflow otherwise.
  let bands: Vec<(i32, usize, usize)> = tops
    .iter()
    .filter_map(|&top| {
      let rr1 = (top + TS).min(hi + 16) - top;
      let lo = (top + 16).max(0) as usize;
      let hi_row = ((top + rr1 - 16) as usize).min(h);
      (hi_row > lo).then_some((top, lo, hi_row))
    })
    .collect();

  // Split each plane into one mutable slice per band. The bands are contiguous
  // and cover the frame exactly, so this is a plain sequence of `split_at_mut`s.
  let sizes: Vec<usize> = bands.iter().map(|&(_, lo, hi_row)| (hi_row - lo) * w).collect();
  let red_parts = split_slices(out.red.as_mut_slice(), &sizes);
  let green_parts = split_slices(out.green.as_mut_slice(), &sizes);
  let blue_parts = split_slices(out.blue.as_mut_slice(), &sizes);

  red_parts
    .into_par_iter()
    .zip(green_parts)
    .zip(blue_parts)
    .zip(bands)
    .for_each(|(((red, green), blue), (top, lo, _))| {
      let mut out = Out { width: w, row0: lo, red, green, blue };
      let mut tile = Tile::new();
      let mut left = -16;
      while left < wi {
        run_tile(cfa, mosaic, top, left, wi, hi, ey, ex, &mut tile, &mut out);
        left += TS - 32;
      }
    });

  Ok(out)
}

/// Split `s` into consecutive pieces of the given byte-free lengths.
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

/// One tile: the whole kernel body (`amaze:196-1594`).
#[allow(clippy::too_many_arguments)]
fn run_tile(
  cfa: &CfaDesc,
  mosaic: &Array2D<f32>,
  top: i32,
  left: i32,
  wi: i32,
  hi: i32,
  ey: i32,
  ex: i32,
  t: &mut Tile,
  out: &mut Out<'_>,
) {
  // ---- bookkeeping (`amaze:198-212`)
  for e in &mut t.nyquist[(3 * TSH) as usize..((TS - 3) * TSH) as usize] {
    *e = 0;
  }
  let bottom = (top + TS).min(hi + 16);
  let right = (left + TS).min(wi + 16);
  let rr1 = bottom - top;
  let cc1 = right - left;
  let rrmin = if top < 0 { 16 } else { 0 };
  let ccmin = if left < 0 { 16 } else { 0 };
  let rrmax = if bottom > hi { hi - top } else { rr1 };
  let ccmax = if right > wi { wi - left } else { cc1 };

  // ---- tile initialisation: mosaic -> `cfa` and `rgbgreen` (`amaze:268-348`)
  // Upstream divides by 65535 here because its `rawData` is 16-bit; this crate's
  // mosaic is already 0..1, so the samples move across unchanged.
  if rrmin > 0 {
    for rr in 0..16 {
      let row = 32 - rr + top;
      for cc in ccmin..ccmax {
        let i = (rr * TS + cc) as usize;
        let v = mosaic.at(row as usize, (cc + left) as usize);
        t.cfa[i] = v;
        t.rgbgreen[i] = v;
      }
    }
  }

  for rr in rrmin..rrmax {
    let row = rr + top;
    for cc in ccmin..ccmax {
      let i = (rr * TS + cc) as usize;
      let v = mosaic.at(row as usize, (cc + left) as usize);
      t.cfa[i] = v;
      t.rgbgreen[i] = v;
    }
  }

  if rrmax < rr1 {
    // Upstream loops a fixed 16 rows here; when the tile is clipped by the
    // frame (`rr1 < TS`) that writes past the tile into the *next* buffer of
    // its single big allocation, which the kernel then overwrites before
    // reading. With per-plane arrays those writes would be real overflows, so
    // the loop is clamped to the rows the tile actually owns — every row the
    // kernel reads is filled either way.
    let rows = (rr1 - rrmax).min(16);
    for rr in 0..rows {
      let row = hi - rr - 2;
      for cc in ccmin..ccmax {
        let i = ((rrmax + rr) * TS + cc) as usize;
        let v = mosaic.at(row as usize, (cc + left) as usize);
        t.cfa[i] = v;
        t.rgbgreen[i] = v;
      }
    }
  }

  if ccmin > 0 {
    for rr in rrmin..rrmax {
      let row = rr + top;
      for cc in 0..16 {
        let i = (rr * TS + cc) as usize;
        let v = mosaic.at(row as usize, (32 - cc + left) as usize);
        t.cfa[i] = v;
        t.rgbgreen[i] = v;
      }
    }
  }

  if ccmax < cc1 {
    // Same clamp as the lower-border block: the fixed 16 columns only stay
    // inside the tile when it is not clipped by the frame's right edge.
    let cols = (cc1 - ccmax).min(16);
    for rr in rrmin..rrmax {
      let row = top + rr;
      for cc in 0..cols {
        let i = (rr * TS + ccmax + cc) as usize;
        let v = mosaic.at(row as usize, (wi - cc - 2) as usize);
        t.cfa[i] = v;
        t.rgbgreen[i] = v;
      }
    }
  }

  // the four corner blocks
  if rrmin > 0 && ccmin > 0 {
    for rr in 0..16 {
      for cc in 0..16 {
        let i = (rr * TS + cc) as usize;
        let v = mosaic.at((32 - rr) as usize, (32 - cc) as usize);
        t.cfa[i] = v;
        t.rgbgreen[i] = v;
      }
    }
  }
  if rrmax < rr1 && ccmax < cc1 {
    let rows = (rr1 - rrmax).min(16);
    let cols = (cc1 - ccmax).min(16);
    for rr in 0..rows {
      for cc in 0..cols {
        let i = ((rrmax + rr) * TS + ccmax + cc) as usize;
        let v = mosaic.at((hi - rr - 2) as usize, (wi - cc - 2) as usize);
        t.cfa[i] = v;
        t.rgbgreen[i] = v;
      }
    }
  }
  if rrmin > 0 && ccmax < cc1 {
    let cols = (cc1 - ccmax).min(16);
    for rr in 0..16 {
      for cc in 0..cols {
        let i = (rr * TS + ccmax + cc) as usize;
        let v = mosaic.at((32 - rr) as usize, (wi - cc - 2) as usize);
        t.cfa[i] = v;
        t.rgbgreen[i] = v;
      }
    }
  }
  if rrmax < rr1 && ccmin > 0 {
    // Upstream reads `rawData[winy + height - rr - 2][winy + 32 - cc]` here —
    // `winy` in the *column* index, where the three sibling blocks use `winx`.
    // It is a genuine typo (`amaze:345`), and it is a no-op for this crate
    // because the window origin is always (0, 0), so the two are the same
    // expression. Kept as `32 - cc` rather than "fixed", because a port that
    // silently differs from upstream here is worse than one that documents it.
    let rows = (rr1 - rrmax).min(16);
    for rr in 0..rows {
      for cc in 0..16 {
        let i = ((rrmax + rr) * TS + cc) as usize;
        let v = mosaic.at((hi - rr - 2) as usize, (32 - cc) as usize);
        t.cfa[i] = v;
        t.rgbgreen[i] = v;
      }
    }
  }

  // ---- horizontal and vertical gradients (`amaze:368-375`)
  for rr in 2..rr1 - 2 {
    for cc in 2..cc1 - 2 {
      let indx = (rr * TS + cc) as usize;
      let delh = abs(t.cfa[indx + 1] - t.cfa[indx - 1]);
      let delv = abs(t.cfa[indx + V1 as usize] - t.cfa[indx - V1 as usize]);
      t.dirwts0[indx] = EPS + abs(t.cfa[indx + V2 as usize] - t.cfa[indx]) + abs(t.cfa[indx] - t.cfa[indx - V2 as usize]) + delv;
      t.dirwts1[indx] = EPS + abs(t.cfa[indx + 2] - t.cfa[indx]) + abs(t.cfa[indx] - t.cfa[indx - 2]) + delh;
      t.delhvsqsum[indx] = sqr(delh) + sqr(delv);
    }
  }

  // ---- interpolate vertical/horizontal colour differences (`amaze:449-532`)
  for rr in 4..rr1 - 4 {
    // `fc(cfarray, rr, 4) & 1` — greenness of the even column, toggled per
    // column below.
    let mut fcswitch = (cfa.fc_i(rr, 4) & 1) == 1;
    for cc in 4..cc1 - 4 {
      let indx = (rr * TS + cc) as usize;
      let c = t.cfa[indx];
      // colour ratios in each cardinal direction
      let cru = t.cfa[indx - V1 as usize]
        * (t.dirwts0[indx - V2 as usize] + t.dirwts0[indx])
        / (t.dirwts0[indx - V2 as usize] * (EPS + c) + t.dirwts0[indx] * (EPS + t.cfa[indx - V2 as usize]));
      let crd = t.cfa[indx + V1 as usize]
        * (t.dirwts0[indx + V2 as usize] + t.dirwts0[indx])
        / (t.dirwts0[indx + V2 as usize] * (EPS + c) + t.dirwts0[indx] * (EPS + t.cfa[indx + V2 as usize]));
      let crl = t.cfa[indx - 1]
        * (t.dirwts1[indx - 2] + t.dirwts1[indx])
        / (t.dirwts1[indx - 2] * (EPS + c) + t.dirwts1[indx] * (EPS + t.cfa[indx - 2]));
      let crr = t.cfa[indx + 1]
        * (t.dirwts1[indx + 2] + t.dirwts1[indx])
        / (t.dirwts1[indx + 2] * (EPS + c) + t.dirwts1[indx] * (EPS + t.cfa[indx + 2]));

      // Hamilton-Adams green in the four directions
      let guha = t.cfa[indx - V1 as usize] + xdiv2f(c - t.cfa[indx - V2 as usize]);
      let gdha = t.cfa[indx + V1 as usize] + xdiv2f(c - t.cfa[indx + V2 as usize]);
      let glha = t.cfa[indx - 1] + xdiv2f(c - t.cfa[indx - 2]);
      let grha = t.cfa[indx + 1] + xdiv2f(c - t.cfa[indx + 2]);

      // adaptive-ratio green
      let mut guar = if abs(1.0 - cru) < ARTHRESH { c * cru } else { guha };
      let mut gdar = if abs(1.0 - crd) < ARTHRESH { c * crd } else { gdha };
      let mut glar = if abs(1.0 - crl) < ARTHRESH { c * crl } else { glha };
      let mut grar = if abs(1.0 - crr) < ARTHRESH { c * crr } else { grha };

      let hwt = t.dirwts1[indx - 1] / (t.dirwts1[indx - 1] + t.dirwts1[indx + 1]);
      let vwt = t.dirwts0[indx - V1 as usize] / (t.dirwts0[indx + V1 as usize] + t.dirwts0[indx - V1 as usize]);

      let gintvha = vwt * gdha + (1.0 - vwt) * guha;
      let ginthha = hwt * grha + (1.0 - hwt) * glha;

      if fcswitch {
        t.vcd[indx] = c - (vwt * gdar + (1.0 - vwt) * guar);
        t.hcd[indx] = c - (hwt * grar + (1.0 - hwt) * glar);
        t.vcdalt[indx] = c - gintvha;
        t.hcdalt[indx] = c - ginthha;
      } else {
        t.vcd[indx] = (vwt * gdar + (1.0 - vwt) * guar) - c;
        t.hcd[indx] = (hwt * grar + (1.0 - hwt) * glar) - c;
        t.vcdalt[indx] = gintvha - c;
        t.hcdalt[indx] = ginthha - c;
      }

      fcswitch = !fcswitch;

      if c > CLIP_PT8 || gintvha > CLIP_PT8 || ginthha > CLIP_PT8 {
        // use Hamilton-Adams if highlights are (nearly) clipped
        guar = guha;
        gdar = gdha;
        glar = glha;
        grar = grha;
        t.vcd[indx] = t.vcdalt[indx];
        t.hcd[indx] = t.hcdalt[indx];
      }

      t.dgintv[indx] = min2(sqr(guha - gdha), sqr(guar - gdar));
      t.dginth[indx] = min2(sqr(glha - grha), sqr(glar - grar));
    }
  }

  // ---- bound the interpolation in saturated regions (`amaze:599-686`)
  for rr in 4..rr1 - 4 {
    let mut c = (cfa.fc_i(rr, 4) & 1) == 1;
    for cc in 4..cc1 - 4 {
      let indx = (rr * TS + cc) as usize;
      let hcdvar = 3.0 * (sqr(t.hcd[indx - 2]) + sqr(t.hcd[indx]) + sqr(t.hcd[indx + 2]))
        - sqr(t.hcd[indx - 2] + t.hcd[indx] + t.hcd[indx + 2]);
      let hcdaltvar = 3.0 * (sqr(t.hcdalt[indx - 2]) + sqr(t.hcdalt[indx]) + sqr(t.hcdalt[indx + 2]))
        - sqr(t.hcdalt[indx - 2] + t.hcdalt[indx] + t.hcdalt[indx + 2]);
      let vcdvar = 3.0
        * (sqr(t.vcd[indx - V2 as usize]) + sqr(t.vcd[indx]) + sqr(t.vcd[indx + V2 as usize]))
        - sqr(t.vcd[indx - V2 as usize] + t.vcd[indx] + t.vcd[indx + V2 as usize]);
      let vcdaltvar = 3.0
        * (sqr(t.vcdalt[indx - V2 as usize]) + sqr(t.vcdalt[indx]) + sqr(t.vcdalt[indx + V2 as usize]))
        - sqr(t.vcdalt[indx - V2 as usize] + t.vcdalt[indx] + t.vcdalt[indx + V2 as usize]);

      // choose the smallest variance; this yields a smoother interpolation
      if hcdaltvar < hcdvar {
        t.hcd[indx] = t.hcdalt[indx];
      }
      if vcdaltvar < vcdvar {
        t.vcd[indx] = t.vcdalt[indx];
      }

      if c {
        // G site
        let ginth = -t.hcd[indx] + t.cfa[indx];
        let gintv = -t.vcd[indx] + t.cfa[indx];

        if t.hcd[indx] > 0.0 {
          if 3.0 * t.hcd[indx] > (ginth + t.cfa[indx]) {
            t.hcd[indx] = -median3(ginth, t.cfa[indx - 1], t.cfa[indx + 1]) + t.cfa[indx];
          } else {
            let hwt = 1.0 - 3.0 * t.hcd[indx] / (EPS + ginth + t.cfa[indx]);
            let alt = -median3(ginth, t.cfa[indx - 1], t.cfa[indx + 1]) + t.cfa[indx];
            t.hcd[indx] = hwt * t.hcd[indx] + (1.0 - hwt) * alt;
          }
        }

        if t.vcd[indx] > 0.0 {
          if 3.0 * t.vcd[indx] > (gintv + t.cfa[indx]) {
            t.vcd[indx] = -median3(gintv, t.cfa[indx - V1 as usize], t.cfa[indx + V1 as usize]) + t.cfa[indx];
          } else {
            let vwt = 1.0 - 3.0 * t.vcd[indx] / (EPS + gintv + t.cfa[indx]);
            let alt = -median3(gintv, t.cfa[indx - V1 as usize], t.cfa[indx + V1 as usize]) + t.cfa[indx];
            t.vcd[indx] = vwt * t.vcd[indx] + (1.0 - vwt) * alt;
          }
        }

        if ginth > CLIP_PT {
          t.hcd[indx] = -median3(ginth, t.cfa[indx - 1], t.cfa[indx + 1]) + t.cfa[indx];
        }
        if gintv > CLIP_PT {
          t.vcd[indx] = -median3(gintv, t.cfa[indx - V1 as usize], t.cfa[indx + V1 as usize]) + t.cfa[indx];
        }
      } else {
        // R or B site
        let ginth = t.hcd[indx] + t.cfa[indx];
        let gintv = t.vcd[indx] + t.cfa[indx];

        if t.hcd[indx] < 0.0 {
          if 3.0 * t.hcd[indx] < -(ginth + t.cfa[indx]) {
            t.hcd[indx] = median3(ginth, t.cfa[indx - 1], t.cfa[indx + 1]) - t.cfa[indx];
          } else {
            let hwt = 1.0 + 3.0 * t.hcd[indx] / (EPS + ginth + t.cfa[indx]);
            let alt = median3(ginth, t.cfa[indx - 1], t.cfa[indx + 1]) - t.cfa[indx];
            t.hcd[indx] = hwt * t.hcd[indx] + (1.0 - hwt) * alt;
          }
        }

        if t.vcd[indx] < 0.0 {
          if 3.0 * t.vcd[indx] < -(gintv + t.cfa[indx]) {
            t.vcd[indx] = median3(gintv, t.cfa[indx - V1 as usize], t.cfa[indx + V1 as usize]) - t.cfa[indx];
          } else {
            let vwt = 1.0 + 3.0 * t.vcd[indx] / (EPS + gintv + t.cfa[indx]);
            let alt = median3(gintv, t.cfa[indx - V1 as usize], t.cfa[indx + V1 as usize]) - t.cfa[indx];
            t.vcd[indx] = vwt * t.vcd[indx] + (1.0 - vwt) * alt;
          }
        }

        if ginth > CLIP_PT {
          t.hcd[indx] = median3(ginth, t.cfa[indx - 1], t.cfa[indx + 1]) - t.cfa[indx];
        }
        if gintv > CLIP_PT {
          t.vcd[indx] = median3(gintv, t.cfa[indx - V1 as usize], t.cfa[indx + V1 as usize]) - t.cfa[indx];
        }

        // Only the scalar branch writes this, and only at red/blue sites — see
        // the module docs.
        t.cddiffsq[indx] = sqr(t.vcd[indx] - t.hcd[indx]);
      }

      c = !c;
    }
  }

  // ---- adaptive weights for the green interpolation (`amaze:741-784`)
  for rr in 6..rr1 - 6 {
    let mut cc = 6 + (cfa.fc_i(rr, 2) & 1) as i32;
    while cc < cc1 - 6 {
      let indx = (rr * TS + cc) as usize;
      let uave = t.vcd[indx] + t.vcd[indx - V1 as usize] + t.vcd[indx - V2 as usize] + t.vcd[indx - V3 as usize];
      let dave = t.vcd[indx] + t.vcd[indx + V1 as usize] + t.vcd[indx + V2 as usize] + t.vcd[indx + V3 as usize];
      let lave = t.hcd[indx] + t.hcd[indx - 1] + t.hcd[indx - 2] + t.hcd[indx - 3];
      let rave = t.hcd[indx] + t.hcd[indx + 1] + t.hcd[indx + 2] + t.hcd[indx + 3];

      let dgrbvvaru = sqr(t.vcd[indx] - uave)
        + sqr(t.vcd[indx - V1 as usize] - uave)
        + sqr(t.vcd[indx - V2 as usize] - uave)
        + sqr(t.vcd[indx - V3 as usize] - uave);
      let dgrbvvard = sqr(t.vcd[indx] - dave)
        + sqr(t.vcd[indx + V1 as usize] - dave)
        + sqr(t.vcd[indx + V2 as usize] - dave)
        + sqr(t.vcd[indx + V3 as usize] - dave);
      let dgrbhvarl = sqr(t.hcd[indx] - lave)
        + sqr(t.hcd[indx - 1] - lave)
        + sqr(t.hcd[indx - 2] - lave)
        + sqr(t.hcd[indx - 3] - lave);
      let dgrbhvarr = sqr(t.hcd[indx] - rave)
        + sqr(t.hcd[indx + 1] - rave)
        + sqr(t.hcd[indx + 2] - rave)
        + sqr(t.hcd[indx + 3] - rave);

      let hwt = t.dirwts1[indx - 1] / (t.dirwts1[indx - 1] + t.dirwts1[indx + 1]);
      let vwt = t.dirwts0[indx - V1 as usize] / (t.dirwts0[indx + V1 as usize] + t.dirwts0[indx - V1 as usize]);

      let vcdvar = EPS_SQ + vwt * dgrbvvard + (1.0 - vwt) * dgrbvvaru;
      let hcdvar = EPS_SQ + hwt * dgrbhvarr + (1.0 - hwt) * dgrbhvarl;

      // fluctuations in the up/down and left/right interpolations
      let dgrbvvaru = t.dgintv[indx] + t.dgintv[indx - V1 as usize] + t.dgintv[indx - V2 as usize];
      let dgrbvvard = t.dgintv[indx] + t.dgintv[indx + V1 as usize] + t.dgintv[indx + V2 as usize];
      let dgrbhvarl = t.dginth[indx] + t.dginth[indx - 1] + t.dginth[indx - 2];
      let dgrbhvarr = t.dginth[indx] + t.dginth[indx + 1] + t.dginth[indx + 2];

      let vcdvar1 = EPS_SQ + vwt * dgrbvvard + (1.0 - vwt) * dgrbvvaru;
      let hcdvar1 = EPS_SQ + hwt * dgrbhvarr + (1.0 - hwt) * dgrbhvarl;

      let varwt = hcdvar / (vcdvar + hcdvar);
      let diffwt = hcdvar1 / (vcdvar1 + hcdvar1);

      // if both agree on the direction, take the one with the stronger
      // discrimination; otherwise take the fluctuation weights
      let pair = (indx >> 1) as usize;
      if (0.5 - varwt) * (0.5 - diffwt) > 0.0 && abs(0.5 - diffwt) < abs(0.5 - varwt) {
        t.hvwt[pair] = varwt;
      } else {
        t.hvwt[pair] = diffwt;
      }

      cc += 2;
    }
  }

  // ---- precompute the Nyquist test (`amaze:803-858`)
  for rr in 6..rr1 - 6 {
    let mut cc = 6 + (cfa.fc_i(rr, 2) & 1) as i32;
    while cc < cc1 - 6 {
      let indx = (rr * TS + cc) as usize;
      t.nyqutest[(indx >> 1) as usize] = (GAUSS_ODD[0] * t.cddiffsq[indx]
        + GAUSS_ODD[1]
          * (t.cddiffsq[(indx as i32 - M1) as usize]
            + t.cddiffsq[(indx as i32 + P1) as usize]
            + t.cddiffsq[(indx as i32 - P1) as usize]
            + t.cddiffsq[(indx as i32 + M1) as usize])
        + GAUSS_ODD[2]
          * (t.cddiffsq[indx - V2 as usize]
            + t.cddiffsq[indx - 2]
            + t.cddiffsq[indx + 2]
            + t.cddiffsq[indx + V2 as usize])
        + GAUSS_ODD[3]
          * (t.cddiffsq[(indx as i32 - M2) as usize]
            + t.cddiffsq[(indx as i32 + P2) as usize]
            + t.cddiffsq[(indx as i32 - P2) as usize]
            + t.cddiffsq[(indx as i32 + M2) as usize]))
        - (GAUSS_GRAD[0] * t.delhvsqsum[indx]
          + GAUSS_GRAD[1]
            * (t.delhvsqsum[indx - V1 as usize]
              + t.delhvsqsum[indx + 1]
              + t.delhvsqsum[indx - 1]
              + t.delhvsqsum[indx + V1 as usize])
          + GAUSS_GRAD[2]
            * (t.delhvsqsum[(indx as i32 - M1) as usize]
              + t.delhvsqsum[(indx as i32 + P1) as usize]
              + t.delhvsqsum[(indx as i32 - P1) as usize]
              + t.delhvsqsum[(indx as i32 + M1) as usize])
          + GAUSS_GRAD[3]
            * (t.delhvsqsum[indx - V2 as usize]
              + t.delhvsqsum[indx - 2]
              + t.delhvsqsum[indx + 2]
              + t.delhvsqsum[indx + V2 as usize])
          + GAUSS_GRAD[4]
            * (t.delhvsqsum[indx - V2 as usize - 1]
              + t.delhvsqsum[indx - V2 as usize + 1]
              + t.delhvsqsum[indx - TS as usize - 2]
              + t.delhvsqsum[indx - TS as usize + 2]
              + t.delhvsqsum[indx + TS as usize - 2]
              + t.delhvsqsum[indx + TS as usize + 2]
              + t.delhvsqsum[indx + V2 as usize - 1]
              + t.delhvsqsum[indx + V2 as usize + 1])
          + GAUSS_GRAD[5]
            * (t.delhvsqsum[(indx as i32 - M2) as usize]
              + t.delhvsqsum[(indx as i32 + P2) as usize]
              + t.delhvsqsum[(indx as i32 - P2) as usize]
              + t.delhvsqsum[(indx as i32 + M2) as usize]));

      cc += 2;
    }
  }

  // ---- Nyquist flags and their bounding box (`amaze:861-891`)
  let mut nystartrow = 0;
  let mut nyendrow = 0;
  let mut nystartcol = TS + 1;
  let mut nyendcol = 0;

  for rr in 6..rr1 - 6 {
    let mut cc = 6 + (cfa.fc_i(rr, 2) & 1) as i32;
    while cc < cc1 - 6 {
      let indx = (rr * TS + cc) as usize;
      if t.nyqutest[(indx >> 1) as usize] > 0.0 {
        t.nyquist[(indx >> 1) as usize] = 1;
        nystartrow = if nystartrow != 0 { nystartrow } else { rr };
        nyendrow = rr;
        nystartcol = if nystartcol > cc { cc } else { nystartcol };
        nyendcol = if nyendcol < cc { cc } else { nyendcol };
      }
      cc += 2;
    }
  }

  let do_nyquist = nystartrow != nyendrow && nystartcol != nyendcol;

  if do_nyquist {
    nyendrow += 1; // because of the `<` condition
    nyendcol += 1;
    nystartcol -= nystartcol & 1;
    nystartrow = nystartrow.max(8);
    nyendrow = nyendrow.min(rr1 - 8);
    nystartcol = nystartcol.max(8);
    nyendcol = nyendcol.min(cc1 - 8);
    for e in &mut t.nyquist2[(4 * TSH) as usize..((TS - 4) * TSH) as usize] {
      *e = 0;
    }

    // "if most of your neighbours are named Nyquist, it's likely that you're
    // one too, or not" (`amaze:918-924`)
    for rr in nystartrow..nyendrow {
      let mut indx = rr * TS + nystartcol + (cfa.fc_i(rr, 2) & 1) as i32;
      while indx < rr * TS + nyendcol {
        let nyquisttemp = t.nyquist[(indx - V2) as usize >> 1] as u32
          + t.nyquist[(indx - M1) as usize >> 1] as u32
          + t.nyquist[(indx + P1) as usize >> 1] as u32
          + t.nyquist[(indx - 2) as usize >> 1] as u32
          + t.nyquist[(indx + 2) as usize >> 1] as u32
          + t.nyquist[(indx - P1) as usize >> 1] as u32
          + t.nyquist[(indx + M1) as usize >> 1] as u32
          + t.nyquist[(indx + V2) as usize >> 1] as u32;
        let keep = t.nyquist[indx as usize >> 1];
        t.nyquist2[indx as usize >> 1] = if nyquisttemp > 4 { 1 } else if nyquisttemp < 4 { 0 } else { keep };
        indx += 2;
      }
    }

    // area interpolation in the Nyquist regions (`amaze:932-967`)
    for rr in nystartrow..nyendrow {
      let mut indx = rr * TS + nystartcol + (cfa.fc_i(rr, 2) & 1) as i32;
      while indx < rr * TS + nyendcol {
        if t.nyquist2[indx as usize >> 1] != 0 {
          let mut sumcfa = 0.0_f32;
          let mut sumh = 0.0_f32;
          let mut sumv = 0.0_f32;
          let mut sumsqh = 0.0_f32;
          let mut sumsqv = 0.0_f32;
          let mut areawt = 0.0_f32;

          let mut i = -6;
          while i < 7 {
            let mut indx1 = indx + i * TS - 6;
            let mut j = -6;
            while j < 7 {
              if t.nyquist2[indx1 as usize >> 1] != 0 {
                let cfatemp = t.cfa[indx1 as usize];
                sumcfa += cfatemp;
                sumh += t.cfa[indx1 as usize - 1] + t.cfa[indx1 as usize + 1];
                sumv += t.cfa[indx1 as usize - V1 as usize] + t.cfa[indx1 as usize + V1 as usize];
                sumsqh += sqr(cfatemp - t.cfa[indx1 as usize - 1]) + sqr(cfatemp - t.cfa[indx1 as usize + 1]);
                sumsqv += sqr(cfatemp - t.cfa[indx1 as usize - V1 as usize]) + sqr(cfatemp - t.cfa[indx1 as usize + V1 as usize]);
                areawt += 1.0;
              }
              indx1 += 2;
              j += 2;
            }
            i += 2;
          }

          sumh = sumcfa - xdiv2f(sumh);
          sumv = sumcfa - xdiv2f(sumv);
          areawt = xdiv2f(areawt);
          let hcdvar = EPS_SQ + abs(areawt * sumsqh - sumh * sumh);
          let vcdvar = EPS_SQ + abs(areawt * sumsqv - sumv * sumv);
          t.hvwt[indx as usize >> 1] = hcdvar / (vcdvar + hcdvar);
        }
        indx += 2;
      }
    }
  }

  // ---- populate green at the red/blue sites (`amaze:972-988`)
  for rr in 8..rr1 - 8 {
    let mut cc = 8 + (cfa.fc_i(rr, 2) & 1) as i32;
    while cc < cc1 - 8 {
      let indx = (rr * TS + cc) as usize;
      let pair = (indx as i32 >> 1) as usize;
      // first ask whether the neighbours discriminate better
      let hvwtalt = xdivf(
        t.hvwt[((indx as i32 - M1) >> 1) as usize]
          + t.hvwt[((indx as i32 + P1) >> 1) as usize]
          + t.hvwt[((indx as i32 - P1) >> 1) as usize]
          + t.hvwt[((indx as i32 + M1) >> 1) as usize],
        2,
      );
      if abs(0.5 - t.hvwt[pair]) < abs(0.5 - hvwtalt) {
        t.hvwt[pair] = hvwtalt;
      }

      t.dgrb0[pair] = intp(t.hvwt[pair], t.vcd[indx], t.hcd[indx]);
      t.rgbgreen[indx] = t.cfa[indx] + t.dgrb0[pair];

      // local curvature of green, for the Nyquist refinement below
      if t.nyquist2[pair] != 0 {
        t.dgrb2_h[pair] = sqr(t.rgbgreen[indx] - xdiv2f(t.rgbgreen[indx - 1] + t.rgbgreen[indx + 1]));
        t.dgrb2_v[pair] = sqr(t.rgbgreen[indx] - xdiv2f(t.rgbgreen[indx - V1 as usize] + t.rgbgreen[indx + V1 as usize]));
      } else {
        t.dgrb2_h[pair] = 0.0;
        t.dgrb2_v[pair] = 0.0;
      }

      cc += 2;
    }
  }

  // ---- refine the Nyquist areas from the green curvature (`amaze:994-1013`)
  if do_nyquist {
    for rr in nystartrow..nyendrow {
      let mut indx = rr * TS + nystartcol + (cfa.fc_i(rr, 2) & 1) as i32;
      while indx < rr * TS + nyendcol {
        let pair = (indx >> 1) as usize;
        if t.nyquist2[pair] != 0 {
          let gvarh = EPS_SQ
            + (GQUINC[0] * t.dgrb2_h[pair]
              + GQUINC[1]
                * (t.dgrb2_h[((indx - M1) >> 1) as usize]
                  + t.dgrb2_h[((indx + P1) >> 1) as usize]
                  + t.dgrb2_h[((indx - P1) >> 1) as usize]
                  + t.dgrb2_h[((indx + M1) >> 1) as usize])
              + GQUINC[2]
                * (t.dgrb2_h[((indx - V2) >> 1) as usize]
                  + t.dgrb2_h[((indx - 2) >> 1) as usize]
                  + t.dgrb2_h[((indx + 2) >> 1) as usize]
                  + t.dgrb2_h[((indx + V2) >> 1) as usize])
              + GQUINC[3]
                * (t.dgrb2_h[((indx - M2) >> 1) as usize]
                  + t.dgrb2_h[((indx + P2) >> 1) as usize]
                  + t.dgrb2_h[((indx - P2) >> 1) as usize]
                  + t.dgrb2_h[((indx + M2) >> 1) as usize]));
          let gvarv = EPS_SQ
            + (GQUINC[0] * t.dgrb2_v[pair]
              + GQUINC[1]
                * (t.dgrb2_v[((indx - M1) >> 1) as usize]
                  + t.dgrb2_v[((indx + P1) >> 1) as usize]
                  + t.dgrb2_v[((indx - P1) >> 1) as usize]
                  + t.dgrb2_v[((indx + M1) >> 1) as usize])
              + GQUINC[2]
                * (t.dgrb2_v[((indx - V2) >> 1) as usize]
                  + t.dgrb2_v[((indx - 2) >> 1) as usize]
                  + t.dgrb2_v[((indx + 2) >> 1) as usize]
                  + t.dgrb2_v[((indx + V2) >> 1) as usize])
              + GQUINC[3]
                * (t.dgrb2_v[((indx - M2) >> 1) as usize]
                  + t.dgrb2_v[((indx + P2) >> 1) as usize]
                  + t.dgrb2_v[((indx - P2) >> 1) as usize]
                  + t.dgrb2_v[((indx + M2) >> 1) as usize]));
          t.dgrb0[pair] = (t.hcd[indx as usize] * gvarv + t.vcd[indx as usize] * gvarh) / (gvarv + gvarh);
          t.rgbgreen[indx as usize] = t.cfa[indx as usize] + t.dgrb0[pair];
        }
        indx += 2;
      }
    }
  }

  // ---- diagonal gradients of the R-B differences (`amaze:1044-1060`)
  for rr in 6..rr1 - 6 {
    if (cfa.fc_i(rr, 2) & 1) == 0 {
      let mut cc = 6;
      while cc < cc1 - 6 {
        let indx = (rr * TS + cc) as usize;
        let pair = (indx as i32 >> 1) as usize;
        t.delp[pair] = abs(t.cfa[(indx as i32 + P1) as usize] - t.cfa[(indx as i32 - P1) as usize]);
        t.delm[pair] = abs(t.cfa[(indx as i32 + M1) as usize] - t.cfa[(indx as i32 - M1) as usize]);
        t.dgrbsq1p[pair] = sqr(t.cfa[indx + 1] - t.cfa[(indx as i32 + 1 - P1) as usize])
          + sqr(t.cfa[indx + 1] - t.cfa[(indx as i32 + 1 + P1) as usize]);
        t.dgrbsq1m[pair] = sqr(t.cfa[indx + 1] - t.cfa[(indx as i32 + 1 - M1) as usize])
          + sqr(t.cfa[indx + 1] - t.cfa[(indx as i32 + 1 + M1) as usize]);
        cc += 2;
      }
    } else {
      let mut cc = 6;
      while cc < cc1 - 6 {
        let indx = (rr * TS + cc) as usize;
        let pair = (indx as i32 >> 1) as usize;
        t.dgrbsq1p[pair] = sqr(t.cfa[indx] - t.cfa[(indx as i32 - P1) as usize]) + sqr(t.cfa[indx] - t.cfa[(indx as i32 + P1) as usize]);
        t.dgrbsq1m[pair] = sqr(t.cfa[indx] - t.cfa[(indx as i32 - M1) as usize]) + sqr(t.cfa[indx] - t.cfa[(indx as i32 + M1) as usize]);
        t.delp[pair] = abs(t.cfa[(indx as i32 + 1 + P1) as usize] - t.cfa[(indx as i32 + 1 - P1) as usize]);
        t.delm[pair] = abs(t.cfa[(indx as i32 + 1 + M1) as usize] - t.cfa[(indx as i32 + 1 - M1) as usize]);
        cc += 2;
      }
    }
  }

  // ---- diagonal interpolation correction (`amaze:1139-1218`)
  for rr in 8..rr1 - 8 {
    let mut cc = 8 + (cfa.fc_i(rr, 2) & 1) as i32;
    while cc < cc1 - 8 {
      let indx = (rr * TS + cc) as usize;
      let indx1 = (indx as i32 >> 1) as usize;
      let c = t.cfa[indx];

      // diagonal colour ratios
      let crse = xmul2f(t.cfa[(indx as i32 + M1) as usize]) / (EPS + c + t.cfa[(indx as i32 + M2) as usize]);
      let crnw = xmul2f(t.cfa[(indx as i32 - M1) as usize]) / (EPS + c + t.cfa[(indx as i32 - M2) as usize]);
      let crne = xmul2f(t.cfa[(indx as i32 + P1) as usize]) / (EPS + c + t.cfa[(indx as i32 + P2) as usize]);
      let crsw = xmul2f(t.cfa[(indx as i32 - P1) as usize]) / (EPS + c + t.cfa[(indx as i32 - P2) as usize]);
      let (rbse, rbnw, rbne, rbsw);

      if abs(1.0 - crse) < ARTHRESH {
        rbse = c * crse;
      } else {
        rbse = t.cfa[(indx as i32 + M1) as usize] + xdiv2f(c - t.cfa[(indx as i32 + M2) as usize]);
      }
      if abs(1.0 - crnw) < ARTHRESH {
        rbnw = c * crnw;
      } else {
        rbnw = t.cfa[(indx as i32 - M1) as usize] + xdiv2f(c - t.cfa[(indx as i32 - M2) as usize]);
      }
      if abs(1.0 - crne) < ARTHRESH {
        rbne = c * crne;
      } else {
        rbne = t.cfa[(indx as i32 + P1) as usize] + xdiv2f(c - t.cfa[(indx as i32 + P2) as usize]);
      }
      if abs(1.0 - crsw) < ARTHRESH {
        rbsw = c * crsw;
      } else {
        rbsw = t.cfa[(indx as i32 - P1) as usize] + xdiv2f(c - t.cfa[(indx as i32 - P2) as usize]);
      }

      let wtse = EPS + t.delm[indx1] + t.delm[((indx as i32 + M1) >> 1) as usize] + t.delm[((indx as i32 + M2) >> 1) as usize];
      let wtnw = EPS + t.delm[indx1] + t.delm[((indx as i32 - M1) >> 1) as usize] + t.delm[((indx as i32 - M2) >> 1) as usize];
      let wtne = EPS + t.delp[indx1] + t.delp[((indx as i32 + P1) >> 1) as usize] + t.delp[((indx as i32 + P2) >> 1) as usize];
      let wtsw = EPS + t.delp[indx1] + t.delp[((indx as i32 - P1) >> 1) as usize] + t.delp[((indx as i32 - P2) >> 1) as usize];

      t.rbm[indx1] = (wtse * rbnw + wtnw * rbse) / (wtse + wtnw);
      t.rbp[indx1] = (wtne * rbsw + wtsw * rbne) / (wtne + wtsw);

      // variance of R-B along the plus and minus diagonals
      let rbvarm = EPS_SQ
        + (GAUSS_EVEN[0]
          * (t.dgrbsq1m[((indx as i32 - V1) >> 1) as usize]
            + t.dgrbsq1m[((indx as i32 - 1) >> 1) as usize]
            + t.dgrbsq1m[((indx as i32 + 1) >> 1) as usize]
            + t.dgrbsq1m[((indx as i32 + V1) >> 1) as usize])
          + GAUSS_EVEN[1]
            * (t.dgrbsq1m[((indx as i32 - V2 - 1) >> 1) as usize]
              + t.dgrbsq1m[((indx as i32 - V2 + 1) >> 1) as usize]
              + t.dgrbsq1m[((indx as i32 - 2 - V1) >> 1) as usize]
              + t.dgrbsq1m[((indx as i32 + 2 - V1) >> 1) as usize]
              + t.dgrbsq1m[((indx as i32 - 2 + V1) >> 1) as usize]
              + t.dgrbsq1m[((indx as i32 + 2 + V1) >> 1) as usize]
              + t.dgrbsq1m[((indx as i32 + V2 - 1) >> 1) as usize]
              + t.dgrbsq1m[((indx as i32 + V2 + 1) >> 1) as usize]));
      t.pmwt[indx1] = rbvarm
        / ((EPS_SQ
          + (GAUSS_EVEN[0]
            * (t.dgrbsq1p[((indx as i32 - V1) >> 1) as usize]
              + t.dgrbsq1p[((indx as i32 - 1) >> 1) as usize]
              + t.dgrbsq1p[((indx as i32 + 1) >> 1) as usize]
              + t.dgrbsq1p[((indx as i32 + V1) >> 1) as usize])
            + GAUSS_EVEN[1]
              * (t.dgrbsq1p[((indx as i32 - V2 - 1) >> 1) as usize]
                + t.dgrbsq1p[((indx as i32 - V2 + 1) >> 1) as usize]
                + t.dgrbsq1p[((indx as i32 - 2 - V1) >> 1) as usize]
                + t.dgrbsq1p[((indx as i32 + 2 - V1) >> 1) as usize]
                + t.dgrbsq1p[((indx as i32 - 2 + V1) >> 1) as usize]
                + t.dgrbsq1p[((indx as i32 + 2 + V1) >> 1) as usize]
                + t.dgrbsq1p[((indx as i32 + V2 - 1) >> 1) as usize]
                + t.dgrbsq1p[((indx as i32 + V2 + 1) >> 1) as usize])))
          + rbvarm);

      // bound the interpolation in regions of high saturation
      if t.rbp[indx1] < c {
        if xmul2f(t.rbp[indx1]) < c {
          t.rbp[indx1] = median3(t.rbp[indx1], t.cfa[(indx as i32 - P1) as usize], t.cfa[(indx as i32 + P1) as usize]);
        } else {
          let pwt = xmul2f(c - t.rbp[indx1]) / (EPS + t.rbp[indx1] + c);
          let alt = median3(t.rbp[indx1], t.cfa[(indx as i32 - P1) as usize], t.cfa[(indx as i32 + P1) as usize]);
          t.rbp[indx1] = pwt * t.rbp[indx1] + (1.0 - pwt) * alt;
        }
      }
      if t.rbm[indx1] < c {
        if xmul2f(t.rbm[indx1]) < c {
          t.rbm[indx1] = median3(t.rbm[indx1], t.cfa[(indx as i32 - M1) as usize], t.cfa[(indx as i32 + M1) as usize]);
        } else {
          let mwt = xmul2f(c - t.rbm[indx1]) / (EPS + t.rbm[indx1] + c);
          let alt = median3(t.rbm[indx1], t.cfa[(indx as i32 - M1) as usize], t.cfa[(indx as i32 + M1) as usize]);
          t.rbm[indx1] = mwt * t.rbm[indx1] + (1.0 - mwt) * alt;
        }
      }
      if t.rbp[indx1] > CLIP_PT {
        t.rbp[indx1] = median3(t.rbp[indx1], t.cfa[(indx as i32 - P1) as usize], t.cfa[(indx as i32 + P1) as usize]);
      }
      if t.rbm[indx1] > CLIP_PT {
        t.rbm[indx1] = median3(t.rbm[indx1], t.cfa[(indx as i32 - M1) as usize], t.cfa[(indx as i32 + M1) as usize]);
      }

      cc += 2;
    }
  }

  // ---- interpolated R+B (`amaze:1241-1251`)
  for rr in 10..rr1 - 10 {
    let mut cc = 10 + (cfa.fc_i(rr, 2) & 1) as i32;
    while cc < cc1 - 10 {
      let indx = (rr * TS + cc) as usize;
      let indx1 = (indx as i32 >> 1) as usize;
      // first ask whether the neighbours discriminate better
      let pmwtalt = xdivf(
        t.pmwt[((indx as i32 - M1) >> 1) as usize]
          + t.pmwt[((indx as i32 + P1) >> 1) as usize]
          + t.pmwt[((indx as i32 - P1) >> 1) as usize]
          + t.pmwt[((indx as i32 + M1) >> 1) as usize],
        2,
      );
      if abs(0.5 - t.pmwt[indx1]) < abs(0.5 - pmwtalt) {
        t.pmwt[indx1] = pmwtalt;
      }
      t.rbint[indx1] = xdiv2f(t.cfa[indx] + t.rbm[indx1] * (1.0 - t.pmwt[indx1]) + t.rbp[indx1] * t.pmwt[indx1]);
      cc += 2;
    }
  }

  // ---- green again, this time from the interpolated R+B (`amaze:1312-1388`)
  for rr in 12..rr1 - 12 {
    let mut cc = 12 + (cfa.fc_i(rr, 2) & 1) as i32;
    while cc < cc1 - 12 {
      let indx = (rr * TS + cc) as usize;
      let indx1 = (indx as i32 >> 1) as usize;
      if abs(0.5 - t.pmwt[indx1]) < abs(0.5 - t.hvwt[indx1]) {
        cc += 2;
        continue;
      }

      let rbint = t.rbint[indx1];
      // ⚠️ These four subscripts are `indx1 ± v1` — the *half-resolution* index
      // space — not `(indx ± v1) >> 1`. That is upstream's own expression
      // (`amaze:1322-1325`, and the SSE path at `:1267-1293` agrees), and it is
      // two rows, not one: in the pair grid a step of `ts` spans two tile rows.
      // Every other neighbour lookup in this kernel goes the other way round
      // (`(indx ± m1) >> 1`), so this one is easy to "fix" by accident.
      let cru = t.cfa[indx - V1 as usize] * 2.0 / (EPS + rbint + t.rbint[(indx1 as i32 - V1) as usize]);
      let crd = t.cfa[indx + V1 as usize] * 2.0 / (EPS + rbint + t.rbint[(indx1 as i32 + V1) as usize]);
      let crl = t.cfa[indx - 1] * 2.0 / (EPS + rbint + t.rbint[(indx1 as i32 - 1) as usize]);
      let crr = t.cfa[indx + 1] * 2.0 / (EPS + rbint + t.rbint[(indx1 as i32 + 1) as usize]);

      let gu = if abs(1.0 - cru) < ARTHRESH {
        rbint * cru
      } else {
        t.cfa[indx - V1 as usize] + xdiv2f(rbint - t.rbint[(indx1 as i32 - V1) as usize])
      };
      let gd = if abs(1.0 - crd) < ARTHRESH {
        rbint * crd
      } else {
        t.cfa[indx + V1 as usize] + xdiv2f(rbint - t.rbint[(indx1 as i32 + V1) as usize])
      };
      let gl = if abs(1.0 - crl) < ARTHRESH {
        rbint * crl
      } else {
        t.cfa[indx - 1] + xdiv2f(rbint - t.rbint[(indx1 as i32 - 1) as usize])
      };
      let gr = if abs(1.0 - crr) < ARTHRESH {
        rbint * crr
      } else {
        t.cfa[indx + 1] + xdiv2f(rbint - t.rbint[(indx1 as i32 + 1) as usize])
      };

      let mut gintv = (t.dirwts0[indx - V1 as usize] * gd + t.dirwts0[indx + V1 as usize] * gu)
        / (t.dirwts0[indx + V1 as usize] + t.dirwts0[indx - V1 as usize]);
      let mut ginth = (t.dirwts1[indx - 1] * gr + t.dirwts1[indx + 1] * gl) / (t.dirwts1[indx - 1] + t.dirwts1[indx + 1]);

      if gintv < rbint {
        if 2.0 * gintv < rbint {
          gintv = median3(gintv, t.cfa[indx - V1 as usize], t.cfa[indx + V1 as usize]);
        } else {
          let vwt = 2.0 * (rbint - gintv) / (EPS + gintv + rbint);
          let alt = median3(gintv, t.cfa[indx - V1 as usize], t.cfa[indx + V1 as usize]);
          gintv = vwt * gintv + (1.0 - vwt) * alt;
        }
      }
      if ginth < rbint {
        if 2.0 * ginth < rbint {
          ginth = median3(ginth, t.cfa[indx - 1], t.cfa[indx + 1]);
        } else {
          let hwt = 2.0 * (rbint - ginth) / (EPS + ginth + rbint);
          let alt = median3(ginth, t.cfa[indx - 1], t.cfa[indx + 1]);
          ginth = hwt * ginth + (1.0 - hwt) * alt;
        }
      }
      if ginth > CLIP_PT {
        ginth = median3(ginth, t.cfa[indx - 1], t.cfa[indx + 1]);
      }
      if gintv > CLIP_PT {
        gintv = median3(gintv, t.cfa[indx - V1 as usize], t.cfa[indx + V1 as usize]);
      }

      t.rgbgreen[indx] = ginth * (1.0 - t.hvwt[indx1]) + gintv * t.hvwt[indx1];
      t.dgrb0[indx1] = t.rgbgreen[indx] - t.cfa[indx];

      cc += 2;
    }
  }

  // ---- split G-B out of G-R (`amaze:1396-1400`)
  // `(ey, ex)` is the red site, so these rows/columns are the blue coset.
  let mut rr = 13 - ey;
  while rr < rr1 - 12 {
    let mut pair = ((rr * TS + 13 - ex) >> 1) as usize;
    let end = ((rr * TS + cc1 - 12) >> 1) as usize;
    while pair < end {
      t.dgrb1[pair] = t.dgrb0[pair];
      t.dgrb0[pair] = 0.0;
      pair += 1;
    }
    rr += 2;
  }

  // ---- fancy chrominance interpolation (`amaze:1426-1436`)
  for rr in 14..rr1 - 14 {
    let mut cc = 14 + (cfa.fc_i(rr, 2) & 1) as i32;
    // `c = 1 - fc / 2`: dcraw codes R = 0 and B = 2, so `c` is **1 at a red site
    // and 0 at a blue one** — it names the plane this site does *not* sample. A
    // red site's own difference is G-R (`dgrb0`), so it refines `dgrb1` (G-B),
    // and its four diagonal neighbours are all blue, which is exactly where G-B
    // lives. Upstream evaluates `c` once, in the `for` initialiser: `cc` steps
    // by 2, so the colour cannot change along the row.
    let c = 1 - cfa.fc_i(rr, cc) / 2;
    while cc < cc1 - 14 {
      let indx = (rr * TS + cc) as usize;
      let wtnw = 1.0
        / (EPS
          + abs(t.plane(c)[((indx as i32 - M1) >> 1) as usize] - t.plane(c)[((indx as i32 + M1) >> 1) as usize])
          + abs(t.plane(c)[((indx as i32 - M1) >> 1) as usize] - t.plane(c)[((indx as i32 - M3) >> 1) as usize])
          + abs(t.plane(c)[((indx as i32 + M1) >> 1) as usize] - t.plane(c)[((indx as i32 - M3) >> 1) as usize]));
      let wtne = 1.0
        / (EPS
          + abs(t.plane(c)[((indx as i32 + P1) >> 1) as usize] - t.plane(c)[((indx as i32 - P1) >> 1) as usize])
          + abs(t.plane(c)[((indx as i32 + P1) >> 1) as usize] - t.plane(c)[((indx as i32 + P3) >> 1) as usize])
          + abs(t.plane(c)[((indx as i32 - P1) >> 1) as usize] - t.plane(c)[((indx as i32 + P3) >> 1) as usize]));
      let wtsw = 1.0
        / (EPS
          + abs(t.plane(c)[((indx as i32 - P1) >> 1) as usize] - t.plane(c)[((indx as i32 + P1) >> 1) as usize])
          + abs(t.plane(c)[((indx as i32 - P1) >> 1) as usize] - t.plane(c)[((indx as i32 + M3) >> 1) as usize])
          + abs(t.plane(c)[((indx as i32 + P1) >> 1) as usize] - t.plane(c)[((indx as i32 - P3) >> 1) as usize]));
      let wtse = 1.0
        / (EPS
          + abs(t.plane(c)[((indx as i32 + M1) >> 1) as usize] - t.plane(c)[((indx as i32 - M1) >> 1) as usize])
          + abs(t.plane(c)[((indx as i32 + M1) >> 1) as usize] - t.plane(c)[((indx as i32 - P3) >> 1) as usize])
          + abs(t.plane(c)[((indx as i32 - M1) >> 1) as usize] - t.plane(c)[((indx as i32 + M3) >> 1) as usize]));

      let value = (wtnw
        * (1.325 * t.plane(c)[((indx as i32 - M1) >> 1) as usize]
          - 0.175 * t.plane(c)[((indx as i32 - M3) >> 1) as usize]
          - 0.075 * t.plane(c)[((indx as i32 - M1 - 2) >> 1) as usize]
          - 0.075 * t.plane(c)[((indx as i32 - M1 - V2) >> 1) as usize])
        + wtne
          * (1.325 * t.plane(c)[((indx as i32 + P1) >> 1) as usize]
            - 0.175 * t.plane(c)[((indx as i32 + P3) >> 1) as usize]
            - 0.075 * t.plane(c)[((indx as i32 + P1 + 2) >> 1) as usize]
            - 0.075 * t.plane(c)[((indx as i32 + P1 + V2) >> 1) as usize])
        + wtsw
          * (1.325 * t.plane(c)[((indx as i32 - P1) >> 1) as usize]
            - 0.175 * t.plane(c)[((indx as i32 - P3) >> 1) as usize]
            - 0.075 * t.plane(c)[((indx as i32 - P1 - 2) >> 1) as usize]
            - 0.075 * t.plane(c)[((indx as i32 - P1 - V2) >> 1) as usize])
        + wtse
          * (1.325 * t.plane(c)[((indx as i32 + M1) >> 1) as usize]
            - 0.175 * t.plane(c)[((indx as i32 + M3) >> 1) as usize]
            - 0.075 * t.plane(c)[((indx as i32 + M1 + 2) >> 1) as usize]
            - 0.075 * t.plane(c)[((indx as i32 + M1 + V2) >> 1) as usize]))
        / (wtnw + wtne + wtsw + wtse);

      t.plane_mut(c)[(indx as i32 >> 1) as usize] = value;

      cc += 2;
    }
  }

  // ---- write red and blue (`amaze:1455-1562`)
  for rr in 16..rr1 - 16 {
    let row = rr + top;
    let mut col = left + 16;
    let mut indx = rr * TS + 16;
    if (cfa.fc_i(rr, 2) & 1) == 1 {
      // even columns are green, so the *odd* one is the sampled site
      while indx < rr * TS + cc1 - 16 - (cc1 & 1) {
        let p = (((indx - V1) >> 1) as usize, ((indx + 1) >> 1) as usize, ((indx - 1) >> 1) as usize, ((indx + V1) >> 1) as usize);
        let temp = 1.0 / (t.hvwt[p.0] + 2.0 - t.hvwt[p.1] - t.hvwt[p.2] + t.hvwt[p.3]);
        let g = t.rgbgreen[indx as usize];
        let r = g - (t.hvwt[p.0] * t.dgrb0[p.0] + (1.0 - t.hvwt[p.1]) * t.dgrb0[p.1] + (1.0 - t.hvwt[p.2]) * t.dgrb0[p.2] + t.hvwt[p.3] * t.dgrb0[p.3]) * temp;
        let b = g - (t.hvwt[p.0] * t.dgrb1[p.0] + (1.0 - t.hvwt[p.1]) * t.dgrb1[p.1] + (1.0 - t.hvwt[p.2]) * t.dgrb1[p.2] + t.hvwt[p.3] * t.dgrb1[p.3]) * temp;
        out.set_red(row, col, r);
        out.set_blue(row, col, b);
        indx += 1;
        col += 1;
        let g = t.rgbgreen[indx as usize];
        out.set_red(row, col, g - t.dgrb0[(indx >> 1) as usize]);
        out.set_blue(row, col, g - t.dgrb1[(indx >> 1) as usize]);
        indx += 1;
        col += 1;
      }
      // The odd-`cc1` tail pixel can sit past the frame's right edge when the
      // tile is clipped there (`col >= wi`). Upstream writes it anyway — the
      // spill lands in the next row's first column and is overwritten by that
      // row's own pass — so dropping it here is output-identical.
      if cc1 & 1 != 0 && col < wi {
        let p = (((indx - V1) >> 1) as usize, ((indx + 1) >> 1) as usize, ((indx - 1) >> 1) as usize, ((indx + V1) >> 1) as usize);
        let temp = 1.0 / (t.hvwt[p.0] + 2.0 - t.hvwt[p.1] - t.hvwt[p.2] + t.hvwt[p.3]);
        let g = t.rgbgreen[indx as usize];
        let r = g - (t.hvwt[p.0] * t.dgrb0[p.0] + (1.0 - t.hvwt[p.1]) * t.dgrb0[p.1] + (1.0 - t.hvwt[p.2]) * t.dgrb0[p.2] + t.hvwt[p.3] * t.dgrb0[p.3]) * temp;
        let b = g - (t.hvwt[p.0] * t.dgrb1[p.0] + (1.0 - t.hvwt[p.1]) * t.dgrb1[p.1] + (1.0 - t.hvwt[p.2]) * t.dgrb1[p.2] + t.hvwt[p.3] * t.dgrb1[p.3]) * temp;
        out.set_red(row, col, r);
        out.set_blue(row, col, b);
      }
    } else {
      // even columns are the sampled site
      while indx < rr * TS + cc1 - 16 - (cc1 & 1) {
        let g = t.rgbgreen[indx as usize];
        out.set_red(row, col, g - t.dgrb0[(indx >> 1) as usize]);
        out.set_blue(row, col, g - t.dgrb1[(indx >> 1) as usize]);
        indx += 1;
        col += 1;
        let p = (((indx - V1) >> 1) as usize, ((indx + 1) >> 1) as usize, ((indx - 1) >> 1) as usize, ((indx + V1) >> 1) as usize);
        let temp = 1.0 / (t.hvwt[p.0] + 2.0 - t.hvwt[p.1] - t.hvwt[p.2] + t.hvwt[p.3]);
        let g = t.rgbgreen[indx as usize];
        let r = g - (t.hvwt[p.0] * t.dgrb0[p.0] + (1.0 - t.hvwt[p.1]) * t.dgrb0[p.1] + (1.0 - t.hvwt[p.2]) * t.dgrb0[p.2] + t.hvwt[p.3] * t.dgrb0[p.3]) * temp;
        let b = g - (t.hvwt[p.0] * t.dgrb1[p.0] + (1.0 - t.hvwt[p.1]) * t.dgrb1[p.1] + (1.0 - t.hvwt[p.2]) * t.dgrb1[p.2] + t.hvwt[p.3] * t.dgrb1[p.3]) * temp;
        out.set_red(row, col, r);
        out.set_blue(row, col, b);
        indx += 1;
        col += 1;
      }
      // See the sibling tail above: the tail pixel can sit past the frame's
      // right edge for a clipped tile, and dropping it is output-identical.
      if cc1 & 1 != 0 && col < wi {
        let g = t.rgbgreen[indx as usize];
        out.set_red(row, col, g - t.dgrb0[(indx >> 1) as usize]);
        out.set_blue(row, col, g - t.dgrb1[(indx >> 1) as usize]);
      }
    }
  }

  // ---- write green (`amaze:1565-1579`)
  for rr in 16..rr1 - 16 {
    let row = rr + top;
    for cc in 16..cc1 - 16 {
      out.set_green(row, cc + left, t.rgbgreen[(rr * TS + cc) as usize]);
    }
  }
}

impl Tile {
  /// `Dgrb[c]` — `0` is G-R, `1` is G-B.
  #[inline(always)]
  fn plane(&self, c: u32) -> &[f32] {
    if c == 0 {
      &self.dgrb0
    } else {
      &self.dgrb1
    }
  }

  #[inline(always)]
  fn plane_mut(&mut self, c: u32) -> &mut [f32] {
    if c == 0 {
      &mut self.dgrb0
    } else {
      &mut self.dgrb1
    }
  }
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::cfa::CfaDesc;

  /// The four Bayer orderings as 2x2 dcraw tiles.
  const ORDERS: [[[u8; 2]; 2]; 4] = [
    [[0, 1], [1, 2]], // RGGB
    [[1, 0], [2, 1]], // GRBG
    [[1, 2], [0, 1]], // GBRG
    [[2, 1], [1, 0]], // BGGR
  ];

  /// A constant frame: every estimate is a no-op, so the whole output — borders
  /// included, which is what proves the 16-pixel mirror is right — must be the
  /// sample value.
  ///
  /// "No-op" is only true to within [`EPS`], though: the `eps` floor inside the
  /// colour-ratio denominators makes `cr ≈ c/(eps+c)` rather than 1, and that
  /// O(eps) ≈ 1e-5 bias survives every pass that follows. It is by design —
  /// one 16-bit LSB is 1.5e-5, so upstream never saw it — and the tolerance
  /// below is two LSBs, still five orders of magnitude below what any real
  /// defect (a dropped pass, a flipped sign) produces.
  const FLAT_TOL: f32 = 3.0 / 65535.0;

  fn flat(w: usize, h: usize, v: f32) -> Array2D<f32> {
    Array2D::filled(w, h, v)
  }

  #[test]
  fn a_flat_field_is_a_fixed_point_for_every_bayer_order() {
    for pattern in ORDERS {
      let c = CfaDesc::bayer_from_2x2(pattern);
      for v in [0.2_f32, 0.5, 0.7] {
        let out = bayer_amaze_demosaic(&c, &flat(48, 48, v)).expect("amaze");
        for plane in [&out.red, &out.green, &out.blue] {
          for &x in plane.as_slice() {
            assert!((x - v).abs() < FLAT_TOL, "pattern {pattern:?} v {v}: got {x}");
          }
        }
      }
    }
  }

  /// Green is the one channel that is *never* re-estimated at a green site: the
  /// tile starts `rgbgreen` at the sample, and the two passes that overwrite it
  /// only visit the other coset. So the green plane must still hold the mosaic
  /// wherever the CFA says green.
  #[test]
  fn green_samples_survive_at_green_sites() {
    for pattern in ORDERS {
      let c = CfaDesc::bayer_from_2x2(pattern);
      let mut raw = Array2D::new(48, 48);
      for r in 0..48 {
        for col in 0..48 {
          raw.set(r, col, ((r * 7 + col * 13) % 11) as f32 / 20.0 + 0.05);
        }
      }
      let out = bayer_amaze_demosaic(&c, &raw).expect("amaze");
      let mut checked = 0;
      for r in 0..48 {
        for col in 0..48 {
          if c.is_green(r, col) {
            let got = out.green.at(r, col);
            let want = raw.at(r, col);
            assert!((got - want).abs() < 1e-5, "pattern {pattern:?} at {r},{col}: {got} != {want}");
            checked += 1;
          }
        }
      }
      assert!(checked > 100, "the CFA never said green?");
    }
  }

  /// The output must be finite and non-negative even when the highlight paths
  /// (clip_pt / clip_pt8 and the medians that follow them) all fire.
  #[test]
  fn saturated_input_stays_finite_and_non_negative() {
    for pattern in ORDERS {
      let c = CfaDesc::bayer_from_2x2(pattern);
      let out = bayer_amaze_demosaic(&c, &flat(48, 48, 1.0)).expect("amaze");
      for plane in [&out.red, &out.green, &out.blue] {
        for &x in plane.as_slice() {
          assert!(x.is_finite() && x >= 0.0, "pattern {pattern:?}: got {x}");
        }
      }
    }
  }

  /// A frame that is not constant must actually be interpolated: every pixel has
  /// to be filled in, including the outermost rows and columns (which no other
  /// kernel in this crate can claim — AMAZE tiles the frame with no border pass).
  #[test]
  fn every_pixel_is_filled_in() {
    let c = CfaDesc::bayer_from_2x2(ORDERS[0]);
    let mut raw = Array2D::filled(40, 40, 0.3);
    for r in 0..40 {
      for col in 0..40 {
        if (r / 4 + col / 4) % 2 == 0 {
          raw.set(r, col, 0.6);
        }
      }
    }
    let out = bayer_amaze_demosaic(&c, &raw).expect("amaze");
    for plane in [&out.red, &out.green, &out.blue] {
      for &x in plane.as_slice() {
        assert!(x > 0.0 && x.is_finite(), "unfilled pixel: {x}");
      }
    }

    // and it is not just a copy of the mosaic: the red/blue planes differ from
    // green at most pixels
    let differing = out.red.as_slice().iter().zip(out.green.as_slice()).filter(|(r, g)| (*r - *g).abs() > 1e-6).count();
    assert!(differing > out.red.len() / 2, "red is a copy of green: {differing}");
  }

  /// Frames whose height or width does not land on the 128-pixel tile pitch:
  /// the last band (or the last tile of a band) can then own **zero** output
  /// rows/columns, which upstream rides out with empty loops and this port
  /// must ride out without underflowing the band split.
  #[test]
  fn frames_off_the_tile_pitch_do_not_underflow() {
    let c = CfaDesc::bayer_from_2x2(ORDERS[0]);
    for (w, h) in [(160, 127), (160, 128), (160, 129), (127, 160), (129, 96), (96, 33)] {
      let out = bayer_amaze_demosaic(&c, &flat(w, h, 0.4)).expect("amaze");
      for plane in [&out.red, &out.green, &out.blue] {
        for &x in plane.as_slice() {
          assert!(x.is_finite() && x >= 0.0, "{w}x{h}: {x}");
        }
      }
    }
  }

  /// Below [`MIN_SIDE`] the mirrored border reads past the frame, so the kernel
  /// refuses instead of panicking.
  #[test]
  fn a_frame_smaller_than_the_border_is_refused() {
    let c = CfaDesc::bayer_from_2x2(ORDERS[0]);
    assert!(matches!(
      bayer_amaze_demosaic(&c, &flat(32, 48, 0.5)),
      Err(Error::Shape(_))
    ));
    assert!(matches!(
      bayer_amaze_demosaic(&c, &flat(48, 32, 0.5)),
      Err(Error::Shape(_))
    ));
  }

  /// Non-Bayer and four-colour CFAs are refused like every other kernel's.
  #[test]
  fn non_bayer_cfas_are_refused() {
    let x = CfaDesc::xtrans_from_6x6([[0; 6]; 6]);
    assert!(matches!(
      bayer_amaze_demosaic(&x, &flat(48, 48, 0.5)),
      Err(Error::UnsupportedCfa(_))
    ));
  }
}
