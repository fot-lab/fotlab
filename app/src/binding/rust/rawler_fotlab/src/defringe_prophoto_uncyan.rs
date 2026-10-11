//! Uncyan — **cyan-fringe** correction, the cyan-side counterpart of
//! `defringe_prophoto_unpurple.rs`, running on the same **linear ProPhoto-RGB 0..1 f32**
//! packed buffer and in the same pipeline slot (after prophoto clipping, before the
//! rawalchemy hand-off). It is wired **between** unpurple and ungreen.
//!
//! # Physics — why this is NOT the old "ungreen"
//!
//! The earlier `defringe_prophoto_ungreen.rs` modeled a *pure green* fringe and selected its
//! mask from the **green** plane. That is physically wrong. A real defringe cyan/green
//! fringe on a sensor is **blue and green acting together** — a glaring blue-green (cyan)
//! fringe — not a pure-green one. The green channel alone does not mark the fringe; the
//! **blue** channel does, exactly as in purple fringing. So this module returns to unpurple's
//! blue-channel mask and lowers the *other* two channels toward the remaining one.
//!
//! # The unpurple ↔ uncyan symmetry (red and green swap roles)
//!
//! Unpurple treats a **purple** fringe: red and blue are both in *excess* above green, so
//! the algorithm lowers **R and B** toward **G** (green is the neutral), using the **blue**
//! plane as the halo mask. The blue excess (`db = B − G`) is the *primary* removal (it is
//! what the mask measures); the red excess (`dr = R − G`) is the *secondary* removal, bounded
//! by the `min/max red:blue` ratios.
//!
//! Uncyan treats a **cyan** fringe: green and blue are both in excess above **red**, so by
//! symmetry it lowers **G and B** toward **R** (red is the neutral), again using the **blue**
//! plane as the halo mask. The blue excess (`db = B − R`) is the *primary* removal (same mask
//! channel as unpurple — the blue plane is the hallmark of both fringes); the green excess
//! (`dg = G − R`) is the *secondary* removal, bounded by the `min/max green:blue` ratios.
//!
//! So the **only** change versus unpurple is the swap of the secondary-lowered channel: red
//! (`R − G`) becomes green (`G − R`), and the neutral it is pulled toward is red instead of
//! green. The mask channel (blue), the blur, and the primary blue removal are byte-identical
//! to unpurple — which is why this module reuses unpurple's blur primitives verbatim and keeps
//! the same `UnpurpleSettings`-shaped parameter record.
//!
//! # Safety — "only ever neutral"
//!
//! Uncyan only ever *lowers* G and B toward R; it never raises any channel. The operation is
//! therefore monotone toward neutral — a pixel can at worst become grey, exactly as unpurple
//! can only ever desaturate toward grey. We can never introduce a new hue cast. (Raising R to
//! reach G/B would inject magenta/cyan and was deliberately rejected for the same reason it was
//! rejected in unpurple/ungreen.)
//!
//! # On `DCYAN_PROPHOTO_SCALE` — read before trusting it
//!
//! Unpurple carries a compensation factor `DB_PROPHOTO_SCALE = 1.16` because it was **measured**:
//! `diag_tree` on `wikipedia-tree` found ProPhoto's `B − G` running ≈0.859× its sRGB-encoded
//! counterpart, so unpurple's `mb` binding was widened by 1/0.859 to recover the reference
//! removal magnitude.
//!
//! That 1.16 is a property of the **blue channel and the `B − G` difference**. Uncyan's primary
//! bound uses the blue channel too (`B − R`), so carrying the same constant for the *blue*
//! `mb` binding is a reasonable first approximation — but its ProPhoto-vs-sRGB deviation has
//! never been measured here, and the green secondary inherits it through `mb`. The constant
//! below is therefore carried over as a **starting point, explicitly unverified for cyan** — it
//! is a named `const` rather than a literal so a future measurement can correct it in one place.

use rayon::prelude::*;

// ---------------------------------------------------------------------------
// Blur primitives — verbatim from unpurple.ml, translated exactly as in
// `defringe_prophoto_unpurple.rs` and `defringe_prophoto_ungreen.rs`. They are duplicated
// rather than shared on purpose: unpurple keeps them private, and each module holding its own
// copy keeps both traceable line-by-line to unpurple.ml and keeps the stages independent of
// each other's refactors. Any change here must be mirrored there.
//
// The mask source is the **blue** plane, identical to unpurple's `make_purple_blur`:
// `intensity · max(0, Pb − min_b)/(1 − min_b)`. The only thing uncyan does differently is the
// per-pixel secondary channel (green, not red) and the neutral it pulls toward (red, not
// green); the mask is blue in both.
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
        // init_acc: (radius+2)*a[0] — OCaml verbatim; the "+2" is load-bearing, not an
        // off-by-one that can be "simplified" away (it decides edge coverage).
        let mut acc = row[0] * (r as f32 + 2.0);
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

/// OCaml integer ceil-division, verbatim: `a/b + (a mod b <> 0)`.
fn div_up(a: i64, b: i64) -> i64 {
  let r = a / b;
  if a % b == 0 { r } else { r + 1 }
}

/// OCaml `truncate`: round toward zero. Rust `as i64` on an f64 already does this, but
/// keeping it explicit stops `truncate (ceil param.radius)` from being mistaken for
/// round-to-nearest.
fn truncate_f64(x: f64) -> i64 {
  x as i64
}

/// `tent_blur` = two box passes of `div_up radius 2` (unpurple.ml `tent_blur`).
fn tent_blur(src: &[f32], width: usize, height: usize, radius: i64) -> Vec<f32> {
  let half = div_up(radius, 2).max(1) as usize;
  let b1 = box_blur_axis(src, width, height, half, 0);
  let b1v = box_blur_axis(&b1, width, height, half, 1);
  let b2 = box_blur_axis(&b1v, width, height, half, 0);
  box_blur_axis(&b2, width, height, half, 1)
}

/// Uncyan parameters.
///
/// Structurally parallel to `UnpurpleSettings` — same field names, same meaning, same
/// role — so the three ProPhoto cores (unpurple, uncyan, ungreen) can be driven from one
/// dialog. The green/blue *ratio* bound is the exact mirror of unpurple's red/blue ratio
/// bound: it tempers how far green may be pulled down relative to the blue primary.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct UncyanSettings {
  /// Blur radius (pixels) for the halo mask. Same role as `UnpurpleSettings::radius`.
  /// The mask is the **blue** plane, exactly as unpurple.
  #[uniffi(default = 5.0)]
  pub radius: f64,
  /// Intensity multiplier on the halo source. Same role as `UnpurpleSettings::intensity`.
  #[uniffi(default = 1.0)]
  pub intensity: f64,
  /// Minimum brightness gate (0..1) on the blue plane. Same role as
  /// `UnpurpleSettings::min_brightness`; `-gentle` uses 0.8 there, and the same value is
  /// sensible here.
  #[uniffi(default = 0.0)]
  pub min_brightness: f64,
  /// Minimum green:blue ratio in the fringe (0 = no floor). Mirrors unpurple's
  /// `min_red_to_blue_ratio` with red→green swapped: it is the floor on how far green may
  /// be lowered relative to the blue primary.
  #[uniffi(default = 0.0)]
  pub min_green_to_blue_ratio: f64,
  /// Maximum green:blue ratio in the fringe. Mirrors unpurple's
  /// `max_red_to_blue_ratio` with red→green swapped; the uncyan default is 3.0
  /// (green may be lowered up to 3× the blue primary), vs unpurple's 0.33, because a
  /// cyan fringe carries far more excess green than a purple fringe carries excess red.
  #[uniffi(default = 3.0)]
  pub max_green_to_blue_ratio: f64,
}

impl Default for UncyanSettings {
  fn default() -> Self {
    UncyanSettings {
      radius: 5.0,
      intensity: 1.0,
      min_brightness: 0.0,
      min_green_to_blue_ratio: 0.0,
      max_green_to_blue_ratio: 3.0,
    }
  }
}

/// Diagnostics for the harness `diagnose:` line.
#[derive(Debug, Clone, Copy, Default)]
pub struct UncyanDiagnostics {
  /// Pixels that had any blue (or green) removed (a cyan fringe was selected).
  pub touched_px: usize,
  /// Mean blue removed over touched pixels (Pb_orig − Pb_new).
  pub blue_removed_mean: f64,
  /// Mean green removed over touched pixels (Pg_orig − Pg_new).
  pub green_removed_mean: f64,
}

/// ProPhoto-vs-sRGB compensation for the blue primary bound — carried over from unpurple's
/// blue-channel measurement (`1/0.859` from `diag_tree` on `wikipedia-tree`) and NOT
/// re-fitted for the `B − R` quantity. See the module docs: the value unpurple fitted
/// describes `B − G`, not `B − R`. Kept as a named const so one future measurement corrects
/// one line.
const DCYAN_PROPHOTO_SCALE: f32 = 1.16;

/// Build the halo mask — **blue** plane as source, identical to unpurple's `make_purple_blur`.
///
/// Mask source = `intensity · max(0, Pb − min_b)/(1 − min_b)`, then `tent_blur` with an
/// integer radius obtained exactly as OCaml does it (`truncate (ceil param.radius)`). The
/// cyan fringe is marked by the blue channel just like the purple fringe, so the mask is
/// byte-identical to unpurple's.
fn make_cyan_blur(rgb: &[f32], width: usize, height: usize, s: &UncyanSettings) -> Vec<f32> {
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
      // Blue plane is the halo source — same as unpurple.
      let pb = rgb[3 * i + 2];
      let grey = (pb - min_brightness).max(0.0) * inv_denom;
      intensity * grey
    })
    .collect();
  // Same verbatim OCaml radius chain as unpurple: `truncate (ceil param.radius)`.
  let radius_int = truncate_f64(s.radius.ceil());
  tent_blur(&mask_src, width, height, radius_int)
}

/// Apply the cyan-fringe removal **in place** on a packed ProPhoto-RGB buffer.
///
/// `rgb` is row-major packed triples (`[r0,g0,b0, r1,g1,b1, …]`); `width` is pixels per row.
/// Only the `g` and `b` components are modified — `r` is left bit-identical. Returns
/// diagnostics; returns all-zero diagnostics (leaving `rgb` untouched) when the buffer is not
/// a whole number of pixels.
///
/// This is the entry point the pipeline calls between unpurple and ungreen, operating on the
/// linear ProPhoto-D50 buffer the grade consumes. It is the cyan mirror of unpurple: lower G
/// and B toward R, mask from the blue plane.
pub fn defringe_prophoto_uncyan(
  rgb: &mut [f32],
  width: usize,
  height: usize,
  s: &UncyanSettings,
) -> UncyanDiagnostics {
  let zero = UncyanDiagnostics::default();
  let n = width * height;
  if n == 0 || rgb.len() != n * 3 {
    return zero;
  }

  let blur = make_cyan_blur(rgb, width, height, s);

  // Local f32 copies so the per-pixel math stays f32; the fields are f64 for ergonomic
  // parsing. (radius / intensity / min_brightness are consumed inside `make_cyan_blur`.)
  let min_green_to_blue_ratio = s.min_green_to_blue_ratio as f32;
  let max_green_to_blue_ratio = s.max_green_to_blue_ratio as f32;

  let diag = std::sync::Mutex::new((0usize, 0.0f64, 0.0f64));
  rgb.par_chunks_mut(3).enumerate().for_each(|(i, px)| {
    let pr = px[0];
    let pg = px[1];
    let pb = px[2];
    // Upstream `bl = min(255, 255*blur)` → ProPhoto `min(1.0, blur)`.
    let bl = blur[i].min(1.0).max(0.0);
    // Excess above the neutral (red), for each colorant that is in excess:
    //   db = B − R   (blue primary, exactly as unpurple's `B − G` with G→R)
    //   dg = G − R   (green secondary, the red→green swap vs unpurple's `R − G`)
    let db = (pb - pr).max(0.0);
    let dg = (pg - pr).max(0.0);
    // --- ProPhoto `db` scale compensation at the binding clamp ------------------
    // Mirrors unpurple's structure verbatim with R↔G swapped: the raw channel difference
    // stays untouched and the compensation widens the *clamp* that binds it. The constant is
    // unpurple's blue-channel 1.16 carried over (see `DCYAN_PROPHOTO_SCALE`).
    const DB_PROPHOTO_SCALE: f32 = DCYAN_PROPHOTO_SCALE;
    // Max blue we accept to remove, ignoring green level (blue is the mask/primary channel).
    let mb = bl.min(db * DB_PROPHOTO_SCALE);
    // Green to remove honors max green:blue ratio. `mb` here is the already-widened value,
    // so this upper-bound clamp inherits the compensation; `dg` itself stays raw.
    let g_diff = dg.min(mb * max_green_to_blue_ratio);
    // Blue to remove honors min green:blue ratio (if enabled).
    let b_diff = if min_green_to_blue_ratio > 0.0 {
      mb.min(g_diff / min_green_to_blue_ratio)
    } else {
      mb
    };
    let gnew = pg - g_diff;
    let bnew = pb - b_diff;
    let touched = db > 1e-6 && mb > 0.0;
    if touched {
      let mut g = diag.lock().unwrap();
      g.0 += 1;
      g.1 += b_diff as f64; // blue removed
      g.2 += g_diff as f64; // green removed
    }
    px[1] = gnew;
    px[2] = bnew;
  });

  let (cnt, bsum, gsum) = *diag.lock().unwrap();
  UncyanDiagnostics {
    touched_px: cnt,
    blue_removed_mean: if cnt > 0 { bsum / cnt as f64 } else { 0.0 },
    green_removed_mean: if cnt > 0 { gsum / cnt as f64 } else { 0.0 },
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  /// Zero intensity ⇒ no halo ⇒ no removal.
  #[test]
  fn zero_radius_is_identity() {
    let mut buf = vec![
      0.9_f32, 0.7, 0.95, 0.2, 0.4, 0.3, 1.0, 1.0, 1.0, 0.45, 0.25, 0.85,
    ];
    let before = buf.clone();
    let mut s = UncyanSettings::default();
    s.radius = 0.0;
    s.intensity = 0.0;
    defringe_prophoto_uncyan(&mut buf, 4, 1, &s);
    assert_eq!(buf, before);
  }

  /// Red channel is never touched — the cyan analogue of unpurple's "G is untouched".
  #[test]
  fn red_is_untouched() {
    let mut buf = vec![0.2_f32, 0.9, 0.3, 0.1, 0.8, 0.95];
    let r_before = [buf[0], buf[3]];
    defringe_prophoto_uncyan(&mut buf, 2, 1, &UncyanSettings::default());
    assert_eq!([buf[0], buf[3]], r_before);
  }

  /// A cyan fringe (G and B both above R) IS corrected: both green and blue are lowered,
  /// never raised, toward the red neutral.
  #[test]
  fn cyan_fringe_is_lowered() {
    let mut buf = vec![0.10_f32, 0.90, 0.85, 0.10, 0.80, 0.95];
    let before = buf.clone();
    let diag = defringe_prophoto_uncyan(&mut buf, 2, 1, &UncyanSettings::default());
    assert!(diag.touched_px > 0, "cyan fringe must qualify");
    assert!(buf[1] < before[1], "G must be lowered");
    assert!(buf[4] < before[4], "G must be lowered");
    assert!(buf[2] < before[2], "B must be lowered");
    assert!(buf[5] < before[5], "B must be lowered");
    // Red is the neutral: never raised.
    assert!(buf[0] <= before[0] && buf[3] <= before[3]);
  }

  /// A non-cyan pixel (red dominant) is returned bit-identical. G/B must not be changed.
  #[test]
  fn non_cyan_is_untouched() {
    // R-dominant: [0.9,0.3,0.4] — G and B both below R, so not a cyan fringe.
    let mut buf = vec![0.9_f32, 0.3, 0.4];
    let before = buf.clone();
    let diag = defringe_prophoto_uncyan(&mut buf, 1, 1, &UncyanSettings::default());
    assert_eq!(diag.touched_px, 0, "R-dominant pixel must not qualify");
    assert_eq!(buf, before);
  }

  /// A malformed buffer (not a whole number of triples) is a no-op, not a panic.
  #[test]
  fn wrong_length_is_noop() {
    let mut buf = vec![0.1_f32, 0.2];
    let before = buf.clone();
    let diag = defringe_prophoto_uncyan(&mut buf, 2, 1, &UncyanSettings::default());
    assert_eq!(buf, before);
    assert_eq!(diag.touched_px, 0);
  }
}
