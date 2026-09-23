//! LMMSE — "directional Linear Minimum Mean Square-error Estimation" Bayer
//! demosaic.
//!
//! Ported from `external/RawTherapee/rtengine/lmmse_demosaic.cc`
//! (`RawImageSource::lmmse_interpolate_omp`, `:42-664`, and
//! `RawImageSource::refinement`, `:666-825`; Copyright (c) 2004-2019 Gabor
//! Horvath, algorithm by L. Zhang and X. Wu, adapted to RawTherapee by Jacques
//! Desmis 3/2013, speed and memory work by Ingo Weyrich 2/2015 — GPL-3.0).
//!
//! The paper is Zhang & Wu, *Color demosaicing via directional Linear Minimum
//! Mean Square-error Estimation*, IEEE TIP 14(12), Dec. 2005. Where IGV weighs
//! two directional colour differences by a gradient, LMMSE treats the
//! difference between the two directions as a **noise-versus-signal** problem:
//! for each direction it computes a high-pass term, a low-pass term and their
//! local means and variances, and blends the directions by the estimated
//! signal-to-noise ratio of each.
//!
//! ```text
//!   step 1  gamma-correct the mosaic into a padded plane          (:151-174)
//!   step 2  G-R / G-B at R/B sites, then at G sites               (:176-231)
//!   step 3  low-pass each of the two difference planes            (:234-256)
//!   step 4  LMMSE blend of the two directions                     (:258-399)
//!   step 5  copy how the CFA is actually sampled                  (:401-432)
//!   step 6  bilinear R/B at G, then at the opposite R/B           (:434-479)
//!   step 7  `iter` passes of a 3x3 differential median + rebuild  (:483-608)
//!   step 8  write out, un-gamma                                    (:610-643)
//!   step 9  `passref` passes of `refinement`  (only if iters > 4)  (:660-662)
//! ```
//!
//! ## Which upstream implementation this is
//!
//! `lmmse_demosaic.cc` selects on `#if defined(__SSE2__) || defined(RT_SIMDE)`
//! in two places: the core blend (`:265-330`) and the median pass (`:496-517`).
//! Both are *peephole vectorisations of the same loop* — unlike IGV, LMMSE's two
//! branches share one indexing scheme, so no structural decision rides on the
//! choice. This port follows the **scalar** text, which is what our target
//! compiles: `__SSE2__` is undefined on aarch64 and `WITH_SIMDE` is opt-in and
//! off by default (`external/RawTherapee/CMakeLists.txt:203`).
//!
//! ## Numeric domain
//!
//! LMMSE is the one kernel in this crate whose internal domain is **not** the
//! mosaic's. Upstream reads `rawData` in RT's 0..65535 scale and immediately
//! pushes it through `Color::gammatab_24_17a`, a 65536-entry LUT indexed in that
//! same scale; the body then works on `[0, 1]` gamma-corrected values, and the
//! write-out converts back with `65535.f * rix[0]` and `Color::igammatab_24_17`.
//! So the mapping is
//!
//! ```text
//!   rawData (RT, 0..65535)  ==  mosaic (ours, 0..1) * SCALE,   SCALE = 65536
//! ```
//!
//! with `SCALE` the same elision `bayer/rcd.rs` documents: RT's own kernels
//! disagree about whether `rawData` saturates at 65535 or 65536, and this crate
//! settled on 65536 so that the mosaic can be fed to any kernel unchanged. Two
//! consequences are visible in the code below and are **not** bugs to be
//! "tidied":
//!
//! * the pass-through branch of the write-out is `CLIP(rawData)` — a clamp to
//!   `[0, 65535]` — so a *saturated* sample comes back as `65535/65536` and not
//!   as `1.0` (`:640`);
//! * every output is divided by `SCALE` on the way out, including the
//!   reconstructed channels, which upstream leaves in the 0..65535 scale.
//!
//! Everything else in the kernel — the `1e-7` variance floors, the `1.75 * Y`
//! threshold, the gradient weights — lives in the gamma-corrected `[0, 1]`
//! domain and is therefore scale-free.
//!
//! `refinement` is the exception in the other direction: its weight
//! denominators `1.f + fabsf(...) + fabsf(...)` and its `+ 0.5f` offset are
//! applied to **rawData-scale** values, so both must be multiplied by `SCALE`
//! when they are evaluated on this crate's planes. See [`refinement`].
//!
//! ## Fidelity notes
//!
//! * **The 10-pixel zero ring is load-bearing, and nothing ever repairs it.**
//!   Upstream allocates five planes of `(height + 2*ba) x (width + 2*ba)` with
//!   `ba = 10` and only ever fills the interior (`:159-165`, `:406-423`); the
//!   ring stays zero. Unlike every other Bayer kernel in RawTherapee — `ahd`,
//!   `amaze`, `dcraw`'s bilinear, `hphd`, `rcd`, `vng4` and even `igv` — LMMSE
//!   **never calls `border_interpolate`**; there is no such call anywhere in
//!   `lmmse_demosaic.cc` and none after its call site either
//!   (`rawimagesource.cc:1834`). The frame's outer pixels are therefore computed
//!   from zeros and are left that way. This port reproduces it. Measured on a
//!   flat field, the contaminated band is about 7 pixels wide, and it is
//!   *asymmetric* because the phase-4/direction reach is 4 while the median pass
//!   only reaches 1 — the useful thing to know is that it is bounded by `ba`.
//! * **`iterations` is a small state machine with three branches, and the third
//!   one is easy to miss.** `:79-88`: `1..=4` ⇒ `iter = iterations - 1` with
//!   `passref = 0`; `5 | 6` ⇒ `iter = 3` with `passref = iterations - 4`; and
//!   `7 | 8` ⇒ `iter = 3` with `passref = iterations - 6`. So `7` and `8`
//!   behave exactly like `5` and `6`, and the three branches together cover
//!   `..=8`. Two more rules sit on top (`:90-97`, `:660`): `iterations == 0`
//!   forces `iter = 0` **and** turns the tone curve off, and `refinement` runs
//!   only when `iterations > 4` — so `iterations > 8` is "gamma on, no median
//!   pass, no refinement", i.e. what `1` does, and a negative value does the same.
//!   The GUI offers `0..=6` with a default of `2`
//!   (`rtgui/tools/bayerprocess.cc:119`, `params/raw.cc:87`), so the `7`/`8`
//!   branch is unreachable from the UI but reachable from a profile; it is
//!   reproduced rather than clamped. (An earlier revision of this port missed the
//!   third branch and made `7` behave like `1`.)
//! * **`iterations = 0` round-trips a flat field exactly.** With the identity
//!   tables the whole kernel reduces to a linear pass, and the tone curve is
//!   exactly invertible, so a constant mosaic of `v` returns exactly `v` in all
//!   three channels — including the pass-through channel, whose `CLIP` cannot
//!   bite below `65535/65536`. That makes `iterations = 0` the strongest single
//!   test available here (see `flat_field_survives_the_identity_curve`).
//! * **The output is not clamped above.** The write-out is
//!   `std::max(0.f, ...)` (`:638`) and `refinement` is `std::max(0.f, v0)`
//!   (`:730`), so both directions are missing an upper bound. The LMMSE blend is
//!   a convex combination of its inputs and stays inside `[0, 1]`, but the
//!   reconstructed R/B channels are `G + (R - G)` sums of two independently
//!   clamped estimates, and `refinement` adds a half-LSB too many — measured on
//!   a random mosaic the output reaches about `1.54` at `iterations = 2` and
//!   `2.64` at `iterations = 6`. RawTherapee clamps much later in its pipeline,
//!   so a consumer of this crate must not assume `[0, 1]`.
//! * **`median(a, b, c)` is the `std::array<T, 3>` network, not the generic
//!   array overload.** Step 2 calls `median(rix[0][0], rix[4][-1], rix[4][1])`
//!   (`:192`, `:204`) as three scalars, which the variadic wrapper
//!   (`median.h:6240-6244`) turns into `median(std::array<float, 3>{...})`. Two
//!   templates are then viable and partial ordering picks
//!   `median(std::array<T, 3>)` (`median.h:53-57`), i.e.
//!   `max(min(a, b), min(c, max(a, b)))`. An earlier revision of this crate
//!   modelled the *generic* overload instead; see `math::median3`.
//! * **The step-4 column start is `FC(rr, 4)`, not `FC(rr, 2)`.** Each phase
//!   picks its starting column from a different neighbour (`:183` uses column 2,
//!   `:213` column 3, `:264` column 4, `:441` column 2, `:466` column 1, `:551`
//!   columns 0 and 1, `:700` column 2, `:740` column 3, `:786` column 2). They
//!   are not interchangeable: each is chosen so that the stride-2 walk lands on
//!   the site class that loop is for, and the parity argument is one line in
//!   each case but the wrong column still compiles and still produces a
//!   plausible image.
//! * **`rawData` is referenced in the *padded* coordinate system.** Every
//!   `FC(rr, cc)` inside the kernel takes padded indices, while the write-out
//!   takes image indices. Because `ba = 10` is even the two agree for a
//!   2x2-periodic CFA, which is presumably why the code gets away with mixing
//!   them; this port keeps the same call sites and does not "fix" either.
//!   A CFA that is not 2x2-periodic would see the difference (see
//!   `padded_lookup_of_a_bayer_is_the_image_lookup`).
//! * **Parallelism.** Every phase here is sharded over rows exactly where
//!   upstream wrote `#pragma omp for`. That is safe in each case for a reason
//!   worth stating once: a phase writes one (plane, site-class) combination and
//!   reads either a different plane, or the same plane at the *other* site
//!   class, or the same plane in the same row. The two chroma passes of IGV
//!   failed exactly this test — they read the plane they write three rows away —
//!   and had to be serialised; LMMSE's do not, so nothing here is serialised and
//!   nothing uses `unsafe`.
//!   The one ordering constraint is *between* phases, and it is real: step 3
//!   reads the differences step 2 wrote, step 4 reads step 3's low-passes and
//!   step 2's differences, step 5 overwrites the difference planes, step 6
//!   reads step 5, and each median pass reads the previous phase's output. Those
//!   are the implicit barriers OpenMP's separate `omp for` regions provided.
//! * **Five planes, and what happens when they do not fit.** The buffers total
//!   `5 * (width + 20) * (height + 20) * 4` bytes — about 900 MB for a 45 MP
//!   sensor. Upstream allocates one block and, if that fails, five smaller ones,
//!   and if *that* fails it falls back to `igv_interpolate` (`:103-126`). This
//!   port always takes the five-block form (identical behaviour, different
//!   allocation strategy) and reproduces the fallback with `try_reserve`, so an
//!   out-of-memory condition degrades to IGV instead of aborting.

use rayon::prelude::*;
use std::sync::OnceLock;

use crate::array2d::Array2D;
use crate::cfa::CfaDesc;
use crate::math::{abs, clip, lim, max0, median3, median9, sqr, xdiv2f};
use crate::{Error, Rgb};

/// The mosaic-to-`rawData` scale. See the module note: this is `rcd`'s elision
/// constant, and LMMSE has to honour it because its tone-curve LUTs are indexed
/// in `rawData` units.
const SCALE: f32 = 65536.0;

/// `ba` — the width of the zero ring that pads every working plane (`:57-59`).
const BA: usize = 10;

/// `MAXVAL` (`rt_math.h:12`) — the upper bound of `CLIP` (`rt_math.h:102-105`).
const MAXVAL: f32 = 65535.0;

/// `maxindex` — the tone-curve table size (`color.cc:172`).
const LUT_SIZE: usize = 65536;

/// `maxs` — `LUTf`'s `size - 2`, the last index `operator[]` will interpolate
/// *from* (`LUT.h:132-133`).
const LUT_MAXS: usize = LUT_SIZE - 2;

/// `maxsf` — the same bound as a float, and the value `operator[]` compares
/// against (`LUT.h:133`, `:472`).
const LUT_MAXSF: f32 = LUT_MAXS as f32;

/// `gamma24_17` (`color.h:1212-1215`) — the tone curve of
/// `Color::gammatab_24_17a`, in `f64` exactly as upstream writes it (the header
/// takes and returns `double`, and the table build is `double`-valued until the
/// store).
#[inline]
fn gamma24_17(x: f64) -> f64 {
  if x <= 0.001867 {
    x * 17.0
  } else {
    1.044445 * (x.ln() / 2.4).exp() - 0.044445
  }
}

/// `igamma24_17` (`color.h:1223-1226`) — the inverse curve.
#[inline]
fn igamma24_17(x: f64) -> f64 {
  if x <= 0.031746 {
    x / 17.0
  } else {
    (((x + 0.044445) / 1.044445).ln() * 2.4).exp()
  }
}

/// `Color::gammatab_24_17a` — `gammatab_24_17a[i] = gamma24_17(i / 65535.0)`
/// (`color.cc:417`), built on first use and shared.
///
/// Rebuilt here rather than sampled at the exact curve because the LUT is a
/// *piecewise-linear* approximation of the curve and the kernel sees that
/// approximation; the two differ by up to ~2.5e-4 across the single interval
/// that straddles the curve's kink at `x = 0.001867`, and by ~1e-10 elsewhere.
fn gammatab_24_17a() -> &'static [f32] {
  static TAB: OnceLock<Vec<f32>> = OnceLock::new();
  TAB.get_or_init(|| (0..LUT_SIZE).map(|i| gamma24_17(i as f64 / 65535.0) as f32).collect())
}

/// `Color::igammatab_24_17` — `65535.0 * igamma24_17(i / 65535.0)`
/// (`color.cc:425`), so its values are in the `rawData` scale, not `[0, 1]`.
fn igammatab_24_17() -> &'static [f32] {
  static TAB: OnceLock<Vec<f32>> = OnceLock::new();
  TAB.get_or_init(|| (0..LUT_SIZE).map(|i| (65535.0 * igamma24_17(i as f64 / 65535.0)) as f32).collect())
}

/// Which tone curve the run uses — `applyGamma` (`:90-97`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Tone {
  /// `Color::gammatab_24_17a` / `Color::igammatab_24_17`.
  Gamma,
  /// `makeIdentity(65535.f)` forward, `makeIdentity()` back (`:146-147`,
  /// `:617`). Both tables are exactly linear, so the lookups are evaluated in
  /// closed form by [`identity_lut`] rather than materialised.
  Identity,
}

impl Tone {
  /// `(*gamtab)[rawData]` — the index is in `rawData` units.
  #[inline(always)]
  fn forward(self, index: f32) -> f32 {
    match self {
      Self::Gamma => lut(gammatab_24_17a(), index, true, true),
      // `LUT_CLIP_BELOW` only (`:146`).
      Self::Identity => identity_lut(index, 65535.0, true, false),
    }
  }

  /// `(*gamtab)[65535.f * x]` — the index is in `rawData` units again, so the
  /// caller multiplies the `[0, 1]` value by 65535 first.
  #[inline(always)]
  fn inverse(self, index: f32) -> f32 {
    match self {
      Self::Gamma => lut(igammatab_24_17(), index, false, false),
      // `makeIdentity()` — `LUT_CLIP_BELOW` is still set from the constructor.
      Self::Identity => identity_lut(index, 1.0, true, false),
    }
  }
}

/// `LUTf::operator[](float)` (`LUT.h:462-485`).
///
/// The index is in the LUT's own units, i.e. `0..=65535` for a 65536-entry
/// table — *not* `[0, 1]` (that is `getVal01`, which LMMSE does not use).
///
/// Three upstream details are load-bearing and easy to lose:
///
/// * `idx = (int)index` **truncates toward zero**, and the branches below can
///   reassign it afterwards; it is not a `floor`.
/// * `index > maxsf` is compared against `65534.0f`, so the last interpolable
///   interval is `[65534, 65535)` and everything above it either clips to
///   `data[65535]` (`LUT_CLIP_ABOVE`) or *extrapolates* from the last
///   interval.
/// * With no clip flag a negative index extrapolates below `data[0]` instead of
///   clamping, which is why `igammatab_24_17` — built with `clip == 0` — needs
///   the caller's `std::max(0.f, ...)`. `1/(1+x)`-style clamping here would
///   silently change saturated highlights.
///
/// `index` is not expected to be a NaN or to exceed `f32`'s `i32` range; C++'s
/// `(int)` conversion is undefined there and Rust saturates, so the two would
/// diverge on input this kernel cannot produce.
#[inline]
fn lut(tab: &[f32], index: f32, clip_below: bool, clip_above: bool) -> f32 {
  let mut idx = index as i32;
  if index < 0.0 {
    if clip_below {
      return tab[0];
    }
    idx = 0;
  } else if index > LUT_MAXSF {
    if clip_above {
      return tab[LUT_SIZE - 1];
    }
    idx = LUT_MAXS as i32;
  }
  let diff = index - idx as f32;
  let p1 = tab[idx as usize];
  let p2 = tab[idx as usize + 1] - p1;
  p1 + p2 * diff
}

/// The `makeIdentity` tables, evaluated without materialising 256 KB.
///
/// `makeIdentity(divisor)` stores `data[i] = i / divisor` and `makeIdentity()`
/// stores `data[i] = i` (`LUT.h:543-554`), both exactly linear, so
/// `operator[]`'s `p1 + (data[idx + 1] - p1) * diff` is reproduced by computing
/// `p1` and `data[idx + 1]` on the fly. This is bit-identical to the table-based
/// lookup, because the table's elements *are* `(float)i / divisor`.
#[inline]
fn identity_lut(index: f32, divisor: f32, clip_below: bool, clip_above: bool) -> f32 {
  let mut idx = index as i32;
  if index < 0.0 {
    if clip_below {
      return 0.0 / divisor;
    }
    idx = 0;
  } else if index > LUT_MAXSF {
    if clip_above {
      return 65535.0 / divisor;
    }
    idx = LUT_MAXS as i32;
  }
  let p1 = idx as f32 / divisor;
  let p2 = (idx as f32 + 1.0) / divisor - p1;
  p1 + p2 * (index - idx as f32)
}

/// The five padded working planes of `lmmse_interpolate_omp`.
///
/// Named after what upstream's `qix[0..5]` hold at the point of writing, which
/// changes twice during the run — the same array is reused as the mosaic, then
/// as the interpolated difference, then as a median-filtered difference.
struct Planes {
  /// `qix[0]` — horizontal `G - R(B)`, later the red plane.
  p0: Vec<f32>,
  /// `qix[1]` — vertical `G - R(B)`, later green.
  p1: Vec<f32>,
  /// `qix[2]` — horizontal low-pass of `p0`, later blue.
  p2: Vec<f32>,
  /// `qix[3]` — vertical low-pass of `p1`, later `median(R - G)`.
  p3: Vec<f32>,
  /// `qix[4]` — the tone-curve-mapped mosaic, later the interpolated
  /// `G - R(B)`, later `median(B - G)`.
  p4: Vec<f32>,
}

impl Planes {
  /// Allocate the five zeroed planes, or `None` if the allocation fails —
  /// upstream's "try to get 5 smaller ones" step (`:103-126`).
  ///
  /// Zeroed because upstream `calloc`s them, and the ring is read: step 4 and
  /// step 6 reach up to four rows and columns past the frame, into the ring.
  fn try_new(n: usize) -> Option<Self> {
    fn zeroed(n: usize) -> Option<Vec<f32>> {
      let mut v: Vec<f32> = Vec::new();
      v.try_reserve_exact(n).ok()?;
      v.resize(n, 0.0);
      Some(v)
    }
    Some(Self { p0: zeroed(n)?, p1: zeroed(n)?, p2: zeroed(n)?, p3: zeroed(n)?, p4: zeroed(n)? })
  }
}

/// The padded geometry, i.e. upstream's `ba`, `rr1`, `cc1`, `w1..w4`
/// (`:56-63`).
#[derive(Clone, Copy, Debug)]
struct Geom {
  width: usize,
  height: usize,
  /// `height + 2 * ba`.
  rr1: usize,
  /// `width + 2 * ba`, and also `w1` — the row stride of every plane.
  cc1: usize,
  w1: usize,
  w2: usize,
  w3: usize,
  w4: usize,
}

impl Geom {
  fn new(width: usize, height: usize) -> Self {
    let cc1 = width + 2 * BA;
    let rr1 = height + 2 * BA;
    Self { width, height, rr1, cc1, w1: cc1, w2: 2 * cc1, w3: 3 * cc1, w4: 4 * cc1 }
  }
}

/// `init + t[0] + t[1] + ... + t[8]`, left-associative.
///
/// Upstream writes its nine-term accumulations out longhand
/// (`mu = (p1 + p2 + ... + p9) / 9.f` and `vx = 1e-7f + SQR(p1 - mu) + ...`,
/// `:348-349`), and floating-point addition is not associative, so the port has
/// to fold in the same order rather than reach for `Iterator::sum` with a
/// different starting point.
#[inline(always)]
fn fold9(init: f32, t: [f32; 9]) -> f32 {
  let mut s = init;
  for x in t {
    s += x;
  }
  s
}

/// Step 1 (`:159-165`) — the tone-curve-mapped mosaic into `p4`.
///
/// Upstream's `#pragma omp for`.
fn load_mosaic(tone: Tone, mosaic: &Array2D<f32>, g: Geom, p4: &mut [f32]) {
  p4.par_chunks_mut(g.cc1).enumerate().for_each(|(rr, row)| {
    if rr < BA || rr >= g.rr1 - BA {
      return;
    }
    let src = mosaic.row(rr - BA);
    for cc in BA..(g.cc1 - BA) {
      row[cc] = tone.forward(src[cc - BA] * SCALE);
    }
  });
}

/// Step 2 (`:176-231`) — the two directional colour differences.
///
/// Writes `p0`/`p1` and reads only `p4`, so a row-shard is safe; upstream uses
/// `schedule(dynamic,16)` on it, which is a load-balancing choice this port does
/// not need to copy.
///
/// The two sub-loops walk the two site classes of the row (the first every
/// column that is *not* green, the second every column that is) with the same
/// stride-2 walk, so neither reads what the other writes.
fn differences(cfa: &CfaDesc, g: Geom, p4: &[f32], p0: &mut [f32], p1: &mut [f32]) {
  p0.par_chunks_mut(g.cc1).zip(p1.par_chunks_mut(g.cc1)).enumerate().for_each(|(rr, (r0, r1))| {
    if rr < 2 || rr >= g.rr1 - 2 {
      return;
    }

    // "G-R(B) at R(B) location" (`:182-210`).
    let mut cc = 2 + (cfa.fc(rr, 2) & 1) as usize;
    while cc < g.cc1 - 2 {
      let i = rr * g.cc1 + cc;
      // A diagonal average of the mapped mosaic, then the mean of the two
      // directional high passes as `Y`.
      let v0 = 0.0625 * (p4[i - g.w1 - 1] + p4[i - g.w1 + 1] + p4[i + g.w1 - 1] + p4[i + g.w1 + 1]) + 0.25 * p4[i];

      // Horizontal: a high pass of the row, i.e. `G - R(B)` along the row.
      let h = -0.25 * (p4[i - 2] + p4[i + 2]) + xdiv2f(p4[i - 1] + p4[i] + p4[i + 1]);
      let y = v0 + xdiv2f(h);
      r0[cc] = if p4[i] > 1.75 * y { median3(h, p4[i - 1], p4[i + 1]) } else { lim(h, 0.0, 1.0) } - p4[i];

      // Vertical: the same along the column.
      let v = -0.25 * (p4[i - g.w2] + p4[i + g.w2]) + xdiv2f(p4[i - g.w1] + p4[i] + p4[i + g.w1]);
      let y = v0 + xdiv2f(v);
      r1[cc] = if p4[i] > 1.75 * y { median3(v, p4[i - g.w1], p4[i + g.w1]) } else { lim(v, 0.0, 1.0) } - p4[i];

      cc += 2;
    }

    // "G-R(B) at G location" (`:212-221`). Note the sign flip: at a green site
    // the *other* colour is the interpolated one, so the difference is built
    // from the high pass of the greens and clamped into `[-1, 0]`.
    let mut cc = 2 + (cfa.fc(rr, 3) & 1) as usize;
    while cc < g.cc1 - 2 {
      let i = rr * g.cc1 + cc;
      let h = 0.25 * (p4[i - 2] + p4[i + 2]) - xdiv2f(p4[i - 1] + p4[i] + p4[i + 1]);
      let v = 0.25 * (p4[i - g.w2] + p4[i + g.w2]) - xdiv2f(p4[i - g.w1] + p4[i] + p4[i + g.w1]);
      r0[cc] = lim(h, -1.0, 0.0) + p4[i];
      r1[cc] = lim(v, -1.0, 0.0) + p4[i];
      cc += 2;
    }
  });
}

/// Step 3 (`:234-256`) — the Gaussian low pass of each difference plane.
///
/// Writes `p2`/`p3`, reads `p0`/`p1`; a row-shard is safe.
fn low_pass(g: Geom, h: [f32; 5], p0: &[f32], p1: &[f32], p2: &mut [f32], p3: &mut [f32]) {
  p2.par_chunks_mut(g.cc1).zip(p3.par_chunks_mut(g.cc1)).enumerate().for_each(|(rr, (r2, r3))| {
    if rr < 4 || rr >= g.rr1 - 4 {
      return;
    }
    for cc in 4..(g.cc1 - 4) {
      let i = rr * g.cc1 + cc;
      r2[cc] = h[0] * p0[i]
        + h[1] * (p0[i - 1] + p0[i + 1])
        + h[2] * (p0[i - 2] + p0[i + 2])
        + h[3] * (p0[i - 3] + p0[i + 3])
        + h[4] * (p0[i - 4] + p0[i + 4]);
      r3[cc] = h[0] * p1[i]
        + h[1] * (p1[i - g.w1] + p1[i + g.w1])
        + h[2] * (p1[i - g.w2] + p1[i + g.w2])
        + h[3] * (p1[i - g.w3] + p1[i + g.w3])
        + h[4] * (p1[i - g.w4] + p1[i + g.w4]);
    }
  });
}

/// Step 4 (`:258-399`, scalar branch) — the LMMSE blend into `p4`.
///
/// For each direction: `mu` and `vx` are the mean and variance of the
/// *low-pass* of the difference, `vn` the variance of the difference minus the
/// low pass (its own high pass), `xh = (raw * vx + lp * vn) / (vx + vn)` the
/// variance-weighted blend and `vh = vx * vn / (vx + vn)` the estimated noise
/// power. The two directions are then combined weightedly by `1 / vh` and
/// `1 / vv`.
///
/// Writes `p4`, reads `p0..p3`; a row-shard is safe.
fn blend(cfa: &CfaDesc, g: Geom, p0: &[f32], p1: &[f32], p2: &[f32], p3: &[f32], p4: &mut [f32]) {
  p4.par_chunks_mut(g.cc1).enumerate().for_each(|(rr, r4)| {
    if rr < 4 || rr >= g.rr1 - 4 {
      return;
    }
    // Note the column: `FC(rr, 4)`, not `FC(rr, 2)` — see the module note.
    let mut cc = 4 + (cfa.fc(rr, 4) & 1) as usize;
    while cc < g.cc1 - 4 {
      let i = rr * g.cc1 + cc;

      // Horizontal.
      let p = [p2[i - 4], p2[i - 3], p2[i - 2], p2[i - 1], p2[i], p2[i + 1], p2[i + 2], p2[i + 3], p2[i + 4]];
      let mu = fold9(0.0, p) / 9.0;
      let vx = fold9(1e-7, p.map(|x| sqr(x - mu)));
      let q = [p[0] - p0[i - 4], p[1] - p0[i - 3], p[2] - p0[i - 2], p[3] - p0[i - 1], p[4] - p0[i], p[5] - p0[i + 1], p[6] - p0[i + 2], p[7] - p0[i + 3], p[8] - p0[i + 4]];
      let vn = fold9(1e-7, q.map(sqr));
      let xh = (p0[i] * vx + p2[i] * vn) / (vx + vn);
      let vh = vx * vn / (vx + vn);

      // Vertical.
      let p = [p3[i - g.w4], p3[i - g.w3], p3[i - g.w2], p3[i - g.w1], p3[i], p3[i + g.w1], p3[i + g.w2], p3[i + g.w3], p3[i + g.w4]];
      let mu = fold9(0.0, p) / 9.0;
      let vx = fold9(1e-7, p.map(|x| sqr(x - mu)));
      let q = [
        p[0] - p1[i - g.w4],
        p[1] - p1[i - g.w3],
        p[2] - p1[i - g.w2],
        p[3] - p1[i - g.w1],
        p[4] - p1[i],
        p[5] - p1[i + g.w1],
        p[6] - p1[i + g.w2],
        p[7] - p1[i + g.w3],
        p[8] - p1[i + g.w4],
      ];
      let vn = fold9(1e-7, q.map(sqr));
      let xv = (p1[i] * vx + p3[i] * vn) / (vx + vn);
      let vv = vx * vn / (vx + vn);

      r4[cc] = (xh * vv + xv * vh) / (vh + vv);
      cc += 2;
    }
  });
}

/// Step 5 (`:401-423`) — re-read how the CFA is actually sampled.
///
/// This *overwrites* `p0..p2` at the sites they belong to and leaves the rest —
/// which is deliberate, because steps 6 and 7 then fill exactly the rest. At a
/// red/blue site it also rebuilds green as `CFA + (G - CFA)`, the interpolated
/// difference step 4 produced in `p4`.
///
/// Writes `p0..p2`, reads `p4` and the mosaic; a row-shard is safe. The padded
/// ring is written with zeros rather than left to `calloc`, exactly as upstream
/// does — the ring is inside the buffer, so it has to be cleared explicitly.
fn copy_cfa(tone: Tone, cfa: &CfaDesc, mosaic: &Array2D<f32>, g: Geom, p4: &[f32], p0: &mut [f32], p1: &mut [f32], p2: &mut [f32]) {
  p0
    .par_chunks_mut(g.cc1)
    .zip(p1.par_chunks_mut(g.cc1))
    .zip(p2.par_chunks_mut(g.cc1))
    .enumerate()
    .for_each(|(rr, ((r0, r1), r2))| {
      for cc in 0..g.cc1 {
        let i = rr * g.cc1 + cc;
        // Signed, because the ring is genuinely outside the image.
        let row = rr as isize - BA as isize;
        let col = cc as isize - BA as isize;
        let inside = row >= 0 && row < g.height as isize && col >= 0 && col < g.width as isize;
        let c = cfa.fc(rr, cc);
        let v = if inside {
          tone.forward(mosaic.at(row as usize, col as usize) * SCALE)
        } else {
          0.0
        };
        match c {
          0 => r0[cc] = v,
          1 => r1[cc] = v,
          _ => r2[cc] = v,
        }
        if c != 1 {
          r1[cc] = v + p4[i];
        }
      }
    });
}

/// Step 6 (`:434-449`) — red/blue at green sites.
///
/// **Serial, and the reason is Rust's borrow checker rather than the algorithm.**
/// The second statement reads `p[c]` one *row* away from where the first writes
/// it, so `p0`/`p2` would have to be borrowed mutably for the writes and
/// immutably for those reads at the same time.
///
/// The two are in fact disjoint: the loop walks the row's *green* columns,
/// writing `p0`/`p2` there, while its `±w1` reads land on the same column of the
/// neighbouring row, which — for any 2x2-periodic CFA — is a red/blue site, i.e.
/// a column this loop never writes. Upstream is therefore race-free here and its
/// `#pragma omp for` is honest. Expressing that in safe Rust would need the
/// compiler to reason about site classes, so this port runs the pass on one
/// thread and produces the single-threaded result. Two of the seven paint-heavy
/// phases pay this; the alternative was a spare plane pair (5 planes becomes 7,
/// on a kernel that already needs ~900 MB at 45 MP) or `unsafe`.
fn chroma_at_green_serial(cfa: &CfaDesc, g: Geom, p0: &mut [f32], p1: &[f32], p2: &mut [f32]) {
  for rr in 1..(g.rr1 - 1) {
    let mut cc = 1 + (cfa.fc(rr, 2) & 1) as usize;
    // `c` is initialised once and toggled twice per iteration, so it returns to
    // the same value each time round; upstream still writes the toggles out.
    let mut c = cfa.fc(rr, cc + 1);
    while cc < g.cc1 - 1 {
      let i = rr * g.cc1 + cc;
      let ch: &mut [f32] = if c == 0 { p0 } else { p2 };
      ch[cc] = p1[i] + xdiv2f(ch[i - 1] - p1[i - 1] + ch[i + 1] - p1[i + 1]);
      c = 2 - c;
      let ch: &mut [f32] = if c == 0 { p0 } else { p2 };
      ch[cc] = p1[i] + xdiv2f(ch[i - g.w1] - p1[i - g.w1] + ch[i + g.w1] - p1[i + g.w1]);
      c = 2 - c;
      cc += 2;
    }
  }
}

/// Step 7 (`:460-470`) — the *other* red/blue channel at red/blue sites, from
/// all four diagonal neighbours.
///
/// Serial for the same borrow-checker reason as [`chroma_at_green_serial`], and
/// again provably disjoint: this loop walks the row's red/blue columns while its
/// `±w1` reads land on green sites of the neighbouring row.
fn chroma_at_rb_serial(cfa: &CfaDesc, g: Geom, p0: &mut [f32], p1: &[f32], p2: &mut [f32]) {
  for rr in 1..(g.rr1 - 1) {
    let mut cc = 1 + (cfa.fc(rr, 1) & 1) as usize;
    // Constant across the walk, because `FC` is 2-periodic in the column index.
    let c = 2 - cfa.fc(rr, cc);
    while cc < g.cc1 - 1 {
      let i = rr * g.cc1 + cc;
      let ch: &mut [f32] = if c == 0 { p0 } else { p2 };
      ch[cc] = p1[i]
        + 0.25
          * (ch[i - g.w1] - p1[i - g.w1] + ch[i - 1] - p1[i - 1] + ch[i + 1] - p1[i + 1] + ch[i + g.w1] - p1[i + g.w1]);
      cc += 2;
    }
  }
}

/// Median pass, first half (`:492-538`) — the 3x3 median of `R - G` and of
/// `B - G`, into `p3` and `p4` respectively.
///
/// Writes `p3`/`p4`, reads `p0`/`p1`/`p2`: a row-shard is safe.
fn median_differences(g: Geom, p0: &[f32], p1: &[f32], p2: &[f32], p3: &mut [f32], p4: &mut [f32]) {
  p3.par_chunks_mut(g.cc1).zip(p4.par_chunks_mut(g.cc1)).enumerate().for_each(|(rr, (r3, r4))| {
    if rr < 1 || rr >= g.rr1 - 1 {
      return;
    }
    for c in [0u32, 2] {
      // `d = c + 3 - (c == 0 ? 0 : 1)`: channel 0 lands in `p3`, channel 2 in `p4`.
      let d = c + 3 - u32::from(c != 0);
      let rc: &[f32] = if c == 0 { p0 } else { p2 };
      let rd: &mut [f32] = if d == 3 { &mut *r3 } else { &mut *r4 };
      for cc in 1..(g.cc1 - 1) {
        let i = rr * g.cc1 + cc;
        rd[cc] = median9([
          rc[i - g.w1 - 1] - p1[i - g.w1 - 1],
          rc[i - g.w1] - p1[i - g.w1],
          rc[i - g.w1 + 1] - p1[i - g.w1 + 1],
          rc[i - 1] - p1[i - 1],
          rc[i] - p1[i],
          rc[i + 1] - p1[i + 1],
          rc[i + g.w1 - 1] - p1[i + g.w1 - 1],
          rc[i + g.w1] - p1[i + g.w1],
          rc[i + g.w1 + 1] - p1[i + g.w1 + 1],
        ]);
      }
    }
  });
}

/// The five planes of one row, with runtime channel selection — the port of
/// upstream's `float *rix[5]` pointer array for step 8's doubly-strided walk.
struct Row<'a> {
  q0: &'a mut [f32],
  q1: &'a mut [f32],
  q2: &'a mut [f32],
  q3: &'a [f32],
  q4: &'a [f32],
}

impl Row<'_> {
  #[inline(always)]
  fn at(&mut self, c: u32, col: usize) -> &mut f32 {
    match c {
      0 => &mut self.q0[col],
      1 => &mut self.q1[col],
      _ => &mut self.q2[col],
    }
  }

  #[inline(always)]
  fn read(&self, c: u32, col: usize) -> f32 {
    match c {
      0 => self.q0[col],
      1 => self.q1[col],
      2 => self.q2[col],
      3 => self.q3[col],
      _ => self.q4[col],
    }
  }
}

/// Median pass, second half (`:540-608`) — rebuild red and blue from the
/// medians, and green as the average of the two reconstructions.
///
/// This walks each row **twice per iteration** (offsets `cc` and `cc + 1`) with
/// the pointers advancing between the halves, which is what the `rix[k]++`
/// sequences in upstream mean. Rows are independent of each other, so the shard
/// is still over rows; the *within-row* order is strictly sequential and is the
/// point of the phase.
fn rebuild_from_medians(cfa: &CfaDesc, g: Geom, p0: &mut [f32], p1: &mut [f32], p2: &mut [f32], p3: &[f32], p4: &[f32]) {
  p0
    .par_chunks_mut(g.cc1)
    .zip(p1.par_chunks_mut(g.cc1))
    .zip(p2.par_chunks_mut(g.cc1))
    .enumerate()
    .for_each(|(rr, ((q0, q1), q2))| {
      let c0 = cfa.fc(rr, 0);
      let mut row = Row { q0, q1, q2, q3, q4 };

      // `d` is `c + 3 - (c == 0 ? 0 : 1)` again: channel 0 -> plane 3, else 4.
      let d_of = |c: u32| c + 3 - u32::from(c != 0);
      // The two writes that open or close a pair of steps, in upstream's order.
      let put_rb = |row: &mut Row, col: usize| {
        let green = row.read(1, col);
        *row.at(0, col) = green + row.read(3, col);
        let green = row.read(1, col);
        *row.at(2, col) = green + row.read(4, col);
      };

      if c0 == 1 {
        // Green in the first column: `cc` even is a green site, `cc + 1` red/blue.
        let c1 = 2 - cfa.fc(rr, 1);
        let d = d_of(c1);
        let mut cc = 0usize;
        while cc < g.cc1 - 1 {
          put_rb(&mut row, cc);
          let green = row.read(1, cc + 1);
          *row.at(c1, cc + 1) = green + row.read(d, cc + 1);
          *row.at(1, cc + 1) = 0.5 * (row.read(0, cc + 1) - row.read(3, cc + 1) + row.read(2, cc + 1) - row.read(4, cc + 1));
          cc += 2;
        }
        if cc < g.cc1 {
          // Only reachable with an odd width.
          put_rb(&mut row, cc);
        }
      } else {
        // Red/blue in the first column: `cc` even is red/blue, `cc + 1` green.
        let c0 = 2 - c0;
        let d = d_of(c0);
        let mut cc = 0usize;
        while cc < g.cc1 - 1 {
          let green = row.read(1, cc);
          *row.at(c0, cc) = green + row.read(d, cc);
          *row.at(1, cc) = 0.5 * (row.read(0, cc) - row.read(3, cc) + row.read(2, cc) - row.read(4, cc));
          put_rb(&mut row, cc + 1);
          cc += 2;
        }
        if cc < g.cc1 {
          let green = row.read(1, cc);
          *row.at(c0, cc) = green + row.read(d, cc);
          *row.at(1, cc) = 0.5 * (row.read(0, cc) - row.read(3, cc) + row.read(2, cc) - row.read(4, cc));
        }
      }
    });
}

/// Step 8 (`:630-643`) — copy the channels out and undo the tone curve.
///
/// A pixel that *is* the CFA sample takes `CLIP(rawData)` on its own channel and
/// the reconstructed value on the other two; the `ii != c` test is the whole of
/// upstream's three-way branch.
fn write_out(tone: Tone, cfa: &CfaDesc, mosaic: &Array2D<f32>, g: Geom, out: &mut Rgb, p0: &[f32], p1: &[f32], p2: &[f32]) {
  let planes: [&[f32]; 3] = [p0, p1, p2];
  out
    .red
    .par_rows_mut()
    .zip(out.green.par_rows_mut())
    .zip(out.blue.par_rows_mut())
    .enumerate()
    .for_each(|(row, ((r, gr), b))| {
      let rr = row + BA;
      let src = mosaic.row(row);
      for col in 0..g.width {
        let cc = col + BA;
        let i = rr * g.cc1 + cc;
        let own = cfa.fc(row, col) as usize;
        let passthrough = clip(src[col] * SCALE) / SCALE;
        let dst = [r, gr, b];
        for (ii, d) in dst.into_iter().enumerate() {
          d[col] = if ii == own { passthrough } else { max0(tone.inverse(MAXVAL * planes[ii][i])) / SCALE };
        }
      }
    });
}

/// The three plane views `refinement` needs, with runtime channel selection.
struct RgbView<'a> {
  w: usize,
  planes: [&'a mut [f32]; 3],
}

impl RgbView<'_> {
  #[inline(always)]
  fn get(&self, c: u32, row: usize, col: usize) -> f32 {
    self.planes[c as usize][row * self.w + col]
  }

  #[inline(always)]
  fn set(&mut self, c: u32, row: usize, col: usize, v: f32) {
    self.planes[c as usize][row * self.w + col] = v;
  }

  /// One directional weight: `1.f / (1.f + fabsf(a) + fabsf(b))`.
  ///
  /// ⚠️ **`SCALE` is required here.** The `1.f` denominator is in `rawData`
  /// units, so the two differences have to be brought back to that scale before
  /// they are added to it. Dropping the scaling would make every weight collapse
  /// towards 1 and turn the whole directional blend into a plain average — the
  /// code would still compile, still look right, and be wrong.
  #[inline(always)]
  fn weight(&self, a: f32, b: f32) -> f32 {
    1.0 / (1.0 + a * SCALE + b * SCALE)
  }
}

/// `refinement` (`:666-825`, scalar branch) — `passref` passes that re-derive
/// the interpolated values from the finished image, by mutual gradient-weighted
/// correction along the four directions.
///
/// Serial. Each of the three loops reads the planes it writes one or two rows
/// away — `R1` reads `green` at `row ± 1` while writing `green` at `row`, and
/// `R2`/`R3` read the red/blue planes at `row ± 1` and `row ± 2` while writing
/// them at `row`. As in step 6/7 the sites are disjoint (each loop writes one
/// site class and reads the other) so upstream's `#pragma omp for` is
/// race-free, but safe Rust cannot see that, and unlike steps 6/7 this path
/// already costs nothing at the default settings: it runs only for
/// `iterations > 4`, and RawTherapee's default is `2`.
///
/// The `+ 0.5f` is half a 16-bit LSB in `rawData` units, so it is `0.5 / SCALE`
/// on this crate's planes.
fn refinement(cfa: &CfaDesc, g: Geom, out: &mut Rgb, passref: i32) {
  let w = g.width;
  let h = g.height;
  // Upstream's `w1`/`w2` are the *image* strides, not the padded ones; the
  // accesses below spell the row offsets out as `row ± 2` and `col ± 2` instead,
  // which is what `∓ w2` and `∓ 2` mean there.
  let half_lsb = 0.5 / SCALE;

  for _ in 0..passref {
    {
      let mut v = RgbView { w, planes: [out.red.as_mut_slice(), out.green.as_mut_slice(), out.blue.as_mut_slice()] };

      // "Reinforce interpolated green pixels on RED/BLUE pixel locations"
      // (:694-732). Writes green, reads the red/blue channel `c` and green.
      for row in 2..h.saturating_sub(2) {
        let mut col = 2 + (cfa.fc(row, 2) & 1) as usize;
        let c = cfa.fc(row, col);
        while col < w.saturating_sub(2) {
          let dl = v.weight(v.get(c, row, col - 2) - v.get(c, row, col), v.get(1, row, col + 1) - v.get(1, row, col - 1));
          let dr = v.weight(v.get(c, row, col + 2) - v.get(c, row, col), v.get(1, row, col + 1) - v.get(1, row, col - 1));
          let du = v.weight(v.get(c, row - 2, col) - v.get(c, row, col), v.get(1, row + 1, col) - v.get(1, row - 1, col));
          let dd = v.weight(v.get(c, row + 2, col) - v.get(c, row, col), v.get(1, row + 1, col) - v.get(1, row - 1, col));
          let num = (v.get(1, row, col - 1) - v.get(c, row, col - 1)) * dl
            + (v.get(1, row, col + 1) - v.get(c, row, col + 1)) * dr
            + (v.get(1, row - 1, col) - v.get(c, row - 1, col)) * du
            + (v.get(1, row + 1, col) - v.get(c, row + 1, col)) * dd;
          let v0 = v.get(c, row, col) + half_lsb + num / (dl + dr + du + dd);
          v.set(1, row, col, max0(v0));
          col += 2;
        }
      }
    }

    {
      let mut v = RgbView { w, planes: [out.red.as_mut_slice(), out.green.as_mut_slice(), out.blue.as_mut_slice()] };

      // "Reinforce interpolated red/blue pixels on GREEN pixel locations"
      // (:734-778). Writes both red/blue channels at a green site, `c` toggling
      // between them; reads green and the channel being written.
      for row in 2..h.saturating_sub(2) {
        let mut col = 2 + (cfa.fc(row, 3) & 1) as usize;
        let mut c = cfa.fc(row, col + 1);
        while col < w.saturating_sub(2) {
          for _ in 0..2 {
            let dl = v.weight(v.get(1, row, col - 2) - v.get(1, row, col), v.get(c, row, col + 1) - v.get(c, row, col - 1));
            let dr = v.weight(v.get(1, row, col + 2) - v.get(1, row, col), v.get(c, row, col + 1) - v.get(c, row, col - 1));
            let du = v.weight(v.get(1, row - 2, col) - v.get(1, row, col), v.get(c, row + 1, col) - v.get(c, row - 1, col));
            let dd = v.weight(v.get(1, row + 2, col) - v.get(1, row, col), v.get(c, row + 1, col) - v.get(c, row - 1, col));
            let num = (v.get(1, row, col - 1) - v.get(c, row, col - 1)) * dl
              + (v.get(1, row, col + 1) - v.get(c, row, col + 1)) * dr
              + (v.get(1, row - 1, col) - v.get(c, row - 1, col)) * du
              + (v.get(1, row + 1, col) - v.get(c, row + 1, col)) * dd;
            let v0 = v.get(1, row, col) + half_lsb - num / (dl + dr + du + dd);
            v.set(c, row, col, max0(v0));
            c = 2 - c;
          }
          col += 2;
        }
      }
    }

    {
      let mut v = RgbView { w, planes: [out.red.as_mut_slice(), out.green.as_mut_slice(), out.blue.as_mut_slice()] };

      // "Reinforce integrated red/blue pixels on BLUE/RED pixel locations"
      // (:780-822). Writes `c` at a red/blue site and scores the gradients
      // against the *other* red/blue channel `d`.
      for row in 2..h.saturating_sub(2) {
        let mut col = 2 + (cfa.fc(row, 2) & 1) as usize;
        let c = 2 - cfa.fc(row, col);
        let d = 2 - c;
        while col < w.saturating_sub(2) {
          let dl = v.weight(v.get(d, row, col - 2) - v.get(d, row, col), v.get(1, row, col + 1) - v.get(1, row, col - 1));
          let dr = v.weight(v.get(d, row, col + 2) - v.get(d, row, col), v.get(1, row, col + 1) - v.get(1, row, col - 1));
          let du = v.weight(v.get(d, row - 2, col) - v.get(d, row, col), v.get(1, row + 1, col) - v.get(1, row - 1, col));
          let dd = v.weight(v.get(d, row + 2, col) - v.get(d, row, col), v.get(1, row + 1, col) - v.get(1, row - 1, col));
          let num = (v.get(1, row, col - 1) - v.get(c, row, col - 1)) * dl
            + (v.get(1, row, col + 1) - v.get(c, row, col + 1)) * dr
            + (v.get(1, row - 1, col) - v.get(c, row - 1, col)) * du
            + (v.get(1, row + 1, col) - v.get(c, row + 1, col)) * dd;
          let v0 = v.get(1, row, col) + half_lsb - num / (dl + dr + du + dd);
          v.set(c, row, col, max0(v0));
          col += 2;
        }
      }
    }
  }
}

/// `:79-97` — `(iter, passref, tone)` for a requested `iterations`.
///
/// Split out of the kernel so it can be tested directly: it is the one purely
/// bookkeeping part of LMMSE, and the one an earlier revision of this port got
/// wrong (by missing the `7 | 8` branch, which makes `7` behave like `1` instead
/// of like `5`).
fn iteration_state(iterations: i32) -> (i32, i32, Tone) {
  let (mut iter, mut passref) = (0i32, 0i32);
  if iterations <= 4 {
    iter = iterations - 1;
    passref = 0;
  } else if iterations <= 6 {
    iter = 3;
    passref = iterations - 4;
  } else if iterations <= 8 {
    iter = 3;
    passref = iterations - 6;
  }
  let tone = if iterations == 0 {
    iter = 0;
    Tone::Identity
  } else {
    Tone::Gamma
  };
  (iter, passref, tone)
}

/// `RawImageSource::lmmse_interpolate_omp(winw, winh, rawData, red, green,
/// blue, iterations)` + its trailing `refinement`.
///
/// `iterations` is RawTherapee's `raw.bayersensor.lmmse_iterations` — the GUI
/// offers `0..=6`, default `2`.
///
/// # Errors
/// [`Error::UnsupportedCfa`] for a CFA this kernel cannot express,
/// [`Error::Shape`] for a degenerate mosaic. An allocation failure is *not* an
/// error: it degrades to [`super::igv::bayer_igv_demosaic`], as upstream does.
pub fn bayer_lmmse_demosaic(cfa: &CfaDesc, mosaic: &Array2D<f32>, iterations: i32) -> Result<Rgb, Error> {
  if cfa.has_fourth_colour() {
    // Upstream's four-colour guard (`:44-54`) "falls back to igv_interpolate",
    // but IGV indexes a three-element `float *rgb[3]` with `FC`, so it cannot
    // demosaic one either — see `bayer/igv.rs`. Refused rather than reproduced.
    return Err(Error::UnsupportedCfa("lmmse on a four-colour CFA"));
  }
  if !cfa.is_bayer {
    return Err(Error::UnsupportedCfa("lmmse on a non-Bayer CFA"));
  }

  let (width, height) = (mosaic.width(), mosaic.height());
  if width == 0 || height == 0 {
    return Err(Error::Shape(format!("lmmse needs a non-empty mosaic, got {width}x{height}")));
  }

  // `:79-97` — the iteration state machine, reproduced including the branch
  // that only a profile can reach.
  let (iter, passref, tone) = iteration_state(iterations);

  let g = Geom::new(width, height);
  let n = g
    .rr1
    .checked_mul(g.cc1)
    .ok_or_else(|| Error::Shape(format!("lmmse scratch size overflows: {width}x{height}")))?;

  // `:103-133` — five planes, or upstream's fallback to IGV.
  let Some(mut planes) = Planes::try_new(n) else {
    return super::igv::bayer_igv_demosaic(cfa, mosaic);
  };

  // `:64-75` — the Gaussian taps. Upstream computes them with `exp` returning
  // `double` and assigns to `float`, so the `f64` round trip is part of the
  // value; the summation and the division are then plain `f32`.
  let raw = [1.0f64, (-1.0f64 / 8.0).exp(), (-4.0f64 / 8.0).exp(), (-9.0f64 / 8.0).exp(), (-16.0f64 / 8.0).exp()];
  let taps = raw.map(|x| x as f32);
  let hs = taps[0] + 2.0 * (taps[1] + taps[2] + taps[3] + taps[4]);
  let h = taps.map(|x| x / hs);

  load_mosaic(tone, mosaic, g, &mut planes.p4);
  differences(cfa, g, &planes.p4, &mut planes.p0, &mut planes.p1);
  low_pass(g, h, &planes.p0, &planes.p1, &mut planes.p2, &mut planes.p3);
  blend(cfa, g, &planes.p0, &planes.p1, &planes.p2, &planes.p3, &mut planes.p4);
  copy_cfa(tone, cfa, mosaic, g, &planes.p4, &mut planes.p0, &mut planes.p1, &mut planes.p2);
  chroma_at_green_serial(cfa, g, &mut planes.p0, &planes.p1, &mut planes.p2);
  chroma_at_rb_serial(cfa, g, &mut planes.p0, &planes.p1, &mut planes.p2);

  for _ in 0..iter {
    median_differences(g, &planes.p0, &planes.p1, &planes.p2, &mut planes.p3, &mut planes.p4);
    rebuild_from_medians(cfa, g, &mut planes.p0, &mut planes.p1, &mut planes.p2, &planes.p3, &planes.p4);
  }

  let mut out = Rgb::new(width, height);
  write_out(tone, cfa, mosaic, g, &mut out, &planes.p0, &planes.p1, &planes.p2);

  // Upstream frees the scratch before refining (`:649-662`); at 45 MP that is
  // ~900 MB, so it is worth doing in the same order.
  drop(planes);

  if iterations > 4 {
    refinement(cfa, g, &mut out, passref);
  }

  Ok(out)
}

#[cfg(test)]
mod tests {
  use super::*;

  /// The four Bayer orderings, as 2x2 dcraw tiles.
  fn cfa(pattern: [[u8; 2]; 2]) -> CfaDesc {
    CfaDesc::bayer_from_2x2(pattern)
  }

  /// A mosaic whose every sample is `v`.
  fn flat(width: usize, height: usize, v: f32) -> Array2D<f32> {
    Array2D::filled(width, height, v)
  }

  /// `BA` is even, which is what lets the kernel evaluate `FC` in *padded*
  /// coordinates at some call sites and in image coordinates at others, and get
  /// the same answer. That is the property the mixed call sites rest on, so it
  /// is asserted rather than assumed — a CFA that is not 2x2-periodic would not
  /// have it, and no single-kernel test would show the difference.
  #[test]
  fn padded_lookup_of_a_bayer_is_the_image_lookup() {
    for pattern in [[[0, 1], [1, 2]], [[2, 1], [1, 0]], [[1, 0], [2, 1]], [[1, 2], [0, 1]]] {
      let c = cfa(pattern);
      for rr in 0..24usize {
        for cc in 0..24usize {
          assert_eq!(c.fc(rr, cc), c.fc(rr + BA, cc + BA), "{pattern:?} at ({rr},{cc})");
        }
      }
    }
  }

  /// The `iterations` state machine, case by case.
  ///
  /// The `7`/`8` rows are the point: they are unreachable from the GUI (whose
  /// range is `0..=6`) and an earlier revision of this port missed upstream's
  /// third branch, making `7` behave like `1` rather than like `5`.
  #[test]
  fn iteration_table_matches_upstream() {
    // (iterations, iter, passref, gamma)
    let cases = [
      (0, 0, 0, false),
      (1, 0, 0, true),
      (2, 1, 0, true),
      (3, 2, 0, true),
      (4, 3, 0, true),
      (5, 3, 1, true),
      (6, 3, 2, true),
      (7, 3, 1, true),
      (8, 3, 2, true),
      (9, 0, 0, true),
      (-1, -2, 0, true),
    ];
    for (it, iter, passref, gamma) in cases {
      let (i, p, t) = iteration_state(it);
      assert_eq!((i, p), (iter, passref), "iterations = {it}");
      let want = if gamma { Tone::Gamma } else { Tone::Identity };
      assert_eq!(t, want, "tone for iterations = {it}");
    }
  }

  /// A flat field must come back flat, with the CFA sample itself *exact*.
  ///
  /// The exact half pins the scale convention: the pass-through branch is
  /// `CLIP(rawData) / SCALE`, and for a below-saturation sample that is `v`
  /// exactly, whereas a port that fed the kernels 0..65535 instead of 0..65536
  /// would return `v * 65535 / 65536`.
  ///
  /// The reconstructed channels are checked with a tolerance rather than for
  /// equality. In exact arithmetic every difference cancels and they too return
  /// `v`, which is how the reference model behaved — but that model ran in
  /// `f64`, where `m + m + m` is exact for an `f32` `m` (26 significant bits)
  /// while in `f32` it rounds, and a single ulp there is enough to survive the
  /// cancellation. Asserting equality would be asserting something about the
  /// rounding of `3 * m`, not about the kernel.
  #[test]
  fn flat_field_comes_back_flat_and_the_cfa_sample_is_exact() {
    let v = 0.5f32;
    let (w, h) = (48usize, 48usize);
    let pattern = [[0, 1], [1, 2]];
    let c = cfa(pattern);

    for iterations in [0, 2] {
      let out = bayer_lmmse_demosaic(&c, &flat(w, h, v), iterations).expect("lmmse");
      // The zero-padded ring contaminates the frame's outer pixels and upstream
      // never repairs them, so the invariant is asserted on the interior only.
      // `BA` is the structural bound: nothing reads further than 4 rows or
      // columns from a pixel that was written.
      for row in BA..(h - BA) {
        for col in BA..(w - BA) {
          let own = c.fc(row, col) as usize;
          for (ii, plane) in [&out.red, &out.green, &out.blue].into_iter().enumerate() {
            let got = plane.at(row, col);
            if ii == own {
              assert_eq!(got, v, "iterations={iterations} passthrough at ({row},{col})");
            } else {
              assert!((got - v).abs() < 1e-6, "iterations={iterations} channel {ii} at ({row},{col}): {got}");
            }
          }
        }
      }
    }
  }

  /// The documented scale quirk, pinned exactly: a **saturated** sample comes
  /// back as `65535 / 65536`, not as `1.0`, because the write-out's pass-through
  /// branch is a clamp to `[0, 65535]` in a scale whose unity is `65536`.
  #[test]
  fn a_saturated_sample_is_65535_over_65536() {
    let (w, h) = (48usize, 48usize);
    let c = cfa([[0, 1], [1, 2]]);
    let out = bayer_lmmse_demosaic(&c, &flat(w, h, 1.0), 2).expect("lmmse");
    let expected = 65535.0f32 / 65536.0;
    assert_ne!(expected, 1.0, "the test is only meaningful if the two differ");

    for row in BA..(h - BA) {
      for col in BA..(w - BA) {
        let own = c.fc(row, col) as usize;
        for (ii, plane) in [&out.red, &out.green, &out.blue].into_iter().enumerate() {
          if ii == own {
            assert_eq!(plane.at(row, col), expected, "passthrough at ({row},{col})");
          }
        }
      }
    }
  }

  /// Black is a fixed point, in every channel and every iteration setting.
  #[test]
  fn black_stays_black() {
    let (w, h) = (32usize, 32usize);
    let c = cfa([[0, 1], [1, 2]]);
    for iterations in 0..=6 {
      let out = bayer_lmmse_demosaic(&c, &flat(w, h, 0.0), iterations).expect("lmmse");
      for (name, plane) in [("r", &out.red), ("g", &out.green), ("b", &out.blue)] {
        assert!(plane.as_slice().iter().all(|&x| x == 0.0), "iterations={iterations} {name} not black");
      }
    }
  }

  /// All four Bayer orderings recover the same flat field, and each returns the
  /// sample on its own channel — so a channel/site mix-up in the write-out's
  /// `ii != own` test would show up here.
  #[test]
  fn every_bayer_ordering_agrees_on_a_flat_field() {
    let v = 0.25f32;
    let (w, h) = (40usize, 40usize);
    for pattern in [[[0, 1], [1, 2]], [[2, 1], [1, 0]], [[1, 0], [2, 1]], [[1, 2], [0, 1]]] {
      let out = bayer_lmmse_demosaic(&cfa(pattern), &flat(w, h, v), 2).expect("lmmse");
      for row in BA..(h - BA) {
        for col in BA..(w - BA) {
          for (ii, plane) in [&out.red, &out.green, &out.blue].into_iter().enumerate() {
            let got = plane.at(row, col);
            assert!((got - v).abs() < 1e-6, "{pattern:?} channel {ii} at ({row},{col}): {got}");
          }
        }
      }
    }
  }

  /// The frame border really is left contaminated — this is a *behaviour* pin,
  /// not a wish.
  ///
  /// RawTherapee calls `border_interpolate` from every other Bayer kernel but
  /// not from LMMSE, so the outer pixels are computed from the zero ring and
  /// stay that way. If someone later "fixes" the border, this test has to be
  /// deleted deliberately, with the upstream evidence in hand — which is the
  /// point.
  #[test]
  fn the_frame_border_is_left_as_the_zero_ring_made_it() {
    let v = 0.5f32;
    let (w, h) = (48usize, 48usize);
    let out = bayer_lmmse_demosaic(&cfa([[0, 1], [1, 2]]), &flat(w, h, v), 0).expect("lmmse");
    let corner = out.green.at(0, 0);
    assert!((corner - v).abs() > 1e-4, "expected a contaminated corner, got {corner}");
    // ...and the interior is *not* contaminated, so the two together say
    // "bounded ring" rather than "the whole image is wrong".
    assert!((out.green.at(h / 2, w / 2) - v).abs() < 1e-6);
  }

  /// `iterations` values that should be indistinguishable, through the public
  /// entry point rather than through the table.
  #[test]
  fn equivalent_iteration_counts_produce_identical_output() {
    let (w, h) = (32usize, 32usize);
    let c = cfa([[0, 1], [1, 2]]);
    let mut mosaic = Array2D::new(w, h);
    for row in 0..h {
      for col in 0..w {
        // Hostile but deterministic: sharp steps and a saturated patch.
        let v = if (row / 3 + col / 5) % 2 == 0 { 0.05 } else { 0.9 };
        mosaic.set(row, col, if row == 1 && col == 1 { 1.0 } else { v });
      }
    }

    let run = |it| bayer_lmmse_demosaic(&c, &mosaic, it).expect("lmmse");
    assert_eq!(run(7), run(5), "iterations 7 must behave like 5");
    assert_eq!(run(8), run(6), "iterations 8 must behave like 6");
    assert_eq!(run(9), run(1), "iterations above 8 must behave like 1");
    assert_ne!(run(0), run(1), "the identity tone curve must differ from the gamma one");
  }

  /// Nothing here may produce a NaN or a negative value, and the output is
  /// deliberately *not* clamped above `1` — see the module note.
  #[test]
  fn output_stays_finite_and_non_negative() {
    let (w, h) = (40usize, 40usize);
    let c = cfa([[0, 1], [1, 2]]);
    let mut mosaic = Array2D::new(w, h);
    for row in 0..h {
      for col in 0..w {
        let v = ((row * 7 + col * 13) % 97) as f32 / 96.0;
        mosaic.set(row, col, v);
      }
    }

    for iterations in 0..=6 {
      let out = bayer_lmmse_demosaic(&c, &mosaic, iterations).expect("lmmse");
      for (name, plane) in [("r", &out.red), ("g", &out.green), ("b", &out.blue)] {
        for (i, &x) in plane.as_slice().iter().enumerate() {
          assert!(x.is_finite(), "iterations={iterations} {name}[{i}] is {x}");
          assert!(x >= 0.0, "iterations={iterations} {name}[{i}] is {x}");
        }
      }
    }
  }

  /// A four-colour CFA is refused rather than handed to IGV, because upstream's
  /// fallback cannot demosaic one either (`bayer/igv.rs`).
  #[test]
  fn a_four_colour_cfa_is_rejected() {
    let mut four = cfa([[0, 1], [1, 2]]);
    four.colors = 4;
    let err = bayer_lmmse_demosaic(&four, &flat(32, 32, 0.3), 2).unwrap_err();
    assert!(matches!(err, Error::UnsupportedCfa(_)), "{err:?}");
  }

  /// ...and so is an X-Trans CFA.
  #[test]
  fn a_non_bayer_cfa_is_rejected() {
    let xt = CfaDesc::xtrans_from_6x6([[1; 6]; 6]);
    let err = bayer_lmmse_demosaic(&xt, &flat(32, 32, 0.3), 2).unwrap_err();
    assert!(matches!(err, Error::UnsupportedCfa(_)), "{err:?}");
  }
}
