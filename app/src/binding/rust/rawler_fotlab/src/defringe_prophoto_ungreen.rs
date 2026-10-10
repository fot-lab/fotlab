//! Ungreen — green-fringe correction, the green-side counterpart of
//! `defringe_prophoto_unpurple.rs`, running on the same **linear ProPhoto-RGB 0..1 f32**
//! packed buffer and in the same pipeline slot (after prophoto clipping, before the
//! rawalchemy hand-off).
//!
//! # This is NOT an upstream port — read this before comparing it to `unpurple.ml`
//!
//! `unpurple.ml` has **no green mode**. Its `param` record is entirely purple, and its
//! README is explicit that the algorithm will not remove green fringing ("The current
//! algorithm won't remove it… I estimate it would take at least a couple of days to
//! obtain a proof of concept, if it is feasible" — `README.md`). There is no
//! `ungreen.ml` to be faithful to.
//!
//! So this module is a **symmetric extension authored for this project**, not a
//! translation. Its legitimacy rests on three pillars, each named below:
//!
//! 1. **The unpurple skeleton, reused.** Mask → tent blur → per-pixel bounded removal.
//!    Keeping the three-stage shape is what makes the two modules tunable against each
//!    other: the same `radius` / `intensity` / `min_brightness` vocabulary drives both.
//! 2. **The green criterion from this repo's tested `green_delta`**
//!    (`rawtrp_correct/src/ca_correction_aca.rs`), which is the only green-fringe logic
//!    in the codebase that has unit tests behind it.
//! 3. **The "only ever neutral" safety property**, which is the one thing unpurple's
//!    design genuinely earns and which must not be lost in the green direction.
//!
//! # Why "only ever neutral" transfers, and what changes
//!
//! Unpurple *lowers* R and B toward G, never touching G. That direction is safe by
//! construction: a pixel can at worst become grey. Green fringing needs the opposite —
//! the excess lives **in G itself**, so the only correct repair is to lower G. Lowering
//! G toward `max(R,B)` is likewise monotone toward neutral, so the safety property
//! survives: **we never raise a channel, so we can never introduce a new hue cast** —
//! we can only ever desaturate toward grey. (The mirror-image strategy, raising R and B
//! until they reach G, was deliberately rejected: it injects magenta/cyan into the
//! image and can manufacture exactly the kind of fringe we are removing.)
//!
//! # The green excess
//!
//! ```text
//! excess = max(G − max(R, B), 0)
//! ```
//!
//! G must be above **both** R and B to count. This is the stricter of the two criteria
//! in circulation — the alternative `max(G − (R+B)/2, 0)` (used by the since-deleted
//! OKLab green branch) only requires G above the r/b midpoint and so fires on merely
//! warm pixels where R ≠ B. Requiring G to be the outright maximum matches how a green
//! fringe actually looks.
//!
//! A trap worth naming, because it was hit and caught in this repo before: the green
//! primary must **not** be written as `max(R−G, 0)` (the purple *red* term reused by
//! symmetry). For a pure-green pixel that expression is identically zero, so green
//! correction silently disables itself with no error and no visible symptom.
//!
//! # On `DG_PROPHOTO_SCALE` — read before trusting it
//!
//! Unpurple carries a compensation factor because it was **measured**: `diag_tree` on
//! `wikipedia-tree` found ProPhoto's `B−G` running ≈0.859× its sRGB-encoded counterpart,
//! so unpurple's `mb` binding was widened by 1/0.859 = 1.16 to recover the reference
//! removal magnitude.
//!
//! That 1.16 is a property of **the blue channel and the `B−G` difference**. This module
//! uses a **green** halo source and a **different** quantity (`G − max(R,B)`), whose
//! ProPhoto-vs-sRGB deviation has never been measured here. The constant below is
//! therefore carried over as a **starting point, explicitly unverified for green** —
//! it is deliberately a named `const` rather than a literal so that a future measurement
//! can correct it in one place. Do not read it as a fitted green-space value.

use rayon::prelude::*;

// ---------------------------------------------------------------------------
// Blur primitives — verbatim from unpurple.ml, translated exactly as in
// `defringe_prophoto_unpurple.rs`. They are duplicated rather than shared on purpose:
// unpurple keeps them private, and each module holding its own copy of the upstream
// primitives keeps both traceable line-by-line to unpurple.ml and keeps the two stages
// independent of each other's refactors. Any change here must be mirrored there.
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

/// Ungreen parameters.
///
/// Structurally parallel to `UnpurpleSettings` — same field names, same meaning, same
/// role — so the two stages can be driven from one dialog. The differences are noted
/// per field. Defaults are this project's, not upstream's: there is no upstream.
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct UngreenSettings {
  /// Blur radius (pixels) for the halo. Same role as `UnpurpleSettings::radius`.
  #[uniffi(default = 5.0)]
  pub radius: f64,
  /// Intensity multiplier on the halo source. Same role as `UnpurpleSettings::intensity`.
  #[uniffi(default = 1.0)]
  pub intensity: f64,
  /// Minimum brightness gate (0..1) on the green plane. Same role as
  /// `UnpurpleSettings::min_brightness`; `-gentle` uses 0.8 there, and the same value is
  /// sensible here.
  #[uniffi(default = 0.0)]
  pub min_brightness: f64,
  /// Weight of the `(R+B)/2` hedge when lowering G (0 = pure `max(R,B)` target,
  /// 0.2 = the RapidRAW split this repo already uses). Mirrors the way unpurple's
  /// `max_red_to_blue_ratio` tempers its secondary channel.
  #[uniffi(default = 0.2)]
  pub hedge: f64,
  /// Floor on how far G may be lowered, as a fraction of `excess`. 1.0 = "remove the
  /// excess completely", the default. Smaller values back off toward the original,
  /// useful when full removal over-corrects.
  #[uniffi(default = 1.0)]
  pub strength: f64,
}

/// Diagnostics for the harness `diagnose:` line.
#[derive(Debug, Clone, Copy, Default)]
pub struct UngreenDiagnostics {
  /// Pixels that qualified as a green fringe and were lowered.
  pub touched_px: usize,
  /// Mean green removed over touched pixels (Pg_orig − Pg_new).
  pub green_removed_mean: f64,
  /// Mean proportional shortfall left behind over touched pixels, in the same units —
  /// `0.0` means every touched pixel was driven fully to neutral.
  pub residual_excess_mean: f64,
}

/// ProPhoto-vs-sRGB compensation for the green excess — **carried over from unpurple's
/// blue-channel measurement and NOT re-fitted for green.** See the module docs: the
/// value unpurple fitted (`1/0.859` from `diag_tree` on `wikipedia-tree`) describes
/// `B−G`, not `G − max(R,B)`. Kept as a named const so one future measurement corrects
/// one line.
const DG_PROPHOTO_SCALE: f32 = 1.16;

/// Build the halo mask (unpurple's `make_purple_blur`, with the **green** plane as source).
///
/// Identical in form to the purple version — mask source
/// `intensity · max(0, Pg − min_b)/(1 − min_b)`, then `tent_blur` with an integer radius
/// obtained exactly as OCaml does it (`truncate (ceil param.radius)`) — so the two stages
/// share their blur behaviour and only the source plane differs.
fn make_green_blur(rgb: &[f32], width: usize, height: usize, s: &UngreenSettings) -> Vec<f32> {
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
      let pg = rgb[3 * i + 1];
      let grey = (pg - min_brightness).max(0.0) * inv_denom;
      intensity * grey
    })
    .collect();
  // Same verbatim OCaml radius chain as unpurple: `truncate (ceil param.radius)`.
  let radius_int = truncate_f64(s.radius.ceil());
  tent_blur(&mask_src, width, height, radius_int)
}

/// Apply the green-fringe removal **in place** on a packed ProPhoto-RGB buffer.
///
/// `rgb` is row-major packed triples (`[r0,g0,b0, r1,g1,b1, …]`); `width` is pixels per
/// row. **Only the `g` component is modified** — R and B are left bit-identical, which
/// is the green analogue of unpurple's guarantee that G is never touched. Returns
/// diagnostics; returns all-zero diagnostics (leaving `rgb` untouched) when the buffer is
/// not a whole number of pixels.
///
/// This is the entry point the pipeline calls between prophoto clipping and the
/// rawalchemy hand-off, operating on the linear ProPhoto-D50 buffer the grade consumes.
pub fn defringe_ungreen(
  rgb: &mut [f32],
  width: usize,
  height: usize,
  s: &UngreenSettings,
) -> UngreenDiagnostics {
  let zero = UngreenDiagnostics::default();
  let n = width * height;
  if n == 0 || rgb.len() != n * 3 {
    return zero;
  }

  let blur = make_green_blur(rgb, width, height, s);

  // Local f32 copies so the per-pixel math stays f32; the fields are f64 for ergonomic
  // parsing. (radius / intensity / min_brightness are consumed inside `make_green_blur`.)
  let hedge = (s.hedge as f32).clamp(0.0, 1.0);
  let strength = (s.strength as f32).clamp(0.0, 1.0);

  // Accumulate (count, green_removed, residual_excess) across the parallel fold.
  let diag = std::sync::Mutex::new((0usize, 0.0f64, 0.0f64));
  rgb.par_chunks_mut(3).enumerate().for_each(|(i, px)| {
    let pr = px[0];
    let pg = px[1];
    let pb = px[2];
    // Upstream `bl = min(255, 255*blur)` → ProPhoto `min(1.0, blur)`.
    let bl = blur[i].min(1.0).max(0.0);
    // The green excess: G above BOTH R and B. See module docs for why this is not
    // `max(R-G, 0)`.
    let excess = (pg - pr.max(pb)).max(0.0);
    // --- ProPhoto compensation at the binding clamp --------------------------------
    // Mirrors unpurple's structure: the raw channel difference stays untouched and the
    // compensation widens the *clamp* that binds it, so nothing downstream inherits a
    // distorted difference. See `DG_PROPHOTO_SCALE` for why this value is provisional.
    let mg = bl.min(excess * DG_PROPHOTO_SCALE);
    // Target: pull G down toward max(R,B), hedged toward the r/b midpoint. Lowering is
    // one-sided — `(pg - target).max(0.0)` — so G is never raised and the pixel can
    // only desaturate toward grey.
    let target = pr.max(pb) * (1.0 - hedge) + (pr + pb) * 0.5 * hedge;
    let correction = (pg - target).max(0.0);
    // `strength` scales the whole removal, so backing off needs no new branch.
    let dg = (correction * strength).min(mg);
    let gnew = pg - dg;
    let touched = excess > 1e-6 && mg > 0.0;
    if touched {
      let mut g = diag.lock().unwrap();
      g.0 += 1;
      g.1 += dg as f64;
      // What the bounded removal left behind, as a fraction of the original excess.
      g.2 += ((gnew - pr.max(pb)).max(0.0) / excess) as f64;
    }
    px[1] = gnew;
  });

  let (cnt, gsum, rsum) = *diag.lock().unwrap();
  UngreenDiagnostics {
    touched_px: cnt,
    green_removed_mean: if cnt > 0 { gsum / cnt as f64 } else { 0.0 },
    residual_excess_mean: if cnt > 0 { rsum / cnt as f64 } else { 0.0 },
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  /// Zero intensity ⇒ no halo ⇒ no removal.
  #[test]
  fn zero_radius_is_identity() {
    let mut buf = vec![0.9_f32, 0.7, 0.95, 0.2, 0.4, 0.3, 1.0, 1.0, 1.0, 0.45, 0.25, 0.85];
    let before = buf.clone();
    let mut s = UngreenSettings::default();
    s.radius = 0.0;
    s.intensity = 0.0;
    defringe_ungreen(&mut buf, 4, 1, &s);
    assert_eq!(buf, before);
  }

  /// R and B are never touched — the green analogue of unpurple's "G is untouched".
  #[test]
  fn red_blue_are_untouched() {
    let mut buf = vec![0.2_f32, 0.9, 0.3, 0.1, 0.8, 0.15];
    let rb_before = [buf[0], buf[2], buf[3], buf[5]];
    defringe_ungreen(&mut buf, 2, 1, &UngreenSettings::default());
    assert_eq!([buf[0], buf[2], buf[3], buf[5]], rb_before);
  }

  /// A pure-green pixel (G above both R and B) IS corrected. This is the regression test
  /// for the `max(R-G, 0)` trap: a green-primary written that way is identically zero
  /// here and would leave the buffer untouched while appearing to run.
  #[test]
  fn pure_green_is_lowered() {
    let mut buf = vec![0.10_f32, 0.90, 0.20, 0.10, 0.90, 0.20];
    let before = buf.clone();
    let diag = defringe_ungreen(&mut buf, 2, 1, &UngreenSettings::default());
    assert!(diag.touched_px > 0, "pure green must qualify as a fringe");
    // Lowered, never raised — and driven all the way to neutral at the default strength,
    // so no green excess survives (this is what the `min(excess*SCALE)` bound buys).
    assert!(buf[1] < before[1], "G must be lowered");
    assert!(buf[4] < before[4], "G must be lowered");
    assert!(buf[1] <= before[1] && buf[4] <= before[4], "G must never be raised");
    assert!(
      diag.residual_excess_mean < 1e-3,
      "default strength should leave no green excess, got {}",
      diag.residual_excess_mean
    );
  }

  /// G is never raised, and a pixel with no green excess is returned bit-identical.
  /// The three samples are chosen so none is green: G must not exceed both R and B.
  #[test]
  fn non_green_is_untouched() {
    // [0.8,0.3,0.4] R-dominant · [0.5,0.2,0.9] B-dominant · [0.7,0.5,0.6] B-dominant.
    // The last one is the trap case — G (0.5) is below R (0.7) but also below B (0.6),
    // so it must NOT count even though it is "greenish" in a loose sense.
    let mut buf = vec![0.8_f32, 0.3, 0.4, 0.5, 0.2, 0.9, 0.7, 0.5, 0.6];
    let before = buf.clone();
    let diag = defringe_ungreen(&mut buf, 3, 1, &UngreenSettings::default());
    assert_eq!(diag.touched_px, 0, "no sample qualifies as a green fringe");
    assert_eq!(buf, before);
  }

  /// `strength` scales the removal monotonically: 1.0 removes more than 0.5.
  #[test]
  fn strength_scales_removal() {
    let px = [0.10_f32, 0.90, 0.20];
    let run = |strength: f64| {
      let mut buf = px.to_vec();
      let mut s = UngreenSettings::default();
      s.strength = strength;
      defringe_ungreen(&mut buf, 1, 1, &s);
      px[1] - buf[1]
    };
    assert!(run(1.0) > run(0.5));
    assert!(run(0.5) > 0.0);
  }

  /// A malformed buffer (not a whole number of triples) is a no-op, not a panic.
  #[test]
  fn wrong_length_is_noop() {
    let mut buf = vec![0.1_f32, 0.2];
    let before = buf.clone();
    let diag = defringe_ungreen(&mut buf, 2, 1, &UngreenSettings::default());
    assert_eq!(buf, before);
    assert_eq!(diag.touched_px, 0);
  }
}