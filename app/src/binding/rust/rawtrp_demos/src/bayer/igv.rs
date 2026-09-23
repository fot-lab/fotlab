//! IGV — "Integrated Gaussian Vector on colour differences" Bayer demosaic.
//!
//! Ported from `external/RawTherapee/rtengine/demosaic_algos.cc:609-865`
//! (`RawImageSource::igv_interpolate`, Copyright (c) 2007-2013 Luis Sanz
//! Rodriguez, "Using High Order Interpolation technique by Jim S, Jimmy Li and
//! Sharmil Randhawa"; adapted to RawTherapee by Jacques Desmis 3/2013 —
//! GPL-3.0).
//!
//! IGV is the oldest of the interpolating kernels in the catalogue and the only
//! one whose *green* estimate is not directional at all:
//!
//! 1. **Colour differences first.** At every red/blue site the kernel forms a
//!    vertical and a horizontal colour difference `G - C`, each from a
//!    Li/Randhawa high-order interpolation of the same colour, weighted by the
//!    inverse of a N/E/W/S **gradient** (`vg`, `hg` in upstream's naming).
//!    The two directional estimates are `n…/s…` and `e…/w…`.
//! 2. **Integrated Gaussian vector.** A 7×7 window of *squared* colour
//!    differences — a Gaussian-shaped weighted sum with negative
//!    cross-terms — turns those differences into a confidence for "this pixel
//!    lies on a vertical edge" vs "… a horizontal edge". Green is then the
//!    gradient-weighted blend of the two directional differences, and the
//!    direction that loses is *median limited* by its two neighbours. This is
//!    the "IGV" of the name, and it is what makes the kernel robust near
//!    saturated edges at the cost of fine detail.
//! 3. **Chroma.** Diagonal, gradient-weighted interpolation of `R` at blue
//!    sites and `B` at red sites; then N/E/W/S interpolation of both at green
//!    sites.
//!
//! ## Which upstream implementation this is
//!
//! `demosaic_algos.cc` contains **two complete implementations** of
//! `igv_interpolate`, selected by `#if defined(__SSE2__) || defined(RT_SIMDE)`
//! (`:217` / `:608`). They are not a scalar kernel plus a vectorisation of it:
//! the SSE2 one repacks the working buffers into half-size interleaved planes
//! (`rgb[2]`, `chr[4]`, `:225-237`), so its indexing differs throughout.
//!
//! This port follows the **scalar** branch, because that is the one our target
//! compiles: `__SSE2__` is undefined on aarch64, and `RT_SIMDE` is opt-in and
//! **off** by default (`external/RawTherapee/CMakeLists.txt:203`,
//! `option(WITH_SIMDE … OFF)`). A desktop x86-64 build takes the SSE2 branch
//! instead; the two are expected to agree to float rounding, but that is a claim
//! to *verify* against a golden render rather than assume, and it is tracked in
//! `rules/DESIGN/detail/FOTLAB-NATIVE-000004.md`.
//!
//! ## Fidelity notes
//!
//! * **`epssq` is `1e-5`, not `1e-10`.** Upstream's comment still reads "mod
//!   epssq -10f =>-5f Jacques 3/2013 to prevent artifact (divide by zero)"
//!   (`:611`) — the `-10` survives only in the prose. It is the floor under the
//!   *squared* gradient sums, so it is tiny either way, but a faithful port must
//!   read the value, not the comment.
//! * **The `calloc` matters, and only for the colour-difference planes.**
//!   Upstream zeroes `vdif`, `hdif` and `chr` (`:619-629`). The first chroma pass
//!   reads `chr[·]` three rows outside the band it writes, and rows within a few
//!   pixels of the frame edge are *never* written at all — upstream lets the
//!   zeros there propagate into the outermost surviving output rows and then
//!   relies on `border_interpolate(…, 8, …)` overwriting the frame
//!   (`:854`). Reusing a scratch buffer without clearing it would make the result
//!   depend on where the buffer had been used before. `rgb` is zeroed too, to
//!   keep the buffer's initial state identical, even though every element it
//!   reads has been written by then.
//! * **The two diagonal-chroma passes must stay two passes.** Steps 4 and 5
//!   (`:729` / `:757`) write *different row parities* of the same planes and each
//!   reads the other's output through its `±1`/`±3` row offsets — that is why
//!   upstream splits them into two `#pragma omp for` loops, and the implicit
//!   barrier between them is a **correctness** requirement, not a tuning knob.
//!   Merging them into one row loop (the bodies are byte-identical) would race.
//! * **`FC(row, 1)` vs `FC(row, 0)`.** The column *start* of every row loop is
//!   picked from a CFA lookup, and the two families of loops disagree about which
//!   column they ask about: the difference/green/chroma-at-R/B loops use
//!   `FC(row, 1)` (`:667`, `:699`, `:730`, `:758`) while the chroma-at-green
//!   loops use `FC(row, 0)` (`:786`, `:809`). Both work out to "start on a pixel
//!   that is not green", but they are not interchangeable text.
//! * **Half-size colour-difference planes need an even width.** Upstream indexes
//!   them as `vdif[indx >> 1]` with the row stride implied by that pack, so a row
//!   occupies exactly `width / 2` slots only when `width` is even (`:628-629`).
//!   This port relies on that to shard those planes by row and returns
//!   [`Error::Shape`] for an odd width — a **documented deviation**: upstream
//!   would instead build a differently-shaped half-plane and still produce an
//!   image. Every Bayer sensor is even in both axes, so the deviation is not
//!   reachable from a real mosaic; it exists so that "shard by row" stays an
//!   argument we can state, rather than one we assume.
//! * **Translating `>> 1` offsets is the easiest thing here to get silently
//!   wrong.** Upstream writes `vdif[(indx - v2) >> 1]` with `v2 = 2 * width`, and
//!   the shift applies to the *difference*, so the packed-plane offset is
//!   `width` slots — a jump of `2` packed rows — and not `width / 2`. The three
//!   offsets `v2`/`v4`/`v6` therefore become `width`, `2 * width`, `3 * width`
//!   (`hv2`/`hv4`/`hv6` below). Writing `half_w` there still compiles and still
//!   reads plausibly-shaped data; it just reads the wrong rows.
//! * **Minimum size.** `border_interpolate` is called with a border of 8 and its
//!   first pass tests only the *left* column bound, so `width > 8` and
//!   `height >= 8` are required for it to stay inside the planes; below that the
//!   port reports [`Error::Shape`] rather than reproducing upstream's unchecked
//!   writes.
//! * **A four-colour CFA cannot be demosaiced by IGV, even though upstream is
//!   what calls IGV for one.** `rcd_demosaic.cc:56-65` and
//!   `vng4_demosaic_RT.cc:67-76` test `FC(i, j) == 3` and "fall back to
//!   `igv_interpolate`" for exactly the case their own `rgb[3]`-shaped logic
//!   cannot handle. But `igv_interpolate` declares `float* rgb[3]` (`:615`) and
//!   loads `rgb[c]` from `FC` (`:649-650`), so for such a CFA it indexes `rgb[3]`
//!   — past the end of a three-element array of *pointers*, reading whatever
//!   the next stack slot holds. Upstream's fallback is therefore not a working
//!   path, and the port cannot reproduce it without reproducing undefined
//!   behaviour; it reports [`Error::UnsupportedCfa`] instead. This is the same
//!   answer `vng4`/`rcd` already give, so the fallback gap stays open by
//!   design rather than being closed by IGV.
//! * **No hand-written SIMD on this branch.** The scalar implementation contains
//!   no intrinsics; the SSE2 siblings are a separate implementation (above).
//!   Scalar plus rayon is the complete port, exactly as for RCD.
//! * **Parallelism, and where it stops.** Steps 1.1, 1.2, 1.3 and 8 are sharded
//!   exactly as upstream's `#pragma omp for` shards them: one rayon task per row
//!   of the plane being written, with the phase boundaries acting as the barriers
//!   OpenMP supplied implicitly. The four chroma passes (4, 5, 6, 7) are **not**
//!   parallel here, and that is a deliberate, recorded gap rather than an
//!   oversight: each of them reads the plane it writes one and three *rows*
//!   outside the rows it owns, so no safe `par_chunks_mut` split of that plane can
//!   describe it. Closing the gap needs a spare chroma plane per channel (8
//!   full-resolution planes instead of 6) or a row-parity buffer layout; both are
//!   noted in `rules/DESIGN/detail/FOTLAB-NATIVE-000004.md`. Nothing anywhere in
//!   this kernel needs `unsafe`.

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::border::border_interpolate;
use crate::cfa::CfaDesc;
use crate::math::{abs, lim, max0, median3, sqr};
use crate::{Error, Rgb};

/// `eps` — divides every gradient sum, so a zero gradient cannot become an
/// infinite weight (`demosaic_algos.cc:611`).
const EPS: f32 = 1e-5;
/// `epssq` — the floor under the integrated Gaussian vector. See the module
/// note: the *value* is `1e-5`.
const EPSSQ: f32 = 1e-5;
/// `48.f * 65535.f` — the normaliser of the Li/Randhawa high-order
/// interpolation (`:675`).
const HIGH_ORDER_NORM: f32 = 3_145_680.0;
/// `MAXVAL` — the 16-bit working range the colour differences are scaled by.
const SCALE: f32 = 65535.0;
/// The border width upstream hands to `border_interpolate` (`:854`).
const BORDER: usize = 8;

/// Interpolate the diagonal chroma of the *other* colour at red/blue sites.
///
/// Upstream's steps 4 and 5 (`:729-743`, `:757-771`). The two loops have
/// byte-identical bodies; run one after the other they cover every row of
/// `[7, height - 7)` by parity, so `row_start` is 7 for the first and 8 for the
/// second. They are kept as two *separate* passes because each reads the other's
/// writes three rows away — the barrier between them is real, not stylistic.
///
/// **Also unlike the rest of the kernel, these two passes stay sequential.**
/// Each one reads the very plane it writes, three rows outside the rows it owns,
/// so no `par_chunks_mut` split of that plane can express it: safe Rust cannot
/// hand a task `&` and `&mut` over overlapping regions of one buffer. The two
/// ways out both cost more than they buy here — a spare chroma plane per channel
/// would take IGV from 6 to 8 full-resolution planes (1.44 GB at 45 MP, on top of
/// the 1.08 GB it already needs), and `unsafe` is not a tool this port uses. So
/// these two passes run in place and in order, exactly as upstream runs them, and
/// the gap is recorded in `rules/DESIGN/detail/FOTLAB-NATIVE-000004.md`.
fn interpolate_diagonal_chroma(cfa: &CfaDesc, width: usize, height: usize, chr0: &mut [f32], chr1: &mut [f32], row_start: usize) {
  // `v1`/`v3` are the row offsets; `±1`/`±3` the column ones (`h1`/`h3`). All
  // eight combinations the body below needs are precomputed so the transcription
  // stays recognisable against upstream's `indx - v1 - h1` spelling.
  let v1 = width;
  let v3 = 3 * width;
  let v1h1 = v1 + 1;
  let v1m1 = v1 - 1;
  let v1h3 = v1 + 3;
  let v1m3 = v1 - 3;
  let v3h1 = v3 + 1;
  let v3m1 = v3 - 1;
  let v3h3 = v3 + 3;
  let v3m3 = v3 - 3;

  let row_end = height.saturating_sub(7);
  let col_end = width.saturating_sub(7);

  let mut row = row_start;
  while row < row_end {
    let col0 = 7 + (cfa.fc(row, 1) & 1) as usize;

    if col0 < col_end {
      // `c = 1 - FC(row, col) / 2`: the *other* chroma plane. At a blue site
      // (FC == 2) this is 0, so the pass started at row 7 writes "R at B" and the
      // one started at row 8 writes "B at R".
      let c = 1 - cfa.fc(row, col0) / 2;
      let ch: &mut [f32] = if c == 0 { &mut *chr0 } else { &mut *chr1 };

      let base = row * width;
      let mut col = col0;
      while col < col_end {
        let indx = base + col;

        // NW, NE, SW, SE inverse gradients over the diagonal colour differences.
        let nwg = 1.0 / (EPS + abs(ch[indx - v1h1] - ch[indx - v3h3]) + abs(ch[indx + v1h1] - ch[indx - v3h3]));
        let neg = 1.0 / (EPS + abs(ch[indx - v1m1] - ch[indx - v3m3]) + abs(ch[indx + v1m1] - ch[indx - v3m3]));
        let swg = 1.0 / (EPS + abs(ch[indx + v1m1] - ch[indx + v3h3]) + abs(ch[indx - v1m1] - ch[indx + v3m3]));
        let seg = 1.0 / (EPS + abs(ch[indx + v1h1] - ch[indx + v3m3]) + abs(ch[indx - v1h1] - ch[indx + v3h3]));

        // Median limiting of the four diagonal colour differences.
        let nwv = median3(ch[indx - v1h1], ch[indx - v3h1], ch[indx - v1h3]);
        let nev = median3(ch[indx - v1m1], ch[indx - v3m1], ch[indx - v1m3]);
        let swv = median3(ch[indx + v1m1], ch[indx + v3m1], ch[indx + v1m3]);
        let sev = median3(ch[indx + v1h1], ch[indx + v3h1], ch[indx + v1h3]);

        ch[indx] = (nwg * nwv + neg * nev + swg * swv + seg * sev) / (nwg + neg + swg + seg);

        col += 2;
      }
    }

    row += 2;
  }
}

/// Interpolate both chroma planes at green sites (upstream's steps 6 and 7,
/// `:785-794` and `:808-818`).
///
/// Upstream runs these as two passes over the same pixel set, one plane each;
/// neither body reads the other plane, so this port walks the rows once and does
/// both. From a green pixel the `±1`/`±3` neighbours in both axes are non-green,
/// so the body never reads a value it is itself writing — but it does read one
/// and three *rows* away in the plane it writes, which is why (like
/// [`interpolate_diagonal_chroma`]) this pass stays sequential.
fn interpolate_green_chroma(cfa: &CfaDesc, width: usize, height: usize, chr0: &mut [f32], chr1: &mut [f32]) {
  let v1 = width;
  let v3 = 3 * width;

  let row_end = height.saturating_sub(7);
  let col_end = width.saturating_sub(7);

  for row in 7..row_end {
    // `FC(row, 0)`, not `FC(row, 1)` — see the module note.
    let col0 = 7 + (cfa.fc(row, 0) & 1) as usize;

    if col0 >= col_end {
      continue;
    }

    let base = row * width;
    let mut col = col0;
    while col < col_end {
      let indx = base + col;

      // N/E/W/S inverse gradients, then the weighted mean. Written as an inner
      // loop over the two planes so the identical transcription is not
      // duplicated; upstream has one such body per plane.
      for p in [&mut *chr0, &mut *chr1] {
        let ng = 1.0 / (EPS + abs(p[indx - v1] - p[indx - v3]) + abs(p[indx + v1] - p[indx - v3]));
        let eg = 1.0 / (EPS + abs(p[indx + 1] - p[indx + 3]) + abs(p[indx - 1] - p[indx + 3]));
        let wg = 1.0 / (EPS + abs(p[indx - 1] - p[indx - 3]) + abs(p[indx + 1] - p[indx - 3]));
        let sg = 1.0 / (EPS + abs(p[indx + v1] - p[indx + v3]) + abs(p[indx - v1] - p[indx + v3]));

        p[indx] = (ng * p[indx - v1] + eg * p[indx + 1] + wg * p[indx - 1] + sg * p[indx + v1]) / (ng + eg + wg + sg);
      }

      col += 2;
    }
  }
}

/// `RawImageSource::igv_interpolate(winw, winh)` — the scalar branch.
///
/// # Errors
/// [`Error::UnsupportedCfa`] for a CFA this kernel cannot express (a
/// four-colour one, or X-Trans), [`Error::Shape`] for an odd width, which the
/// half-size colour-difference packing cannot be sharded across rows with.
pub fn bayer_igv_demosaic(cfa: &CfaDesc, mosaic: &Array2D<f32>) -> Result<Rgb, Error> {
  if cfa.has_fourth_colour() {
    return Err(Error::UnsupportedCfa("igv on a four-colour CFA"));
  }
  if !cfa.is_bayer {
    return Err(Error::UnsupportedCfa("igv on a non-Bayer CFA"));
  }

  let width = mosaic.width();
  let height = mosaic.height();
  if width % 2 != 0 {
    return Err(Error::Shape(format!("igv needs an even width, got {width}")));
  }

  // `border_interpolate(…, BORDER, …)` reads a 3x3 neighbourhood around every
  // frame pixel and, in its first pass, tests only the *left* bound
  // (`j1 > -1`), so the plane needs a column of slack on the right:
  // `BORDER < width`. Its first/last-row passes index rows `0..BORDER`, so it
  // must fit vertically too. Both bounds are upstream's own shape assumptions;
  // below them this port reports a shape error instead of writing outside the
  // planes, which is what upstream's unchecked `red[i][j] = …` would do.
  if width <= BORDER || height < BORDER {
    return Err(Error::Shape(format!("mosaic too small for igv's {BORDER}-pixel border: {width}x{height}")));
  }

  let n = width * height;
  let half_w = width / 2;
  // `calloc(width * height / 2, sizeof(float))` — note the integer division.
  let hv = n / 2;

  // rgb[0..3]: the mosaic sample at each pixel's own CFA site, and (in rgb[1])
  // the finished green everywhere. Zeroed like upstream's `calloc`; every
  // element actually read has been written, but the initial state is kept
  // identical rather than argued about.
  let mut rgb0 = vec![0.0f32; n];
  let mut rgb1 = vec![0.0f32; n];
  let mut rgb2 = vec![0.0f32; n];

  // chr[0] = (G - R) / 65535, chr[1] = (G - B) / 65535. **Load-bearing zeros**:
  // the first diagonal pass reads them outside the region it writes, and the
  // outermost rows/columns are never written at all (see the module note).
  let mut chr0 = vec![0.0f32; n];
  let mut chr1 = vec![0.0f32; n];

  // Vertical and horizontal colour differences, at red/blue sites only, packed
  // at half resolution as upstream does. Also read outside the band that writes
  // them, so also load-bearing.
  let mut vdif = vec![0.0f32; hv];
  let mut hdif = vec![0.0f32; hv];

  // Row offsets, mirroring upstream's `v1..v6` (`:614`). `v1`/`v3`/`v5` address
  // the full-size planes; `v2`/`v4`/`v6` address the half-size ones and appear
  // below as `hv2`/`hv4`/`hv6`, which is what `>> 1` does to them.
  let v1 = width;
  let v2 = 2 * width;
  let v3 = 3 * width;
  let v4 = 4 * width;
  let v5 = 5 * width;

  // Step 1.1 (`:647-651`) — the mosaic into its own channel, non-negativity
  // clamped. This is the whole of upstream's `rgb[c][indx] = max(0, rawData)`.
  rgb0
    .par_chunks_mut(width)
    .zip(rgb1.par_chunks_mut(width))
    .zip(rgb2.par_chunks_mut(width))
    .enumerate()
    .for_each(|(row, ((r0, r1), r2))| {
      let raw = mosaic.row(row);
      for (col, &sample) in raw.iter().enumerate() {
        let v = max0(sample);
        let c = cfa.fc(row, col);
        if c == 0 {
          r0[col] = v;
        } else if c == 1 {
          r1[col] = v;
        } else {
          r2[col] = v;
        }
      }
    });

  // Step 1.2 (`:666-683`) — at every red/blue site with a five-pixel margin:
  // the N/E/W/S gradients, the Li/Randhawa high-order interpolation of the
  // *other two* channels, and the resulting vertical/horizontal colour
  // differences. The uniform CFA colour along a row lets `c` be hoisted out of
  // the column loop, as upstream does.
  vdif
    .par_chunks_mut(half_w)
    .zip(hdif.par_chunks_mut(half_w))
    .enumerate()
    .for_each(|(row, (vd, hd))| {
      if row < 5 || row >= height.saturating_sub(5) {
        return;
      }

      let col0 = 5 + (cfa.fc(row, 1) & 1) as usize;
      let col_end = width.saturating_sub(5);
      if col0 >= col_end {
        return;
      }

      let c = cfa.fc(row, col0);
      // `rgb[c]` at this row's red/blue sites is the mosaic itself; `rgb[1]` at
      // the green neighbours likewise. Neither has been interpolated yet.
      let rgbc: &[f32] = if c == 0 { &rgb0 } else { &rgb2 };

      let base = row * width;
      let mut col = col0;
      while col < col_end {
        let indx = base + col;

        // N, E, W, S gradients.
        let ng = EPS + (abs(rgb1[indx - v1] - rgb1[indx - v3]) + abs(rgbc[indx] - rgbc[indx - v2])) / SCALE;
        let eg = EPS + (abs(rgb1[indx + 1] - rgb1[indx + 3]) + abs(rgbc[indx] - rgbc[indx + 2])) / SCALE;
        let wg = EPS + (abs(rgb1[indx - 1] - rgb1[indx - 3]) + abs(rgbc[indx] - rgbc[indx - 2])) / SCALE;
        let sg = EPS + (abs(rgb1[indx + v1] - rgb1[indx + v3]) + abs(rgbc[indx] - rgbc[indx + v2])) / SCALE;

        // N, E, W, S Li/Randhawa high-order interpolation, then Hamilton-Adams
        // style clamping. `48 * 65535` is the coefficient sum.
        let nv = lim(
          (23.0 * rgb1[indx - v1] + 23.0 * rgb1[indx - v3] + rgb1[indx - v5] + rgb1[indx + v1] + 40.0 * rgbc[indx]
            - 32.0 * rgbc[indx - v2]
            - 8.0 * rgbc[indx - v4])
            / HIGH_ORDER_NORM,
          0.0,
          1.0,
        );
        let ev = lim(
          (23.0 * rgb1[indx + 1] + 23.0 * rgb1[indx + 3] + rgb1[indx + 5] + rgb1[indx - 1] + 40.0 * rgbc[indx]
            - 32.0 * rgbc[indx + 2]
            - 8.0 * rgbc[indx + 4])
            / HIGH_ORDER_NORM,
          0.0,
          1.0,
        );
        let wv = lim(
          (23.0 * rgb1[indx - 1] + 23.0 * rgb1[indx - 3] + rgb1[indx - 5] + rgb1[indx + 1] + 40.0 * rgbc[indx]
            - 32.0 * rgbc[indx - 2]
            - 8.0 * rgbc[indx - 4])
            / HIGH_ORDER_NORM,
          0.0,
          1.0,
        );
        let sv = lim(
          (23.0 * rgb1[indx + v1] + 23.0 * rgb1[indx + v3] + rgb1[indx + v5] + rgb1[indx - v1] + 40.0 * rgbc[indx]
            - 32.0 * rgbc[indx + v2]
            - 8.0 * rgbc[indx + v4])
            / HIGH_ORDER_NORM,
          0.0,
          1.0,
        );

        // Vertical and horizontal colour differences, in the `[0, 1]` domain the
        // kernels work in (hence `/ 65535`).
        let j = col >> 1;
        vd[j] = (sg * nv + ng * sv) / (ng + sg) - rgbc[indx] / SCALE;
        hd[j] = (wg * ev + eg * wv) / (eg + wg) - rgbc[indx] / SCALE;

        col += 2;
      }
    });

  // Step 1.3 (`:698-713`) — the integrated Gaussian vector over the colour
  // differences gives the vertical/horizontal confidences; green at every
  // red/blue site is the blend of the two directional differences, with the
  // losing direction median limited by its neighbours. The chroma of the
  // pixel's *own* colour is written at the same time.
  //
  // `vdif`/`hdif` are addressed with the half-resolution row stride: an offset of
  // `k * width` in the *full-size* index becomes `(k * width) >> 1 == k * width / 2`
  // slots in the packed plane, i.e. a jump of `k` packed rows. So the `±v2`,
  // `±v4`, `±v6` neighbours (`vN = N * width`) land `width`, `2 * width` and
  // `3 * width` slots away — *not* `half_w`, `2 * half_w`, `3 * half_w`. Getting
  // this wrong still compiles and still looks plausible; it silently mixes rows.
  let hv2 = width;
  let hv4 = 2 * width;
  let hv6 = 3 * width;

  rgb1
    .par_chunks_mut(width)
    .zip(chr0.par_chunks_mut(width))
    .zip(chr1.par_chunks_mut(width))
    .enumerate()
    .for_each(|(row, ((g_row, c0), c1))| {
      if row < 7 || row >= height.saturating_sub(7) {
        return;
      }

      let col0 = 7 + (cfa.fc(row, 1) & 1) as usize;
      let col_end = width.saturating_sub(7);
      if col0 >= col_end {
        return;
      }

      let c = cfa.fc(row, col0);
      let rgbc: &[f32] = if c == 0 { &rgb0 } else { &rgb2 };
      let ch: &mut [f32] = if c / 2 == 0 { c0 } else { c1 };

      let base = row * width;
      let mut col = col0;
      while col < col_end {
        let indx = base + col;
        let i = indx >> 1;

        // H & V integrated Gaussian vector over the variance of the colour
        // differences. Note the SQR arguments are *sums of three* differences.
        let ng = lim(
          EPSSQ
            + 78.0 * sqr(vdif[i])
            + 69.0 * (sqr(vdif[i - hv2]) + sqr(vdif[i + hv2]))
            + 51.0 * (sqr(vdif[i - hv4]) + sqr(vdif[i + hv4]))
            + 21.0 * (sqr(vdif[i - hv6]) + sqr(vdif[i + hv6]))
            - 6.0 * sqr(vdif[i - hv2] + vdif[i] + vdif[i + hv2])
            - 10.0 * (sqr(vdif[i - hv4] + vdif[i - hv2] + vdif[i]) + sqr(vdif[i] + vdif[i + hv2] + vdif[i + hv4]))
            - 7.0 * (sqr(vdif[i - hv6] + vdif[i - hv4] + vdif[i - hv2]) + sqr(vdif[i + hv2] + vdif[i + hv4] + vdif[i + hv6])),
          0.0,
          1.0,
        );
        let eg = lim(
          EPSSQ
            + 78.0 * sqr(hdif[i])
            + 69.0 * (sqr(hdif[i - 1]) + sqr(hdif[i + 1]))
            + 51.0 * (sqr(hdif[i - 2]) + sqr(hdif[i + 2]))
            + 21.0 * (sqr(hdif[i - 3]) + sqr(hdif[i + 3]))
            - 6.0 * sqr(hdif[i - 1] + hdif[i] + hdif[i + 1])
            - 10.0 * (sqr(hdif[i - 2] + hdif[i - 1] + hdif[i]) + sqr(hdif[i] + hdif[i + 1] + hdif[i + 2]))
            - 7.0 * (sqr(hdif[i - 3] + hdif[i - 2] + hdif[i - 1]) + sqr(hdif[i + 1] + hdif[i + 2] + hdif[i + 3])),
          0.0,
          1.0,
        );

        // Median limiting of each direction by its two neighbours.
        let nv = median3(0.725 * vdif[i] + 0.1375 * vdif[i - hv2] + 0.1375 * vdif[i + hv2], vdif[i - hv2], vdif[i + hv2]);
        let ev = median3(0.725 * hdif[i] + 0.1375 * hdif[i - 1] + 0.1375 * hdif[i + 1], hdif[i - 1], hdif[i + 1]);

        // Chrominance estimate and green population.
        let chroma = (eg * nv + ng * ev) / (ng + eg);
        ch[indx] = chroma;
        g_row[col] = rgbc[indx] + SCALE * chroma;

        col += 2;
      }
    });

  // Steps 4 and 5 (`:729-743`, `:757-771`) — one pass per row parity, in order:
  // the second reads what the first wrote three rows away.
  interpolate_diagonal_chroma(cfa, width, height, &mut chr0, &mut chr1, 7);
  interpolate_diagonal_chroma(cfa, width, height, &mut chr0, &mut chr1, 8);

  // Steps 6 and 7 (`:785-794`, `:808-818`) — the chroma at green sites, both
  // planes in one walk.
  interpolate_green_chroma(cfa, width, height, &mut chr0, &mut chr1);

  // Step 8 (`:847-852`) — the frame interior. Upstream writes rows/columns
  // `7..W-7` here and leaves the eight-pixel frame to `border_interpolate`.
  let mut out = Rgb::new(width, height);
  out
    .red
    .par_rows_mut()
    .zip(out.green.par_rows_mut())
    .zip(out.blue.par_rows_mut())
    .enumerate()
    .for_each(|(row, ((r, g), b))| {
      if row < 7 || row >= height.saturating_sub(7) {
        return;
      }
      let base = row * width;
      for col in 7..width.saturating_sub(7) {
        let indx = base + col;
        let green = max0(rgb1[indx]);
        r[col] = max0(rgb1[indx] - SCALE * chr0[indx]);
        g[col] = green;
        b[col] = max0(rgb1[indx] - SCALE * chr1[indx]);
      }
    });

  // Step 9 (`:854`) — the eight-pixel frame, from the *mosaic*, exactly as
  // upstream; it also overwrites the outermost rows/columns the main loop wrote.
  border_interpolate(cfa, mosaic, &mut out.red, &mut out.green, &mut out.blue, BORDER);

  Ok(out)
}

#[cfg(test)]
mod tests {
  use super::*;

  /// The four Bayer orderings, as 2x2 dcraw tiles.
  fn cfa(pattern: [[u8; 2]; 2]) -> CfaDesc {
    CfaDesc::bayer_from_2x2(pattern)
  }

  /// A mosaic whose every sample is `v`, so the kernel must return a flat field.
  fn flat(width: usize, height: usize, v: f32) -> Array2D<f32> {
    Array2D::filled(width, height, v)
  }

  /// A pale, non-flat mosaic: a deterministic pattern that exercises both row
  /// parities and both CFA colours without ever being constant.
  fn textured(width: usize, height: usize) -> Array2D<f32> {
    let mut m = Array2D::<f32>::new(width, height);
    for row in 0..height {
      for col in 0..width {
        let v = 0.2 + 0.5 * (((row * 7 + col * 13) % 17) as f32) / 17.0;
        m.set(row, col, v);
      }
    }
    m
  }

  /// An odd width is refused, because the half-size colour-difference planes
  /// cannot be sharded across rows with `indx >> 1` addressing.
  #[test]
  fn an_odd_width_is_refused() {
    let err = bayer_igv_demosaic(&cfa([[0, 1], [1, 2]]), &flat(65, 64, 0.3)).unwrap_err();
    assert!(matches!(err, Error::Shape(_)), "{err:?}");
  }

  /// X-Trans is not this kernel's CFA, and a four-colour CFA is the case
  /// upstream's own "fall back to igv" path would index `rgb[3]` for.
  #[test]
  fn non_bayer_and_four_colour_cfas_are_refused() {
    let xt = CfaDesc::xtrans_from_6x6([[1; 6]; 6]);
    assert!(matches!(bayer_igv_demosaic(&xt, &flat(32, 32, 0.2)), Err(Error::UnsupportedCfa(_))));

    let mut four = cfa([[0, 1], [1, 2]]);
    four.colors = 4;
    assert!(matches!(bayer_igv_demosaic(&four, &flat(32, 32, 0.2)), Err(Error::UnsupportedCfa(_))));
  }

  /// Every channel of a flat mosaic comes back flat, and equal to the input.
  #[test]
  fn a_flat_field_comes_back_flat() {
    for pattern in [[[0, 1], [1, 2]], [[2, 1], [1, 0]], [[1, 0], [2, 1]], [[1, 2], [0, 1]]] {
      let c = cfa(pattern);
      let out = bayer_igv_demosaic(&c, &flat(64, 64, 0.5)).expect("igv");
      for row in 0..64 {
        for col in 0..64 {
          for (name, v) in [("R", out.red.at(row, col)), ("G", out.green.at(row, col)), ("B", out.blue.at(row, col))] {
            assert!((v - 0.5).abs() < 1e-4, "{pattern:?} {name} at ({row},{col}) = {v}");
          }
        }
      }
    }
  }

  /// The samples the mosaic actually carries are reproduced exactly, because
  /// both the green population and the two chroma differencing steps invert
  /// around them. This is the cheapest check that the CFA parity and both chroma
  /// planes are wired to the right colours.
  #[test]
  fn sampled_channels_are_preserved_exactly() {
    let c = cfa([[0, 1], [1, 2]]);
    let mosaic = textured(64, 64);
    let out = bayer_igv_demosaic(&c, &mosaic).expect("igv");

    for row in 8..56 {
      for col in 8..56 {
        let expected = mosaic.at(row, col);
        match c.fc(row, col) {
          0 => assert!((out.red.at(row, col) - expected).abs() < 1e-4, "R at ({row},{col})"),
          1 => assert!((out.green.at(row, col) - expected).abs() < 1e-4, "G at ({row},{col})"),
          _ => assert!((out.blue.at(row, col) - expected).abs() < 1e-4, "B at ({row},{col})"),
        }
      }
    }
  }

  /// Every output is finite and non-negative for a hostile input: saturated
  /// samples, a black pixel, and one that forces a zero gradient sum.
  #[test]
  fn hostile_mosaic_stays_finite() {
    let c = cfa([[0, 1], [1, 2]]);
    let mut mosaic = textured(48, 48);
    for col in 0..48 {
      mosaic.set(10, col, 1.0);
      mosaic.set(11, col, 1.0);
    }
    mosaic.set(20, 20, 0.0);
    mosaic.set(21, 21, 0.0);

    let out = bayer_igv_demosaic(&c, &mosaic).expect("igv");
    for row in 0..48 {
      for col in 0..48 {
        for (name, v) in [("R", out.red.at(row, col)), ("G", out.green.at(row, col)), ("B", out.blue.at(row, col))] {
          assert!(v.is_finite(), "{name} at ({row},{col}) is {v}");
          assert!(v >= 0.0, "{name} at ({row},{col}) = {v}");
        }
      }
    }
  }

  /// The kernel is deterministic: nothing here depends on the rayon split.
  #[test]
  fn the_result_does_not_depend_on_the_thread_pool() {
    let c = cfa([[0, 1], [1, 2]]);
    let mosaic = textured(80, 60);

    let single = rayon::ThreadPoolBuilder::new()
      .num_threads(1)
      .build()
      .expect("pool")
      .install(|| bayer_igv_demosaic(&c, &mosaic))
      .expect("igv");

    let many = rayon::ThreadPoolBuilder::new()
      .num_threads(4)
      .build()
      .expect("pool")
      .install(|| bayer_igv_demosaic(&c, &mosaic))
      .expect("igv");

    assert_eq!(single, many, "single- and multi-threaded runs must agree bit for bit");
  }
}
