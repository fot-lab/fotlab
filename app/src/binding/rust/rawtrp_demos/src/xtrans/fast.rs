//! FAST X-Trans demosaic — `fast_xtrans_interpolate`
//! (`xtrans_demosaic.cc:969-1029`).
//!
//! One weighted cross-kernel pass over the interior: every pixel estimates its
//! two missing channels from the colour-weighted 3x3 sum, keeping its own raw
//! sample. The green normalisation is where the structure shows: a *solitary*
//! green (both horizontal neighbours the same colour) has exactly two direct
//! red and blue neighbours in the window, so the plain sums already total the
//! right weight; a non-solitary green has one direct and one diagonal neighbour
//! of each, totalling 0.75, and the `* 1.3333333` rescale fixes the weight.
//!
//! Reads no per-image data and has no absolute constants — it is exactly
//! homogeneous of degree 1, so a flat field is an exact fixed point.
//!
//! Parallel over rows, like upstream's `#pragma omp parallel for schedule(
//! dynamic, 16)`; the 1-pixel border comes first from
//! [`border::xtrans_border_interpolate`] (upstream also calls it first, and the
//! interior pass then overwrites the interior rows — the two write sets are
//! disjoint).

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::cfa::CfaDesc;
use crate::xtrans::border::xtrans_border_interpolate;
use crate::{Error, Rgb};

/// The cross kernel, shared with the border pass (`xtrans_demosaic.cc:981-985`).
const WEIGHT: [[f32; 3]; 3] = [[0.25, 0.5, 0.25], [0.5, 0.0, 0.5], [0.25, 0.5, 0.25]];

/// Demosaic an X-Trans mosaic with RawTherapee's `fast` X-Trans kernel.
///
/// # Errors
/// Propagated from the border pass — which cannot actually fail today; the
/// signature mirrors every other kernel for the dispatcher's sake.
pub fn xtrans_fast_demosaic(cfa: &CfaDesc, raw: &Array2D<f32>) -> Result<Rgb, Error> {
  let (w, h) = (raw.width(), raw.height());
  let mut out = Rgb::new(w, h);

  xtrans_border_interpolate(cfa, raw, &mut out, 1);

  {
    let Rgb { red, green, blue } = &mut out;
    red
      .par_rows_mut()
      .zip(green.par_rows_mut())
      .zip(blue.par_rows_mut())
      .enumerate()
      .for_each(|(row, ((r_row, g_row), b_row))| {
        if row < 1 || row >= h - 1 {
          return;
        }
        for col in 1..w - 1 {
          let mut sum = [0.0f32; 3];
          for v in 0..3 {
            for x_off in 0..3 {
              let y = row + v - 1;
              let x = col + x_off - 1;
              sum[cfa.xtrans_color(y, x) as usize] += raw.at(y, x) * WEIGHT[v][x_off];
            }
          }
          match cfa.xtrans_color(row, col) {
            0 => {
              r_row[col] = raw.at(row, col);
              g_row[col] = sum[1] * 0.5;
              b_row[col] = sum[2];
            }
            1 => {
              g_row[col] = raw.at(row, col);
              if cfa.xtrans_color(row, col - 1) == cfa.xtrans_color(row, col + 1) {
                // Solitary green: exactly two direct red and blue neighbours.
                r_row[col] = sum[0];
                b_row[col] = sum[2];
              } else {
                // Non-solitary: one direct and one diagonal neighbour of each
                // — weight 0.75, rescaled up to 1.0.
                r_row[col] = sum[0] * 1.333_333_3;
                b_row[col] = sum[2] * 1.333_333_3;
              }
            }
            _ => {
              r_row[col] = sum[0];
              g_row[col] = sum[1] * 0.5;
              b_row[col] = raw.at(row, col);
            }
          }
        }
      });
  }

  Ok(out)
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::xtrans::border::test_support::{std_cfa, XTRANS_6X6};

  #[test]
  fn a_flat_field_is_an_exact_fixed_point() {
    let c = std_cfa();
    for v in [0.2_f32, 0.5, 0.7] {
      let out = xtrans_fast_demosaic(&c, &Array2D::filled(40, 33, v)).expect("fast xtrans");
      for plane in [&out.red, &out.green, &out.blue] {
        for &x in plane.as_slice() {
          assert!((x - v).abs() < 1e-6, "v {v}: got {x}");
        }
      }
    }
  }

  #[test]
  fn the_green_layout_of_the_standard_matrix_is_three_periodic() {
    // The kernels lean on isgreen's %3 periodicity; assert it for the
    // standard matrix so a matrix change cannot silently break the port.
    for row in 0..6 {
      for col in 0..6 {
        let fcol = XTRANS_6X6[row][col];
        let isgreen3 = XTRANS_6X6[row % 3][col % 3] == 1;
        assert_eq!(fcol == 1, isgreen3, "green periodicity broken at ({row},{col})");
      }
    }
  }

  #[test]
  fn green_samples_survive_at_green_sites() {
    let c = std_cfa();
    let mut raw = Array2D::filled(40, 33, 0.25);
    for row in 0..33 {
      for col in 0..40 {
        if c.xtrans_color(row, col) == 1 {
          raw.set(row, col, 0.75);
        }
      }
    }
    let out = xtrans_fast_demosaic(&c, &raw).expect("fast xtrans");
    for row in 0..33 {
      for col in 0..40 {
        if c.xtrans_color(row, col) == 1 {
          assert_eq!(out.green.at(row, col), 0.75, "green site ({row},{col})");
        }
      }
    }
  }

  #[test]
  fn every_pixel_is_finite_and_non_negative_on_a_ramp() {
    let c = std_cfa();
    let mut raw = Array2D::filled(37, 29, 0.0);
    for row in 0..29 {
      for col in 0..37 {
        raw.set(row, col, ((row * 37 + col) % 17) as f32 / 16.0);
      }
    }
    let out = xtrans_fast_demosaic(&c, &raw).expect("fast xtrans");
    for plane in [&out.red, &out.green, &out.blue] {
      for &x in plane.as_slice() {
        assert!(x.is_finite() && x >= 0.0, "bad pixel value {x}");
      }
    }
  }
}
