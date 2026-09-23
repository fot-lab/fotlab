//! AHD — Adaptive Homogeneity-Directed Bayer demosaic.
//!
//! Ported from `external/RawTherapee/rtengine/ahd_demosaic_RT.cc`
//! (Copyright (c) 2018 Ingo Weyrich, GPL-3.0) — `RawImageSource::ahd_demosaic()`.
//!
//! AHD interpolates the whole frame **twice** — once preferring the horizontal
//! direction and once the vertical — converts both to CIELab, counts for each
//! pixel how many of its four neighbours are "homogeneous" in Lab, and emits the
//! direction whose 3x3 neighbourhood is the most homogeneous; on a tie it emits
//! the mean of the two. The 5-pixel frame comes from `border_interpolate`.
//!
//! **Not advertised to the UI.** AHD is ported and dispatchable through
//! `crate::demosaic_bayer`, but its homogeneity judgement runs in CIELab, so it
//! needs the camera's own colour matrix — per-image data this crate has no source
//! for by itself. `algo::IMPLEMENTED_BAYER` therefore omits `"ahd"`, which is what
//! keeps it off the menu; add the name back there to wire it up
//! (`FOTLAB-NATIVE-000004` rev 12).
//!
//! ## Fidelity notes
//!
//! * **The kernel stays in the mosaic's own 0..1 domain, and the Lab conversion
//!   compensates.** Unlike the kernels that round-trip through RT's 0..65535
//!   scale, AHD cannot simply be lifted: the `cbrt` LUT is *indexed* by the XYZ
//!   triple (`cbrt[xyz[0]]`), so the working range is what makes the lookup land
//!   where upstream's does. Everything else in the kernel is homogeneous of
//!   degree 1 in the samples (every term is a difference or a weighted mean), so
//!   the only adaptation needed is `xyz * SCALE` at the two LUT lookups and
//!   `lim01` where upstream writes `CLIP`. That keeps the border — which
//!   `border_interpolate` fills straight from the mosaic — in the same units as
//!   the interior, which a scale round trip would not.
//! * **`xyz_cam` is supplied by the caller, and its default assumes sRGB.**
//!   Upstream builds it from `imatrices.rgb_cam`, the camera's own RGB→XYZ
//!   matrix; this crate only ever sees a mosaic and a `CFA`, so the matrix
//!   arrives as an argument and [`crate::BayerParams::xyz_cam`] defaults to
//!   `xyz_rgb / d65_white` — i.e. "the camera's channels already *are* sRGB".
//!   `rawler_fotlab` replaces it with `RawImage::cam_to_xyz_normalized()`. The
//!   convention that makes this interchangeable is that a *neutral* (1,1,1)
//!   camera triple maps to XYZ (1,1,1): both upstream's `rgb_cam` path and
//!   rawler's normalised matrix satisfy it, and it is what puts white at the top
//!   of the `cbrt` LUT. AHD uses the matrix only to *compare* pixels, so an
//!   imprecise one costs directional accuracy, never a wrong-shaped image.
//! * **Parallelism is over row bands, not over tiles.** Upstream's
//!   `#pragma omp for collapse(2)` schedules both the row and the column loop.
//!   The column dimension cannot be shared here: two horizontally adjacent tiles
//!   write *different columns of the same rows*, which a row-major `Array2D`
//!   cannot hand out as disjoint `&mut` slices without `unsafe`. The row
//!   dimension can: consecutive `top`s are `STEP = TS - 6` apart and each band
//!   writes `[top + 3, top + TS - 3)`, which tiles the interior exactly, so the
//!   three output planes are chunked by band and the column loop runs serially
//!   inside one. (`TS - 6` is not arbitrary — a tile loses three rows and three
//!   columns at each edge, one per pass.)
//! * **The tile buffers are allocated once per band and reused across its
//!   column tiles**, as upstream reuses one per-thread buffer across every tile
//!   it visits. That is only sound because every position a pass reads has
//!   already been written by an earlier pass of the *same* tile: the green pass
//!   covers `rows [top, min(top+TS, H-2))` × the non-green columns from `left`,
//!   and each of the later passes' halo bounds is exactly the previous pass's
//!   write bound — see the bounds notes on each pass. Nothing is zeroed between
//!   tiles, which is also what upstream does.
//! * **`cng` is a per-row constant**, not per-column: upstream computes it as
//!   `fc(row + 1, fc(row + 1, 0) & 1)`, i.e. the *non-green* colour of the row
//!   below. A green pixel's horizontal neighbours are that colour's complement
//!   and its vertical neighbours are that colour, which is why the same `cng`
//!   serves the whole row.

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::border::border_interpolate;
use crate::cfa::CfaDesc;
use crate::math::{abs, lim01, max2, median3, min2, sqr};
use crate::{Error, Rgb};

/// Upstream's `TS` — the edge of the square each tile works on, and the stride
/// between the two direction planes (`rix[-TS]` is "the row above").
const TS: usize = 144;

/// `TS - 6` — the distance between consecutive tiles. Each pass starts one row
/// and one column further in than the last (green at `+0`, red/blue at `+1`,
/// homogeneity at `+2`, combination at `+3`), so a tile only *emits*
/// `[top + 3, top + TS - 3)`; stepping by `TS - 6` makes those emitted windows
/// tile the interior with no gap and no overlap.
const STEP: usize = TS - 6;

/// Upstream's `border_interpolate(W, H, 5, rawData, red, green, blue)`.
const BORDER: usize = 5;

/// RT's `rawData` is 0..65535 where this crate's mosaic is 0..1 — the factor the
/// two Lab lookups scale by so their LUT index lands where upstream's does.
/// `65536`, matching the rest of the crate (`bayer/dcb.rs`, `bayer/lmmse.rs`);
/// the 0.0015% difference from `MAXVAL` is far below anything here.
const SCALE: f32 = 65536.0;

/// Upstream's `constexpr int dirs[4] = { -1, 1, -TS, TS }`, in units of one
/// `lab`/`homo` slot. The first two are the horizontal direction's neighbours
/// and the last two the vertical direction's, which is what `leps`/`abeps` pair
/// up: `d = 0` is judged on `dirs[0..2]` and `d = 1` on `dirs[2..4]`.
const DIRS: [isize; 4] = [-1, 1, -(TS as isize), TS as isize];

/// RT's `LUTf cbrt(65536)` (`ahd_demosaic_RT.cc:51` and `:70-73`), together with
/// the *float* `operator[]` of `LUT.h:462-485`.
///
/// Two things about it are not what "look up a table" suggests, and both are
/// load-bearing:
///
/// * the index is **truncated towards zero** (`(int)index`, with a comment
///   upstream saying "don't use floor!"), not rounded or floored; and
/// * the two neighbouring entries are then **linearly interpolated**
///   (`diff = index - idx; p1 + p2 * diff`), which is what makes the 65536-entry
///   table behave like the continuous cube root rather than a staircase.
///
/// Out-of-range indices are *not* clamped: the index is pinned to
/// `[0, size - 2]` and `diff` keeps the whole overshoot, so the two ends
/// extrapolate along the last segment. A negative XYZ — which a camera matrix
/// with negative coefficients can produce before any clipping — therefore
/// extrapolates *below* `data[0]` rather than saturating.
struct Cbrt(Vec<f32>);

impl Cbrt {
  /// `cbrt[i] = i/65535 > 0.008856 ? std::cbrt(r) : 7.787 * r + 16 / 116.0`.
  fn new() -> Self {
    let mut lut = Vec::with_capacity(65536);
    for i in 0..=65535u32 {
      let r = i as f32 / 65535.0;
      lut.push(if r > 0.008856 { r.cbrt() } else { 7.787 * r + 16.0 / 116.0 });
    }
    Self(lut)
  }

  /// `LUTf::operator[](float)` for a table with no clip flags set.
  fn at(&self, index: f32) -> f32 {
    let maxs = self.0.len() - 2;
    let idx = if index < 0.0 {
      0
    } else if index > maxs as f32 {
      maxs
    } else {
      // `(int)index`. A NaN saturates to 0 in Rust and is then carried by
      // `diff`; upstream's `(int)NaN` is unspecified, and no real frame reaches
      // it, so this is the honest spelling rather than a designed rule.
      index as usize
    };
    let p1 = self.0[idx];
    let p2 = self.0[idx + 1] - p1;
    p1 + p2 * (index - idx as f32)
  }
}

/// One band's scratch: the two direction planes, each `2 * TS * TS` slots laid
/// out `d * TS * TS + tr * TS + tc` so that an offset of `±1` is the next column
/// and `±TS` the next row, exactly as upstream's `float[2][TS][TS][3]` gives.
///
/// Upstream packs all three into one `float[13 * TS * TS]` allocation with
/// `homo` reinterpreted out of the tail; here they are three vectors, which is
/// the same addressing without the cast.
struct TileBuf {
  rgb: Vec<[f32; 3]>,
  lab: Vec<[f32; 3]>,
  homo: Vec<u16>,
}

impl TileBuf {
  fn new() -> Self {
    let n = 2 * TS * TS;
    Self {
      rgb: vec![[0.0; 3]; n],
      lab: vec![[0.0; 3]; n],
      homo: vec![0; n],
    }
  }
}

/// Run one tile: green, then red/blue + Lab, then the homogeneity maps, then the
/// combination. Only the last pass writes to the output, and only into
/// `[row_start, row_start + rows)` of the three planes handed in.
#[allow(clippy::too_many_arguments)]
fn ahd_tile(
  cfa: &CfaDesc,
  raw: &Array2D<f32>,
  xyz_cam: &[[f32; 3]; 3],
  cbrt: &Cbrt,
  w: usize,
  h: usize,
  top: usize,
  left: usize,
  row_start: usize,
  buf: &mut TileBuf,
  red: &mut [f32],
  green: &mut [f32],
  blue: &mut [f32],
) {
  //  Interpolate green horizontally and vertically.
  //
  //  The loop starts at `left + (fc(row, left) & 1)` and steps by 2, which walks
  //  the *non-green* columns of the row — the positions green has to be
  //  interpolated *at*. It writes only channel 1; channel 1 of a green position
  //  is written further down, by the red/blue pass, as the raw sample.
  for row in top..(top + TS).min(h.saturating_sub(2)) {
    let col_end = (left + TS).min(w.saturating_sub(2));
    let mut col = left + usize::from(cfa.is_green(row, left));
    if col >= col_end {
      continue;
    }
    let p = raw.row(row);
    let up1 = raw.row(row - 1);
    let up2 = raw.row(row - 2);
    let dn1 = raw.row(row + 1);
    let dn2 = raw.row(row + 2);
    let base = (row - top) * TS;
    while col < col_end {
      let tc = col - left;
      let val0 = 0.25 * ((p[col - 1] + p[col] + p[col + 1]) * 2.0 - p[col - 2] - p[col + 2]);
      buf.rgb[base + tc][1] = median3(val0, p[col - 1], p[col + 1]);
      let val1 = 0.25 * ((up1[col] + p[col] + dn1[col]) * 2.0 - up2[col] - dn2[col]);
      buf.rgb[TS * TS + base + tc][1] = median3(val1, up1[col], dn1[col]);
      col += 2;
    }
  }

  //  Interpolate red and blue, and convert to CIELab.
  //
  //  `cng` is the non-green colour of the row *below*, so a green pixel's
  //  horizontal neighbours are `2 - cng` and its vertical ones are `cng`.
  for d in 0..2 {
    let plane = d * TS * TS;
    for row in (top + 1)..(top + TS - 1).min(h.saturating_sub(3)) {
      let cng = if cfa.is_green(row + 1, 0) {
        cfa.fc(row + 1, 1)
      } else {
        cfa.fc(row + 1, 0)
      } as usize;
      let other = 2 - cng;
      let col_end = (left + TS - 1).min(w.saturating_sub(3));
      let p = raw.row(row);
      let up = raw.row(row - 1);
      let dn = raw.row(row + 1);
      let base = plane + (row - top) * TS;
      for col in (left + 1)..col_end {
        let idx = base + (col - left);
        if cfa.is_green(row, col) {
          // The horizontal estimate uses the raw samples either side minus the
          // *interpolated* greens either side — a chroma difference transported
          // onto this pixel — and likewise for the vertical one.
          let dh = 0.5 * (p[col - 1] + p[col + 1] - buf.rgb[idx - 1][1] - buf.rgb[idx + 1][1]);
          buf.rgb[idx][other] = lim01(p[col] + dh);
          let dv = 0.5 * (up[col] + dn[col] - buf.rgb[idx - TS][1] - buf.rgb[idx + TS][1]);
          buf.rgb[idx][cng] = lim01(p[col] + dv);
          buf.rgb[idx][1] = p[col];
        } else {
          // The four diagonal neighbours are the *other* non-green colour here,
          // so their raw-vs-green difference is this pixel's chroma in `cng`.
          let s = up[col - 1] + up[col + 1] + dn[col - 1] + dn[col + 1]
            - buf.rgb[idx - TS - 1][1]
            - buf.rgb[idx - TS + 1][1]
            - buf.rgb[idx + TS - 1][1]
            - buf.rgb[idx + TS + 1][1];
          buf.rgb[idx][cng] = lim01(buf.rgb[idx][1] + 0.25 * s);
          buf.rgb[idx][other] = p[col];
        }

        let r = buf.rgb[idx];
        let x = xyz_cam[0][0] * r[0] + xyz_cam[0][1] * r[1] + xyz_cam[0][2] * r[2];
        let y = xyz_cam[1][0] * r[0] + xyz_cam[1][1] * r[1] + xyz_cam[1][2] * r[2];
        let z = xyz_cam[2][0] * r[0] + xyz_cam[2][1] * r[1] + xyz_cam[2][2] * r[2];

        let fx = cbrt.at(x * SCALE);
        let fy = cbrt.at(y * SCALE);
        let fz = cbrt.at(z * SCALE);

        buf.lab[idx][0] = 116.0 * fy - 16.0;
        buf.lab[idx][1] = 500.0 * (fx - fy);
        buf.lab[idx][2] = 200.0 * (fy - fz);
      }
    }
  }

  //  Build homogeneity maps from the CIELab images.
  //
  //  `leps`/`abeps` are the *cross-direction* thresholds: `d = 0` is judged on
  //  its horizontal neighbours and `d = 1` on its vertical ones, and both are
  //  compared against the smaller of the two maxima. A pixel is homogeneous in a
  //  direction when it is within both.
  for row in (top + 2)..(top + TS - 2).min(h.saturating_sub(4)) {
    let tr = row - top;
    let col_end = (left + TS - 2).min(w.saturating_sub(4));
    for col in (left + 2)..col_end {
      let tc = col - left;
      let mut ldiff = [[0.0f32; 4]; 2];
      let mut abdiff = [[0.0f32; 4]; 2];

      for d in 0..2 {
        let here = d * TS * TS + tr * TS + tc;
        let lix = buf.lab[here];
        for i in 0..4 {
          let n = buf.lab[(here as isize + DIRS[i]) as usize];
          ldiff[d][i] = abs(lix[0] - n[0]);
          abdiff[d][i] = sqr(lix[1] - n[1]) + sqr(lix[2] - n[2]);
        }
      }

      let leps = min2(max2(ldiff[0][0], ldiff[0][1]), max2(ldiff[1][2], ldiff[1][3]));
      let abeps = min2(max2(abdiff[0][0], abdiff[0][1]), max2(abdiff[1][2], abdiff[1][3]));

      for d in 0..2 {
        let mut hm = 0u16;
        for i in 0..4 {
          hm += u16::from(ldiff[d][i] <= leps && abdiff[d][i] <= abeps);
        }
        buf.homo[d * TS * TS + tr * TS + tc] = hm;
      }
    }
  }

  //  Combine the most homogeneous pixels for the final result.
  for row in (top + 3)..(top + TS - 3).min(h.saturating_sub(5)) {
    let tr = row - top;
    let col_end = (left + TS - 3).min(w.saturating_sub(5));
    for col in (left + 3)..col_end {
      let tc = col - left;
      let mut hm0 = 0u32;
      let mut hm1 = 0u32;
      for i in (tr - 1)..=(tr + 1) {
        for j in (tc - 1)..=(tc + 1) {
          hm0 += u32::from(buf.homo[i * TS + j]);
          hm1 += u32::from(buf.homo[TS * TS + i * TS + j]);
        }
      }

      let o = (row - row_start) * w + col;
      if hm0 != hm1 {
        let dir = if hm1 > hm0 { TS * TS } else { 0 };
        let s = dir + tr * TS + tc;
        red[o] = buf.rgb[s][0];
        green[o] = buf.rgb[s][1];
        blue[o] = buf.rgb[s][2];
      } else {
        // Upstream's `0.5f * (a + b)` — written as a sum halved, not a mean, so
        // the two roundings happen in the same order.
        let a = tr * TS + tc;
        let b = TS * TS + a;
        red[o] = 0.5 * (buf.rgb[a][0] + buf.rgb[b][0]);
        green[o] = 0.5 * (buf.rgb[a][1] + buf.rgb[b][1]);
        blue[o] = 0.5 * (buf.rgb[a][2] + buf.rgb[b][2]);
      }
    }
  }
}

/// `RawImageSource::ahd_demosaic()`.
///
/// `xyz_cam` is the camera → normalised-XYZ matrix; see the module note for the
/// convention it has to satisfy and what the caller's default means.
pub fn bayer_ahd_demosaic(cfa: &CfaDesc, mosaic: &Array2D<f32>, xyz_cam: &[[f32; 3]; 3]) -> Result<Rgb, Error> {
  let (w, h) = (mosaic.width(), mosaic.height());
  // The widest stencil is the 5-tap green one (two samples either side), and
  // `border_interpolate(…, 5, …)` needs five rows and five columns of its own on
  // each side; below 11 there is no interior left to tile.
  if w < 11 || h < 11 {
    return Err(Error::Shape(format!("bayer_ahd: mosaic too small: {w}x{h}")));
  }
  if cfa.has_fourth_colour() {
    // Upstream has no guard, but a fourth colour would make `cng` resolve to a
    // colour the three-channel Lab conversion has no row for, so refuse it and
    // let the caller fall back.
    return Err(Error::UnsupportedCfa("bayer_ahd"));
  }

  let mut out = Rgb::new(w, h);
  border_interpolate(cfa, mosaic, &mut out.red, &mut out.green, &mut out.blue, BORDER);

  // Built once and shared read-only: upstream builds it before the parallel
  // region too (`ahd_demosaic_RT.cc:70-73`), so every thread sees the same table.
  let cbrt = Cbrt::new();
  let xyz = *xyz_cam;

  // The interior rows `[BORDER, h - BORDER)` are what the tiles own, and the
  // bands tile them exactly: band `k` covers `[BORDER + k*STEP, BORDER + (k+1)*STEP)`
  // clipped to the end, which is precisely `[top + 3, top + TS - 3)` for
  // `top = 2 + k*STEP`.
  let band_len = STEP * w;
  let n_bands = (h - 2 * BORDER + STEP - 1) / STEP;

  {
    let Rgb { red, green, blue } = &mut out;
    // The band count is derived twice — once as a row count and once from the
    // slice length — and the two are only equal because `STEP` divides the
    // interior the way a tile's emitted window does. Worth pinning: if it ever
    // stopped holding, the last band would write past its chunk.
    debug_assert_eq!(red.as_mut_slice()[BORDER * w..(h - BORDER) * w].chunks(band_len).len(), n_bands);
    let r_bands = &mut red.as_mut_slice()[BORDER * w..(h - BORDER) * w];
    let g_bands = &mut green.as_mut_slice()[BORDER * w..(h - BORDER) * w];
    let b_bands = &mut blue.as_mut_slice()[BORDER * w..(h - BORDER) * w];

    r_bands
      .par_chunks_mut(band_len)
      .zip(g_bands.par_chunks_mut(band_len))
      .zip(b_bands.par_chunks_mut(band_len))
      .enumerate()
      .for_each(|(k, ((r_band, g_band), b_band))| {
        let top = 2 + k * STEP;
        let row_start = top + 3;
        let mut buf = TileBuf::new();
        let mut left = 2;
        while left < w.saturating_sub(BORDER) {
          ahd_tile(
            cfa, mosaic, &xyz, &cbrt, w, h, top, left, row_start, &mut buf, &mut r_band[..], &mut g_band[..], &mut b_band[..],
          );
          left += STEP;
        }
      });
  }

  Ok(out)
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::XYZ_CAM_FROM_SRGB;

  /// `CfaDesc::bayer_from_2x2` takes dcraw colour codes (0=R, 1=G, 2=B).
  fn cfa(pattern: [[u8; 2]; 2]) -> CfaDesc {
    CfaDesc::bayer_from_2x2(pattern)
  }

  /// The four Bayer rotations, so a test can't pass by matching one layout.
  const ROTATIONS: [[[u8; 2]; 2]; 4] = [
    [[0, 1], [1, 2]], // RGGB
    [[2, 1], [1, 0]], // BGGR
    [[1, 0], [2, 1]], // GBRG
    [[1, 2], [0, 1]], // GRBG
  ];

  fn filled(w: usize, h: usize, v: f32) -> Array2D<f32> {
    Array2D::filled(w, h, v)
  }

  fn ramp(w: usize, h: usize) -> Array2D<f32> {
    let mut raw = Array2D::new(w, h);
    for i in 0..h {
      for j in 0..w {
        raw.set(i, j, 0.2 + 0.004 * i as f32 + 0.006 * j as f32);
      }
    }
    raw
  }

  /// A flat mosaic is the kernel's fixed point: every estimate is a mean of
  /// equal samples, both Lab images are constant, so `hm0 == hm1` everywhere and
  /// the tie branch averages two identical planes. Asserts the *whole* frame,
  /// border included, which also pins `border_interpolate`'s 3x3 mean.
  #[test]
  fn a_flat_mosaic_reconstructs_exactly() {
    let (w, h) = (32usize, 32usize);
    for pattern in ROTATIONS {
      let c = cfa(pattern);
      let out = bayer_ahd_demosaic(&c, &filled(w, h, 0.4), &XYZ_CAM_FROM_SRGB).expect("ahd");
      for (name, plane) in [("r", &out.red), ("g", &out.green), ("b", &out.blue)] {
        for (i, row) in plane.rows().enumerate() {
          for (j, &v) in row.iter().enumerate() {
            assert!(
              (v - 0.4).abs() < 1e-5,
              "flat {pattern:?} {name}[{i}][{j}] = {v}, expected 0.4"
            );
          }
        }
      }
    }
  }

  /// A **linear ramp** must come back exactly, in the interior, in all three
  /// channels and for all four rotations.
  ///
  /// This is the test the flat one cannot do. Every estimate in AHD is a
  /// second-order difference correction on top of a sample, and those
  /// differences annihilate a plane exactly — so each interpolated value is the
  /// ramp value at *its own* pixel rather than a blend of its neighbours. Any
  /// CFA parity error, off-by-one column, or `cng` read from the wrong row makes
  /// some term mix two colours and the identity stops holding, which a flat
  /// mosaic would never reveal.
  ///
  /// Only the interior is asserted: `border_interpolate` averages the *green*
  /// samples of a 3x3 neighbourhood, and those are not centred on the pixel
  /// unless the ramp is constant.
  #[test]
  fn a_linear_ramp_reconstructs_exactly_in_the_interior() {
    let (w, h) = (32usize, 32usize);
    for pattern in ROTATIONS {
      let c = cfa(pattern);
      let raw = ramp(w, h);
      let out = bayer_ahd_demosaic(&c, &raw, &XYZ_CAM_FROM_SRGB).expect("ahd");
      for (name, plane) in [("r", &out.red), ("g", &out.green), ("b", &out.blue)] {
        for i in BORDER..h - BORDER {
          for j in BORDER..w - BORDER {
            let want = raw.at(i, j);
            let got = plane.at(i, j);
            assert!(
              (got - want).abs() < 1e-5,
              "ramp {pattern:?} {name}[{i}][{j}] = {got}, expected {want}"
            );
          }
        }
      }
    }
  }

  /// A curved surface is *not* a fixed point, which is what keeps the two tests
  /// above from passing for a kernel that just copies the mosaic.
  #[test]
  fn a_curved_surface_is_not_a_fixed_point() {
    let (w, h) = (32usize, 32usize);
    let c = cfa(ROTATIONS[0]);
    let mut raw = Array2D::new(w, h);
    for i in 0..h {
      for j in 0..w {
        let x = j as f32 / w as f32;
        let y = i as f32 / h as f32;
        raw.set(i, j, 0.2 + 0.5 * x * y);
      }
    }
    let out = bayer_ahd_demosaic(&c, &raw, &XYZ_CAM_FROM_SRGB).expect("ahd");
    let mut worst = 0.0f32;
    for i in BORDER..h - BORDER {
      for j in BORDER..w - BORDER {
        worst = worst.max((out.green.at(i, j) - raw.at(i, j)).abs());
      }
    }
    assert!(worst > 1e-4, "a curved surface must move, worst deviation was {worst}");
  }

  /// Every channel is finite and inside the unit range for an arbitrary mosaic.
  #[test]
  fn output_is_finite_and_bounded() {
    let (w, h) = (48usize, 40usize);
    let c = cfa(ROTATIONS[2]);
    let mut raw = Array2D::new(w, h);
    for i in 0..h {
      for j in 0..w {
        raw.set(i, j, ((i * 7 + j * 13) % 17) as f32 * 0.055);
      }
    }
    let out = bayer_ahd_demosaic(&c, &raw, &XYZ_CAM_FROM_SRGB).expect("ahd");
    for (name, plane) in [("r", &out.red), ("g", &out.green), ("b", &out.blue)] {
      for &v in plane.as_slice() {
        assert!(v.is_finite(), "{name} produced {v}");
        assert!((0.0..=1.0).contains(v), "{name} out of range: {v}");
      }
    }
  }

  /// The `cbrt` table is upstream's curve, *interpolated* rather than indexed,
  /// and it extrapolates past both ends instead of clamping.
  #[test]
  fn cbrt_lut_is_the_upstream_curve_and_interpolates() {
    let c = Cbrt::new();
    assert!((c.at(65535.0) - 1.0).abs() < 1e-6, "the top of the table is cbrt(1)");
    assert!((c.at(0.0) - 16.0 / 116.0).abs() < 1e-7, "the bottom is the linear branch");

    // An integer index is an exact entry; a half-integer one interpolates.
    let a = c.at(1000.0);
    let b = c.at(1001.0);
    assert!((a - (1000.0f32 / 65535.0).cbrt()).abs() < 1e-6);
    let mid = c.at(1000.5);
    assert!(mid > a.min(b) && mid < a.max(b), "{mid} must lie between {a} and {b}");

    // Out of range: the index is pinned but `diff` keeps the overshoot.
    assert!(c.at(-1.0) < c.at(0.0), "below zero the table extrapolates downwards");
    assert!(c.at(70000.0) > c.at(65534.0));
  }

  /// The convention that makes the caller's matrix and the sRGB default
  /// interchangeable: a neutral triple maps to XYZ (1,1,1). That is what puts
  /// white at the top of the `cbrt` table, so a matrix that does not satisfy it
  /// would put every Lab value in the wrong part of the curve.
  #[test]
  fn the_default_xyz_cam_maps_neutral_to_unit_xyz() {
    for (i, row) in XYZ_CAM_FROM_SRGB.iter().enumerate() {
      let sum = row[0] + row[1] + row[2];
      assert!((sum - 1.0).abs() < 1e-6, "row {i} sums to {sum}, expected 1");
    }
  }

  #[test]
  fn a_four_colour_cfa_is_rejected() {
    let mut c = cfa(ROTATIONS[0]);
    c.colors = 4;
    assert!(matches!(
      bayer_ahd_demosaic(&c, &filled(32, 32, 0.4), &XYZ_CAM_FROM_SRGB),
      Err(Error::UnsupportedCfa(_))
    ));
  }

  #[test]
  fn a_frame_too_small_is_rejected() {
    let c = cfa(ROTATIONS[0]);
    assert!(matches!(
      bayer_ahd_demosaic(&c, &filled(10, 32, 0.4), &XYZ_CAM_FROM_SRGB),
      Err(Error::Shape(_))
    ));
    assert!(matches!(
      bayer_ahd_demosaic(&c, &filled(32, 10, 0.4), &XYZ_CAM_FROM_SRGB),
      Err(Error::Shape(_))
    ));
  }
}
