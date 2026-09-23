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

/// `rtengine::median(a, b, c)` — the three-argument overload.
///
/// Upstream's variadic wrapper (`median.h:6241-6244`) builds a
/// `std::array<float, 3>` and calls the array overload, which does
/// `std::nth_element(array.begin(), array.begin() + 1, array.end())` and returns
/// element `1` (`median.h:35-40`). libstdc++ short-circuits `nth_element` to
/// `__insertion_sort` for a range of three or fewer elements
/// (`if (__last - __first > 3)`), so the array ends up **fully sorted** and the
/// answer is the ordinary middle value.
///
/// The branch structure below is that insertion sort, kept rather than replaced
/// by a three-comparator sorting network, because the two disagree once a NaN is
/// involved and every comparison upstream makes is a `<`. In particular the
/// network's `if b < a { swap(a, b) }` steps would move a NaN differently from
/// `__unguarded_linear_insert`'s single `while (val < *next)` scan.
///
/// The scan is safe without a bound check: `__unguarded_linear_insert` is only
/// reached when `val < v[0]` was **false** (that is the test that routed us into
/// the `else`), and `val == v[i]` is untouched at that point, so the loop must
/// stop at `next == 0` at the latest. Upstream relies on exactly that sentinel;
/// here the worst case is a panic instead of the read below the array that C++'s
/// unguarded scan would perform.
#[inline]
#[must_use]
pub fn median3(a: f32, b: f32, c: f32) -> f32 {
  let mut v = [a, b, c];

  for i in 1..v.len() {
    if v[i] < v[0] {
      // `__insertion_sort`'s front-insert path: rotate `v[..=i]` right by one.
      let val = v[i];
      let mut j = i;
      while j > 0 {
        v[j] = v[j - 1];
        j -= 1;
      }
      v[0] = val;
    } else {
      let val = v[i];
      let mut last = i;
      let mut next = i - 1;
      while val < v[next] {
        v[last] = v[next];
        last = next;
        next -= 1;
      }
      v[last] = val;
    }
  }

  v[1]
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

  #[test]
  fn median3_returns_the_middle_value() {
    for (a, b, c) in [(3.0, 1.0, 2.0), (1.0, 2.0, 3.0), (3.0, 2.0, 1.0), (2.0, 2.0, 5.0), (-1.0, -9.0, 4.0)] {
      let mut sorted = [a, b, c];
      sorted.sort_by(f32::total_cmp);
      assert_eq!(median3(a, b, c), sorted[1], "median3({a}, {b}, {c})");
    }
  }

  /// Upstream's three-argument `median` is `nth_element` reduced to an insertion
  /// sort, so its behaviour for a NaN argument is whatever `val < *next` says —
  /// **not** a designed rule. These two cases pin that we reproduced it rather
  /// than substituting a tidier median (a NaN in the middle propagates; a NaN
  /// first makes every later comparison false and leaves the finite middle).
  #[test]
  fn median3_reproduces_upstreams_nan_behaviour() {
    assert_eq!(median3(f32::NAN, 2.0, 3.0), 2.0);
    assert_eq!(median3(3.0, 2.0, f32::NAN), 3.0);
    assert!(median3(2.0, f32::NAN, 3.0).is_nan());
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
