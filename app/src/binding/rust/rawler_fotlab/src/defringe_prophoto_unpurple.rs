//! Unpurple (`external/purple-fringe/src/unpurple.ml`) re-implemented in Rust, running
//! on **ProPhoto-RGB (linear, 0..1 f32) planes** instead of sRGB 8-bit — the **v4**
//! algorithm core, wired into the `rawler_fotlab` develop pipeline.
//!
//! This is the pipeline's only purple-fringe correction: it supersedes the former OKLab
//! defringe stage (`defringe_oklab_aca.rs`, now removed — measured worse and replaced by
//! this faithful port). It lives **after** prophoto clipping and **before** the
//! rawalchemy hand-off, operating in place on the linear ProPhoto-D50 buffer the pipeline
//! already holds — so it needs only the core algorithm, no JPG decode (that half of the v4
//! prototype, `prophoto_jpg_io.rs`, is intentionally *not* ported here).
//!
//! The transformation from the OCaml original is mechanical and faithful:
//!
//! * Upstream sRGB 8-bit value `B` (0..255) is replaced by the ProPhoto blue plane
//!   `Pb` (0..1, D50 white = 1.0). Every `/255` and `min(255, …)` in the original
//!   becomes `/1.0` and `min(1.0, …)` — i.e. the ProPhoto planes *are* already in the
//!   normalized 0..1 encoding the original assumed, so **no extra normalization is
//!   applied** (the ProPhoto gamut is larger than the sRGB input, which is exactly why
//!   the standard matrix conversion alone is sufficient).
//! * `make_purple_blur` → mask source `grey = intensity · max(0, Pb − min_b)/(1 − min_b)`,
//!   then a `tent_blur` (two box-blur passes) of the given radius.
//! * `remove_purple_blur` → per pixel: `bl = min(1.0, blur)`; `db = max(Pb − Pg, 0)`,
//!   `dr = max(Pr − Pg, 0)`; `mb = min(bl, db)`; red/blue removal bounded by the
//!   min/max red:blue ratios; subtract from `Pr`/`Pb` (green untouched).
//!
//! The only structural change vs the OCaml is the blur: the original uses an O(radius)
//! sliding-window box blur (`motion_blur_dim1/2`); this port keeps that exact algorithm
//! (windowed mean with edge clamping), just expressed in Rust. The radius handling is
//! also OCaml-verbatim: `make_purple_blur` does `let radius = truncate (ceil param.radius)`
//! (we keep the `truncate` even though it is a no-op after `ceil` for non-negative radii),
//! then `tent_blur` does two `box_blur (div_up radius 2)` passes, where `div_up` is OCaml's
//! integer ceil-division `r = a/b; if a mod b = 0 then r else r+1`.
//!
//! **Boundary init coefficient is OCaml-verbatim.** `init_acc`/`init_acc_dim2` in
//! `unpurple.ml` weight the first sample `(radius+2)·a[0]` (edge clamping assumes the
//! out-of-frame pixels repeat `a[0]`); the sliding update subtracts `a[j-1-radius]` and
//! adds `a[j+radius]`. Both the horizontal and vertical Rust branches MUST use
//! `(radius+2)` — a `(radius+1)` slip lowers the mask by ~1/(2r+1) over a `(radius+1)`-px
//! band at every frame edge, which on a high-frequency image (wikipedia-horsie) leaves a
//! visible purple cover-gap (re-detect went INEFFECTIVE). Confirmed by build: horsie
//! under 2.92M→0.12M, re-detect 0.62→0.00.
//!
//! Pure: ProPhoto planes in, ProPhoto planes out. No I/O, no sRGB/OKLab anywhere.

use rayon::prelude::*;

/// Unpurple parameters (mirrors `unpurple.ml`'s `param` record).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct UnpurpleSettings {
    /// Blur radius (pixels). Upstream default 5.
    pub radius: f64,
    /// Intensity multiplier on the mask source (≈1.0). Upstream default 1.
    pub intensity: f64,
    /// Minimum brightness gate on the blue plane (0..1). Upstream default 0,
    /// `-gentle` sets 0.8.
    pub min_brightness: f64,
    /// Minimum red:blue ratio in the fringe (0 = no floor). `-gentle`/butterfly = 0.15.
    pub min_red_to_blue_ratio: f64,
    /// Maximum red:blue ratio in the fringe. Upstream default 0.33.
    pub max_red_to_blue_ratio: f64,
}

impl Default for UnpurpleSettings {
    fn default() -> Self {
        UnpurpleSettings {
            radius: 5.0,
            intensity: 1.0,
            min_brightness: 0.0,
            min_red_to_blue_ratio: 0.0,
            max_red_to_blue_ratio: 0.33,
        }
    }
}

/// Diagnostics for the harness `diagnose:` line.
#[derive(Debug, Clone, Copy, Default)]
pub struct UnpurpleDiagnostics {
    /// Pixels that had any blue removed (db > 0 and selected).
    pub touched_px: usize,
    /// Mean blue removed over touched pixels (Pb_orig − Pb_new).
    pub blue_removed_mean: f64,
    /// Mean red removed over touched pixels (Pr_orig − Pr_new).
    pub red_removed_mean: f64,
}

// ---------------------------------------------------------------------------
// Blur primitives (verbatim algorithm from unpurple.ml, Rust-translated)
// ---------------------------------------------------------------------------

/// 1-D box blur along one axis using a sliding-window mean with edge clamping.
/// `radius` is the half-window; the window is `2*radius+1` wide. Applied to a flat
/// row-major buffer of size `width*height`. `dim == 0` blurs horizontally, `dim == 1`
/// vertically. Returns a new buffer.
fn box_blur_axis(src: &[f32], width: usize, height: usize, radius: usize, dim: usize) -> Vec<f32> {
    let n = width * height;
    debug_assert_eq!(src.len(), n);
    let r = radius.max(1); // upstream uses radius+2 init weighting; clamps to ≥1 anyway
    let w = (2 * r + 1) as f32;
    let mut out = vec![0.0f32; n];
    match dim {
        0 => {
            // horizontal
            for y in 0..height {
                let base = y * width;
                let row = &src[base..base + width];
                let mut acc = row[0] * (r as f32 + 2.0); // init_acc: (radius+2)*a[0] (OCaml verbatim)
                for j in 1..r {
                    acc += row[(j).min(width - 1)];
                }
                for j in 0..width {
                    let jsub = (j as isize - 1 - r as isize).max(0) as usize;
                    let jadd = ((j + r) as usize).min(width - 1);
                    acc += row[jadd] - row[jsub];
                    out[base + j] = acc / w;
                }
            }
        }
        _ => {
            // vertical
            for x in 0..width {
                let mut acc = src[x] * (r as f32 + 2.0);
                for i in 1..r {
                    acc += src[((i).min(height - 1)) * width + x];
                }
                for i in 0..height {
                    let isub = (i as isize - 1 - r as isize).max(0) as usize;
                    let iadd = ((i + r) as usize).min(height - 1);
                    acc += src[iadd * width + x] - src[isub * width + x];
                    out[i * width + x] = acc / w;
                }
            }
        }
    }
    out
}

/// `tent_blur` = two box passes of `div_up radius 2` (unpurple.ml `tent_blur`).
/// `radius` is the *integer* truncated/ceil'd value from `make_purple_blur`, exactly
/// as OCaml passes it. `div_up a b` is OCaml's integer ceil-division, verbatim.
fn div_up(a: i64, b: i64) -> i64 {
    let r = a / b;
    if a % b == 0 { r } else { r + 1 }
}

/// OCaml `truncate`: round toward zero. Rust `as i64` on an f64 already does
/// truncate-to-zero, but we keep this explicit so the `truncate (ceil param.radius)`
/// step in `make_purple_blur` maps 1:1 onto the OCaml source and cannot be mistaken
/// for round-to-nearest.
fn truncate_f64(x: f64) -> i64 {
    x as i64
}

fn tent_blur(src: &[f32], width: usize, height: usize, radius: i64) -> Vec<f32> {
    let half = div_up(radius, 2).max(1) as usize;
    let b1 = box_blur_axis(src, width, height, half, 0);
    let b1v = box_blur_axis(&b1, width, height, half, 1);
    let b2 = box_blur_axis(&b1v, width, height, half, 0);
    box_blur_axis(&b2, width, height, half, 1)
}

/// Build the defocus/intensity mask (unpurple.ml `make_purple_blur`).
/// Mask source = `intensity · max(0, Pb − min_b)/(1 − min_b)`, then `tent_blur`.
///
/// Operates on a packed ProPhoto-RGB buffer (`[r0,g0,b0, r1,g1,b1, …]`) — the exact shape
/// the pipeline hands to rawalchemy, so no separate plane struct is needed here.
fn make_purple_blur(rgb: &[f32], width: usize, height: usize, s: &UnpurpleSettings) -> Vec<f32> {
    let n = width * height;
    let min_brightness = s.min_brightness as f32;
    let intensity = s.intensity as f32;
    let inv_denom = if (1.0f32 - min_brightness).abs() < 1e-6 {
        1.0f32
    } else {
        1.0f32 / (1.0f32 - min_brightness)
    };
    let mask_src: Vec<f32> = (0..n)
        .into_par_iter()
        .map(|i| {
            let pb = rgb[3 * i + 2];
            let grey = (pb - min_brightness).max(0.0) * inv_denom;
            intensity * grey
        })
        .collect();
    // OCaml `make_purple_blur`: `let radius = truncate (ceil param.radius) in`.
    // `truncate` rounds toward zero; we keep both steps verbatim so the integer
    // radius the blur sees is exactly what OCaml sees.
    let radius_int = truncate_f64(s.radius.ceil());
    tent_blur(&mask_src, width, height, radius_int)
}

/// Apply the purple-fringe removal **in place** on a packed ProPhoto-RGB buffer.
///
/// `rgb` is row-major packed triples (`[r0,g0,b0, r1,g1,b1, …]`); `width` is pixels per row.
/// Only `r`/`b` components are modified; `g` is untouched. Returns diagnostics. Returns an
/// all-zero diagnostics (and leaves `rgb` untouched) when the buffer is not a whole number of
/// pixels.
///
/// This is the entry point the pipeline calls between prophoto clipping and the rawalchemy
/// hand-off: it receives the linear ProPhoto-D50 buffer `RawlerImageDeveloped.rgb`, defringes
/// it, and hands the same buffer forward.
pub fn defringe_prophoto(
    rgb: &mut [f32],
    width: usize,
    height: usize,
    s: &UnpurpleSettings,
) -> UnpurpleDiagnostics {
    let zero = UnpurpleDiagnostics::default();
    let n = width * height;
    if n == 0 || rgb.len() != n * 3 {
        return zero;
    }

    let blur = make_purple_blur(rgb, width, height, s);

    // Local f32 copies of the ratio settings so the per-pixel math stays f32 (upstream
    // uses f32 throughout). Settings fields are f64 for ergonomic CLI/env parsing.
    // (radius/intensity/min_brightness are consumed inside `make_purple_blur`.)
    let min_red_to_blue_ratio = s.min_red_to_blue_ratio as f32;
    let max_red_to_blue_ratio = s.max_red_to_blue_ratio as f32;

    let diag = std::sync::Mutex::new((0usize, 0.0f64, 0.0f64));
    rgb.par_chunks_mut(3).enumerate().for_each(|(i, px)| {
        let pr = px[0];
        let pg = px[1];
        let pb = px[2];
        // Upstream `bl = min(255, 255*blur)` → ProPhoto `min(1.0, blur)`.
        let bl = blur[i].min(1.0).max(0.0);
        // Amount of blue/red that would produce grey if removed.
        // (These are the raw ProPhoto channel differences — NOT rescaled here; see the
        // `mb` clamp below for where the space compensation is applied.)
        let db = (pb - pg).max(0.0);
        let dr = (pr - pg).max(0.0);
        // --- ProPhoto `db` scale compensation at the binding clamp --------------------
        // OCaml computes `db = B-G`, `dr = R-G` on 8-bit *sRGB-encoded* values (no gamma
        // decode), while v4 runs in ProPhoto RGB (0-1, gamma 1.8 applied in io). The
        // nonlinear sRGB→ProPhoto map makes the ProPhoto channel differences systematically
        // smaller than their sRGB-encoded counterparts. `diag_tree.rs` measured on
        // `wikipedia-tree`: at under-pixels the ProPhoto `db` is ~0.859× the sRGB `db`
        // (median 15.46 vs 18.00). 69.9% of under-pixels have their `mb = min(bl, db)`
        // *bound by db*, and the ProPhoto db is smaller, so `mb_pp < mb_srgb` and we remove
        // less blue than the reference → under.
        // The compensation therefore belongs at the `mb` binding itself (not on `db`/`dr`,
        // which would also distort the raw channel differences feeding `dr`/`r_diff`): we
        // widen the `db` side of the `min` by `DB_PROPHOTO_SCALE` (derived from the
        // measured 0.859 ratio) so the bounded `mb` recovers the reference removal
        // magnitude. `bl` is untouched and `dr`/`r_diff` are NOT scaled — `mb` already
        // carries the widened value into the upper bounds of `r_diff = min(dr, mb*max_ratio)`
        // and `b_diff = min(mb, ...)`, so those clamps are relaxed automatically through
        // `mb`; applying the scale to `dr` directly would distort the raw channel diff.
        const DB_PROPHOTO_SCALE: f32 = 1.16; // = 1/0.859, from diag_tree on wikipedia-tree
        // Max blue we accept to remove, ignoring red level.
        let mb = bl.min(db * DB_PROPHOTO_SCALE);
        // Red to remove honors max red:blue ratio. `mb` here is the already-widened value,
        // so this upper-bound clamp inherits the compensation; `dr` itself stays raw.
        let r_diff = dr.min(mb * max_red_to_blue_ratio);
        // Blue to remove honors min red:blue ratio (if enabled).
        let b_diff = if min_red_to_blue_ratio > 0.0 {
            mb.min(r_diff / min_red_to_blue_ratio)
        } else {
            mb
        };
        let rnew = pr - r_diff;
        let bnew = pb - b_diff;
        let touched = db > 1e-6 && mb > 0.0;
        if touched {
            let mut g = diag.lock().unwrap();
            g.0 += 1;
            g.1 += b_diff as f64; // blue removed
            g.2 += r_diff as f64; // red removed
        }
        px[0] = rnew;
        px[2] = bnew;
    });

    let (cnt, bsum, asum) = *diag.lock().unwrap();
    UnpurpleDiagnostics {
        touched_px: cnt,
        blue_removed_mean: if cnt > 0 { bsum / cnt as f64 } else { 0.0 },
        red_removed_mean: if cnt > 0 { asum / cnt as f64 } else { 0.0 },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Identity settings (radius 0 / no-op) leave the buffer unchanged.
    #[test]
    fn zero_radius_is_identity() {
        let mut buf = vec![
            0.9_f32, 0.7, 0.95, 0.2, 0.4, 0.3, 1.0, 1.0, 1.0, 0.45, 0.25, 0.85,
        ];
        let before = buf.clone();
        let mut s = UnpurpleSettings::default();
        s.radius = 0.0;
        s.intensity = 0.0;
        defringe_prophoto(&mut buf, 4, 1, &s);
        assert_eq!(buf, before);
    }

    /// Green channel is never touched by the removal.
    #[test]
    fn green_is_untouched() {
        let mut buf = vec![0.9_f32, 0.7, 0.95, 0.2, 0.4, 0.3];
        let g_before = [buf[1], buf[4]];
        let mut s = UnpurpleSettings::default();
        s.intensity = 0.0; // no halo ⇒ no removal, but g must be pristine either way
        defringe_prophoto(&mut buf, 2, 1, &s);
        assert_eq!([buf[1], buf[4]], g_before);
    }

    /// A malformed buffer (not a whole number of triples) is a no-op, not a panic.
    #[test]
    fn wrong_length_is_noop() {
        let mut buf = vec![0.1_f32, 0.2];
        let before = buf.clone();
        defringe_prophoto(&mut buf, 2, 1, &UnpurpleSettings::default());
        assert_eq!(buf, before);
    }
}
