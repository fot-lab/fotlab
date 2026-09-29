//! Row-major 2-D buffer, mirroring RawTherapee's `array2D<T>`.
//!
//! Ported from `external/RawTherapee/rtengine/array2D.h`
//! (Copyright (c) 2011 Jan Rinze Peterzon, GPL-3.0).
//!
//! The upstream class is a flat `std::vector<T>` plus a `T**` row-pointer table,
//! so `a[i][j]` and `a[i]` (a `T*` row) are both valid. This port keeps the same
//! shape — a flat `Vec<T>` with `width`/`height`, addressed as `row(i)[j]` — and
//! adds the rayon-friendly views the kernels need to replace `#pragma omp for`.
//!
//! Unlike C++ the buffer is always **zero-initialised**, which is what the RT
//! kernels implicitly expect on their borders (RT's `array2D(w, h)` does *not*
//! clear, so the dual-demosaic `intp(blend, existing, …)` path relies on the
//! base algorithm having written every element first; we initialise to be safe).

use rayon::prelude::*;
use rayon::slice::ChunksMut;

/// A `height x width` row-major array of `T`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Array2D<T> {
  width: usize,
  height: usize,
  data: Vec<T>,
}

impl<T: Clone + Default> Array2D<T> {
  /// Allocate a zero-initialised `width x height` array.
  pub fn new(width: usize, height: usize) -> Self {
    Self { width, height, data: vec![T::default(); width * height] }
  }

  /// Allocate a `width x height` array filled with `value`.
  pub fn filled(width: usize, height: usize, value: T) -> Self {
    Self { width, height, data: vec![value; width * height] }
  }
}

impl<T> Array2D<T> {
  /// Image width (number of columns).
  #[inline(always)]
  pub fn width(&self) -> usize {
    self.width
  }

  /// Image height (number of rows).
  #[inline(always)]
  pub fn height(&self) -> usize {
    self.height
  }

  /// Number of elements (`width * height`).
  #[inline(always)]
  pub fn len(&self) -> usize {
    self.data.len()
  }

  /// Whether the array holds no elements.
  #[inline(always)]
  pub fn is_empty(&self) -> bool {
    self.data.is_empty()
  }

  /// The whole buffer, row-major.
  #[inline(always)]
  pub fn as_slice(&self) -> &[T] {
    &self.data
  }

  /// The whole buffer, row-major, mutably.
  #[inline(always)]
  pub fn as_mut_slice(&mut self) -> &mut [T] {
    &mut self.data
  }

  /// Row `i` as a slice (the `a[i]` `T*` of the upstream class).
  #[inline(always)]
  pub fn row(&self, i: usize) -> &[T] {
    let start = i * self.width;
    &self.data[start..start + self.width]
  }

  /// Row `i` as a mutable slice.
  #[inline(always)]
  pub fn row_mut(&mut self, i: usize) -> &mut [T] {
    let start = i * self.width;
    &mut self.data[start..start + self.width]
  }

  /// Iterate the rows in order.
  pub fn rows(&self) -> impl Iterator<Item = &[T]> {
    self.data.chunks(self.width)
  }

  /// Iterate the rows in order, mutably.
  pub fn rows_mut(&mut self) -> impl Iterator<Item = &mut [T]> {
    self.data.chunks_mut(self.width)
  }
}

impl<T: Copy> Array2D<T> {
  /// `a[r][c]`, bounds-checked.
  #[inline(always)]
  pub fn at(&self, r: usize, c: usize) -> T {
    self.data[r * self.width + c]
  }

  /// Mutable `a[r][c]`, bounds-checked.
  #[inline(always)]
  pub fn at_mut(&mut self, r: usize, c: usize) -> &mut T {
    let idx = r * self.width + c;
    &mut self.data[idx]
  }

  /// `a[r][c] = v`, bounds-checked.
  #[inline(always)]
  pub fn set(&mut self, r: usize, c: usize, v: T) {
    self.data[r * self.width + c] = v;
  }
}

impl<T: Send> Array2D<T> {
  /// The rows as a parallel iterator of disjoint mutable rows — the rayon
  /// equivalent of `#pragma omp for` over rows, and the sharding point the RT
  /// kernels use. Never use a per-element parallel iterator: the scheduling
  /// overhead dominates (`rules/REVIEW/detail/OPTIMZ-PERFRM-000007.md`).
  #[inline(always)]
  pub fn par_rows_mut(&mut self) -> ChunksMut<'_, T> {
    let width = self.width;
    self.data.par_chunks_mut(width)
  }
}

#[cfg(test)]
mod tests {
  use super::Array2D;

  #[test]
  fn indexing_matches_row_major() {
    let mut a = Array2D::<f32>::new(4, 3);
    a.set(2, 1, 7.0);
    assert_eq!(a.at(2, 1), 7.0);
    assert_eq!(a.row(2)[1], 7.0);
    assert_eq!(a.as_slice()[2 * 4 + 1], 7.0);
    *a.at_mut(0, 3) = -1.0;
    assert_eq!(a.row(0)[3], -1.0);
    assert_eq!(a.as_slice().len(), 12);
    assert_eq!(a.rows().count(), 3);
  }
}
