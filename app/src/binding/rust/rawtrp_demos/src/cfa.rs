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
//! ## A Bayer CFA has FOUR colour levels, not three
//!
//! This is the single most easily-misread part of the contract, and getting it
//! wrong silently breaks `vng4`:
//!
//! * dcraw's colour code is `0/1/2/3 = R/G1/B/G2` for a Bayer sensor
//!   (`dcraw.cc:173` — "Return values are either 0/1/2/3 = G/M/C/Y or
//!   0/1/2/3 = R/G1/B/G2"). The **two greens are distinct levels**, so a plain
//!   RGGB CFA is stored as `0xb4b4b4b4`, which RT spells out as
//!   "R G1 B G2" (`rawimage.cc:1373`).
//! * `RawImage::set_prefilters()` (`rawimage.h:50-56`) keeps that original in
//!   `prefilters` and then **folds G2 into G1** (`3 -> 1`) via
//!   `filters &= ~((filters & 0x55555555) << 1)`, so `0xb4b4b4b4` becomes
//!   `0x94949494`. That folded mask is what `RawImageSource::FC()`, `ISGREEN()`
//!   and `ISBLUE()` read, and hence what `border_interpolate`, IGV, DCB and the
//!   bilinear kernel see — a 3-colour view.
//! * RT relies on the 4-colour original elsewhere: `copyOriginalPixels` restores
//!   it with the comment "we need 4 blacks for bayer processing"
//!   (`rawimagesource.cc:2646`), and `camconst.cc:104` documents level 3 as
//!   "G2 = G1".
//!
//! So both masks are reproduced here:
//!
//! * [`CfaDesc::fc`] — the **folded** mask, i.e. RT's `FC()`. Three-valued.
//! * [`CfaDesc::fc_pre`] — the **unfolded** mask, i.e. the local
//!   `#define fc(row,col)` of `vng4_demosaic_RT.cc:62` which reads `prefilters`
//!   directly. Four-valued, and the reason VNG4 can interpolate *both* greens
//!   (its `color ^= 2` swaps G1/G2, and its `pix[ip[0] + 3]` reads the second
//!   green).
//!
//! [`CfaDesc::colors`] separately records how many colours the *sensor* has
//! (`ri->get_colors()`): `3` for a normal Bayer or X-Trans, `4` for a
//! four-colour CFA such as RGBE (`dcraw.cc:11065-11067`). That is the property
//! the kernels test before bailing out — it is **not** the same thing as "the
//! unfolded mask contains a 3", because for a normal Bayer it always does.

/// Bayer/X-Trans colour-filter-array description.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CfaDesc {
  /// `true` for a Bayer (2x2-periodic) sensor, `false` for X-Trans (6x6).
  pub is_bayer: bool,
  /// Colour levels the sensor reports, `ri->get_colors()`: `3` for a normal
  /// Bayer/X-Trans, `4` for RGBE-style four-colour CFAs.
  pub colors: u8,
  /// dcraw mask **after** `set_prefilters()` folded G2 into G1 — the mask
  /// `RawImageSource::FC()` reads.
  pub filters: u32,
  /// The same mask **before** the fold, as `set_prefilters()` saved it. Keeps
  /// G1 and G2 apart; only `vng4`'s local `fc()` macro reads it.
  pub prefilters: u32,
  /// X-Trans 6x6 colour table (`0 = R`, `1 = G`, `2 = B`); unused for Bayer.
  pub xtrans: [[u8; 6]; 6],
}

/// dcraw's mask field for the second green of a Bayer CFA.
const G2: u8 = 3;

impl CfaDesc {
  /// Build a Bayer CFA from its 2x2 tile, given in dcraw colour indices
  /// (`0 = R`, `1 = G`, `2 = B`) and laid out as `pattern[row][col]`.
  ///
  /// The tile is expanded over dcraw's lattice (periodic over 2 columns and 8
  /// rows, a 2-bit field per cell at bit offset
  /// `(((row << 1) & 14) + (col & 1)) << 1`) to build the **four-colour**
  /// original, and [`Self::filters`] is then derived from it by reproducing
  /// `set_prefilters()`'s fold. The second green — the one in the tile's odd row
  /// — is labelled [`G2`], matching RT's own `0xb4b4b4b4 // R G1 B G2` for RGGB;
  /// the folded result is the canonical dcraw constant, which the tests pin for
  /// all four Bayer orderings.
  pub fn bayer_from_2x2(pattern: [[u8; 2]; 2]) -> Self {
    let mut prefilters = 0u32;
    for row in 0..8usize {
      for col in 0..2usize {
        let shift = ((((row << 1) & 14) + (col & 1)) << 1) as u32;
        let mut level = pattern[row & 1][col & 1] & 3;
        // The tile's odd row holds G2; G1 lives in the even row.
        if level == 1 && (row & 1) == 1 {
          level = G2;
        }
        prefilters |= (level as u32) << shift;
      }
    }

    // `set_prefilters()`: keep the original, then fold G2 -> G1 so the
    // three-valued questions (`FC`/`ISGREEN`/`ISBLUE`) still answer.
    let filters = fold_prefilters(prefilters);

    Self { is_bayer: true, colors: 3, filters, prefilters, xtrans: [[0; 6]; 6] }
  }

  /// Build an X-Trans CFA from its 6x6 colour table (`0 = R`, `1 = G`, `2 = B`).
  ///
  /// `filters` is set to `9`, the sentinel `RawImage::isXtrans()` tests
  /// (`rawimage.h:249-252`).
  pub fn xtrans_from_6x6(xtrans: [[u8; 6]; 6]) -> Self {
    Self { is_bayer: false, colors: 3, filters: 9, prefilters: 9, xtrans }
  }

  /// `FC(row, col)` as `RawImageSource::FC()` computes it — the **folded** mask,
  /// so this is three-valued for any Bayer CFA.
  #[inline(always)]
  #[must_use]
  pub fn fc(&self, row: usize, col: usize) -> u32 {
    self.fc_i(row as i32, col as i32)
  }

  /// [`Self::fc`] with signed indices, reproducing C's wrap for the negative and
  /// `16`-row lookups the up-front kernel tables perform (`row << 1 & 14` on a
  /// two's-complement `i32` behaves exactly as it does in C).
  #[inline(always)]
  #[must_use]
  pub fn fc_i(&self, row: i32, col: i32) -> u32 {
    (self.filters >> shift_of(row, col)) & 3
  }

  /// The same lookup over the **unfolded** mask, i.e. the local
  /// `fc()` macro of `vng4_demosaic_RT.cc:62`. Returns `3` at the second green.
  #[inline(always)]
  #[must_use]
  pub fn fc_pre(&self, row: usize, col: usize) -> u32 {
    self.fc_pre_i(row as i32, col as i32)
  }

  /// [`Self::fc_pre`] with signed indices.
  #[inline(always)]
  #[must_use]
  pub fn fc_pre_i(&self, row: i32, col: i32) -> u32 {
    (self.prefilters >> shift_of(row, col)) & 3
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

  /// Whether the sensor has a colour beyond R/G/B (`ri->get_colors() > 3`), i.e.
  /// a CFA the RGB demosaic kernels cannot express. For a normal Bayer this is
  /// always `false` even though [`Self::fc_pre`] does return `3`, because that
  /// `3` is the second green.
  #[inline(always)]
  #[must_use]
  pub fn has_fourth_colour(&self) -> bool {
    self.colors > 3
  }
}

/// dcraw's cell bit offset: `(((row << 1) & 14) + (col & 1)) << 1`, with C's
/// two's-complement wrap for negative `row`/`col`. Always in `0..=30`.
#[inline(always)]
fn shift_of(row: i32, col: i32) -> u32 {
  ((((row << 1) & 14) + (col & 1)) << 1) as u32
}

/// `RawImage::set_prefilters()`'s fold of the second green into the first:
/// `filters &= ~((filters & 0x55555555) << 1)`.
///
/// Idempotent, and a no-op on a mask that has no `3` in it.
#[inline]
#[must_use]
pub fn fold_prefilters(prefilters: u32) -> u32 {
  prefilters & !((prefilters & 0x5555_5555) << 1)
}

#[cfg(test)]
mod tests {
  use super::CfaDesc;

  /// The four-colour original folds to the canonical dcraw constant, for all
  /// four Bayer orderings. This pins the G1/G2 labelling: if it is wrong for a
  /// pattern, the folded value stops matching dcraw.
  #[test]
  fn four_colour_originals_fold_to_the_dcraw_constants() {
    let cases = [
      ([[0, 1], [1, 2]], 0x9494_9494u32, "RGGB"),
      ([[2, 1], [1, 0]], 0x1616_1616, "BGGR"),
      ([[1, 0], [2, 1]], 0x6161_6161, "GRBG"),
      ([[1, 2], [0, 1]], 0x4949_4949, "GBRG"),
    ];
    for (pattern, expected, name) in cases {
      let cfa = CfaDesc::bayer_from_2x2(pattern);
      assert_eq!(cfa.filters, expected, "{name} folded mask");
    }
  }

  /// RGGB's unfolded mask is RT's own worked example, `0xb4b4b4b4 // R G1 B G2`.
  #[test]
  fn rggb_unfolded_keeps_both_greens_apart() {
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    assert_eq!(cfa.prefilters, 0xb4b4_b4b4);
    assert_eq!(cfa.fc(0, 0), 0); // R
    assert_eq!(cfa.fc(0, 1), 1); // G1
    assert_eq!(cfa.fc(1, 0), 1); // G2 reads as green through the folded mask
    assert_eq!(cfa.fc(1, 1), 2); // B
    assert_eq!(cfa.fc_pre(0, 1), 1, "the even-row green is G1");
    assert_eq!(cfa.fc_pre(1, 0), 3, "the odd-row green is G2");
  }

  #[test]
  fn bggr_is_the_transpose() {
    let cfa = CfaDesc::bayer_from_2x2([[2, 1], [1, 0]]);
    assert_eq!(cfa.filters, 0x1616_1616);
    assert!(cfa.is_blue(0, 0));
    assert_eq!(cfa.prefilters, 0x3636_3636);
  }

  /// A normal Bayer is a *three-colour* sensor even though its unfolded mask
  /// contains a `3` — the distinction `has_fourth_colour` must respect.
  #[test]
  fn a_normal_bayer_is_not_a_four_colour_cfa() {
    let cfa = CfaDesc::bayer_from_2x2([[0, 1], [1, 2]]);
    assert!((0..2).any(|i| (0..2).any(|j| cfa.fc_pre(i, j) == 3)), "the unfolded mask does carry G2");
    assert!(!cfa.has_fourth_colour(), "but the sensor still reports 3 colours");
  }

  #[test]
  fn xtrans_uses_the_filters_sentinel() {
    let cfa = CfaDesc::xtrans_from_6x6([[1; 6]; 6]);
    assert!(!cfa.is_bayer);
    assert_eq!(cfa.filters, 9);
    assert_eq!(cfa.xtrans_color(7, 8), 1);
    assert!(!cfa.has_fourth_colour());
  }
}
