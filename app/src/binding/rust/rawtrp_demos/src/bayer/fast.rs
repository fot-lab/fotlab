//! FAST — Emil Martinec's fast Bayer demosaic.
//!
//! Ported from `external/RawTherapee/rtengine/fast_demo.cc`
//! (Copyright (c) 2008-2010 Emil Martinec, GPL-3.0) —
//! `RawImageSource::fast_demosaic()`, **scalar branch** (`#else` of the
//! `__SSE2__` guards; upstream's vectorised green pass differs in control
//! flow — it processes 4-lane groups and writes the tile buffers at every
//! position, relying on the third pass to overwrite the green sites — but
//! produces the same pixels).
//!
//! Structure: a three-sequence border fill (`bord = 5`: every row's first/last
//! five columns, then the first/last five rows' interior columns — the same
//! colour-mean scheme as `border_interpolate` but with its own, slightly
//! different clamping), then a tiled interior (`TS = 224`, advance `TS - 4`,
//! so consecutive tiles overlap by 4 and the writes interleave seamlessly):
//! gradient-weighted green at R/B sites, colour-difference R/B at R/B sites
//! (the diagonal-neighbour form with a `min(clip_pt, Σdiag)` highlight guard),
//! colour-difference R/B at G sites, write-out.
//!
//! ## Fidelity notes
//!
//! * **`clip_pt = 4.0`.** Upstream works in 16-bit units (`clip_pt = 4 * 65535 *
//!   initialGain`); the mosaic this crate hands over is 0..1, and with
//!   `initialGain = 1` (this kernel reads no per-image data beyond the CFA) that
//!   is exactly `4 * 65535 / 65535`. It enters only the saturation guard
//!   `min(clip_pt, Σ of the four diagonal raws)`.
//! * **No eps floors** — unlike AMAZE/LMMSE this kernel is exactly homogeneous
//!   of degree 1, so a flat field is an exact fixed point and the test can
//!   assert at `f32` epsilon.
//! * **The tiles read the raw frame directly** (only the tile buffers are
//!   per-band), so the pass-1 gradient stencils (`±3` samples) need the frame
//!   to have 3 pixels beyond the tile cover — guaranteed by `top/left ≥ 3` and
//!   `bottom/right ≤ H-3/W-3`.
//! * **Tiles are parallelised by row band** (one buffer set per band, tiles
//!   sequential left-to-right within a band), the same trade every tiled
//!   kernel in this crate makes: upstream hands each OpenMP thread one
//!   `3·TS·TS` buffer for a whole stripe of tiles.
//! * Upstream has no minimum-size guard, but the border windows index
//!   `j + 2 ≤ 6` (left) and `j - 1 ≥ W - 6` (right) unclamped, so frames
//!   narrower than 7 would read out of bounds in upstream too; this port
//!   refuses anything below `2 * bord` on either side instead.

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::cfa::CfaDesc;
use crate::math::max0;
use crate::{Error, Rgb};

const BORD: i32 = 5;
const TS: i32 = 224;
/// `16.0f / SQR(4.0f + i)` — upstream's `INVGRAD` gradient weight.
#[inline(always)]
fn invgrad(i: f32) -> f32 {
  let s = 4.0 + i;
  16.0 / (s * s)
}

/// The saturation guard threshold in the mosaic's 0..1 domain.
const CLIP_PT: f32 = 4.0;

/// Fill one border pixel: the CFA-sampled channel keeps the raw value, the
/// other two become the mean of their colour over the window
/// `[i_lo, i_hi) x [j_lo, j_hi)` of the raw frame.
///
/// This is the shared shape of `fast_demo.cc`'s four border sections — they
/// differ only in how the window is clamped, which the caller expresses by
/// passing already-clamped bounds (each section's own asymmetry preserved).
#[inline]
fn border_fill(
  cfa: &CfaDesc,
  raw: &Array2D<f32>,
  out: &mut Rgb,
  i: usize,
  j: usize,
  i_lo: i32,
  i_hi: i32,
  j_lo: i32,
  j_hi: i32,
) {
  let mut sum = [0.0f32; 6];
  for i1 in i_lo..i_hi {
    for j1 in j_lo..j_hi {
      let c = cfa.fc(i1 as usize, j1 as usize) as usize;
      sum[c] += raw.at(i1 as usize, j1 as usize);
      sum[c + 3] += 1.0;
    }
  }
  let (row, col) = (i, j);
  match cfa.fc(i, j) {
    1 => {
      out.red.row_mut(row)[col] = sum[0] / sum[3];
      out.green.row_mut(row)[col] = raw.at(i, j);
      out.blue.row_mut(row)[col] = sum[2] / sum[5];
    }
    0 => {
      out.green.row_mut(row)[col] = sum[1] / sum[4];
      out.red.row_mut(row)[col] = raw.at(i, j);
      out.blue.row_mut(row)[col] = sum[2] / sum[5];
    }
    _ => {
      out.green.row_mut(row)[col] = sum[1] / sum[4];
      out.red.row_mut(row)[col] = sum[0] / sum[3];
      out.blue.row_mut(row)[col] = raw.at(i, j);
    }
  }
}

/// Demosaic a Bayer mosaic with RawTherapee's `fast` kernel.
///
/// # Errors
/// [`Error::Shape`] if the mosaic is below `2 * bord = 10` on either side,
/// [`Error::UnsupportedCfa`] for a four-colour CFA.
pub fn bayer_fast_demosaic(cfa: &CfaDesc, raw: &Array2D<f32>) -> Result<Rgb, Error> {
  let (w, h) = (raw.width() as i32, raw.height() as i32);
  if w < 2 * BORD || h < 2 * BORD {
    return Err(Error::Shape(format!(
      "bayer_fast: mosaic too small: {w}x{h} (need >= {} per side)",
      2 * BORD
    )));
  }
  if cfa.has_fourth_colour() {
    // Upstream has no guard (fast is only reachable with an RGB CFA), but a
    // fourth colour would silently produce wrong colours, so refuse it and let
    // the caller fall back — the same treatment every ported kernel gives.
    return Err(Error::UnsupportedCfa("bayer_fast"));
  }

  let (w, h) = (w as usize, h as usize);
  let mut out = Rgb::new(w, h);

  // --- Border: every row's first/last BORD columns, then the interior
  // columns of the first/last BORD rows (`fast_demo.cc:108-259`). The four
  // sections keep their own clamping asymmetries verbatim.
  for i in 0..h {
    let imin = (i as i32 - 1).max(0);
    let imax = (i as i32 + 2).min(h as i32);
    for j in 0..BORD as usize {
      let jmin = (j as i32 - 1).max(0);
      border_fill(cfa, raw, &mut out, i, j, imin, imax, jmin, j as i32 + 2);
    }
    for j in w - BORD as usize..w {
      let jmax = (j as i32 + 2).min(w as i32);
      border_fill(cfa, raw, &mut out, i, j, imin, imax, j as i32 - 1, jmax);
    }
  }
  for j in BORD as usize..w - BORD as usize {
    for i in 0..BORD as usize {
      let imin = (i as i32 - 1).max(0);
      border_fill(cfa, raw, &mut out, i, j, imin, i as i32 + 2, j as i32 - 1, j as i32 + 2);
    }
    for i in h - BORD as usize..h {
      let imax = (i as i32 + 2).min(h as i32);
      border_fill(cfa, raw, &mut out, i, j, i as i32 - 1, imax, j as i32 - 1, j as i32 + 2);
    }
  }

  // --- Tiled interior (`fast_demo.cc:278-485`). Tiles start at `bord - 2`,
  // advance `TS - 4`, and are clipped to `H - bord + 2` / `W - bord + 2`;
  // each writes out `[top+2, bottom-2) x [left+2, right-2)`, so consecutive
  // tiles abut exactly and the union of border and tiles covers the frame.
  let tops: Vec<i32> = (BORD - 2..h as i32 - BORD + 2).step_by((TS - 4) as usize).collect();
  let lefts: Vec<i32> = (BORD - 2..w as i32 - BORD + 2).step_by((TS - 4) as usize).collect();

  // One buffer set per row band; tiles of the band run left-to-right in order,
  // and every buffer position a pass reads was written earlier in the *same*
  // tile, so reuse across tiles needs no clearing (upstream relies on the
  // same fact for its per-thread buffers).
  let bands: Vec<Vec<(i32, i32, i32, i32)>> = tops
    .iter()
    .map(|&top| {
      let bottom = (top + TS).min(h as i32 - BORD + 2);
      lefts
        .iter()
        .map(|&left| {
          let right = (left + TS).min(w as i32 - BORD + 2);
          (top, left, bottom, right)
        })
        .collect()
    })
    .collect();

  bands.into_par_iter().for_each(|tiles| {
    let ts = TS as usize;
    let mut greentile = vec![0.0f32; ts * ts];
    let mut redtile = vec![0.0f32; ts * ts];
    let mut bluetile = vec![0.0f32; ts * ts];

    for &(top, left, bottom, right) in &tiles {
      // Pass 1 — gradient-weighted green at R/B sites; raw copy elsewhere
      // (`fast_demo.cc:300-360`, scalar branch).
      for i in top..bottom {
        let rr = (i - top) as usize;
        for j in left..right {
          let cc = (j - left) as usize;
          let px = raw.at(i as usize, j as usize);
          let g = if cfa.fc(i as usize, j as usize) == 1 {
            px
          } else {
            // Directional weights from three sample differences per side.
            let px_up1 = raw.at((i - 1) as usize, j as usize);
            let px_up2 = raw.at((i - 2) as usize, j as usize);
            let px_up3 = raw.at((i - 3) as usize, j as usize);
            let px_dn1 = raw.at((i + 1) as usize, j as usize);
            let px_dn2 = raw.at((i + 2) as usize, j as usize);
            let px_dn3 = raw.at((i + 3) as usize, j as usize);
            let px_lf1 = raw.at(i as usize, (j - 1) as usize);
            let px_lf2 = raw.at(i as usize, (j - 2) as usize);
            let px_lf3 = raw.at(i as usize, (j - 3) as usize);
            let px_rt1 = raw.at(i as usize, (j + 1) as usize);
            let px_rt2 = raw.at(i as usize, (j + 2) as usize);
            let px_rt3 = raw.at(i as usize, (j + 3) as usize);
            // Upstream spells the down/left weights with the *same*
            // neighbour pairs in reverse subtraction order; the absolute
            // values make the two spellings identical.
            let wtu = invgrad((px_dn1 - px_up1).abs() + (px - px_up2).abs() + (px_up1 - px_up3).abs());
            let wtd = invgrad((px_up1 - px_dn1).abs() + (px - px_dn2).abs() + (px_dn1 - px_dn3).abs());
            let wtl = invgrad((px_rt1 - px_lf1).abs() + (px - px_lf2).abs() + (px_lf1 - px_lf3).abs());
            let wtr = invgrad((px_lf1 - px_rt1).abs() + (px - px_rt2).abs() + (px_rt1 - px_rt3).abs());
            (wtu * px_up1 + wtd * px_dn1 + wtl * px_lf1 + wtr * px_rt1) / (wtu + wtd + wtl + wtr)
          };
          greentile[rr * ts + cc] = g;
          redtile[rr * ts + cc] = px;
          bluetile[rr * ts + cc] = px;
        }
      }

      // Pass 2 — R/B at R/B sites from the diagonal colour difference, with
      // the saturation guard (`fast_demo.cc:367-402`, scalar branch). The
      // site columns of a row all share one parity; `fc(i, 2) & 1` tells
      // whether the tile-relative start of that parity is 1 or 2 (left is
      // always odd, and the tile pitch is even, so this alignment holds for
      // every tile).
      for i in top + 1..bottom - 1 {
        let rr = (i - top) as usize;
        let cc0 = ((cfa.fc(i as usize, 2) & 1) + 1) as usize;
        // The first R/B site of the row: red sites get blue interpolated
        // (the diagonals of a red site are all blue sites) and vice versa.
        let site_is_red = cfa.fc(i as usize, (left as usize + cc0)) == 0;
        let mut cc = cc0;
        let mut j = left + cc0 as i32;
        while j < right - 1 {
          let diag_raw = raw.at((i - 1) as usize, (j - 1) as usize)
            + raw.at((i - 1) as usize, (j + 1) as usize)
            + raw.at((i + 1) as usize, (j + 1) as usize)
            + raw.at((i + 1) as usize, (j - 1) as usize);
          let g_here = greentile[rr * ts + cc];
          let g_diag = greentile[(rr - 1) * ts + cc - 1]
            + greentile[(rr - 1) * ts + cc + 1]
            + greentile[(rr + 1) * ts + cc + 1]
            + greentile[(rr + 1) * ts + cc - 1];
          let value = g_here - 0.25 * (g_diag - CLIP_PT.min(diag_raw));
          if site_is_red {
            bluetile[rr * ts + cc] = value;
          } else {
            redtile[rr * ts + cc] = value;
          }
          cc += 2;
          j += 2;
        }
      }

      // Pass 3 — R/B at green sites from the cross colour difference
      // (`fast_demo.cc:411-443`, scalar branch). The neighbours read here
      // are all R/B sites, which pass 2 wrote.
      for i in top + 2..bottom - 2 {
        let rr = (i - top) as usize;
        let mut cc = (2 + (cfa.fc(i as usize, 2) & 1)) as usize;
        let mut j = left + cc as i32;
        while j < right - 2 {
          redtile[rr * ts + cc] = greentile[rr * ts + cc]
            - 0.25
              * ((greentile[(rr - 1) * ts + cc] - redtile[(rr - 1) * ts + cc])
                + (greentile[(rr + 1) * ts + cc] - redtile[(rr + 1) * ts + cc])
                + (greentile[rr * ts + cc - 1] - redtile[rr * ts + cc - 1])
                + (greentile[rr * ts + cc + 1] - redtile[rr * ts + cc + 1]));
          bluetile[rr * ts + cc] = greentile[rr * ts + cc]
            - 0.25
              * ((greentile[(rr - 1) * ts + cc] - bluetile[(rr - 1) * ts + cc])
                + (greentile[(rr + 1) * ts + cc] - bluetile[(rr + 1) * ts + cc])
                + (greentile[rr * ts + cc - 1] - bluetile[rr * ts + cc - 1])
                + (greentile[rr * ts + cc + 1] - bluetile[rr * ts + cc + 1]));
          cc += 2;
          j += 2;
        }
      }

      // Write-out, clamped at zero (`fast_demo.cc:446-472`).
      for i in top + 2..bottom - 2 {
        let rr = (i - top) as usize;
        for j in left + 2..right - 2 {
          let cc = (j - left) as usize;
          out.red.row_mut(i as usize)[j as usize] = max0(redtile[rr * ts + cc]);
          out.green.row_mut(i as usize)[j as usize] = max0(greentile[rr * ts + cc]);
          out.blue.row_mut(i as usize)[j as usize] = max0(bluetile[rr * ts + cc]);
        }
      }
    }
  });

  Ok(out)
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::cfa::CfaDesc;

  /// RGGB, GRBG, GBRG, BGGR — every kernel must agree on all four.
  const ORDERS: [[[u8; 2]; 2]; 4] = [[[0, 1], [1, 2]], [[1, 0], [2, 1]], [[1, 2], [0, 1]], [[2, 1], [1, 0]]];

  #[test]
  fn a_flat_field_is_an_exact_fixed_point_for_every_bayer_order() {
    for pattern in ORDERS {
      let c = CfaDesc::bayer_from_2x2(pattern);
      for v in [0.2_f32, 0.5, 0.7] {
        let out = bayer_fast_demosaic(&c, &Array2D::filled(60, 48, v)).expect("fast");
        for plane in [&out.red, &out.green, &out.blue] {
          for &x in plane.as_slice() {
            assert!((x - v).abs() < 1e-6, "pattern {pattern:?} v {v}: got {x}");
          }
        }
      }
    }
  }

  #[test]
  fn green_samples_survive_at_green_sites() {
    let c = CfaDesc::bayer_from_2x2(ORDERS[0]);
    let mut raw = Array2D::filled(60, 48, 0.25);
    for row in 0..48 {
      for col in 0..60 {
        if c.fc(row, col) == 1 {
          raw.set(row, col, 0.75);
        }
      }
    }
    let out = bayer_fast_demosaic(&c, &raw).expect("fast");
    for row in 0..48 {
      for col in 0..60 {
        if c.fc(row, col) == 1 {
          assert_eq!(out.green.at(row, col), 0.75, "green site ({row},{col}) must keep its sample");
        }
      }
    }
  }

  #[test]
  fn every_pixel_is_written_even_off_the_tile_pitch() {
    // 23x17: the tile pass runs (both sides > 10) but neither side reaches
    // the tile size, so the single tile and the border must interleave to a
    // full cover with no gap or double-write artifact.
    let c = CfaDesc::bayer_from_2x2(ORDERS[2]);
    let out = bayer_fast_demosaic(&c, &Array2D::filled(23, 17, 0.5)).expect("fast");
    for plane in [&out.red, &out.green, &out.blue] {
      for (i, row) in plane.as_slice().chunks(23).enumerate() {
        for (j, &x) in row.iter().enumerate() {
          assert!((x - 0.5).abs() < 1e-6, "unwritten or wrong pixel ({i},{j}): {x}");
        }
      }
    }
  }

  #[test]
  fn a_frame_below_the_border_size_is_refused() {
    let c = CfaDesc::bayer_from_2x2(ORDERS[0]);
    assert!(matches!(bayer_fast_demosaic(&c, &Array2D::filled(32, 9, 0.5)), Err(Error::Shape(_))));
    assert!(matches!(bayer_fast_demosaic(&c, &Array2D::filled(9, 32, 0.5)), Err(Error::Shape(_))));
  }

  #[test]
  fn a_four_colour_cfa_is_refused() {
    let mut c = CfaDesc::bayer_from_2x2(ORDERS[0]);
    c.colors = 4;
    assert!(matches!(bayer_fast_demosaic(&c, &Array2D::filled(40, 40, 0.5)), Err(Error::UnsupportedCfa(_))));
  }
}
