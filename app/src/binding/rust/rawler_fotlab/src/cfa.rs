//! CFA plane decomposition for pre-demosaic, per-colour processing.
//!
//! Wraps rawler's [`CFAConfig`] and exposes a per-photosite *plane index* so that
//! downstream stages (dehaze, guided filter) can treat each colour sub-lattice
//! as an independent, regular grid.
//!
//! - 2×2 Bayer (RGGB / GRBG / BGGR / GBRG) is split into **four** planes
//!   `R / G1 / G2 / B`. The two greens become distinct planes, each a regular
//!   half-resolution grid — this is what lets a box-blur guided filter run on
//!   every plane without the merged-green quincunx problem documented in
//!   `FOTLAB-RAWLER-000010` (finding F5).
//! - Other CFAs (6×6 X-Trans, four-colour, …) fall back to grouping by raw
//!   colour value, matching rawler's `PlaneColor` behaviour. When a plane is
//!   not a single decimated grid its `offsets` has more than one entry and
//!   [`CfaPlanes::is_regular`] is `false`, signalling the consumer to skip the
//!   sub-lattice guided path and use a global per-plane floor instead.

use std::collections::HashMap;

use rawler::rawimage::CFAConfig;

/// Number of decimated samples along one axis: the photosites at
/// `offset, offset + period, …` that still land inside `extent`.
fn count_samples(extent: usize, offset: usize, period: usize) -> usize {
    let period = period.max(1);
    extent
        .saturating_sub(offset)
        .saturating_add(period - 1)
        / period
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Odd and even extents, both parities of offset: the sample count must be
    /// the number of usable photosites, never one past the last one.
    #[test]
    fn count_samples_matches_the_photosite_run() {
        for extent in [1usize, 2, 5, 11, 32] {
            for offset in [0usize, 1] {
                let got = count_samples(extent, offset, 2);
                let want = (offset..extent).step_by(2).count();
                assert_eq!(got, want, "extent {extent} offset {offset}");
            }
        }
    }

    /// Regression: `sublattice_dims` used the row offset for the width and the
    /// column offset for the height. Invisible for square-period CFAs with even
    /// dimensions, wrong for everything else.
    #[test]
    fn sublattice_dims_use_the_axis_matching_each_offset() {
        let planes = CfaPlanes {
            period: 2,
            nplanes: 1,
            lut: vec![0; 4],
            offsets: vec![vec![(1, 0)]], // row-offset 1, column-offset 0
            regular: true,
        };
        // Row-offset 1, column-offset 0 over an 11x10 frame:
        //   cols 0,2,4,6,8,10 -> 6 wide;  rows 1,3,5,7,9 -> 5 tall.
        assert_eq!(planes.sublattice_dims(11, 10, 0), (6, 5));
        let swapped = CfaPlanes {
            offsets: vec![vec![(0, 1)]], // row-offset 0, column-offset 1
            ..planes
        };
        // Row-offset 0, column-offset 1: rows 0,2,4,6,8 -> 5; cols 1,3,5,7,9 -> 5.
        assert_eq!(swapped.sublattice_dims(11, 10, 0), (5, 5));
    }
}

/// Per-CFA-colour plane decomposition.
pub struct CfaPlanes {
    /// Repeating CFA period (2 for Bayer, 6 for X-Trans).
    period: usize,
    /// Number of distinct planes.
    nplanes: usize,
    /// `period * period` lookup: `(row % period) * period + (col % period)` -> plane.
    lut: Vec<usize>,
    /// For each plane, the `(dr, dc)` offsets within the period that belong to it.
    offsets: Vec<Vec<(usize, usize)>>,
    /// `true` when every plane is a single decimated grid (box-blur safe).
    regular: bool,
}

impl CfaPlanes {
    /// Build the decomposition from a rawler CFA config.
    pub fn from_cfa(config: &CFAConfig) -> CfaPlanes {
        let period = config.cfa.width.max(config.cfa.height).max(1);

        if period == 2 {
            // Four distinct regular grids keyed by position — this is the whole
            // point: the two Bayer greens become two separate planes.
            let mut lut = vec![0usize; 4];
            let mut offsets: Vec<Vec<(usize, usize)>> = vec![Vec::new(), Vec::new(), Vec::new(), Vec::new()];
            for r in 0..2usize {
                for c in 0..2usize {
                    let p = r * 2 + c;
                    lut[r * 2 + c] = p;
                    offsets[p].push((r, c));
                }
            }
            CfaPlanes { period, nplanes: 4, lut, offsets, regular: true }
        } else {
            // Group by raw colour id (`CFA::color_at` returns the u8 colour value).
            let mut map: HashMap<usize, usize> = HashMap::new();
            let mut offsets: Vec<Vec<(usize, usize)>> = Vec::new();
            let mut lut = vec![0usize; period * period];
            for r in 0..period {
                for c in 0..period {
                    let color = config.cfa.color_at(r, c);
                    let p = *map.entry(color).or_insert_with(|| {
                        offsets.push(Vec::new());
                        offsets.len() - 1
                    });
                    lut[r * period + c] = p;
                    offsets[p].push((r, c));
                }
            }
            let regular = offsets.iter().all(|o| o.len() == 1);
            CfaPlanes { period, nplanes: offsets.len(), lut, offsets, regular }
        }
    }

    /// Build a decomposition straight from period offsets (test only).
    ///
    /// [`CfaPlanes::from_cfa`] needs a real rawler `CFAConfig`; unit tests want
    /// to state a layout — Bayer, X-Trans, a plane with no sub-lattice at all —
    /// without dragging a sensor description along.
    #[cfg(test)]
    pub(crate) fn from_offsets(offsets: Vec<Vec<(usize, usize)>>, period: usize) -> CfaPlanes {
        let period = period.max(1);
        let mut lut = vec![0usize; period * period];
        for (p, members) in offsets.iter().enumerate() {
            for &(dr, dc) in members {
                if dr < period && dc < period {
                    lut[dr * period + dc] = p;
                }
            }
        }
        let regular = offsets.iter().all(|members| members.len() == 1);
        let nplanes = offsets.len();
        CfaPlanes { period, nplanes, lut, offsets, regular }
    }

    /// Single global plane for non-CFA input (pre-coloured RGB, `cpp > 1`,
    /// monochrome). Marks itself non-regular so the algorithm applies the old
    /// global-floor behaviour instead of a sub-lattice guided path.
    pub fn trivial() -> CfaPlanes {
        CfaPlanes {
            period: 1,
            nplanes: 1,
            lut: vec![0],
            offsets: vec![vec![(0, 0)]],
            regular: false,
        }
    }

    /// Plane index of the photosite at `(row, col)`.
    #[inline]
    pub fn plane_at(&self, row: usize, col: usize) -> usize {
        self.lut[(row % self.period) * self.period + (col % self.period)]
    }

    pub fn nplanes(&self) -> usize {
        self.nplanes
    }

    pub fn period(&self) -> usize {
        self.period
    }

    /// `true` when every plane is a single decimated grid (box-blur safe).
    pub fn is_regular(&self) -> bool {
        self.regular
    }

    /// For a regular plane, its single `(dr, dc)` offset within the period.
    ///
    /// # Panics
    /// Panics if the plane is not regular; check [`CfaPlanes::is_regular`] first.
    pub fn offset(&self, plane: usize) -> (usize, usize) {
        self.offsets[plane][0]
    }

    /// Dimensions `(gw, gh)` of the decimated sub-lattice grid for a regular plane.
    ///
    /// # Panics
    /// Panics if the plane is not regular.
    pub fn sublattice_dims(&self, width: usize, height: usize, plane: usize) -> (usize, usize) {
        let (dr, dc) = self.offset(plane);
        // Width follows the *column* offset and height the *row* offset — they
        // were swapped here, which only stayed invisible because every CFA met
        // so far is square-perioded (2, 6) and evenly dimensioned. It also
        // mattered less than it should have: consumers re-derive this shape from
        // the same `period` when they merge the planes back together, so a wrong
        // count only showed up as a tail of samples past the frame edge.
        let gw = count_samples(width, dc, self.period);
        let gh = count_samples(height, dr, self.period);
        (gw, gh)
    }
}
