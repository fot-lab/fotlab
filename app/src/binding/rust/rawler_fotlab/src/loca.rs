//! LoCA correction orchestration — translates the rawler `CFAConfig` into the
//! `CfaDesc` the `rawtrp_correct` kernels take and forwards the call.
//!
//! `rawtrp_correct::correct_loca_bayer` is the FotLab first-party pre-demosaic
//! longitudinal-CA / purple-fringe correction (`rules/DESIGN/detail/FOTLAB-RENDER-000003`):
//! it runs on the linear 0..1 mosaic, **after** the lateral-CA stage (`ca.rs`)
//! and **before** demosaic. This module only bridges types; the algorithm lives
//! in the crate.
//!
//! ## Contract
//!
//! `correct_loca(pixels, width, height, settings, cfa) -> pixels`. `settings =
//! None` (the Kotlin switch OFF) is the identity — the whole stage is skipped.
//! So is `settings.enabled == false` (the master switch inside `LocaSettings`):
//! either path short-circuits the entire stage — no edge detection, no
//! criteria, no repair. Within the stage, the 去紫边 / 去绿边 pair switches gate
//! their own bound criteria+behaviour units (see `LocaSettings`). The
//! correction runs on the **full-frame** scaled mosaic, in the same
//! pre-demosaic neighbour-quality position as the LCA stage.
//!
//! ## Degrade-not-fail
//!
//! A non-Bayer / four-colour CFA (`UnsupportedCfa`), an odd width (`OddWidth`),
//! or a kernel error all `log::warn!` and pass the pixels through unchanged —
//! LoCA composes with LCA (which only mutates R/B) because it only mutates the
//! G plane.

use rawler::imgop::{Dim2, Point, Rect};
use rawler::rawimage::CFAConfig;

use crate::demosaic::bayer_cfa_desc;

/// LoCA fringe-correction settings from Kotlin (the Studio LoCA dialog).
/// `None` = the stage is off. Three gate switches, all default-on so the Kotlin
/// side only needs to opt OUT:
///
/// * `enabled` — the **master switch**: `false` short-circuits the entire LoCA
///   stage (no edge detection, no criteria, no repair), regardless of the pair
///   switches.
/// * `purple_enabled` — the 去紫边 (purple-fringe) pair switch. Criteria
///   (`min(r,b) > g` + `lum > purple_lum_min` + on-edge) and behaviour (raise G)
///   are BOUND: the switch gates both together.
/// * `green_enabled` — the 去绿边 (green-fringe) pair switch. Criteria
///   (`g > max(r,b)` + `lum > green_lum_min` + on-edge) and behaviour (lower G
///   toward max(r,b)) are BOUND the same way. LoCA never touches R/B.
///
/// `purple_lum_min` / `green_lum_min` are caller-passable thresholds (default
/// 0.5 each; a passed value is used as-is, clamped to 0..1 in the kernel).
#[derive(Debug, Clone, uniffi::Record)]
pub struct LocaSettings {
    /// Master switch: `false` short-circuits the whole LoCA stage.
    #[uniffi(default = true)]
    pub enabled: bool,
    /// 去紫边 pair switch: run the magenta criteria + raise-G behaviour.
    #[uniffi(default = true)]
    pub purple_enabled: bool,
    /// 去绿边 pair switch: run the green-excess criteria + lower-G behaviour.
    /// LoCA never touches R/B — both pairs act on the G plane only.
    #[uniffi(default = true)]
    pub green_enabled: bool,
    /// Purple-pair repair strength, 0..1 (1.0 = full RapidRAW-style correction).
    /// The Kotlin LoCA dialog exposes this as 去紫边强度.
    #[uniffi(default = 1.0)]
    pub purple_strength: f32,
    /// Green-pair repair strength, 0..1 (1.0 = full RapidRAW-style correction).
    /// The Kotlin LoCA dialog exposes this as 去绿边强度.
    #[uniffi(default = 1.0)]
    pub green_strength: f32,
    /// Purple-pair luminance threshold (raw-linear, pre-WB). Default 0.5.
    #[uniffi(default = 0.5)]
    pub purple_lum_min: f32,
    /// Green-pair luminance threshold (raw-linear, pre-WB). Default 0.5.
    #[uniffi(default = 0.5)]
    pub green_lum_min: f32,
}

/// Run the pre-demosaic LoCA correction over the full-frame mosaic.
///
/// `cfa = None` (non-CFA input) skips the stage — a CFA-domain correction has
/// nothing to key off. A kernel error degrades to the uncorrected input (see
/// the module docs).
pub(crate) fn correct_loca(
    pixels: Vec<f32>,
    width: usize,
    height: usize,
    settings: Option<&LocaSettings>,
    cfa: Option<&CFAConfig>,
) -> Vec<f32> {
    let Some(settings) = settings else {
        return pixels; // switch OFF: identity, free
    };
    if !settings.enabled {
        // MASTER switch off: short-circuit the entire LoCA stage — no edge
        // detection, no criteria, no repair — regardless of the pair switches.
        return pixels;
    }
    let Some(config) = cfa else {
        return pixels; // non-CFA input: nothing to correct
    };
    // The correction sees the whole scaled frame, so the CFA description is built
    // at the full-frame origin — the same layout the demosaic stage later shifts
    // from its own (active-area) ROI.
    let roi = Rect::new(Point::new(0, 0), Dim2::new(width, height));
    let Some(cfa_desc) = bayer_cfa_desc(&config.cfa, roi) else {
        log::warn!("LoCA correction needs a 2x2 R/G/B Bayer CFA; skipping the stage");
        return pixels;
    };

    let params = rawtrp_correct::LocaParams {
        purple_strength: settings.purple_strength as f64,
        green_strength: settings.green_strength as f64,
        purple_enabled: settings.purple_enabled,
        green_enabled: settings.green_enabled,
        purple_lum_min: settings.purple_lum_min,
        green_lum_min: settings.green_lum_min,
    };

    // Wrap the mosaic, correct in place, hand the buffer back. `Array2D` owns a
    // plain row-major `Vec`, so the round trip is two row-wise moves.
    let mut mosaic = rawtrp_demosaic::Array2D::new(width, height);
    for row in 0..height {
        mosaic.row_mut(row).copy_from_slice(&pixels[row * width..(row + 1) * width]);
    }
    match rawtrp_correct::correct_loca_bayer(&mut mosaic, &cfa_desc, &params) {
        Ok(()) => {
            let mut out = pixels;
            for row in 0..height {
                out[row * width..(row + 1) * width].copy_from_slice(mosaic.row(row));
            }
            out
        }
        Err(e) => {
            log::warn!(
                "LoCA correction failed ({e:?}); passing the mosaic through uncorrected"
            );
            pixels
        }
    }
}
