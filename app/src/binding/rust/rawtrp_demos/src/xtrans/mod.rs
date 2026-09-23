//! Ported **X-Trans** demosaic kernels — RawTherapee's Fuji X-Trans paths from
//! `rtengine/xtrans_demosaic.cc`.
//!
//! Scope follows the colour-matrix criterion of `FOTLAB-NATIVE-000004` rev 12:
//! upstream computes `xyz_cam` unconditionally (`xtrans_demosaic.cc:217-228`)
//! but only the `useCieLab` **3-pass** path ever reads it (`:656`) — so
//! [`one_pass`](one_pass) and [`fast`](fast) are matrix-free and ported here,
//! while `three_pass`/`four_pass` stay parked out of
//! [`algo::IMPLEMENTED_XTRANS`](crate::algo::IMPLEMENTED_XTRANS) until a matrix
//! supplier is decided, exactly like Bayer's AHD/EAHD.
//!
//! The two dcraw-style lookups the whole module is built on:
//!
//! * `fcol(row, col)`  = `xtrans[row % 6][col % 6]` — our
//!   [`CfaDesc::xtrans_color`](crate::CfaDesc::xtrans_color), identical.
//! * `isgreen(row, col)` = `xtrans[row % 3][col % 3] & 1` — deliberately
//!   **3-periodic**, not 6. The X-Trans layout guarantees the green/non-green
//!   map is 3-periodic even though the R/B assignment is not (row 2 and row 5
//!   of the standard matrix differ in colour but agree in greenness). The
//!   kernels lean on this everywhere; [`one_pass`] additionally derives its
//!   `sgrow`/`sgcol` solitary-green anchor and its `allhex` hexagon tables
//!   from pure `%3` walks.

pub mod border;
pub mod fast;
pub mod one_pass;

use crate::cfa::CfaDesc;

/// `ISGREEN(row, col)` as `xtrans_demosaic.cc:120` defines it — note the `%3`.
#[inline(always)]
pub(crate) fn is_green(cfa: &CfaDesc, row: usize, col: usize) -> bool {
  cfa.xtrans[row % 3][col % 3] == 1
}
