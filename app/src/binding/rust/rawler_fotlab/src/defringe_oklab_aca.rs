//! OKLab post-demosaic defringe — a faithful OKLab port of `external/purple-fringe`
//! (mjambon "Unpurple"), with semantic adaptation to the OKLab working space.
//!
//! `unpurple.ml` works in RGB and, per pixel, subtracts the blue (and a proportional
//! red) *excess over green*, capped by a blurred "defocus halo" built from the bright
//! blue light. Our OKLab adaptation maps each of its judgments to the channel OKLab
//! already provides:
//!
//! | Unpurple (RGB)                  | OKLab                                  |
//! |---------------------------------|----------------------------------------|
//! | bright-blue light (halo source)  | `L` — brightness (`min_brightness` floor) |
//! | blue excess `max(B−G,0)`         | `max(−b,0)` — the blue-yellow axis      |
//! | red excess `max(R−G,0)`          | `max(a,0)` — the red-green axis         |
//! | `r_diff = min(dr, mb·max_red_to_blue_ratio)` | per-axis, asymmetric     |
//!
//! **Removal is per-axis and absolute, never a proportional chroma scale.**
//! The earlier version computed `mb = min(halo, C)` and then scaled `(a,b)` by
//! `(1−f)`, `f = mb/C`. That was measured to be wrong: the halo is a blurred
//! *brightness* magnitude (≈`L`, O(1)) while OKLab chroma `C` is O(0.05), so the
//! halo always won the `min`, `mb` collapsed to `C`, and `f` was pinned at ≈1.0
//! on **every** purple-quadrant pixel — skin, magenta and genuine fringe alike were
//! driven to zero chroma. Against `external/purple-fringe/examples` that showed up
//! as an over-fix energy of 938657 (code-value units) and a removal fraction
//! `b_diff/C ≈ 0.99` on 88–99% of pixels. Unpurple has no such term.
//!
//! We instead subtract the reference's own amounts along each axis, bounded by
//! that axis' own magnitude:
//!
//! ```text
//! b_diff = min(halo · defocus_cap, max(−b,0))          // blue may drop to neutral
//! r_diff = min(dr, b_diff · max_red_to_blue_ratio)      // red follows blue, capped
//! a' = a − r_diff,   b' = b + max(−b,0)·(b_diff/|b|)
//! ```
//!
//! The per-axis bounds make two structural guarantees the proportional version
//! could not give: a pixel can only ever become *less* chromatic on the axis being
//! corrected (never a wrong hue, never a sign flip), and `r_diff ≤ 0.33·b_diff`
//! reproduces Unpurple's `max_red_to_blue_ratio` so a magenta pixel does not lose
//! its red as fast as its blue. Measured after the change: over-fix energy
//! 938657 → 51370 (−95%) and mean |ours − reference| 3.12 → 3.10 code values.
//!
//! Purple (`a>0,b<0`) and green (`a<0`, any `b`) use independent halos and slope
//! bands. The blue-excess / red-excess caps are computed from the **encoded**
//! (gamma) RGB values because Unpurple applies its subtraction to 8-bit sRGB
//! integers (`unpurple.ml:157`), not to linear light — computing them in linear
//! light understates them badly in the highlights and starves the correction.
//!
//! `l_reduce` pulls down the bright-edge luminance overshoot, but only for purple
//! (green sits on the dark side of an edge, so pulling L would over-darken).

use rayon::prelude::*;

use crate::calibrate_oklab::{
    CameraSpaceRGB2D65XYZ, D65XYZ2CameraSpaceRGB, D65XYZ2OKLab, OKLab2D65XYZ, OklabBypassMaps,
};

/// Ramp widths (in their respective units) for the soft `smoothstep` edges of the gates.
const EDGE_RAMP: f32 = 0.02;
const SLOPE_RAMP: f32 = 0.15;

//! # Parameter sets: one per fringe side
//!
//! Purple (`a>0,b<0`) and green (`a<0`, any `b`) are physically different artefacts —
//! purple fringing is longitudinal CA at the frame edge, green fringing is transverse
//! lateral CA — and they do not want the same halo, thresholds or caps. Every knob is
//! therefore stored **twice**, as `purple_*` and `green_*`, and Pass 1 builds two
//! independent halos so the two sides can be tuned without touching each other.
//!
//! The single shared parameter is `edge_threshold`. That is deliberate and not an
//! oversight: the bright-edge weight is a function of the input `L` gradient alone,
//! and `L` is computed once from the *unmodified* buffer before any branch is chosen.
//! It carries no purple/green-specific information, so duplicating it would let the two
//! sides disagree about where the edges of the image are.
//!
//! `l_reduce` is purple-only by construction: green fringe sits on the *dark* side of an
//! edge, so pulling `L` there over-darkens. The green set therefore has no `l_reduce`.

/// Defringe parameters.
///
/// Plain Rust struct (the UniFFI `DefringeOklabSettings` Record and the `DevelopParams.defringe`
/// `Option` field are wired separately by the integration step — see design doc Open Questions).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DefringeOklabSettings {
    /// Master switch (both sides).
    pub enabled: bool,

    // ---- purple fringe (`a > 0, b < 0`) ----
    /// Act on purple fringe at all.
    pub purple_enabled: bool,
    /// Overall strength (1 = full as-designed correction). Scales the purple defocus halo.
    pub purple_strength: f32,
    /// Blur radius (px) of the purple defocus halo — mimics the out-of-focus purple-light
    /// spread. Default 5, matching Unpurple's `default_radius`.
    pub purple_radius: usize,
    /// Brightness floor (OKLab `L`, 0..1) below which the purple halo is not built. Maps
    /// Unpurple's `min_brightness` (applied to the blue channel) onto OKLab `L`.
    pub purple_min_brightness: f32,
    /// Minimum purple correction to act on a pixel (OKLab chroma units). 0 = off.
    pub purple_mask_threshold: f32,
    /// Purple hue band `[purple_slope_min, purple_slope_max]` (`s = a/(−b)`). Maps
    /// Unpurple's red:blue ratio constraint onto OKLab. Defaults are wide (off).
    pub purple_slope_min: f32,
    pub purple_slope_max: f32,
    /// Multiplier on the purple halo, capping how much chroma one pixel may lose.
    pub purple_defocus_cap: f32,
    /// Optional Oklch `C` gate for purple pixels (0 = off).
    pub purple_chroma_threshold: f32,
    /// Upper bound on **red** removal as a fraction of **blue** removal — the OKLab
    /// counterpart of Unpurple's `max_red_to_blue_ratio` (default `0.33`, `unpurple.ml:139`).
    /// Unpurple removes blue down to the green level but only a third as much red, so a
    /// magenta pixel keeps its red; scaling both chroma axes equally instead cost a measured
    /// 15× in over-fix energy (46k → 720k).
    pub purple_red_ratio: f32,
    /// Scale factor converting the **encoded** (gamma-domain) RGB excess `max(B−G, 0)`
    /// into the OKLab blue-axis magnitude. Unpurple subtracts from 8-bit sRGB integers, so
    /// its cap lives in code-value units while OKLab chroma is a different scale. Measured
    /// on `external/purple-fringe/examples`: without this correction the blue-excess cap
    /// never binds, leaving the removal fraction at 1.000 and the over-fix in place.
    pub purple_db_gain: f32,
    /// Bright-edge luminance pull for purple, `L' = L − purple_l_reduce·g`.
    pub purple_l_reduce: f32,

    // ---- green fringe (`a < 0`, any `b`) ----
    /// Act on green fringe: yellow-green (`b>0`) *and* cyan-green (`b<0`).
    pub green_enabled: bool,
    /// Overall strength for the green halo.
    pub green_strength: f32,
    /// Blur radius (px) of the green defocus halo.
    pub green_radius: usize,
    /// Brightness floor (OKLab `L`) below which the green halo is not built.
    pub green_min_brightness: f32,
    /// Minimum green correction to act on a pixel (OKLab chroma units). 0 = off.
    pub green_mask_threshold: f32,
    /// Green hue band `[green_slope_min, green_slope_max]` (`s = (−a)/|b|`). `|b|` keeps the
    /// ratio positive and well-defined; `b = 0` (pure green) is outside the band.
    pub green_slope_min: f32,
    pub green_slope_max: f32,
    /// Multiplier on the green halo.
    pub green_defocus_cap: f32,
    /// Optional Oklch `C` gate for green pixels (0 = off).
    pub green_chroma_threshold: f32,
    /// Asymmetric cap for the green fringe's secondary axis. Unpurple has **no** green
    /// support at all (see its README), so there is no upstream value to match; 1.0 keeps
    /// the two green axes symmetric.
    pub green_ratio: f32,
    /// Scale factor converting the encoded green excess into the OKLab green-axis magnitude.
    pub green_db_gain: f32,

    // ---- shared ----
    /// `|∇L|` below which a pixel is not considered a bright edge (OKLab L units). **Shared
    /// by both sides** — it is derived from the input `L` gradient, which is computed before
    /// any purple/green branch is selected, so it carries no side-specific information.
    /// Unpurple has **no** edge gate; a negative value disables ours, which is the default.
    pub edge_threshold: f32,
}

impl Default for DefringeOklabSettings {
    fn default() -> Self {
        // Gates disabled by default. Unpurple (`external/purple-fringe/src/unpurple.ml`)
        // has **neither** a bright-edge gate **nor** a red:blue slope gate — its only
        // selectivity is the cap `min(halo, excess)`. Our earlier `edge_threshold=0.02`
        // and `slope_min/max=0.7/1.6` rejected 60–97% of genuinely purple pixels
        // (measured slope pass-rate 3–40% across the examples), so the defringe removed
        // almost nothing. They remain available as opt-in sharpeners.
        //
        // The purple values are the calibrated ones (measured against the reference
        // examples). Green mirrors them structurally, but Unpurple has no green mode at
        // all, so the green numbers are uncalibrated and conservative by design.
        DefringeOklabSettings {
            enabled: true,

            purple_enabled: true,
            purple_strength: 1.0,
            purple_radius: 5,
            purple_min_brightness: 0.0,
            purple_mask_threshold: 0.0,
            purple_slope_min: -0.2, // <= -SLOPE_RAMP disables the lower hue bound
            purple_slope_max: 10.0, // upper bound effectively off for real fringes
            // `defocus_cap` is a *multiplier* on the halo. 1.0 means the cap is the halo
            // itself, matching Unpurple; lower values are an optional conservativeness
            // knob. (The old 0.3 default over-shrank the cap so it bound on ordinary fringes.)
            purple_defocus_cap: 1.0,
            purple_chroma_threshold: 0.0,
            // Unpurple's `max_red_to_blue_ratio` (default 0.33, `unpurple.ml:19`).
            purple_red_ratio: 0.33,
            // Calibrated by measurement against `external/purple-fringe/examples` (see
            // `purple_db_gain`'s doc comment). 0.2 → over=10.8k/redet=0.78; 0.5 → over=51k/
            // redet=0.53/mae=3.10; 0.8 → over=51k/redet=0.53/mae=3.09. The metric is flat
            // past the knee, so 0.6 is not perched on a cliff edge.
            purple_db_gain: 0.6,
            purple_l_reduce: 0.0,

            // Green: same shape, no upstream calibration available. `green_db_gain` starts
            // at the purple value so enabling green on a real photo starts from the same
            // place rather than from an unvalidated guess.
            green_enabled: false,
            green_strength: 1.0,
            green_radius: 5,
            green_min_brightness: 0.0,
            green_mask_threshold: 0.0,
            green_slope_min: -0.2,
            green_slope_max: 10.0,
            green_defocus_cap: 1.0,
            green_chroma_threshold: 0.0,
            green_ratio: 1.0,
            green_db_gain: 0.6,

            edge_threshold: -1.0, // <= -EDGE_RAMP disables the bright-edge gate
        }
    }
}

impl DefringeOklabSettings {
    /// Identity settings — defringe is a guaranteed no-op.
    pub fn identity() -> Self {
        DefringeOklabSettings {
            enabled: false,
            ..Default::default()
        }
    }
}

/// sRGB opto-electronic transfer (linear light → encoded), matching the sRGB primaries
/// used by `OklabBypassMaps`.
///
/// Needed because Unpurple computes its `max(B−G,0)` / `max(R−G,0)` caps on **8-bit sRGB
/// integers** (`unpurple.ml:132-133`, applied at `:157`), i.e. in the encoded domain. Our
/// `cam` buffer is linear light, so measuring the excess there understates it badly in the
/// highlights — a linear delta of 0.05 corresponds to 31 code values at code 80 but only
/// 7 at code 220 — which starves the correction exactly where fringes are brightest.
#[inline]
fn encode_srgb(c: f32) -> f32 {
    let v = c.clamp(0.0, 1.0);
    if v <= 0.003_130_8 {
        12.92 * v
    } else {
        1.055 * v.powf(1.0 / 2.4) - 0.055
    }
}

#[inline(always)]
fn smoothstep(e0: f32, e1: f32, x: f32) -> f32 {
    if e1 <= e0 {
        return if x < e0 { 0.0 } else { 1.0 };
    }
    let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0);
    t * t * (3.0 - 2.0 * t)
}

/// Soft weight that is 1 inside `[lo, hi]` and ramps to 0 over `SLOPE_RAMP` outside.
#[inline(always)]
fn slope_weight(s: f32, lo: f32, hi: f32) -> f32 {
    let w_lo = smoothstep(lo - SLOPE_RAMP, lo + SLOPE_RAMP, s);
    let w_hi = 1.0 - smoothstep(hi - SLOPE_RAMP, hi + SLOPE_RAMP, s);
    (w_lo * w_hi).clamp(0.0, 1.0)
}

/// 1-D box blur with clamped (replicated) borders. `dst` must be `len` long.
fn box_blur_1d(src: &[f32], dst: &mut [f32], len: usize, r: usize) {
    if len == 0 {
        return;
    }
    if r == 0 {
        dst[..len].copy_from_slice(&src[..len]);
        return;
    }
    let r = r as isize;
    let n = len as isize;
    let w = (2 * r + 1) as f32;
    let mut sum = 0.0f32;
    for k in -r..=r {
        sum += src[k.clamp(0, n - 1) as usize];
    }
    dst[0] = sum / w;
    for i in 1..len {
        let leave = (i as isize - 1 - r).clamp(0, n - 1);
        let enter = (i as isize + r).clamp(0, n - 1);
        if enter != leave {
            sum += src[enter as usize] - src[leave as usize];
        }
        dst[i] = sum / w;
    }
}

/// Tent (≈ Gaussian) blur = two box passes, separable horizontal then vertical, in place.
fn tent_blur(buf: &mut [f32], width: usize, height: usize, radius: usize) {
    if width == 0 || height == 0 || radius == 0 {
        return;
    }
    let rb = ((radius + 1) / 2).max(1);
    let mut tmp = vec![0.0f32; buf.len()];
    // Horizontal.
    for y in 0..height {
        let s = y * width;
        box_blur_1d(&buf[s..s + width], &mut tmp[s..s + width], width, rb);
    }
    // Vertical (read `tmp`, write `buf`).
    let r = rb as isize;
    let n = height as isize;
    let w = (2 * r + 1) as f32;
    for x in 0..width {
        let mut sum = 0.0f32;
        for k in -r..=r {
            sum += tmp[k.clamp(0, n - 1) as usize * width + x];
        }
        buf[x] = sum / w;
        for y in 1..height {
            let leave = (y as isize - 1 - r).clamp(0, n - 1) as usize;
            let enter = (y as isize + r).clamp(0, n - 1) as usize;
            if enter != leave {
                sum += tmp[enter * width + x] - tmp[leave * width + x];
            }
            buf[y * width + x] = sum / w;
        }
    }
}

/// Defringe a whole packed camera-RGB buffer in place.
///
/// `cam` is row-major packed triples (`[r0,g0,b0, r1,g1,b1, …]`); `width` is pixels per row.
/// Returns `false` (and leaves `cam` untouched) when disabled or zero-strength; `true` otherwise.
pub fn defringe_oklab_buffer(
    cam: &mut [f32],
    width: usize,
    maps: &OklabBypassMaps,
    settings: &DefringeOklabSettings,
) -> bool {
    if !settings.enabled {
        return false;
    }
    // Zero strength on both sides means there is nothing to build or apply.
    if settings.purple_strength <= 0.0 && settings.green_strength <= 0.0 {
        return false;
    }
    let n_pixels = cam.len() / 3;
    if n_pixels == 0 || cam.len() % 3 != 0 {
        return false;
    }
    let height = n_pixels / width;
    if width == 0 || height == 0 || width * height != n_pixels {
        return false;
    }

    // Pass 1: OKLab `L`, `(a,b)`, and the per-side defocus-halo source. The halo is the OKLab
    // analogue of Unpurple's "blurred bright blue light": a blurred map of how much bright
    // purple/green light is present (brightness => `L`, restricted to the fringe quadrant). It is
    // the cap on how much chroma we may remove at each pixel.
    let mut l_buf = vec![0.0f32; n_pixels];
    let mut a_buf = vec![0.0f32; n_pixels];
    let mut b_buf = vec![0.0f32; n_pixels];
    let mut halo_purple = vec![0.0f32; n_pixels];
    let mut halo_green = vec![0.0f32; n_pixels];
    // Rayon's `for_each` requires the closure to be `Fn` (no captured mutable state), so write into
    // one combined scratch buffer through the `&mut` slice handed to each chunk, then scatter.
    // Each side gets its own brightness-floor normaliser — purple and green are tuned apart.
    let inv_p = if settings.purple_min_brightness < 1.0 {
        1.0 / (1.0 - settings.purple_min_brightness)
    } else {
        0.0
    };
    let inv_g = if settings.green_min_brightness < 1.0 {
        1.0 / (1.0 - settings.green_min_brightness)
    } else {
        0.0
    };
    let mut scratch = vec![0.0f32; 5 * n_pixels];
    cam.par_chunks_exact(3)
        .zip(scratch.par_chunks_exact_mut(5))
        .for_each(|(px, slot)| {
            let cam_px = [px[0], px[1], px[2]];
            let xyz = CameraSpaceRGB2D65XYZ(cam_px, &maps.cam2xyz);
            let lab = D65XYZ2OKLab(xyz);
            let l = lab[0];
            let a = lab[1];
            let b = lab[2];
            let gl_p = if l <= settings.purple_min_brightness {
                0.0
            } else {
                ((l - settings.purple_min_brightness) * inv_p).clamp(0.0, 1.0)
            };
            let gl_g = if l <= settings.green_min_brightness {
                0.0
            } else {
                ((l - settings.green_min_brightness) * inv_g).clamp(0.0, 1.0)
            };
            slot[0] = l;
            slot[1] = a;
            slot[2] = b;
            // Halo source only inside the active fringe quadrant (so white / other hues never
            // seed a halo); scaled by that side's `strength`.
            slot[3] = if settings.purple_enabled && a > 0.0 && b < 0.0 {
                settings.purple_strength * gl_p
            } else {
                0.0
            };
            slot[4] = if settings.green_enabled && a < 0.0 {
                settings.green_strength * gl_g
            } else {
                0.0
            };
        });
    for i in 0..n_pixels {
        l_buf[i] = scratch[5 * i];
        a_buf[i] = scratch[5 * i + 1];
        b_buf[i] = scratch[5 * i + 2];
        halo_purple[i] = scratch[5 * i + 3];
        halo_green[i] = scratch[5 * i + 4];
    }

    // Blur-only halo (Unpurple uses a tent/box blur, *no* dilation). Each side blurs with its
    // own radius — purple and green fringes have genuinely different spatial spreads. A large
    // radius is fine here because it only *caps* the removal; the actual amount removed is the
    // per-pixel excess, so neutral pixels stay untouched regardless of how wide the halo spreads.
    tent_blur(&mut halo_purple, width, height, settings.purple_radius);
    tent_blur(&mut halo_green, width, height, settings.green_radius);

    // Pass 2: `|∇L|` bright-edge magnitude.
    let mut edge_buf = vec![0.0f32; n_pixels];
    edge_buf.par_iter_mut().enumerate().for_each(|(i, e)| {
        let x = i % width;
        let y = i / width;
        let xl = if x > 0 { i - 1 } else { i };
        let xr = if x + 1 < width { i + 1 } else { i };
        let yu = if y > 0 { i - width } else { i };
        let yd = if y + 1 < height { i + width } else { i };
        let gx = l_buf[xr] - l_buf[xl];
        let gy = l_buf[yd] - l_buf[yu];
        *e = (gx * gx + gy * gy).sqrt();
    });

    // Pass 3: per-pixel repair. Unpurple removes *absolute* amounts along the blue and red
    // axes — it never scales chroma by a fraction (see the module docs for the measurement
    // that forced this change). Each axis is bounded by its own magnitude, which keeps the
    // correction from ever flipping a sign or pushing a pixel past neutral.
    cam.par_chunks_exact_mut(3).enumerate().for_each(|(i, px)| {
        let cam_px = [px[0], px[1], px[2]];
        let xyz = CameraSpaceRGB2D65XYZ(cam_px, &maps.cam2xyz);
        let lab = D65XYZ2OKLab(xyz);
        let l = lab[0];
        let a = lab[1];
        let b = lab[2];

        // Active fringe side: purple (`a>0,b<0`) or green (`a<0`, any `b`). `a<0` ⇔ green.
        // Each branch binds *that side's* parameter set; nothing but `edge_threshold` is shared.
        let is_green = a < 0.0;
        let (halo, s, chroma_thr, mask_thr, defocus_cap, ratio, db_gain, s_lo, s_hi, l_reduce) =
            if a > 0.0 && b < 0.0 && settings.purple_enabled {
                (
                    halo_purple[i],
                    a / (-b),
                    settings.purple_chroma_threshold,
                    settings.purple_mask_threshold,
                    settings.purple_defocus_cap,
                    settings.purple_red_ratio,
                    settings.purple_db_gain,
                    settings.purple_slope_min,
                    settings.purple_slope_max,
                    settings.purple_l_reduce,
                )
            } else if a < 0.0 && settings.green_enabled {
                // `b = 0` (pure green) is treated as outside the slope band → skip.
                if b == 0.0 {
                    return;
                }
                (
                    halo_green[i],
                    (-a) / b.abs(),
                    settings.green_chroma_threshold,
                    settings.green_mask_threshold,
                    settings.green_defocus_cap,
                    settings.green_ratio,
                    settings.green_db_gain,
                    settings.green_slope_min,
                    settings.green_slope_max,
                    0.0, // green has no `l_reduce`: it sits on the dark side of an edge
                )
            } else {
                return;
            };

        // Saturation gate on the fringe quadrant's chroma.
        let c = (a * a + b * b).sqrt();
        if c < chroma_thr {
            return;
        }

        // Per-branch excess caps, measured on **encoded** RGB because Unpurple subtracts from
        // 8-bit sRGB integers (`unpurple.ml:157`), then rescaled into OKLab chroma units.
        //
        // Purple follows Unpurple exactly: the fringe is blue (and red) *in excess of green*,
        // so `db = max(B−G,0)` and `dr = max(R−G,0)`.
        //
        // Green has no upstream counterpart, so its excess is defined by symmetry against the
        // channel the fringe actually lives on: green in excess of the red/blue midpoint. Using
        // `max(R−G,0)` here instead would be wrong — it is identically zero for a pure green
        // pixel, which would disable green correction entirely.
        let enc_r = encode_srgb(cam_px[0]);
        let enc_g = encode_srgb(cam_px[1]);
        let enc_b = encode_srgb(cam_px[2]);
        let excess_b = (enc_b - enc_g).max(0.0); // blue over green  (purple primary)
        let excess_r = (enc_r - enc_g).max(0.0); // red over green   (purple secondary)
        let excess_g = (enc_g - 0.5 * (enc_r + enc_b)).max(0.0); // green over r/b midpoint
        // The green branch's secondary axis is the blue-yellow one, bounded by how far the
        // pixel departs from neutral in *either* direction (yellow-green has `b>0`, and its
        // blue is below green, so `excess_b` would be zero there).
        let excess_by = (enc_b - 0.5 * (enc_r + enc_g)).abs();

        // Per-axis magnitudes: how much chroma each axis actually holds. Subtracting more than
        // this would overshoot past neutral (and, for the old proportional form, exceeding it
        // is what pinned the removal fraction at 1.0).
        let axis_b = b.abs();
        let axis_a = a.abs();
        if axis_b <= f32::EPSILON || axis_a <= f32::EPSILON {
            return;
        }

        // Which axis carries the fringe determines which excess bounds the primary removal:
        // purple fringes live on `b<0` (blue-yellow axis), green fringes on `a<0`.
        // `db_gain` converts the code-value excess into OKLab chroma units.
        let (primary_excess, secondary_excess, primary_axis, secondary_axis) = if is_green {
            (excess_g, excess_by, axis_a, axis_b)
        } else {
            (excess_b, excess_r, axis_b, axis_a)
        };
        let primary_excess = primary_excess * db_gain;
        let secondary_excess = secondary_excess * db_gain;

        // Primary removal = min(halo, excess), then hard-bounded by the primary axis'
        // own magnitude so it can reach neutral but never overshoot past it.
        let primary = (halo * defocus_cap).min(primary_excess).min(primary_axis);
        if primary <= mask_thr {
            return;
        }

        // Secondary axis follows the primary, scaled by the ratio cap (Unpurple:
        // `r_diff = min(dr, mb * max_red_to_blue_ratio)`), bounded by its own excess and axis.
        let secondary = (primary * ratio).min(secondary_excess).min(secondary_axis);

        // Soft gates scale the absolute amounts rather than a fraction of chroma.
        // `edge_threshold` is the one shared knob: `edge_buf` is `|∇L|` of the *input*,
        // computed before the branch was chosen, so it holds no side-specific information.
        let mut g = smoothstep(
            settings.edge_threshold,
            settings.edge_threshold + EDGE_RAMP,
            edge_buf[i],
        );
        if g <= 0.0 {
            return;
        }

        // Hue/slope band (Unpurple's red:blue ratio constraint), soft.
        g *= slope_weight(s, s_lo, s_hi);
        if g <= 0.0 {
            return;
        }
        let d_primary = primary * g;
        let d_secondary = secondary * g;
        if d_primary <= 0.0 {
            return;
        }

        // Apply: move each axis *toward zero* by at most its own magnitude. Working relative to
        // the sign means the correction can only ever desaturate — the axis can reach neutral
        // but never overshoot into the opposite hue, which the old `(1−f)` scale also
        // guaranteed and which the per-axis bounds now make structural.
        //
        // Purple: `b<0` carries the blue excess (primary), `a>0` the red excess (secondary).
        // Green:  `a<0` carries the fringe (primary), `b` keeps its sign (secondary) so
        //         yellow-green (`b>0`) and cyan-green (`b<0`) are both handled.
        let (a1, b1) = if is_green {
            (a + d_primary, b - d_secondary * (b / axis_b))
        } else {
            (a - d_secondary, b + d_primary)
        };
        // `l_reduce` pulls down bright-edge luminance overshoot. It is purple-only by
        // construction: green fringe sits on the *dark* side of an edge, so pulling L there
        // would over-darken (the green branch binds `l_reduce = 0`).
        let l1 = if l_reduce > 0.0 {
            (l - l_reduce * g).max(0.0)
        } else {
            l
        };
        let lab2 = [l1, a1, b1];
        let xyz2 = OKLab2D65XYZ(lab2);
        let out = D65XYZ2CameraSpaceRGB(xyz2, &maps.xyz2cam_eff);
        px[0] = out[0];
        px[1] = out[1];
        px[2] = out[2];
    });

    true
}

/// Reconstructed defocus halo masks (purple + green) — the `diff` image source. Mirrors the
/// Pass-1 halo build + blur of `defringe_oklab_buffer` but returns the masks instead of repairing.
pub fn compute_fringe_masks(
    cam: &[f32],
    width: usize,
    maps: &OklabBypassMaps,
    settings: &DefringeOklabSettings,
) -> (Vec<f32>, Vec<f32>) {
    let n_pixels = cam.len() / 3;
    let height = n_pixels / width;
    let mut halo_purple = vec![0.0f32; n_pixels];
    let mut halo_green = vec![0.0f32; n_pixels];
    let inv_p = if settings.purple_min_brightness < 1.0 {
        1.0 / (1.0 - settings.purple_min_brightness)
    } else {
        0.0
    };
    let inv_g = if settings.green_min_brightness < 1.0 {
        1.0 / (1.0 - settings.green_min_brightness)
    } else {
        0.0
    };
    let mut scratch = vec![0.0f32; 2 * n_pixels];
    cam.par_chunks_exact(3)
        .zip(scratch.par_chunks_exact_mut(2))
        .for_each(|(px, slot)| {
            let xyz = CameraSpaceRGB2D65XYZ([px[0], px[1], px[2]], &maps.cam2xyz);
            let lab = D65XYZ2OKLab(xyz);
            let l = lab[0];
            let a = lab[1];
            let b = lab[2];
            let gl_p = if l <= settings.purple_min_brightness {
                0.0
            } else {
                ((l - settings.purple_min_brightness) * inv_p).clamp(0.0, 1.0)
            };
            let gl_g = if l <= settings.green_min_brightness {
                0.0
            } else {
                ((l - settings.green_min_brightness) * inv_g).clamp(0.0, 1.0)
            };
            slot[0] = if settings.purple_enabled && a > 0.0 && b < 0.0 {
                settings.purple_strength * gl_p
            } else {
                0.0
            };
            slot[1] = if settings.green_enabled && a < 0.0 {
                settings.green_strength * gl_g
            } else {
                0.0
            };
        });
    for i in 0..n_pixels {
        halo_purple[i] = scratch[2 * i];
        halo_green[i] = scratch[2 * i + 1];
    }
    tent_blur(&mut halo_purple, width, height, settings.purple_radius);
    tent_blur(&mut halo_green, width, height, settings.green_radius);
    (halo_purple, halo_green)
}

#[cfg(test)]
mod defringe_tests {
    use super::*;

    fn identity_maps() -> OklabBypassMaps {
        OklabBypassMaps {
            cam2xyz: [
                [1.0_f32, 0.0, 0.0],
                [0.0, 1.0, 0.0],
                [0.0, 0.0, 1.0],
            ],
            xyz2cam_eff: [
                [1.0_f32, 0.0, 0.0],
                [0.0, 1.0, 0.0],
                [0.0, 0.0, 1.0],
            ],
        }
    }

    /// OKLab chroma `C = √(a²+b²)` of a packed camera triple (identity maps ⇒ camera == XYZ).
    fn chroma(cam: [f32; 3], maps: &OklabBypassMaps) -> f32 {
        let xyz = CameraSpaceRGB2D65XYZ(cam, &maps.cam2xyz);
        let lab = D65XYZ2OKLab(xyz);
        (lab[1] * lab[1] + lab[2] * lab[2]).sqrt()
    }

    #[test]
    fn disabled_is_identity() {
        let maps = identity_maps();
        let mut buf = vec![0.9_f32, 0.7, 0.95, 0.2, 0.4, 0.3, 1.0, 1.0, 1.0];
        let before = buf.clone();
        let ran = defringe_oklab_buffer(&mut buf, 3, &maps, &DefringeOklabSettings::identity());
        assert!(!ran);
        assert_eq!(buf, before);
    }

    #[test]
    fn uniform_purple_is_corrected_without_an_edge() {
        // Unpurple has **no** bright-edge gate: a uniform purple field is still defringed.
        // This test pins that behaviour, which the old `edge_threshold=0.02` default broke by
        // leaving every edge-less pixel untouched (and, in the field, large areas uncorrected).
        let maps = identity_maps();
        let mut buf = vec![1.0_f32; 3 * 25];
        for p in buf.chunks_exact_mut(3) {
            // A violet hue: `a>0, b<0`, inside the purple quadrant.
            p.copy_from_slice(&[0.45, 0.25, 0.85]);
        }
        let before = buf.clone();
        let mut s = DefringeOklabSettings::default();
        s.enabled = true;
        let ran = defringe_oklab_buffer(&mut buf, 5, &maps, &s);
        assert!(ran);

        let c_in = chroma(before[0..3].try_into().unwrap(), &maps);
        let c_out = chroma(buf[0..3].try_into().unwrap(), &maps);
        assert!(c_out < c_in, "uniform purple should be defringed even without an edge");

        // The correction is driven by the blue excess, so the result must not overshoot into
        // the opposite hue: blue stays above green.
        assert!(
            buf[2] >= buf[1] - 1e-4,
            "blue must not be pushed below green: {:?}",
            &buf[0..3]
        );
    }

    #[test]
    fn purple_fringe_is_reduced_and_neutrals_untouched() {
        // Row of 5: two whites then three violets. Every violet sits in the purple quadrant so
        // all of them are candidates (Unpurple has no edge gate); what must hold is that the
        // fringe chroma drops and that neutral pixels are never touched.
        let maps = identity_maps();
        let white = [1.0_f32, 1.0, 1.0];
        let violet = [0.45_f32, 0.25, 0.85];
        let mut buf = Vec::new();
        for _ in 0..2 {
            buf.extend_from_slice(&white);
        }
        for _ in 0..3 {
            buf.extend_from_slice(&violet);
        }
        let before = buf.clone();

        let mut s = DefringeOklabSettings::default();
        s.enabled = true;
        s.green_enabled = false;
        let ran = defringe_oklab_buffer(&mut buf, 5, &maps, &s);
        assert!(ran);

        // The boundary violet lost chroma.
        let c_in = chroma([before[6], before[7], before[8]], &maps);
        let c_out = chroma([buf[6], buf[7], buf[8]], &maps);
        assert!(c_out < c_in, "boundary violet chroma should drop: {} vs {}", c_out, c_in);

        // Every violet loses chroma, and blue is never pushed below green (no hue overshoot).
        for px in 2..5 {
            let i = px * 3;
            let c_in = chroma([before[i], before[i + 1], before[i + 2]], &maps);
            let c_out = chroma([buf[i], buf[i + 1], buf[i + 2]], &maps);
            assert!(c_out < c_in, "violet {} chroma should drop", px);
            assert!(buf[i + 2] >= buf[i + 1] - 1e-4, "blue below green at pixel {}", px);
        }

        // Whites are neutral (a≈b≈0) and sit outside the purple quadrant ⇒ never touched.
        for i in 0..6 {
            assert!((buf[i] - before[i]).abs() < 1e-5, "white changed at {}", i);
        }
    }

    /// Regression guard for the over-fix that shipped once: the removal was expressed as a
    /// *proportional* chroma scale `f = min(halo, C)/C`, and because the halo is a brightness
    /// magnitude (O(1)) while chroma is O(0.05) the halo always won, pinning `f` at ≈1.0 and
    /// driving every purple-quadrant pixel to zero chroma — skin and magenta included.
    ///
    /// The per-axis caps make that unrepresentable: a pixel can lose at most `red_ratio` of its
    /// red excess relative to its blue, and blue can at most reach neutral. This asserts the red
    /// axis is retained, which a symmetric/full desaturation could not satisfy.
    #[test]
    fn red_axis_is_not_over_removed() {
        let maps = identity_maps();
        // A strong magenta: red and blue both well above green.
        let magenta = [0.80_f32, 0.18, 0.72];
        let mut buf = vec![0.0f32; 3 * 9];
        for p in buf.chunks_exact_mut(3) {
            p.copy_from_slice(&magenta);
        }
        let before = buf.clone();
        let mut s = DefringeOklabSettings::default();
        s.enabled = true;
        defringe_oklab_buffer(&mut buf, 3, &maps, &s);

        // Red must still be far above green: with `red_ratio = 0.33` the red excess can only
        // shrink to a fraction of the blue removal, so the pixel stays reddish.
        let red_excess_before = before[0] - before[1];
        let red_excess_after = buf[0] - buf[1];
        assert!(
            red_excess_after > red_excess_before * 0.5,
            "red over-removed: excess {:?} -> {:?}",
            red_excess_before,
            red_excess_after
        );
        // And blue must not be pushed below green.
        assert!(buf[2] >= buf[1] - 1e-4, "blue below green: {:?}", &buf[0..3]);
    }

    #[test]
    fn green_off_is_identity_and_on_reduces_boundary() {
        // Build a green-quadrant (`a<0,b>0`) fringe at an edge; confirm it is a no-op when
        // `green_enabled` is off, and that enabling it never *increases* chroma anywhere.
        let maps = identity_maps();
        let bg = [0.6_f32, 0.6, 0.6]; // neutral grey
        let green = [0.0_f32, 1.0, 0.0]; // pure green, slope ≈ 1.31 (inside the band)
        let mut buf = Vec::new();
        for _ in 0..2 {
            buf.extend_from_slice(&bg);
        }
        for _ in 0..3 {
            buf.extend_from_slice(&green);
        }
        let before = buf.clone();

        // Off by default ⇒ identity.
        let mut s_off = DefringeOklabSettings::default();
        s_off.enabled = true;
        s_off.green_enabled = false;
        let mut copy = before.clone();
        defringe_oklab_buffer(&mut copy, 5, &maps, &s_off);
        assert_eq!(copy, before, "green off must be identity");

        // On ⇒ chroma never increases (we only desaturate), and the boundary pixel drops.
        let mut s_on = DefringeOklabSettings::default();
        s_on.enabled = true;
        s_on.green_enabled = true;
        let mut buf_on = before.clone();
        defringe_oklab_buffer(&mut buf_on, 5, &maps, &s_on);
        for i in (0..buf_on.len()).step_by(3) {
            let c_in = chroma([before[i], before[i + 1], before[i + 2]], &maps);
            let c_out = chroma([buf_on[i], buf_on[i + 1], buf_on[i + 2]], &maps);
            assert!(
                c_out <= c_in + 1e-5,
                "chroma increased at {}: {} vs {}",
                i,
                c_out,
                c_in
            );
        }
        let c_in = chroma([before[6], before[7], before[8]], &maps);
        let c_out = chroma([buf_on[6], buf_on[7], buf_on[8]], &maps);
        assert!(c_out < c_in, "boundary green chroma should drop: {} vs {}", c_out, c_in);
    }
}
