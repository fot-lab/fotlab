//! `xtransborder_interpolate` — the X-Trans border fill
//! (`xtrans_demosaic.cc:122-173`).
//!
//! Weighted colour means over the (clamped) 3x3 neighbourhood with the cross
//! kernel `{0.25, 0.5, 0.25; 0.5, 0, 0.5; 0.25, 0.5, 0.25}` — the centre weight
//! is 0, so the sampled pixel never contributes to its own estimate. The
//! CFA-sampled channel keeps the raw value; the green-at-a-green corner case
//! (a window that is all green, `sum[3] == 0`) falls back to copying the raw
//! value into all three planes, exactly as upstream comments.
//!
//! ## Fidelity note — the interior skip
//!
//! Upstream walks every pixel and *jumps* `col` from `border` to
//! `width - border` to skip the interior. For `width < 2 * border` that jump
//! moves `col` **backwards** and the upstream loop re-processes columns (for
//! `width < 2 * border - 1` it never terminates). This port expresses the same
//! skip as an explicit `continue` over the interior rectangle, which is
//! pixel-identical wherever upstream terminates and merely refuses to hang
//! where it does not — reachable only for absurd frame sizes (`border` is 11
//! for the 1-pass kernel, 1 for `fast`).
//!
//! The pass is sequential, like every border pass in this crate (upstream runs
//! it outside the parallel region).

use crate::array2d::Array2D;
use crate::cfa::CfaDesc;
use crate::Rgb;

/// The cross kernel, `xtrans_demosaic.cc:128-132`.
const WEIGHT: [[f32; 3]; 3] = [[0.25, 0.5, 0.25], [0.5, 0.0, 0.5], [0.25, 0.5, 0.25]];

/// Fill every pixel outside the `border`-wide frame band.
pub(crate) fn xtrans_border_interpolate(cfa: &CfaDesc, raw: &Array2D<f32>, out: &mut Rgb, border: usize) {
  let (w, h) = (raw.width(), raw.height());
  for row in 0..h {
    for col in 0..w {
      let in_interior = col >= border && col < w - border && row >= border && row < h - border;
      if in_interior {
        continue;
      }
      fill_pixel(cfa, raw, out, row, col);
    }
  }
}

/// Fill one pixel (`xtrans_demosaic.cc:140-171`).
#[inline]
fn fill_pixel(cfa: &CfaDesc, raw: &Array2D<f32>, out: &mut Rgb, row: usize, col: usize) {
  let (w, h) = (raw.width(), raw.height());
  let mut sum = [0.0f32; 6];

  let y_lo = row.saturating_sub(1);
  let y_hi = (row + 1).min(h - 1);
  let x_lo = col.saturating_sub(1);
  let x_hi = (col + 1).min(w - 1);
  for y in y_lo..=y_hi {
    for x in x_lo..=x_hi {
      // `v`/`h` in upstream track the kernel row/column; the clamped loop
      // makes `y - row + 1` / `x - col + 1` their exact values.
      let wt = WEIGHT[y - row + 1][x - col + 1];
      let f = cfa.xtrans_color(y, x) as usize;
      sum[f] += raw.at(y, x) * wt;
      sum[f + 3] += wt;
    }
  }

  let r = out.red.row_mut(row);
  let g = out.green.row_mut(row);
  let b = out.blue.row_mut(row);
  match cfa.xtrans_color(row, col) {
    0 => {
      r[col] = raw.at(row, col);
      g[col] = sum[1] / sum[4];
      b[col] = sum[2] / sum[5];
    }
    1 => {
      if sum[3] == 0.0 {
        // At the 4 corner pixels it can happen that we have only green
        // pixels in the 2x2 area (upstream's comment).
        r[col] = raw.at(row, col);
        g[col] = raw.at(row, col);
        b[col] = raw.at(row, col);
      } else {
        r[col] = sum[0] / sum[3];
        g[col] = raw.at(row, col);
        b[col] = sum[2] / sum[5];
      }
    }
    _ => {
      r[col] = sum[0] / sum[3];
      g[col] = sum[1] / sum[4];
      b[col] = raw.at(row, col);
    }
  }
}

#[cfg(test)]
pub(crate) mod test_support {
  use crate::cfa::CfaDesc;

  /// The standard Fuji X-Trans 6x6 matrix (dcraw / LibRaw default).
  pub(crate) const XTRANS_6X6: [[u8; 6]; 6] = [
    [1, 1, 0, 1, 1, 2],
    [1, 1, 2, 1, 1, 0],
    [2, 0, 1, 0, 2, 1],
    [1, 1, 2, 1, 1, 0],
    [1, 1, 0, 1, 1, 2],
    [0, 2, 1, 2, 0, 1],
  ];

  pub(crate) fn std_cfa() -> CfaDesc {
    CfaDesc::xtrans_from_6x6(XTRANS_6X6)
  }
}
