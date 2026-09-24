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
        let gw = (width + self.period - 1 - dr) / self.period;
        let gh = (height + self.period - 1 - dc) / self.period;
        (gw, gh)
    }
}
