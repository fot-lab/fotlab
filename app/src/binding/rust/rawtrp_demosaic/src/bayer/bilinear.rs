//! Bilinear Bayer demosaic.
//!
//! Ported from `external/RawTherapee/rtengine/bayer_bilinear_demosaic.cc`
//! ("copyright (c) 2020 Ingo Weyrich <heckflosse67@gmx.de>", GPL-3.0) —
//! `RawImageSource::bayer_bilinear_demosaic()`.
//!
//! Upstream describes it as "optimized for speed, intended use is for flat
//! regions of dual-demosaic": it is the low-contrast fallback of the
//! `dual_demosaic_RT` hybrids, and is also exposed standalone here. It is the
//! simplest kernel in the port and the one every other kernel's plumbing can be
//! checked against.
//!
//! ## Fidelity notes
//!
//! * The upstream loop body writes `green`, then whichever of `red`/`blue` is not
//!   the row's non-green colour (`nonGreen1`), then the other (`nonGreen2`). The
//!   pointer swap on a blue row is reproduced as a swap of the two output row
//!   slices.
//! * Upstream walks columns as `j, j+1` pairs starting at `2 - (FC(i, 1) & 1)` so
//!   each pair **begins on a green pixel** — which is what makes
//!   `green[i][j] = rawData[i][j]` valid without a special case. This port walks
//!   the interior column by column instead; the two branch bodies are upstream's
//!   `j` and `j + 1` bodies verbatim (same four terms, same summation order), and
//!   the per-column form also covers column `1` and column `w - 2`, which the pair
//!   loop skips whenever its start is 2. See the note at the loop.
//! * `blend` is the `dual_demosaic_RT` blend mask: the result is
//!   `intp(blend, existing, bilinear)` = `blend*existing + (1-blend)*bilinear`.
//!   Upstream never calls the kernel without a mask; passing `None` here means an
//!   all-zero mask, i.e. pure bilinear, which is how the standalone candidate
//!   uses it (`intp(0, x, y) == y` exactly).
//! * Upstream has **no border fill at all** — `bayer_bilinear_demosaic` is called
//!   only by `dual_demosaic_RT` (`dual_demosaic_RT.cc:115`), on top of the base
//!   algorithm's already-complete planes, so the frame comes from there. A
//!   standalone kernel would leave rows `0`/`H-1` and columns `0`/`W-1` at zero, so
//!   this port finishes with `border_interpolate(…, 1, …)`. That is an **addition**,
//!   not a ported line, and it is why the standalone candidate produces a complete
//!   image — the interior comes from the loop above, the one-pixel ring from here.
//! * `#pragma omp parallel for` over rows becomes rayon over rows. Each row
//!   writes only its own row of the three output planes and reads only
//!   `rawData`/`blend`, so the split needs no fix-up pass and is bit-identical to
//!   the sequential result.

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::border::border_interpolate;
use crate::cfa::CfaDesc;
use crate::math::intp;
use crate::{Error, Rgb};

/// `RawImageSource::bayer_bilinear_demosaic(blend, rawData, red, green, blue)`.
///
/// `raw` is the `width x height` mosaic; the result is three `width x height`
/// planes with the border ring filled by `border_interpolate(…, 1, …)`.
pub fn bayer_bilinear_demosaic(cfa: &CfaDesc, blend: Option<&Array2D<f32>>, raw: &Array2D<f32>) -> Result<Rgb, Error> {
  let (w, h) = (raw.width(), raw.height());
  if w < 4 || h < 4 {
    return Err(Error::Shape(format!("bayer_bilinear: mosaic too small: {w}x{h}")));
  }
  if cfa.has_fourth_colour() {
    // Upstream has no guard here (it is only ever reached with an RGB CFA), but a
    // fourth colour would silently produce wrong colours, so refuse it and let
    // the caller fall back.
    return Err(Error::UnsupportedCfa("bayer_bilinear"));
  }

  let mut out = Rgb::new(w, h);

  // `blend.map_or(0.0, …)` == an all-zero mask == pure bilinear.
  let bl = |i: usize, j: usize| blend.map_or(0.0f32, |b| b.at(i, j));

  // `#pragma omp parallel for` over `i in 1..H-1`.
  out
    .red
    .par_rows_mut()
    .zip(out.green.par_rows_mut())
    .zip(out.blue.par_rows_mut())
    .enumerate()
    .for_each(|(i, ((red_row, green_row), blue_row))| {
      if i == 0 || i == h - 1 {
        return;
      }

      // Blue row => nonGreen1 is blue, nonGreen2 is red (upstream swaps the
      // pointers); otherwise nonGreen1 is red and nonGreen2 is blue.
      let is_blue_row = cfa.fc(i, 0) == 2 || cfa.fc(i, 1) == 2;
      let (non_green1, non_green2) =
        if is_blue_row { (&mut *blue_row, &mut *red_row) } else { (&mut *red_row, &mut *blue_row) };

      // Upstream unrolls this as a two-column pair loop that "always begins with
      // a green pixel" (`j = 2 - (FC(i, 1) & 1)`, step 2, while `j < W - 2`).
      // Writing it per column is the same arithmetic — the branches below are
      // upstream's `j` and `j + 1` bodies verbatim, with `j + 1` re-indexed to
      // `j` (same four terms in the same summation order) — but it also covers
      // the two halves upstream silently skips: when the start is 2 the pairs
      // are (2,3), (4,5), …, so column 1 and column `w - 2` are never written.
      // Upstream gets away with that because `bayer_bilinear_demosaic` is only
      // ever called by `dual_demosaic_RT` on top of the base algorithm's
      // already-complete planes (`dual_demosaic_RT.cc:115`), where those
      // columns keep that algorithm's values. This port exposes the kernel
      // standalone, so the planes have to come out complete.
      for j in 1..w - 1 {
        if cfa.is_green(i, j) {
          // Green site: keep the sample, average the two non-green colours
          // horizontally / vertically.
          green_row[j] = intp(bl(i, j), green_row[j], raw.at(i, j));
          non_green1[j] = intp(bl(i, j), non_green1[j], (raw.at(i, j - 1) + raw.at(i, j + 1)) * 0.5);
          non_green2[j] = intp(bl(i, j), non_green2[j], (raw.at(i - 1, j) + raw.at(i + 1, j)) * 0.5);
        } else {
          // Non-green site: keep the sample, take green from the four
          // orthogonal neighbours and the opposite colour from the four
          // diagonals.
          non_green1[j] = intp(bl(i, j), non_green1[j], raw.at(i, j));
          green_row[j] = intp(
            bl(i, j),
            green_row[j],
            ((raw.at(i - 1, j) + raw.at(i, j - 1)) + (raw.at(i, j + 1) + raw.at(i + 1, j))) * 0.25,
          );
          non_green2[j] = intp(
            bl(i, j),
            non_green2[j],
            ((raw.at(i - 1, j - 1) + raw.at(i - 1, j + 1)) + (raw.at(i + 1, j - 1) + raw.at(i + 1, j + 1))) * 0.25,
          );
        }
      }
    });

  // Upstream: `border_interpolate(W, H, 1, rawData, red, green, blue)`.
  border_interpolate(cfa, raw, &mut out.red, &mut out.green, &mut out.blue, 1);

  Ok(out)
}

#[cfg(test)]
mod tests {
  use super::*;

  /// A flat grey mosaic must demosaic to the same flat value everywhere,
  /// including the border ring.
  #[test]
  fn flat_field_is_flat() {
    let (w, h) = (8usize, 8usize);
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let raw = Array2D::filled(w, h, 0.25);

    let rgb = bayer_bilinear_demosaic(&cfa, None, &raw).expect("demosaic");
    for i in 0..h {
      for j in 0..w {
        assert!((rgb.red.at(i, j) - 0.25).abs() < 1e-6, "R at {i},{j}");
        assert!((rgb.green.at(i, j) - 0.25).abs() < 1e-6, "G at {i},{j}");
        assert!((rgb.blue.at(i, j) - 0.25).abs() < 1e-6, "B at {i},{j}");
      }
    }
  }

  /// The CFA-sampled channel keeps its sample exactly at green/red/blue sites.
  #[test]
  fn sampled_channels_are_preserved() {
    let (w, h) = (8usize, 8usize);
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let mut raw = Array2D::new(w, h);
    // A single bright sample in the interior.
    raw.set(4, 4, 1.0);

    let rgb = bayer_bilinear_demosaic(&cfa, None, &raw).expect("demosaic");
    assert_eq!(cfa.fc(4, 4), 0, "expected R at 4,4 for RGGB");
    assert_eq!(rgb.red.at(4, 4), 1.0, "the sampled channel is kept verbatim");
    assert!(rgb.green.at(4, 4) < 1.0, "the other channels are interpolated");
  }

  /// A non-zero blend mask selects the incoming plane: `intp(1, old, new) == old`.
  /// That identity is what makes `dual_demosaic_RT`'s mask safe, and it is the
  /// only reason the standalone candidate can pass `None` (an all-zero mask).
  #[test]
  fn blend_one_selects_the_incoming_plane() {
    let (w, h) = (8usize, 8usize);
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    let raw = Array2D::filled(w, h, 0.5);
    let ones = Array2D::filled(w, h, 1.0);

    let rgb = bayer_bilinear_demosaic(&cfa, Some(&ones), &raw).expect("demosaic");
    // Interior pixels keep the (zero) incoming planes; only the border ring, which
    // `border_interpolate` fills from `rawData`, is non-zero.
    assert_eq!(rgb.green.at(4, 4), 0.0);
    assert_eq!(rgb.red.at(4, 4), 0.0);
    assert!(rgb.green.at(0, 0) > 0.0, "border ring comes from border_interpolate");
  }
}
