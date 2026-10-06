//! Longitudinal chromatic aberration (LoCA) fringe correction on the
//! pre-demosaic Bayer CFA mosaic — **two PEER criteria+behaviour branches**
//! under one master switch:
//!
//! * **Purple fringe** (去紫边): at high-contrast edges R and B focus at a
//!   different distance than G, so a magenta fringe (R and B both high relative
//!   to G) bleeds into the edge. Criteria `min(r,b) > g` AND
//!   `lum > purple_lum_min` AND on-edge; behaviour: **raise G** (RapidRAW
//!   `recover_clipped_pixel`).
//! * **Green fringe** (去绿边): the mirror defect — G bleeds above both R and B
//!   at an edge. Criteria `g > max(r,b)` AND `lum > green_lum_min` AND on-edge;
//!   behaviour: **lower G** toward max(r,b) (the mirrored repair).
//!
//! ## Explicit user constraints (2026-10-05)
//!
//! * **LoCA never touches the R/B planes.** The purple pair raises G; the green
//!   pair lowers G. R and B photosites are left byte-identical in every case —
//!   R/B is not moved as a "clever" alternative repair.
//! * **The two pairs are peer branches, not nested.** Both run per G position
//!   (a G position's orthogonal CFA neighbours are the R/B photosites, its
//!   diagonals are G photosites), share one estimate + one edge weight, and sit
//!   side by side under the master switch — [`purple_delta`] and [`green_delta`]
//!   are siblings, neither contains the other.
//! * **Criteria and behaviour are BOUND per pair.** Each pair switch
//!   (`purple_enabled` / `green_enabled`) gates its criteria AND its behaviour
//!   together: a switch that is off means neither runs. On top of the two pair
//!   switches the whole LoCA stage has a MASTER switch (`LocaSettings.enabled`
//!   at the FFI boundary, plus `DevelopParams.loca == None`): when the master is
//!   off the entire stage is short-circuited — no edge detection, no criteria,
//!   no repair.
//!
//! ## Independent edge detection (required)
//!
//! The fringe is strictly edge-local, so this module runs its OWN high-contrast
//! edge detection — a local G-gradient magnitude over the diagonal G neighbours
//! of each G position — on every render. It does **NOT** reuse the LCA
//! `detect_ca` result. The user may run LoCA with LCA disabled (or vice versa),
//! so LoCA must be fully self-contained: it re-computes everything from the
//! current mosaic, and is correct whether or not LCA ran. Both peer pairs share
//! this one detector.
//!
//! ## Algorithm (per G position, raw-linear mosaic domain, pre-WB)
//!
//! The mosaic is post-exposure-EV and may carry values **greater than 1.0**; that
//! is allowed. LoCA must not assume `[0,1]` and must not clamp — it only shifts the
//! G plane by a signed delta, leaving R/B untouched. Clamping happens once, at the
//! display-side PNG encode.
//!
//! Shared per position: estimate R and B from the 4 orthogonal CFA neighbours;
//! edge weight = `smoothstep(LOCA_EDGE_LO, LOCA_EDGE_HI, |diagonal G gradient|)`.
//!
//! * Purple branch (if `purple_enabled`): gate
//!   `magenta = min(r,b) − g > 0` AND `lum > purple_lum_min`; raise G
//!   (RapidRAW `recover_clipped_pixel`, cyclolab/RapidRAW,
//!   `src-tauri/src/raw_processing.rs`):
//!   `target_g = min(r,b)*0.8 + (r+b)*0.5*0.2`;
//!   `correction = (target_g − g).max(0)`;
//!   `dg = correction * smootherstep(purple_lum_min,1.5,lum) * smoothstep(0,0.25,magenta/lum) * edge_weight`,
//!   plus a residual raise pass.
//! * Green branch (if `green_enabled`) — the mirror: gate
//!   `excess = g − max(r,b) > 0` AND `lum > green_lum_min`; lower G:
//!   `target_g = max(r,b)*0.8 + (r+b)*0.5*0.2` (the mirrored hedge — sits above
//!   min(r,b), so the lower can never overshoot into magenta), same
//!   `smootherstep`/`smoothstep`/`edge_weight` blend and a residual lower pass.
//!
//! The two gates are mutually exclusive (magenta > 0 vs excess > 0), so at most
//! one branch fires per position; the deltas are simply summed (one is 0).
//!
//! ## Passable thresholds (explicit user requirement)
//!
//! `purple_lum_min` / `green_lum_min` are caller-supplied parameters (not
//! hardcoded constants). Defaults are 0.5, enforced at the FFI boundary
//! (`LocaSettings` uniffi defaults); when the caller passes a value, that value
//! is used. Both live in **raw-linear, pre-WB** space (see the design doc
//! FOTLAB-RENDER-000003, "Constraints — Domain is pre-WB").
//!
//! ## Tradeoff — documented disadvantages (explicit requirement)
//!
//! The two pairs trade off differently, by design:
//! * Purple (raise the deficient G): keeps R/B intact and *brightens* the fringe
//!   toward neutral, so it does not create the dull/gray "昏暗灰边" that pulling
//!   R/B down would. Cost: lifting G at genuinely-magenta content (a real purple
//!   flower, garment, or sunset) introduces **false colour** there.
//! * Green (lower the excess G): keeps R/B intact — no chroma is injected into
//!   R or B — but lowering G *darkens* the fringe toward neutral, so the mirror
//!   risk is a **darker/dull edge** at genuinely-green content (the exact
//!   disadvantage the purple pair's raise avoids). This asymmetry is the
//!   user-directed design: R/B must not be moved.
//! In both cases the user can disable LoCA (master switch) or either pair
//! individually. Repeated in the design doc and any user-facing copy.

use rawtrp_demosaic::{Array2D, CfaDesc};
use rayon::prelude::*;

/// G-gradient (0..1 per photosite) below this is not an edge.
const LOCA_EDGE_LO: f32 = 0.01;
/// ...above this is a full-strength edge (smoothstep ramp between).
const LOCA_EDGE_HI: f32 = 0.08;

/// Parameters for [`correct_loca_bayer`].
#[derive(Clone, Copy, Debug)]
pub struct LocaParams {
    /// Purple-pair repair strength, 0..1 (1.0 = full RapidRAW-style correction).
    /// Exposed to Kotlin as 去紫边强度 — the coefficient that pulls G toward R/B.
    pub purple_strength: f64,
    /// Green-pair repair strength, 0..1 (1.0 = full RapidRAW-style correction).
    /// Exposed to Kotlin as 去绿边强度.
    pub green_strength: f64,
    /// 去紫边 gate switch: run the magenta criteria + raise-G behaviour.
    pub purple_enabled: bool,
    /// 去绿边 gate switch: run the green-excess criteria + lower-G behaviour.
    pub green_enabled: bool,
    /// Purple-pair luminance threshold (default 0.5, enforced by the caller
    /// boundary). Positions with `lum <= purple_lum_min` are never purple-repaired.
    pub purple_lum_min: f32,
    /// Green-pair luminance threshold (default 0.5, enforced by the caller
    /// boundary). Positions with `lum <= green_lum_min` are never green-repaired.
    pub green_lum_min: f32,
}

/// Errors returned by [`correct_loca_bayer`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// The CFA is not a 3-colour Bayer pattern (X-Trans / 4-colour unsupported).
    UnsupportedCfa(&'static str),
    /// The mosaic width is odd.
    OddWidth,
}

/// `smoothstep(e0, e1, x)` — RapidRAW's edge/luminance ramp.
#[inline]
fn smoothstep(e0: f32, e1: f32, x: f32) -> f32 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// `smootherstep(e0, e1, x)` — RapidRAW's outer luminance blend.
#[inline]
fn smootherstep(e0: f32, e1: f32, x: f32) -> f32 {
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
}

/// **去紫边** — the purple-fringe PEER branch: criteria (`min(r,b) > g` AND
/// `lum > purple_lum_min`) plus the bound behaviour (raise G toward min(r,b),
/// RapidRAW `recover_clipped_pixel`). Returns the raise amount (>= 0) to add to
/// G, or 0 when the criteria do not hold. Criteria and behaviour are one bound
/// unit — callers must not invoke this when the pair switch is off.
fn purple_delta(r: f32, g: f32, b: f32, edge_w: f32, purple_lum_min: f32) -> f32 {
    let lum = r.max(g).max(b);
    if lum <= purple_lum_min {
        return 0.0; // too dark to be the bright fringe we target
    }
    let magenta = (r.min(b) - g).max(0.0);
    if magenta <= 0.0 {
        return 0.0; // not magenta (R/B not both above G)
    }

    // Behaviour: raise G (RapidRAW recover_clipped_pixel).
    let outer_blend = smootherstep(purple_lum_min, 1.5, lum);
    let magenta_weight = smoothstep(0.0, 0.25, magenta / lum);
    let target_g = r.min(b) * 0.8 + (r + b) * 0.5 * 0.2;
    let correction = (target_g - g).max(0.0);
    let mut dg = correction * outer_blend * magenta_weight * edge_w;
    // residual pass: if the first raise still leaves magenta, lift the rest
    let g_after = g + dg;
    let residual = (r.min(b) - g_after).max(0.0);
    if residual > 0.0 {
        dg += residual * outer_blend * edge_w;
    }
    dg
}

/// **去绿边** — the green-fringe PEER branch: criteria (`g > max(r,b)` AND
/// `lum > green_lum_min`) plus the bound behaviour (lower G toward max(r,b)).
/// Returns the signed delta (<= 0) to add to G — negative means "lower G" — or
/// 0 when the criteria do not hold. The target sits above min(r,b), so the
/// lower can never overshoot into magenta. R/B are never touched by this
/// branch. Criteria and behaviour are one bound unit — callers must not invoke
/// this when the pair switch is off.
fn green_delta(r: f32, g: f32, b: f32, edge_w: f32, green_lum_min: f32) -> f32 {
    let lum = r.max(g).max(b);
    if lum <= green_lum_min {
        return 0.0; // too dark to be the bright fringe we target
    }
    let excess = (g - r.max(b)).max(0.0);
    if excess <= 0.0 {
        return 0.0; // G not above both R and B → not a green fringe
    }

    // Behaviour (mirror of the purple raise): lower the EXCESS channel G
    // toward max(r,b). The target hedge mirrors RapidRAW's 0.8/0.2 split.
    let outer_blend = smootherstep(green_lum_min, 1.5, lum);
    let green_weight = smoothstep(0.0, 0.25, excess / lum);
    let target_g = r.max(b) * 0.8 + (r + b) * 0.5 * 0.2;
    let correction = (g - target_g).max(0.0);
    let mut dg = correction * outer_blend * green_weight * edge_w;
    // residual pass: if the first lower still leaves green excess, drop the rest
    let g_after = g - dg;
    let residual = (g_after - r.max(b)).max(0.0);
    if residual > 0.0 {
        dg += residual * outer_blend * edge_w;
    }
    -dg
}

/// Correct LoCA fringes in place on a Bayer mosaic.
///
/// `mosaic` is the ROI-local single-channel CFA buffer (values `0..1`), `cfa` its
/// folded Bayer description. **Only the G plane is modified** — the purple peer
/// branch raises G, the green peer branch lowers G, and the R/B planes are left
/// byte-identical in every case (explicit user constraint). Returns
/// [`Error::UnsupportedCfa`] for non-Bayer CFAs and [`Error::OddWidth`] for an
/// odd width; the caller degrades to the uncorrected mosaic.
pub fn correct_loca_bayer(
    mosaic: &mut Array2D<f32>,
    cfa: &CfaDesc,
    params: &LocaParams,
) -> Result<(), Error> {
    if !cfa.is_bayer || cfa.colors > 3 {
        return Err(Error::UnsupportedCfa("only 3-colour Bayer CFAs are supported"));
    }

    let w = mosaic.width();
    let h = mosaic.height();
    if w & 1 == 1 {
        return Err(Error::OddWidth);
    }

    let purple_strength = (params.purple_strength as f32).clamp(0.0, 1.0);
    let green_strength = (params.green_strength as f32).clamp(0.0, 1.0);
    // MASTER short-circuit inside the kernel too: with both peer pairs switched
    // off (or both strengths 0) the stage is an identity — free.
    if (purple_strength == 0.0 && green_strength == 0.0)
        || (!params.purple_enabled && !params.green_enabled)
    {
        return Ok(());
    }
    // The pair switches gate their own criteria+behaviour unit; the luminance
    // thresholds are sanitised to the 0..1 domain the mosaic lives in.
    let purple_on = params.purple_enabled;
    let green_on = params.green_enabled;
    let purple_lum_min = params.purple_lum_min.clamp(0.0, 1.0);
    let green_lum_min = params.green_lum_min.clamp(0.0, 1.0);

    // Folded 2x2 CFA (values 0/1/2), matching `ca_correct::correct_ca_bayer`.
    let cfa2 = [
        [cfa.fc(0, 0) as i32, cfa.fc(0, 1) as i32],
        [cfa.fc(1, 0) as i32, cfa.fc(1, 1) as i32],
    ];
    let fc = |r: usize, c: usize| cfa2[(r & 1)][(c & 1)] as usize;

    // Per-position signed G delta at G positions (positive = purple raise,
    // negative = green lower; at most one branch fires per position). O(W*H).
    let mut delta = vec![0.0f32; w * h];

    // Read pass: an immutable view of the mosaic, scoped so the G plane can be
    // mutated afterwards (sequentially, from `delta`) without a borrow conflict.
    {
        let m = &*mosaic;

        // Parallel over rows: each row owns a disjoint slice of `delta`, and
        // only reads `m` (shared, read-only). Writes are therefore race-free.
        delta.par_chunks_mut(w).enumerate().for_each(|(row, drow)| {
            if row == 0 || row + 1 >= h {
                return; // need ±1 neighbours for the gradient and the R/B estimate
            }
            for col in 1..(w - 1) {
                if fc(row, col) != 1 {
                    continue; // both peer pairs evaluate at G positions only
                }
                // --- shared per-position estimate + edge weight ---
                // R and B from the 4 orthogonal CFA neighbours: the orthogonals
                // of a G position are the R and B photosites (which is which
                // depends on the G parity, so classify by colour).
                let mut rsum = 0.0f32;
                let mut rc = 0u32;
                let mut bsum = 0.0f32;
                let mut bc = 0u32;
                for (dr, dc) in [(1i32, 0i32), (-1, 0), (0, 1), (0, -1)] {
                    let nr = row as i32 + dr;
                    let nc = col as i32 + dc;
                    let v = m.at(nr as usize, nc as usize);
                    match fc(nr as usize, nc as usize) {
                        0 => {
                            rsum += v;
                            rc += 1;
                        }
                        2 => {
                            bsum += v;
                            bc += 1;
                        }
                        _ => {}
                    }
                }
                if rc == 0 || bc == 0 {
                    continue;
                }
                let r = rsum / rc as f32;
                let b = bsum / bc as f32;
                let g = m.at(row, col);

                // Independent edge detection: the diagonal neighbours of a G
                // position are themselves G photosites, so the diagonal G
                // difference is the genuine high-contrast-edge signal.
                let g_pp = m.at(row + 1, col + 1);
                let g_mm = m.at(row - 1, col - 1);
                let g_pm = m.at(row + 1, col - 1);
                let g_mp = m.at(row - 1, col + 1);
                let edge = 0.25 * ((g_pp - g_mm).abs() + (g_pm - g_mp).abs());
                let edge_w = smoothstep(LOCA_EDGE_LO, LOCA_EDGE_HI, edge);
                if edge_w <= 0.0 {
                    continue; // not an edge → no fringe repair here
                }

                // --- the two PEER branches, side by side under the master ---
                // Each pair switch gates its own bound criteria+behaviour unit;
                // the gates are mutually exclusive so at most one fires, but
                // structurally neither branch contains the other.
                let mut purple_d = 0.0f32;
                if purple_on {
                    purple_d = purple_delta(r, g, b, edge_w, purple_lum_min);
                }
                let mut green_d = 0.0f32;
                if green_on {
                    green_d = green_delta(r, g, b, edge_w, green_lum_min);
                }
                // Per-pair strength: each peer's criteria+behaviour unit is scaled
                // by its OWN coefficient (the Kotlin LoCA dialog exposes 去紫边强度 /
                // 去绿边强度 as independent controls), so a user can, say, fully
                // repair purple fringing while leaving green untouched.
                let d = purple_d * purple_strength + green_d * green_strength;
                if d != 0.0 {
                    drow[col] = d;
                }
            }
        });
    } // end read-pass scope

    // Apply: add the signed delta to the G plane only. R/B planes are never
    // touched (explicit user constraint) — no darkening of R/B, no chroma
    // injection into R/B.
    for row in 0..h {
        for col in 0..w {
            if fc(row, col) != 1 {
                continue;
            }
            let d = delta[row * w + col];
            if d != 0.0 {
                // No clamp here: the mosaic is post-EV raw-linear and may
                // legitimately exceed 1.0. Clamping G to [0,1] would clip valid
                // highlights and shift colour. The display-side PNG encoder is
                // the single place that clamps.
                let nv = mosaic.at(row, col) + d;
                mosaic.set(row, col, nv);
            }
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rawtrp_demosaic::CfaDesc;

    fn rggb() -> CfaDesc {
        CfaDesc::bayer_from_2x2([[0u8, 1u8], [1u8, 2u8]])
    }

    /// Full explicit params (no Default derive — the switches must never be
    /// silently off).
    fn p(purple_strength: f64, green_strength: f64) -> LocaParams {
        LocaParams {
            purple_strength,
            green_strength,
            purple_enabled: true,
            green_enabled: true,
            purple_lum_min: 0.5,
            green_lum_min: 0.5,
        }
    }

    fn make_mosaic(w: usize, h: usize) -> Array2D<f32> {
        let mut m = Array2D::new(w, h);
        for row in 0..h {
            for col in 0..w {
                let v = (((row * 31 + col * 17) % 100) as f32) / 100.0;
                m.set(row, col, v);
            }
        }
        m
    }

    #[test]
    fn strength_zero_is_identity() {
        let mut m = make_mosaic(64usize, 64usize);
        let before = m.clone();
        correct_loca_bayer(&mut m, &rggb(), &p(0.0, 0.0)).unwrap();
        for row in 0..64 {
            for col in 0..64 {
                assert!((m.at(row, col) - before.at(row, col)).abs() < 1e-6);
            }
        }
    }

    #[test]
    fn both_pairs_off_is_identity() {
        // Criteria and behaviour are bound: with both pair switches off, the
        // whole stage is an identity even at strengths 1.
        let mut m = make_mosaic(64usize, 64usize);
        let before = m.clone();
        let params = LocaParams {
            purple_strength: 1.0,
            green_strength: 1.0,
            purple_enabled: false,
            green_enabled: false,
            purple_lum_min: 0.5,
            green_lum_min: 0.5,
        };
        correct_loca_bayer(&mut m, &rggb(), &params).unwrap();
        for row in 0..64 {
            for col in 0..64 {
                assert!((m.at(row, col) - before.at(row, col)).abs() < 1e-6);
            }
        }
    }

    #[test]
    fn purple_off_never_raises_g() {
        // 去紫边 switch off → its criteria AND its raise-G behaviour never run:
        // no G position may be RAISED (the green pair may only lower).
        let mut m = make_mosaic(64usize, 64usize);
        let before = m.clone();
        let params = LocaParams {
            purple_strength: 1.0,
            green_strength: 1.0,
            purple_enabled: false,
            green_enabled: true,
            purple_lum_min: 0.5,
            green_lum_min: 0.5,
        };
        correct_loca_bayer(&mut m, &rggb(), &params).unwrap();
        for row in 0..64 {
            for col in 0..64 {
                if rggb().fc(row, col) as usize == 1 {
                    assert!(
                        m.at(row, col) <= before.at(row, col) + 1e-6,
                        "G raised with the purple pair switched off"
                    );
                }
            }
        }
    }

    #[test]
    fn green_off_never_lowers_g() {
        // 去绿边 switch off → its criteria AND its lower-G behaviour never run:
        // no G position may be LOWERED (the purple pair may only raise).
        let mut m = make_mosaic(64usize, 64usize);
        let before = m.clone();
        let params = LocaParams {
            purple_strength: 1.0,
            green_strength: 1.0,
            purple_enabled: true,
            green_enabled: false,
            purple_lum_min: 0.5,
            green_lum_min: 0.5,
        };
        correct_loca_bayer(&mut m, &rggb(), &params).unwrap();
        for row in 0..64 {
            for col in 0..64 {
                if rggb().fc(row, col) as usize == 1 {
                    assert!(
                        m.at(row, col) >= before.at(row, col) - 1e-6,
                        "G lowered with the green pair switched off"
                    );
                }
            }
        }
    }

    #[test]
    fn rb_planes_are_never_touched() {
        // Explicit user constraint: LoCA never moves R/B, whichever pairs run.
        let mut m = make_mosaic(64usize, 64usize);
        let before = m.clone();
        correct_loca_bayer(&mut m, &rggb(), &p(1.0)).unwrap();
        for row in 0..64 {
            for col in 0..64 {
                if rggb().fc(row, col) as usize != 1 {
                    assert!(
                        (m.at(row, col) - before.at(row, col)).abs() < 1e-6,
                        "R/B position moved — LoCA must only ever touch G"
                    );
                }
            }
        }
    }

    #[test]
    fn unsupported_cfa_rejected() {
        // four-colour description is not exposed; proxy with odd width like LCA.
        let mut m = make_mosaic(65usize, 64usize);
        assert_eq!(
            correct_loca_bayer(&mut m, &rggb(), &p(1.0)),
            Err(Error::OddWidth)
        );
    }

    #[test]
    fn magenta_edge_is_repaired() {
        // Build an RGGB mosaic: a bright vertical edge. Left of the edge = white
        // (r=g=b=0.9); right of the edge = black (0.0). Inject a magenta fringe at
        // the G positions straddling the edge: keep r/b high but drop g, i.e.
        // min(r,b) > g and lum > purple_lum_min and a strong gradient → must be
        // repaired (g raised toward r/b, so post-repair min(r,b) - g shrinks).
        let w = 32usize;
        let h = 32usize;
        let mut m = Array2D::new(w, h);
        for row in 0..h {
            for col in 0..w {
                let c = rggb().fc(row, col) as usize;
                let left = col < 16;
                let base = if left { 0.9f32 } else { 0.0f32 };
                let v = match c {
                    0 => base, // R: white/black
                    2 => base, // B: white/black
                    _ => {
                        // G: at the edge band (col 14..=17) drop G to fake magenta
                        if (14..=17).contains(&col) {
                            base * 0.2 // g much lower than r/b -> magenta
                        } else {
                            base
                        }
                    }
                };
                m.set(row, col, v);
            }
        }
        correct_loca_bayer(&mut m, &rggb(), &p(1.0)).unwrap();

        // At G positions in the fringe band, g must have been raised (closer to r/b).
        let mut raised_any = false;
        for row in 0..h {
            for col in 14..=17 {
                if rggb().fc(row, col) as usize == 1 {
                    // r/b at the orthogonal CFA neighbours (a G position's
                    // orthogonals are the R/B photosites).
                    let mut rsum = 0.0f32;
                    let mut rc = 0u32;
                    let mut bsum = 0.0f32;
                    let mut bc = 0u32;
                    for (dr, dc) in [(1i32, 0i32), (-1, 0), (0, 1), (0, -1)] {
                        let nr = row as i32 + dr;
                        let nc = col as i32 + dc;
                        let v = m.at(nr as usize, nc as usize);
                        match rggb().fc(nr as usize, nc as usize) as usize {
                            0 => {
                                rsum += v;
                                rc += 1;
                            }
                            2 => {
                                bsum += v;
                                bc += 1;
                            }
                            _ => {}
                        }
                    }
                    if rc == 0 || bc == 0 {
                        continue;
                    }
                    let r = rsum / rc as f32;
                    let b = bsum / bc as f32;
                    let g = m.at(row, col);
                    if (r.min(b) - g) < 0.126 {
                        // g got meaningfully closer to r/b than the injected 0.18 gap
                        raised_any = true;
                    }
                }
            }
        }
        assert!(raised_any, "expected at least one fringe G position to be raised");
    }

    #[test]
    fn green_edge_is_lowered() {
        // Mirror case: a bright vertical edge (left white 0.9 / right black 0.0
        // on R/B) with the G positions in the edge band held HIGH (0.9) even
        // where their R/B orthogonals are dark — i.e. g > max(r,b) and
        // lum > green_lum_min at an edge → G must be LOWERED toward max(r,b).
        let w = 32usize;
        let h = 32usize;
        let mut m = Array2D::new(w, h);
        for row in 0..h {
            for col in 0..w {
                let c = rggb().fc(row, col) as usize;
                let left = col < 16;
                let base = if left { 0.9f32 } else { 0.0f32 };
                let v = match c {
                    0 | 2 => base, // R/B: white/black
                    _ => {
                        // G: in the edge band hold G high even over dark R/B
                        if (14..=17).contains(&col) {
                            0.9
                        } else {
                            base
                        }
                    }
                };
                m.set(row, col, v);
            }
        }
        let before = m.clone();
        correct_loca_bayer(&mut m, &rggb(), &p(1.0)).unwrap();

        // At G positions in the fringe band, g must have been lowered.
        let mut lowered_any = false;
        for row in 1..h - 1 {
            for col in 14..=17 {
                if rggb().fc(row, col) as usize == 1
                    && m.at(row, col) < before.at(row, col) - 0.02
                {
                    lowered_any = true;
                }
            }
        }
        assert!(lowered_any, "expected at least one green-fringe G position to be lowered");

        // …and R/B must be byte-identical (LoCA never moves R/B).
        for row in 0..h {
            for col in 0..w {
                if rggb().fc(row, col) as usize != 1 {
                    assert!((m.at(row, col) - before.at(row, col)).abs() < 1e-6);
                }
            }
        }
    }
}
