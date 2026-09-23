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

/// `LIM(val, low, high)`.
#[inline(always)]
#[must_use]
pub const fn lim(val: f32, low: f32, high: f32) -> f32 {
  if val < low {
    low
  } else if val > high {
    high
  } else {
    val
  }
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

#[cfg(test)]
mod tests {
  use super::*;

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
}
