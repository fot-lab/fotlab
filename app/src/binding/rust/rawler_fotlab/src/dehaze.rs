//! Dehaze orchestration — builds the CFA plane decomposition and delegates the
//! algorithm to [`crate::dehaze_guided_filter`].
//!
//! The actual pixel work (histogram floor, guided filter, spatial dehaze) lives
//! in `dehaze_guided_filter.rs`; this module only translates the rawler
//! `CFAConfig` into a [`crate::cfa::CfaPlanes`] and forwards the call. `develop.rs`
//! supplies the two optional guided-filter radii (`radius_dark`, `radius_guide`),
//! which are forwarded unchanged to the core.
//!
//! ## Contract
//!
//! `dehaze(pixels, width, height, strength, percentile, cfa, active,
//! radius_dark, radius_guide) -> pixels`. `strength = None` (or `0`) is identity;
//! `percentile = None` defaults to 1% (clamped to `[0, 1]` internally);
//! `radius_dark` / `radius_guide = None` default to `GUIDE_RADIUS` (8, clamped
//! to >= 1).
//!
//! `cfa = None` (non-CFA input) falls back to a single global plane, matching
//! the old behaviour; `cpp > 1` multi-channel buffers are left untouched by the
//! length check in the algorithm.

use rawler::rawimage::CFAConfig;

use crate::cfa::CfaPlanes;
use crate::dehaze_guided_filter::dehaze as dehaze_run;

/// Sub-lattice guided-filter radius (in sub-lattice pixels) and epsilon.
///
/// `GUIDE_RADIUS` sets the spatial extent of the haze field (a larger radius
/// yields a smoother, lower-frequency field). `GUIDE_EPS` is the guided-filter
/// regularisation — smaller keeps more edges, larger smooths more.
const GUIDE_RADIUS: usize = 8;
const GUIDE_EPS: f32 = 0.01;

pub(crate) fn dehaze(
    pixels: Vec<f32>,
    width: usize,
    height: usize,
    strength: Option<f32>,
    percentile: Option<f32>,
    cfa: Option<&CFAConfig>,
    active: Option<(usize, usize, usize, usize)>,
    radius_dark: Option<i32>,
    radius_guide: Option<i32>,
) -> Vec<f32> {
    let dark_radius = radius_dark.unwrap_or(GUIDE_RADIUS as i32).max(1) as usize;
    let guide_radius = radius_guide.unwrap_or(GUIDE_RADIUS as i32).max(1) as usize;
    match cfa {
        Some(config) => {
            let planes = CfaPlanes::from_cfa(config);
            dehaze_run(pixels, width, height, strength, percentile, &planes, active, dark_radius, guide_radius, GUIDE_EPS)
        }
        None => {
            // Non-CFA input: single global plane (no spatial field).
            let planes = CfaPlanes::trivial();
            dehaze_run(pixels, width, height, strength, percentile, &planes, active, dark_radius, guide_radius, GUIDE_EPS)
        }
    }
}
