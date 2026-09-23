//! `interpolate_row_rb_mul_pp` — the red/blue row pass several Bayer kernels share.
//!
//! Ported from `external/RawTherapee/rtengine/rawimagesource_i.h:59-181`
//! (`RawImageSource::interpolate_row_rb_mul_pp`, Copyright (c) 2004-2010 Gabor
//! Horvath, GPL-3.0). It is `inline` upstream and called by `hphd_demosaic`
//! (`hphd_demosaic_RT.cc:352`) and `eahd_demosaic` (`eahd_demosaic.cc:438-442`).
//!
//! ## Fidelity notes
//!
//! * **The three multipliers are dropped as arguments.** Every caller passes
//!   `1.0` for `r_mul`/`g_mul`/`b_mul`, so this port hard-codes them to 1 and
//!   keeps only the terms that survive: `r_mul * raw` becomes `raw`. Nothing is
//!   reordered — the four diagonal terms are still summed in upstream's order
//!   before dividing by `n`.
//! * **`pg`/`ng` are `Option`.** Upstream tests `pg && ng` and does *nothing* when
//!   either is null (`EAHD` relies on that: it passes null for the row it has not
//!   computed yet). `Option<&[f32]>` is that test; `cg` is never null.
//! * **`width`/`skip` are dropped, `x1` is 0.** Both callers pass `x1 = 0`,
//!   `width = W`, `skip = 1`, which makes upstream's `jx` equal to `j` for the
//!   whole row and the `jx < width` test the same as `j < W`. Upstream still uses
//!   the *members* `W`/`H` for its bounds tests, so `w`/`h` are parameters here.
//! * **`std::max(0.f, …)`** is applied to both outputs, in both branches. It is
//!   the only clamp in the function, and it is why a chroma error large enough to
//!   push a channel below zero shows up as a flat zero rather than a negative
//!   value.
//! * The `j == 0` / `j == W-1` and `i == 0` / `i == H-1` special cases are kept
//!   even though `hphd_demosaic` (which only calls this for `i in 4..H-4`) never
//!   reaches them: `border_interpolate` overwrites those columns afterwards
//!   anyway, and `EAHD` calls this on rows 1 and `H-2`.
//!
//! Numerically the function is linear in `rawData` and `green`, apart from the
//! `max(0.f, …)` clamps, so it is domain-agnostic: it produces the same numbers
//! on a 0..1 mosaic as on RT's 0..65535 one.

use crate::array2d::Array2D;
use crate::cfa::CfaDesc;
use crate::math::max0;

/// Fill `ar`/`ab` (row `i` of the red and blue planes) from the mosaic and the
/// three green rows around it.
///
/// `pg` is green row `i-1`, `cg` green row `i`, `ng` green row `i+1`. All row
/// slices are `w` long; `h` is the frame height. When `pg` or `ng` is `None` the
/// function does nothing, matching upstream's `pg && ng` guard.
pub(crate) fn interpolate_row_rb_mul_pp(
  cfa: &CfaDesc,
  raw: &Array2D<f32>,
  ar: &mut [f32],
  ab: &mut [f32],
  pg: Option<&[f32]>,
  cg: &[f32],
  ng: Option<&[f32]>,
  i: usize,
  w: usize,
  h: usize,
) {
  // Upstream's `pg && ng` guard, written as a binding so the rest of the
  // function can use them without re-testing.
  let (pg, ng) = match (pg, ng) {
    (Some(pg), Some(ng)) => (pg, ng),
    _ => return,
  };

  // Upstream: `if ((ri->ISRED(i, 0) || ri->ISRED(i, 1)) && pg && ng)`. On a 2x2
  // Bayer CFA one of the first two pixels of a red row *is* red, so this is the
  // "RGRGR or GRGRGR" test; the other branch is "BGBGB or GBGBGB".
  if cfa.is_red(i, 0) || cfa.is_red(i, 1) {
    for j in 0..w {
      if cfa.is_red(i, j) {
        // red is simple
        ar[j] = raw.at(i, j);
        // blue: cross interpolation
        let mut b = 0.0f32;
        let mut n = 0i32;

        if i > 0 && j > 0 {
          b += raw.at(i - 1, j - 1) - pg[j - 1];
          n += 1;
        }
        if i > 0 && j < w - 1 {
          b += raw.at(i - 1, j + 1) - pg[j + 1];
          n += 1;
        }
        if i < h - 1 && j > 0 {
          b += raw.at(i + 1, j - 1) - ng[j - 1];
          n += 1;
        }
        if i < h - 1 && j < w - 1 {
          b += raw.at(i + 1, j + 1) - ng[j + 1];
          n += 1;
        }

        // `std::max(1, n)`: `n` is 0 only on a 1x1 frame, which no kernel reaches.
        b = cg[j] + b / (n.max(1) as f32);
        ab[j] = max0(b);
      } else {
        // linear R-G interp. horizontally
        let r = if j == 0 {
          cg[0] + raw.at(i, 1) - cg[1]
        } else if j == w - 1 {
          cg[w - 1] + raw.at(i, w - 2) - cg[w - 2]
        } else {
          cg[j] + (raw.at(i, j - 1) - cg[j - 1] + raw.at(i, j + 1) - cg[j + 1]) / 2.0
        };
        ar[j] = max0(r);
        // linear B-G interp. vertically
        let b = if i == 0 {
          ng[j] + raw.at(1, j) - cg[j]
        } else if i == h - 1 {
          pg[j] + raw.at(h - 2, j) - cg[j]
        } else {
          cg[j] + (raw.at(i - 1, j) - pg[j] + raw.at(i + 1, j) - ng[j]) / 2.0
        };
        ab[j] = max0(b);
      }
    }
  } else {
    for j in 0..w {
      if cfa.is_blue(i, j) {
        // blue is simple
        ab[j] = raw.at(i, j);
        // red: cross interpolation
        let mut r = 0.0f32;
        let mut n = 0i32;

        if i > 0 && j > 0 {
          r += raw.at(i - 1, j - 1) - pg[j - 1];
          n += 1;
        }
        if i > 0 && j < w - 1 {
          r += raw.at(i - 1, j + 1) - pg[j + 1];
          n += 1;
        }
        if i < h - 1 && j > 0 {
          r += raw.at(i + 1, j - 1) - ng[j - 1];
          n += 1;
        }
        if i < h - 1 && j < w - 1 {
          r += raw.at(i + 1, j + 1) - ng[j + 1];
          n += 1;
        }

        r = cg[j] + r / (n.max(1) as f32);
        ar[j] = max0(r);
      } else {
        // linear B-G interp. horizontally
        let b = if j == 0 {
          cg[0] + raw.at(i, 1) - cg[1]
        } else if j == w - 1 {
          cg[w - 1] + raw.at(i, w - 2) - cg[w - 2]
        } else {
          cg[j] + (raw.at(i, j - 1) - cg[j - 1] + raw.at(i, j + 1) - cg[j + 1]) / 2.0
        };
        ab[j] = max0(b);
        // linear R-G interp. vertically
        let r = if i == 0 {
          ng[j] + raw.at(1, j) - cg[j]
        } else if i == h - 1 {
          pg[j] + raw.at(h - 2, j) - cg[j]
        } else {
          cg[j] + (raw.at(i - 1, j) - pg[j] + raw.at(i + 1, j) - ng[j]) / 2.0
        };
        ar[j] = max0(r);
      }
    }
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  /// A flat mosaic with a flat green plane must leave red and blue flat too:
  /// every chroma difference is zero, so both cross interpolations return
  /// `cg[j]` and both linear ones return `cg[j]` as well.
  #[test]
  fn flat_planes_reproduce_green() {
    let (w, h) = (16usize, 16usize);
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let raw = Array2D::filled(w, h, 0.4);
    let g = vec![0.4f32; w];

    for i in 1..h - 1 {
      let (mut ar, mut ab) = (vec![0.0f32; w], vec![0.0f32; w]);
      interpolate_row_rb_mul_pp(&cfa, &raw, &mut ar, &mut ab, Some(&g), &g, Some(&g), i, w, h);
      for j in 0..w {
        assert!((ar[j] - 0.4).abs() < 1e-6, "R at {i},{j} = {}", ar[j]);
        assert!((ab[j] - 0.4).abs() < 1e-6, "B at {i},{j} = {}", ab[j]);
      }
    }
  }

  /// The CFA-sampled channel is copied verbatim, which is the property the
  /// kernels rely on: a saturated sample survives the red/blue pass untouched.
  #[test]
  fn the_sampled_channel_is_copied() {
    let (w, h) = (16usize, 16usize);
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let mut raw = Array2D::new(w, h);
    raw.set(8, 8, 1.0);
    let g = vec![0.0f32; w];

    let (mut ar, mut ab) = (vec![0.0f32; w], vec![0.0f32; w]);
    interpolate_row_rb_mul_pp(&cfa, &raw, &mut ar, &mut ab, Some(&g), &g, Some(&g), 8, w, h);
    // RGGB, row 8 even, col 8 even => red.
    assert_eq!(cfa.fc(8, 8), 0);
    assert_eq!(ar[8], 1.0);
  }

  /// `pg`/`ng` = `None` is upstream's `pg && ng` guard: the row is left alone.
  #[test]
  fn a_missing_neighbour_row_is_a_no_op() {
    let (w, h) = (16usize, 16usize);
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let raw = Array2D::filled(w, h, 0.4);
    let g = vec![0.4f32; w];

    let (mut ar, mut ab) = (vec![0.0f32; w], vec![0.0f32; w]);
    interpolate_row_rb_mul_pp(&cfa, &raw, &mut ar, &mut ab, None, &g, Some(&g), 8, w, h);
    assert!(ar.iter().all(|&x| x == 0.0) && ab.iter().all(|&x| x == 0.0));
    interpolate_row_rb_mul_pp(&cfa, &raw, &mut ar, &mut ab, Some(&g), &g, None, 8, w, h);
    assert!(ar.iter().all(|&x| x == 0.0) && ab.iter().all(|&x| x == 0.0));
  }

  /// A blue row swaps the roles: the sampled channel is blue and the
  /// cross-interpolated one is red.
  #[test]
  fn a_blue_row_swaps_the_channels() {
    let (w, h) = (16usize, 16usize);
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let mut raw = Array2D::new(w, h);
    // Row 9 is a blue row for RGGB (row 1 of the 2x2 tile).
    raw.set(9, 9, 1.0);
    let g = vec![0.0f32; w];

    let (mut ar, mut ab) = (vec![0.0f32; w], vec![0.0f32; w]);
    interpolate_row_rb_mul_pp(&cfa, &raw, &mut ar, &mut ab, Some(&g), &g, Some(&g), 9, w, h);
    assert_eq!(cfa.fc(9, 9), 2, "expected blue at 9,9 for RGGB");
    assert_eq!(ab[9], 1.0);
  }
}
