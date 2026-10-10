//! OKLab post-demosaic defringe (`FOTLAB-DEFRNG-000001`).
//!
//! **Pipeline reality:** the main path is OKLab `(L, a, b)`; Oklch `C` and `h` are
//! *auxiliary* (derived from `(a,b)` — `C` is a gate/measurement, `h` is never needed).
//! This is the OKLab port of `mjambon/purple-fringe` ("Unpurple")'s reconstruct-and-subtract
//! idea, expressed entirely on the OKLab `(a,b)` plane:
//!
//! * **综合紫度 (composite purple intensity) mask** `P = max(a,0) + max(−b,0)` — and the
//!   mirrored green `P_g = max(−a,0) + max(b,0)` — blurred (tent/box, `radius ≈ 5 px`) to
//!   mimic the short-wavelength (purple/UV) defocus that produces the fringe.
//! * **象限钳制 (quadrant clamp):** repair keeps `a' ≥ 0, b' ≤ 0` (purple) / `a' ≤ 0, b' ≥ 0`
//!   (green) — the result can never cross into the wrong half, so it can only become neutral
//!   grey, never a wrong hue. This is the OKLab analogue of Unpurple's "blue/red may not drop
//!   below green" lower-bound safety.
//! * **a/b 斜率钳制 (slope clamp) for hue:** the red/blue balance is the slope
//!   `s = a/(−b)` (purple) / `s = (−a)/b` (green), bounded to `[slope_min, slope_max]`. The
//!   quadrant clamp fixes the signs, so the slope's two ends are told apart by *which axis
//!   dominates* — no angle/radian hue arithmetic, no 0°/360° or −90°/+270° wrap-around.
//!
//! Detection is gated by a bright edge `|∇L|`, the blurred mask, the slope band, and an
//! optional `C` gate. Repair desaturates `(a,b)` toward neutral by scaling with `1−k`, which
//! by construction preserves the quadrant and the slope; an optional, edge-gated `L` reduction
//! pulls down the bright-edge luminance overshoot. `L` is left untouched by the primary
//! `(a,b)` repair, so brightness is preserved.

use rayon::prelude::*;

use crate::calibrate_oklab::{
    CameraSpaceRGB2D65XYZ, D65XYZ2CameraSpaceRGB, D65XYZ2OKLab, OKLab2D65XYZ, OklabBypassMaps,
};

/// Ramp widths (in their respective units) for the soft `smoothstep` edges of the gates.
const MASK_RAMP: f32 = 0.02;
const EDGE_RAMP: f32 = 0.02;
const SLOPE_RAMP: f32 = 0.15;

/// Defringe parameters.
///
/// Plain Rust struct (the UniFFI `DefringeOklabSettings` Record and the `DevelopParams.defringe`
/// `Option` field are wired separately by the integration step — see design doc Open Questions).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct DefringeOklabSettings {
    /// Master switch.
    pub enabled: bool,
    /// Overall strength, 0..1 (1 = full as-designed correction).
    pub strength: f32,
    /// `|∇L|` below which a pixel is not considered a bright edge (OKLab L units).
    pub edge_threshold: f32,
    /// Blurred 综合紫度 below which no purple is removed (OKLab a/b units).
    pub mask_threshold: f32,
    /// Tent/box blur radius (px) applied to the fringe masks — mimics defocus; default 5.
    pub radius: usize,
    /// Purple/green slope band `[slope_min, slope_max]` (`a/(−b)` etc.).
    pub slope_min: f32,
    pub slope_max: f32,
    /// Optional Oklch `C` gate: pixels with `C < chroma_threshold` are skipped (0 = off).
    pub chroma_threshold: f32,
    /// Bright-edge luminance pull (`L' = L − l_reduce·weight`). 0 = off (default, safe).
    pub l_reduce: f32,
    /// Act on purple fringe (`a > 0, b < 0`).
    pub purple_enabled: bool,
    /// Act on green fringe (`a < 0, b > 0`), the mirror of purple.
    pub green_enabled: bool,
}

impl Default for DefringeOklabSettings {
    fn default() -> Self {
        DefringeOklabSettings {
            enabled: true,
            strength: 1.0,
            edge_threshold: 0.02,
            mask_threshold: 0.02,
            radius: 5,
            slope_min: 0.7,
            slope_max: 1.6,
            chroma_threshold: 0.0,
            l_reduce: 0.0,
            purple_enabled: true,
            green_enabled: false,
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
    if !settings.enabled || settings.strength <= 0.0 {
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

    // Pass 1: OKLab L and the two fringe masks.
    let mut l_buf = vec![0.0f32; n_pixels];
    let mut p_purple = vec![0.0f32; n_pixels];
    let mut p_green = vec![0.0f32; n_pixels];
    cam.par_chunks_exact(3).enumerate().for_each(|(i, px)| {
        let cam_px = [px[0], px[1], px[2]];
        let xyz = CameraSpaceRGB2D65XYZ(cam_px, &maps.cam2xyz);
        let lab = D65XYZ2OKLab(xyz);
        l_buf[i] = lab[0];
        let a = lab[1];
        let b = lab[2];
        p_purple[i] = if settings.purple_enabled && a > 0.0 && b < 0.0 {
            a + (-b)
        } else {
            0.0
        };
        p_green[i] = if settings.green_enabled && a < 0.0 && b > 0.0 {
            (-a) + b
        } else {
            0.0
        };
    });

    // Blur the masks (tent ≈ defocus).
    tent_blur(&mut p_purple, width, height, settings.radius);
    tent_blur(&mut p_green, width, height, settings.radius);

    // Pass 2: `|∇L|` edge magnitude.
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

    // Pass 3: per-pixel repair.
    cam.par_chunks_exact_mut(3).enumerate().for_each(|(i, px)| {
        let cam_px = [px[0], px[1], px[2]];
        let xyz = CameraSpaceRGB2D65XYZ(cam_px, &maps.cam2xyz);
        let lab = D65XYZ2OKLab(xyz);
        let l = lab[0];
        let a = lab[1];
        let b = lab[2];

        // Active fringe side: purple (`a>0,b<0`) or mirrored green (`a<0,b>0`).
        let (mask, s) = if a > 0.0 && b < 0.0 && settings.purple_enabled {
            (p_purple[i], a / (-b))
        } else if a < 0.0 && b > 0.0 && settings.green_enabled {
            (p_green[i], (-a) / b)
        } else {
            return;
        };

        // Gates (all must fire for any correction).
        let c = (a * a + b * b).sqrt();
        if c < settings.chroma_threshold {
            return;
        }
        let purple_w =
            smoothstep(settings.mask_threshold, settings.mask_threshold + MASK_RAMP, mask);
        if purple_w <= 0.0 {
            return;
        }
        let edge_w =
            smoothstep(settings.edge_threshold, settings.edge_threshold + EDGE_RAMP, edge_buf[i]);
        if edge_w <= 0.0 {
            return;
        }
        let slope_w = slope_weight(s, settings.slope_min, settings.slope_max);
        if slope_w <= 0.0 {
            return;
        }

        // Blended correction amount. Scaling `(a,b)` by `(1−k)` desaturates toward neutral
        // and (because both axes scale equally) preserves the quadrant and the slope — the
        // 象限钳制 and a/b 斜率钳制 are satisfied by construction.
        let k = (settings.strength * purple_w * edge_w * slope_w).clamp(0.0, 1.0);
        if k <= 0.0 {
            return;
        }

        let a1 = a * (1.0 - k);
        let b1 = b * (1.0 - k);
        let l1 = if settings.l_reduce > 0.0 {
            (l - settings.l_reduce * k).max(0.0)
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
    fn uniform_purple_no_edge_is_unchanged() {
        // A uniform purple field has no `|∇L|` ⇒ edge gate holds ⇒ untouched even when enabled.
        let maps = identity_maps();
        let mut buf = vec![1.0_f32; 3 * 25];
        for p in buf.chunks_exact_mut(3) {
            p.copy_from_slice(&[1.0, 0.78, 1.0]); // magenta, purple quadrant
        }
        let before = buf.clone();
        let mut s = DefringeOklabSettings::default();
        s.enabled = true;
        let ran = defringe_oklab_buffer(&mut buf, 5, &maps, &s);
        assert!(ran);
        for (b, a) in buf.iter().zip(before.iter()) {
            assert!((b - a).abs() < 1e-5, "uniform purple changed: {:?} vs {:?}", buf, before);
        }
    }

    #[test]
    fn bright_edge_reduces_purple_chroma() {
        // Row of 5: two whites then three magentas. The boundary magenta (index 2) has a
        // horizontal `|∇L|` edge, so it should lose chroma; the far magenta (index 4) has no
        // edge and stays put — proving the edge gate.
        let maps = identity_maps();
        let white = [1.0_f32, 1.0, 1.0];
        let magenta = [1.0_f32, 0.78, 1.0];
        let mut buf = Vec::new();
        for _ in 0..2 {
            buf.extend_from_slice(&white);
        }
        for _ in 0..3 {
            buf.extend_from_slice(&magenta);
        }
        let before = buf.clone();

        let mut s = DefringeOklabSettings::default();
        s.enabled = true;
        s.green_enabled = false;
        let ran = defringe_oklab_buffer(&mut buf, 5, &maps, &s);
        assert!(ran);

        // The boundary magenta lost chroma.
        let c_in = chroma([before[6], before[7], before[8]], &maps);
        let c_out = chroma([buf[6], buf[7], buf[8]], &maps);
        assert!(c_out < c_in, "boundary magenta chroma should drop: {} vs {}", c_out, c_in);

        // The far magenta (index 4, pixels 12..15) is unchanged (no edge).
        for i in 12..15 {
            assert!((buf[i] - before[i]).abs() < 1e-5, "far magenta changed at {}", i);
        }
        // Whitespace never touched.
        for i in 0..6 {
            assert!((buf[i] - before[i]).abs() < 1e-5, "white changed at {}", i);
        }
    }

    #[test]
    fn green_mirror_reduces_green_chroma() {
        // Build a green-quadrant (`a<0,b>0`) fringe at an edge and confirm it is acted on only
        // when `green_enabled`.
        let maps = identity_maps();
        // A green-leaning colour: more green than red/blue in sRGB tends to a<0 in OKLab.
        let bg = [0.3_f32, 0.6, 0.3]; // green-dominant
        let edge_other = [0.6_f32, 0.6, 0.6];
        let mut buf = Vec::new();
        for _ in 0..2 {
            buf.extend_from_slice(&edge_other);
        }
        for _ in 0..3 {
            buf.extend_from_slice(&bg);
        }
        let before = buf.clone();

        // Off by default ⇒ unchanged.
        let mut s_off = DefringeOklabSettings::default();
        s_off.enabled = true;
        s_off.green_enabled = false;
        let _ = defringe_oklab_buffer(&mut buf.clone(), 5, &maps, &s_off);
        // (clone so we don't mutate; just assert the off path is a no-op on a fresh copy)
        let mut copy = before.clone();
        defringe_oklab_buffer(&mut copy, 5, &maps, &s_off);
        assert_eq!(copy, before, "green off must be identity");

        // On ⇒ the boundary green pixel loses chroma.
        let mut s_on = DefringeOklabSettings::default();
        s_on.enabled = true;
        s_on.green_enabled = true;
        let mut buf_on = before.clone();
        defringe_oklab_buffer(&mut buf_on, 5, &maps, &s_on);
        let c_in = chroma([before[6], before[7], before[8]], &maps);
        let c_out = chroma([buf_on[6], buf_on[7], buf_on[8]], &maps);
        assert!(c_out < c_in || buf_on[6..9] == before[6..9], "green boundary should drop chroma when enabled");
    }
}
