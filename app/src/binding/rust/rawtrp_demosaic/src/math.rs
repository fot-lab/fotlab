//! Scalar helpers reproducing the parts of `rt_math.h` the kernels use.
//!
//! Ported from `external/RawTherapee/rtengine/rt_math.h`
//! (GPL-3.0). Only the pieces the demosaic kernels call are reproduced, with
//! identical semantics — `intp` is the hot one and must stay bit-compatible
//! (`a * (b - c) + c`, evaluated in that order).

/// Full-scale value of the 16-bit working range, as in RT (`MAXVAL = 0xffff`).
pub const MAXVAL: f32 = 65535.0;

/// `intp(a, b, c) = a * b + (1 - a) * c`, written so it is exact for `a ∈ {0, 1}`
/// and linear in `b`, `c` (see the upstream comment).
#[inline(always)]
#[must_use]
pub const fn intp(a: f32, b: f32, c: f32) -> f32 {
  a * (b - c) + c
}

/// `SGN(a)` — `-1`, `0` or `+1`.
#[inline(always)]
#[must_use]
pub const fn sgn(a: f32) -> f32 {
  if a > 0.0 {
    1.0
  } else if a < 0.0 {
    -1.0
  } else {
    0.0
  }
}

/// `min` folded over a slice (upstream's variadic `rtengine::min`).
#[inline]
#[must_use]
pub fn min_n(v: &[f32]) -> f32 {
  let mut m = v[0];
  for &x in &v[1..] {
    if x < m {
      m = x;
    }
  }
  m
}

/// `max` folded over a slice (upstream's variadic `rtengine::max`).
#[inline]
#[must_use]
pub fn max_n(v: &[f32]) -> f32 {
  let mut m = v[0];
  for &x in &v[1..] {
    if x > m {
      m = x;
    }
  }
  m
}

/// `std::min(a, b)` as libstdc++ computes it: `b < a ? b : a`.
///
/// Same asymmetry as [`max2`], mirrored: a NaN **second** argument makes
/// `b < a` false, so the *first* argument wins. Upstream's `rtengine::min`
/// (`rt_math.h:60-63`) is the same expression, so this is the exact `min` the
/// kernels' `LIM` is built from.
#[inline(always)]
#[must_use]
pub const fn min2(a: f32, b: f32) -> f32 {
  if b < a {
    b
  } else {
    a
  }
}

/// `LIM(val, low, high)` — upstream's `max(low, min(val, high))`
/// (`rt_math.h:90-93`), composed from [`min2`]/[`max2`] rather than written as a
/// range test.
///
/// The difference is only visible for a NaN `val`, and it is worth pinning:
/// `min2(NaN, high)` keeps the NaN (`high < NaN` is false), and then
/// `max2(low, NaN)` falls back to **`low`** (`low < NaN` is false). A plain
/// `if val < low { low } else if val > high { high } else { val }` would instead
/// pass the NaN straight through — a different image wherever a kernel clamps a
/// statistic that a `0 * (1 / 0)` upstream (`-ffast-math` is off) can make NaN.
#[inline(always)]
#[must_use]
pub const fn lim(val: f32, low: f32, high: f32) -> f32 {
  max2(low, min2(val, high))
}

/// `LIM01(a)` — clamp to `[0, 1]`.
#[inline(always)]
#[must_use]
pub const fn lim01(a: f32) -> f32 {
  lim(a, 0.0, 1.0)
}

/// `CLIP(a)` — clamp to `[0, 65535]`.
#[inline(always)]
#[must_use]
pub const fn clip(a: f32) -> f32 {
  lim(a, 0.0, MAXVAL)
}

/// `std::max(0.f, x)` — the non-negativity clamp the kernels apply to every
/// difference-based estimate.
///
/// Spelled out rather than using `f32::max` so the NaN case is explicit and
/// pinned by a test: libstdc++'s `std::max(0.f, NaN)` is
/// `0.f < NaN ? NaN : 0.f`, i.e. **`0.0`** — and a NaN can legitimately arrive
/// here (e.g. `vng4`'s neighbour average divides by `num`, which may be 0).
/// Using `f32::max` would happen to agree, but relying on that coincidence in a
/// numerics kernel would be careless.
#[inline(always)]
#[must_use]
pub const fn max0(x: f32) -> f32 {
  if x > 0.0 {
    x
  } else {
    0.0
  }
}

/// `std::max(a, b)` as libstdc++ computes it: `a < b ? b : a`.
///
/// Spelled out for the same reason as [`max0`]: the `a < b` test is false for a
/// NaN `b`, so a NaN **second** argument yields `a` instead of propagating. The
/// kernels lean on that when they floor a statistic — `rcd`'s
/// `std::max(epssq, ...)` must yield `epssq` rather than `NaN` if a colour
/// difference high pass ever evaluates to `NaN`.
#[inline(always)]
#[must_use]
pub const fn max2(a: f32, b: f32) -> f32 {
  if a < b {
    b
  } else {
    a
  }
}

/// `std::abs` for a bare `f32` (upstream calls `std::fabs`).
#[inline(always)]
#[must_use]
pub const fn abs(a: f32) -> f32 {
  if a < 0.0 {
    -a
  } else {
    a
  }
}

/// `SQR(x)` — upstream's `constexpr T SQR(T x) { return x * x; }`
/// (`rt_math.h:36-39`), a **function** and not a macro, so an argument
/// expression is evaluated once as a whole: `SQR(a + b + c)` is
/// `(a + b + c) * (a + b + c)`, *not* `a + b + c*a + b + c`.
#[inline(always)]
#[must_use]
pub const fn sqr(x: f32) -> f32 {
  x * x
}

/// `xdiv2f(d)` (`sleef.h:1278-1288`) — halve a `float` by decrementing its
/// exponent field.
///
/// Transcribed as the bit trick it is rather than as `d * 0.5`, because the two
/// disagree on the inputs the trick was never meant for. The guard is
/// `intval & 0x7FFFFFFF` on the *bit pattern*, so: an infinity becomes
/// `0x7F000000` ≈ 1.7e38 instead of staying infinite, a NaN loses an exponent
/// and comes back **finite**, and a denormal corrupts. Only zero is
/// special-cased — and the guard is skipped for `+0.0` and `-0.0` alike, so the
/// function is sign-preserving on zero (which `d * 0.5` also is, but for a
/// different reason).
///
/// No caller in this crate reaches the divergent cases — `lmmse` passes sums of
/// three `[0, 1]`-domain values — but the function is short and the whole point
/// of it is the bit manipulation, so it is copied rather than paraphrased.
///
/// The subtraction wraps in two's complement. `intval -= 1 << 23` is signed
/// overflow, hence UB in the standard, but a plain wrap on every compiler
/// RawTherapee ships with; Rust makes the wrap explicit.
#[inline(always)]
#[must_use]
pub fn xdiv2f(d: f32) -> f32 {
  let bits = d.to_bits() as i32;
  if bits & 0x7FFF_FFFF != 0 {
    f32::from_bits(bits.wrapping_sub(1 << 23) as u32)
  } else {
    d
  }
}

/// `xmul2f(d)` (`sleef.h:1266-1276`) — `xdiv2f`'s inverse: double a `float` by
/// *incrementing* its exponent field.
///
/// Same reasoning as [`xdiv2f`]. AMAZE is why the crate needs it: the kernel
/// compares `xmul2f(v)` against the raw sample in several "is the interpolation
/// out of gamut" tests (`amaze_demosaic_RT.cc:1194`, `:1203`, `:1361`…), so both
/// directions of the pair have to behave the way upstream's do.
#[inline(always)]
#[must_use]
pub fn xmul2f(d: f32) -> f32 {
  let bits = d.to_bits() as i32;
  if bits & 0x7FFF_FFFF != 0 {
    f32::from_bits(bits.wrapping_add(1 << 23) as u32)
  } else {
    d
  }
}

/// `xdivf(d, n)` (`sleef.h:1290-1300`) — divide by `2^n` by subtracting `n` from
/// the exponent field.
///
/// `xdiv2f(d)` is `xdivf(d, 1)`; the guard and the wrapping subtraction behave
/// exactly as documented there. AMAZE calls it as `xdivf(a + b + c + d, 2)` —
/// i.e. a *quarter*, not a half — to average four colour-difference weights
/// (`amaze_demosaic_RT.cc:976`, `:1244`).
#[inline(always)]
#[must_use]
pub fn xdivf(d: f32, n: i32) -> f32 {
  let bits = d.to_bits() as i32;
  if bits & 0x7FFF_FFFF != 0 {
    f32::from_bits(bits.wrapping_sub(n << 23) as u32)
  } else {
    d
  }
}

/// `rtengine::median(a, b, c)` — the three-argument overload.
///
/// The variadic wrapper (`median.h:6240-6244`) forwards to
/// `median(std::array<T, 3>{a, b, c})`. Two templates are then viable — the
/// generic `median(std::array<T, N>)` (`median.h:41-51`, `nth_element`-based) and
/// the dedicated `median(std::array<T, 3>)` (`median.h:53-57`) — and partial
/// ordering picks the **second**, because `std::array<T, 3>` is more specialized
/// than `std::array<T, N>`. So the three-argument call is this network:
///
/// ```text
/// max(min(a, b), min(c, max(a, b)))
/// ```
///
/// ⚠️ This was ported wrong once: an earlier revision reproduced the *generic*
/// overload's libstdc++ `nth_element`-shortcut-to-insertion-sort path, on the
/// reading that the wrapper "calls the array overload". It does — the specialized
/// one. The two agree for finite inputs (both yield the middle value) and differ
/// as soon as a NaN is involved, which is why nothing caught it: this is exactly
/// the "same answer on the inputs you happened to test" failure mode.
///
/// Every comparison goes through [`min2`]/[`max2`] rather than
/// `f32::min`/`f32::max` so a NaN moves exactly as it does under
/// `std::min`/`std::max` (a NaN *first* operand survives `min`, a NaN *second*
/// operand survives `max`); see the tests for the three NaN cases, which pin the
/// asymmetry.
#[inline]
#[must_use]
pub fn median3(a: f32, b: f32, c: f32) -> f32 {
  max2(min2(a, b), min2(c, max2(a, b)))
}

/// `rtengine::median` for nine samples — the 9-element selection network of
/// `median.h:174-215`.
///
/// Transcribed comparison for comparison, and deliberately **not** replaced by a
/// sort or a tidier network. Upstream's header credits
/// <http://ndevilla.free.fr/median/median.pdf> via Flössie and Ingo Weyrich, and
/// the sequence of `min`/`max` pairs *is* the algorithm — reordering them is not
/// a refactor. Every comparison goes through [`min2`]/[`max2`] rather than
/// `f32::min`/`f32::max` so that a NaN propagates exactly as upstream's
/// `std::min`/`std::max` make it (a NaN **second** operand is swallowed, a NaN
/// first is kept) — see [`min2`] for why that asymmetry is load-bearing here.
///
/// The result for nine finite samples is the usual middle value; the network is
/// only guaranteed to *place* the median, which is all the kernel needs.
#[inline]
#[must_use]
pub fn median9(a: [f32; 9]) -> f32 {
  let mut v = a;
  let mut tmp;

  tmp = min2(v[1], v[2]);
  v[2] = max2(v[1], v[2]);
  v[1] = tmp;
  tmp = min2(v[4], v[5]);
  v[5] = max2(v[4], v[5]);
  v[4] = tmp;
  tmp = min2(v[7], v[8]);
  v[8] = max2(v[7], v[8]);
  v[7] = tmp;
  tmp = min2(v[0], v[1]);
  v[1] = max2(v[0], v[1]);
  v[0] = tmp;
  tmp = min2(v[3], v[4]);
  v[4] = max2(v[3], v[4]);
  v[3] = tmp;
  tmp = min2(v[6], v[7]);
  v[7] = max2(v[6], v[7]);
  v[6] = tmp;
  tmp = min2(v[1], v[2]);
  v[2] = max2(v[1], v[2]);
  v[1] = tmp;
  tmp = min2(v[4], v[5]);
  v[5] = max2(v[4], v[5]);
  v[4] = tmp;
  tmp = min2(v[7], v[8]);
  v[8] = max2(v[7], v[8]);
  v[7] = tmp;
  v[3] = max2(v[0], v[3]);
  v[5] = min2(v[5], v[8]);
  v[7] = max2(v[4], tmp);
  tmp = min2(v[4], tmp);
  v[6] = max2(v[3], v[6]);
  v[4] = max2(v[1], tmp);
  v[2] = min2(v[2], v[5]);
  v[4] = min2(v[4], v[7]);
  tmp = min2(v[4], v[2]);
  v[2] = max2(v[4], v[2]);
  v[4] = max2(v[6], tmp);
  min2(v[4], v[2])
}

#[cfg(test)]
mod tests {
  use super::*;

  /// `lim` must be the *composition* upstream writes, not an equivalent range
  /// test: a NaN `val` collapses to `low`, because `min2(NaN, high)` keeps the
  /// NaN and `max2(low, NaN)` then returns `low`.
  #[test]
  fn lim_collapses_a_nan_to_the_low_bound() {
    assert_eq!(lim(-1.0, 0.0, 1.0), 0.0);
    assert_eq!(lim(2.0, 0.0, 1.0), 1.0);
    assert_eq!(lim(0.5, 0.0, 1.0), 0.5);
    assert_eq!(lim(0.0, 0.0, 1.0), 0.0);
    assert_eq!(lim(f32::NAN, 0.0, 1.0), 0.0, "upstream's composition swallows the NaN");
    assert_eq!(lim01(f32::NAN), 0.0);
  }

  /// `min2` mirrors `max2`'s asymmetry: a NaN **second** argument loses.
  #[test]
  fn min2_keeps_the_first_operand_for_a_nan_second() {
    assert_eq!(min2(1.0, 2.0), 1.0);
    assert_eq!(min2(2.0, 1.0), 1.0);
    assert_eq!(min2(1.0, f32::NAN), 1.0);
    assert!(min2(f32::NAN, 1.0).is_nan());
  }

  /// `SQR` is a function, so it squares the argument *expression*, not the last
  /// term of it.
  #[test]
  fn sqr_squares_the_whole_argument() {
    assert_eq!(sqr(3.0), 9.0);
    assert_eq!(sqr(1.0 + 2.0 + 3.0), 36.0, "SQR(a + b + c) is (a + b + c)^2");
  }

  /// `xdiv2f` halves for the values the kernels pass it, and by a *bit trick*
  /// for the rest: the infinities, the NaNs and the denormals do not behave like
  /// `* 0.5`, which is exactly why it is transcribed rather than simplified.
  #[test]
  fn xdiv2f_decrements_the_exponent() {
    assert_eq!(xdiv2f(0.0), 0.0);
    assert_eq!(xdiv2f(-0.0), -0.0, "the guard is skipped for both zeros");
    assert_eq!(xdiv2f(1.0), 0.5);
    assert_eq!(xdiv2f(-3.0), -1.5);
    assert_eq!(xdiv2f(1e-30), 5e-31);

    // Not `inf / 2 == inf`: the exponent field is decremented whatever it was.
    assert_eq!(xdiv2f(f32::INFINITY), f32::from_bits(0x7F00_0000));
    assert_eq!(xdiv2f(f32::NEG_INFINITY), f32::from_bits(0xFF00_0000));
    assert!(xdiv2f(f32::NAN).is_finite(), "a NaN loses an exponent and comes back finite");
  }

  #[test]
  fn median3_returns_the_middle_value() {
    for (a, b, c) in [(3.0, 1.0, 2.0), (1.0, 2.0, 3.0), (3.0, 2.0, 1.0), (2.0, 2.0, 5.0), (-1.0, -9.0, 4.0)] {
      let mut sorted = [a, b, c];
      sorted.sort_by(f32::total_cmp);
      assert_eq!(median3(a, b, c), sorted[1], "median3({a}, {b}, {c})");
    }
  }

  /// Upstream's three-argument `median` is the `std::array<T, 3>` network
  /// `max(min(a, b), min(c, max(a, b)))`, so its behaviour for a NaN argument is
  /// whatever `std::min`/`std::max`'s single `<` test says — **not** a designed
  /// rule. These three cases pin the asymmetry: a NaN *first* argument survives
  /// `min`, so it reaches the outer `max` as its first operand and is returned; a
  /// NaN *second* argument loses every comparison it takes part in, so the answer
  /// is the finite middle value.
  ///
  /// They also pin the correction: an earlier revision modelled the *generic*
  /// `median(std::array<T, N>)` overload instead (insertion sort over three
  /// elements), which returns `2.0`, `3.0` and `NaN` respectively for these same
  /// three calls. Both implementations pass the finite test above, which is why
  /// they are spelled out separately here.
  #[test]
  fn median3_reproduces_upstreams_nan_behaviour() {
    assert!(median3(f32::NAN, 2.0, 3.0).is_nan(), "a NaN first operand survives min and reaches max");
    assert_eq!(median3(3.0, 2.0, f32::NAN), 2.0);
    assert_eq!(median3(2.0, f32::NAN, 3.0), 2.0);
  }

  /// The nine-sample network must return the middle value for finite input —
  /// and, being a selection network rather than a sort, the two infinities that
  /// an unclamped colour difference can produce must still land on the same
  /// answer a full sort would give.
  #[test]
  fn median9_is_the_middle_of_nine() {
    let cases: [[f32; 9]; 5] = [
      [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0],
      [9.0, 8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0],
      [5.0, 1.0, 9.0, 3.0, 7.0, 2.0, 8.0, 4.0, 6.0],
      [0.0; 9],
      [f32::NEG_INFINITY, -1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, f32::INFINITY],
    ];
    for a in cases {
      let mut sorted = a;
      sorted.sort_by(f32::total_cmp);
      assert_eq!(median9(a), sorted[4], "median9({a:?})");
    }
  }

  /// Repeated values are the case a naive network gets wrong: the network must
  /// still agree with the sort when the median is one of many duplicates.
  #[test]
  fn median9_handles_duplicates() {
    let a = [2.0, 2.0, 2.0, 2.0, 2.0, 7.0, 9.0, 1.0, 3.0];
    let mut sorted = a;
    sorted.sort_by(f32::total_cmp);
    assert_eq!(median9(a), sorted[4]);
  }

  #[test]
  fn intp_is_exact_at_edges() {
    assert_eq!(intp(0.0, 3.0, 5.0), 5.0);
    assert_eq!(intp(1.0, 3.0, 5.0), 3.0);
    assert_eq!(intp(0.25, 8.0, 4.0), 5.0);
  }

  #[test]
  fn folds_and_limits() {
    assert_eq!(min_n(&[3.0, -1.0, 2.0]), -1.0);
    assert_eq!(max_n(&[3.0, -1.0, 2.0]), 3.0);
    assert_eq!(lim01(-3.0), 0.0);
    assert_eq!(clip(70000.0), MAXVAL);
    assert_eq!(sgn(-0.5), -1.0);
  }

  /// `max0` must swallow NaN into `0.0`, like libstdc++'s `std::max(0.f, NaN)`.
  #[test]
  fn max0_clamps_and_swallows_nan() {
    assert_eq!(max0(0.5), 0.5);
    assert_eq!(max0(-1.0), 0.0);
    assert_eq!(max0(0.0), 0.0);
    assert_eq!(max0(f32::NAN), 0.0, "a NaN estimate must not poison the pixel");
    assert_eq!(max0(f32::INFINITY), f32::INFINITY);
  }

  /// `xmul2f` / `xdivf` are exact powers of two for finite inputs, so they agree
  /// with the multiplication — but they are *bit* tricks, so the non-finite
  /// inputs are where they must differ. That is the whole point of copying them.
  #[test]
  fn the_exponent_tricks_round_trip_and_diverge_on_non_finite() {
    for v in [1.0_f32, 0.5, 0.1, -3.25, 65535.0] {
      assert_eq!(xmul2f(v), v * 2.0, "{v}");
      assert_eq!(xdiv2f(v), v * 0.5, "{v}");
      assert_eq!(xdivf(v, 1), xdiv2f(v), "{v}");
      assert_eq!(xdivf(v, 2), v * 0.25, "{v}");
      // exact round trip — the reason both directions use the same trick
      assert_eq!(xdiv2f(xmul2f(v)), v, "{v}");
    }

    // zero keeps its sign and is left alone (the guard skips it)
    assert_eq!(xmul2f(0.0), 0.0);
    assert_eq!(xdivf(-0.0, 2), -0.0);

    // the divergence: `xdivf` on an infinity/NaN is finite, `x * 0.25` is not
    assert_eq!(xdivf(f32::INFINITY, 2), f32::from_bits(0x7E80_0000));
    assert!(xdivf(f32::NAN, 2).is_finite());
  }

  /// `max2` must reproduce `std::max`'s asymmetry: a NaN **second** argument
  /// yields the first, which is what keeps the kernels' `max(epssq, …)` floors
  /// finite; a NaN first argument propagates, exactly as `std::max` does.
  #[test]
  fn max2_keeps_the_first_operand_for_a_nan_second() {
    assert_eq!(max2(1.0, 2.0), 2.0);
    assert_eq!(max2(2.0, 1.0), 2.0);
    assert_eq!(max2(1.0, f32::NAN), 1.0);
    assert!(max2(f32::NAN, 1.0).is_nan());
  }
}
