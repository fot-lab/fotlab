//! CFA description — the whole "sensor state" a demosaic kernel needs.
//!
//! Ported from `external/RawTherapee/rtengine/rawimage.h`
//! (Copyright (c) 2004-2010 Gabor Horvath, GPL-3.0) and the `FC()` member of
//! `rawimagesource.cc:517`.
//!
//! RawTherapee's kernels are `RawImageSource` members that reach the outside
//! world through three things only: `W`/`H` (geometry, passed as arguments in
//! this port), `rawData` (the mosaic, passed as `&Array2D<f32>`) and the CFA
//! mask — `ri->FC()` / `ri->ISGREEN()` / `ri->ISBLUE()`. This type is that third
//! piece, so a kernel needs no `RawImageSource` (`FOTLAB-NATIVE-000004` D2,
//! `RAWTRP-DECODE-000003` §3.1).
//!
//! ## Two masks, on purpose
//!
//! Upstream keeps **two** copies of the colour mask and different kernels read
//! different ones — getting this wrong silently shifts every colour:
//!
//! * `filters` — **masked**. `RawImage::set_prefilters()` (`rawimage.h:50-56`)
//!   stores the original in `prefilters` and then folds a fourth colour into
//!   green (`3 -> 1`) via `filters &= ~((filters & 0x55555555) << 1)`, so a
//!   4-colour CFA still answers the 3-colour `ISGREEN`/`ISBLUE` questions. This
//!   is what `RawImageSource::FC()`, `ISGREEN()` and `ISBLUE()` read, and hence
//!   what `border_interpolate`, IGV, DCB and the bilinear kernel see.
//! * `prefilters` — **unmasked**. `vng4_demosaic_RT.cc:62` defines its *own*
//!   `fc()` macro over `prefilters`, deliberately keeping the 4th colour so its
//!   `fc(i,j) == 3` guard can detect a non-RGB CFA and bail out to IGV.
//!
//! Both are reproduced here. Colour indices are dcraw's: `0 = R`, `1 = G`,
//! `2 = B`, `3 = a fourth colour` (usually G2).

/// Bayer/X-Trans colour-filter-array description.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CfaDesc {
  /// `true` for a Bayer (2x2-periodic) sensor, `false` for X-Trans (6x6).
  pub is_bayer: bool,
  /// dcraw `filters` bitmask, **masked** as `set_prefilters()` leaves it.
  pub filters: u32,
  /// dcraw `filters` bitmask, **unmasked** (`prefilters`) — see the module doc.
  pub prefilters: u32,
  /// X-Trans 6x6 colour table (`0 = R`, `1 = G`, `2 = B`); unused for Bayer.
  pub xtrans: [[u8; 6]; 6],
}

impl CfaDesc {
  /// Build a Bayer CFA from its 2x2 tile, given in dcraw colour indices and laid
  /// out as `pattern[row][col]` for rows/cols `0..2`.
  ///
  /// The dcraw mask is periodic over 2 columns and 8 rows with a 2-bit field per
  /// cell at bit offset `(((row << 1) & 14) + (col & 1)) << 1`; repeating the 2x2
  /// tile over that lattice reproduces e.g. `RGGB -> 0x94949494`, the value the
  /// inventory doc quotes (`RAWTRP-DECODE-000003` §3.2).
  pub fn bayer_from_2x2(pattern: [[u8; 2]; 2]) -> Self {
    let mut filters = 0u32;
    for row in 0..8usize {
      for col in 0..2usize {
        let shift = ((((row << 1) & 14) + (col & 1)) << 1) as u32;
        filters |= ((pattern[row & 1][col & 1] as u32) & 3) << shift;
      }
    }

    // `set_prefilters()`: remember the original, then fold 3 -> 1 so the 3-colour
    // questions (`ISGREEN`/`ISBLUE`) still answer for a 4-colour CFA.
    let prefilters = filters;
    let filters = filters & !((filters & 0x5555_5555) << 1);

    Self { is_bayer: true, filters, prefilters, xtrans: [[0; 6]; 6] }
  }

  /// Build an X-Trans CFA from its 6x6 colour table (`0 = R`, `1 = G`, `2 = B`).
  ///
  /// `filters` is set to `9`, the sentinel `RawImage::isXtrans()` tests
  /// (`rawimage.h:249-252`).
  pub fn xtrans_from_6x6(xtrans: [[u8; 6]; 6]) -> Self {
    Self { is_bayer: false, filters: 9, prefilters: 9, xtrans }
  }

  /// `FC(row, col)` as `RawImageSource::FC()` computes it — the **masked** mask.
  #[inline(always)]
  #[must_use]
  pub fn fc(&self, row: usize, col: usize) -> u32 {
    (self.filters >> ((((row << 1) & 14) + (col & 1)) << 1)) & 3
  }

  /// The same lookup over the **unmasked** mask, i.e. the local `fc()` macro of
  /// `vng4_demosaic_RT.cc:62`.
  #[inline(always)]
  #[must_use]
  pub fn fc_pre(&self, row: usize, col: usize) -> u32 {
    (self.prefilters >> ((((row << 1) & 14) + (col & 1)) << 1)) & 3
  }

  /// `ISGREEN(row, col)`.
  #[inline(always)]
  #[must_use]
  pub fn is_green(&self, row: usize, col: usize) -> bool {
    self.fc(row, col) == 1
  }

  /// `ISBLUE(row, col)`.
  #[inline(always)]
  #[must_use]
  pub fn is_blue(&self, row: usize, col: usize) -> bool {
    self.fc(row, col) == 2
  }

  /// `ISRED(row, col)`.
  #[inline(always)]
  #[must_use]
  pub fn is_red(&self, row: usize, col: usize) -> bool {
    self.fc(row, col) == 0
  }

  /// X-Trans colour at `(row, col)` (`0 = R`, `1 = G`, `2 = B`).
  #[inline(always)]
  #[must_use]
  pub fn xtrans_color(&self, row: usize, col: usize) -> u8 {
    self.xtrans[row % 6][col % 6]
  }

  /// Whether the Bayer CFA contains a fourth colour (the `FC(i,j) == 3` guard the
  /// kernels use to fall back).
  #[inline]
  #[must_use]
  pub fn has_fourth_colour(&self) -> bool {
    (0..2).any(|i| (0..2).any(|j| self.fc_pre(i, j) == 3))
  }
}

#[cfg(test)]
mod tests {
  use super::CfaDesc;

  #[test]
  fn rggb_round_trips_to_the_dcraw_constant() {
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    assert_eq!(cfa.filters, 0x9494_9494);
    assert_eq!(cfa.fc(0, 0), 0); // R
    assert_eq!(cfa.fc(0, 1), 1); // G
    assert_eq!(cfa.fc(1, 0), 1); // G
    assert_eq!(cfa.fc(1, 1), 2); // B
  }

  #[test]
  fn bggr_is_the_transpose() {
    let cfa = CfaDesc::bayer_from_2x2([[2, 1], [1, 0]]);
    assert_eq!(cfa.filters, 0x1616_1616);
    assert!(cfa.is_blue(0, 0));
  }

  #[test]
  fn four_colour_masks_to_green_but_prefilters_keeps_it() {
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [3, 2]]);
    assert_eq!(cfa.fc_pre(1, 0), 3);
    assert_eq!(cfa.fc(1, 0), 1);
    assert!(cfa.has_fourth_colour());
  }
}
