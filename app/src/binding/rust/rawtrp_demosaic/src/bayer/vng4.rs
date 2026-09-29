//! VNG4 Bayer demosaic.
//!
//! Ported from `external/RawTherapee/rtengine/vng4_demosaic_RT.cc`
//! (GPL-3.0; header: "VNG4 demosaic algorithm, optimized for speed by Ingo
//! Weyrich") — `RawImageSource::vng4_demosaic`.
//!
//! VNG ("variable number of gradients", Chang/Hung/Chuang) is dcraw's
//! `vng_interpolate` (`dcraw.c:4422`), restricted here to the green channel and
//! split into two stages:
//!
//! 1. a **linear** first pass that fills the three channels each pixel does not
//!    sample, using a 3x3 weighted mean (`1 << shift` with
//!    `shift = (y == 0) + (x == 0)`, i.e. **2** orthogonal / **1** diagonal —
//!    upstream's 2:1, not the textbook 4:1 bilinear kernel);
//! 2. the **VNG** pass, which derives `green` at every pixel from a weighted sum
//!    over the eight neighbours whose accumulated gradient is below a
//!    min+half-max threshold, and then `vng4interpolate_row_redblue` derives the
//!    missing red/blue from that green plus the raw values.
//!
//! ## The two greens are the whole trick
//!
//! This kernel reads the **unfolded** CFA mask ([`CfaDesc::fc_pre`]), where a
//! Bayer CFA keeps G1 and G2 apart as levels `1` and `3`. That is what makes
//! channel `3` of the working image a *valid green*: the first pass fills all
//! four channels, so `pix[ip[0] + 3]` and `color ^= 2` in the VNG average are
//! meaningful. Reading the folded mask instead would leave channel `3` empty and
//! the kernel would degenerate — see [`crate::cfa`] for the full contract.
//!
//! **Both masks appear in this one kernel.** Upstream's local `#define fc` reads
//! the unfolded `prefilters`, but `vng4interpolate_row_redblue` calls
//! `ri->ISGREEN`/`ri->ISBLUE`, which read the **folded** `filters` and are
//! three-valued. The two are therefore *not* interchangeable: the scatter, the
//! first pass's tables and the VNG `color` go through
//! [`CfaDesc::fc_pre`]/[`CfaDesc::fc_pre_i`], while
//! [`interpolate_row_redblue`] goes through
//! [`CfaDesc::is_green`]/[`CfaDesc::is_blue`]. Using one mask for the other
//! compiles cleanly and silently produces a wrong image.
//!
//! ## Fidelity notes
//!
//! * `TERMS` and `CHOOD` are byte-identical to upstream's tables (and to
//!   dcraw's), verified mechanically against the source files.
//! * Upstream's gradient parser consumes **at most two** gradient indices per
//!   term (`ip += 5` plus one optional `ip++`), whereas dcraw loops over all of
//!   them. That is only sound because no term carrying more than two gradient
//!   bits survives the two table filters — checked for all four Bayer orderings,
//!   and pinned by `no_surviving_term_needs_more_than_two_gradients`. Where
//!   upstream would then read past the end of its code buffer, this port simply
//!   ignores a third index.
//! * Upstream fills the working image and interpolates row `ii - 1` in the *same*
//!   iteration, a one-row software pipeline. It is not a data dependency: every
//!   read of the first pass targets a neighbour's **native** channel, which
//!   nothing in the pass ever writes. This port therefore scatters first and
//!   interpolates second, which is bit-identical and row-parallel — and the read
//!   then comes straight from the mosaic, which is what that native channel
//!   holds.
//! * The same argument removes the `firstRow`/`lastRow` fix-ups: they exist only
//!   to patch OpenMP chunk boundaries. Green is produced for rows/cols `2..h-2`
//!   and red/blue for `3..h-3`, with `border_interpolate(…, 3, …)` covering the
//!   three-pixel frame — exactly the set a single-threaded upstream run covers.
//! * `vng4` is for three-colour RGB CFAs: a sensor with a fourth colour needs a
//!   four-channel model this kernel does not have. Upstream enforces that with
//!   `if (FC(i, j) == 3)` (`vng4_demosaic_RT.cc:67-76`) and falls back to
//!   `igv_interpolate`. That guard **does** fire: `FC` reads the folded mask, and
//!   `set_prefilters()` folds only when `isBayer() && get_colors() == 3`
//!   (`rawimage.h:50-56`), so a four-colour CFA keeps its `3`. This port tests the
//!   same property through [`CfaDesc::has_fourth_colour`], which agrees with the
//!   literal test for every CFA the type can describe; IGV is not ported yet, so
//!   it reports the unsupported CFA instead of falling back.
//! * **The weight is an `int -> float` conversion, not a bit-cast.** Upstream
//!   writes `*reinterpret_cast<float*>(ip++) = 1 << weight;` — the *lvalue* type
//!   is `float`, so the int is **converted**, giving exactly `1.0` or `2.0`; the
//!   SSE comment about "saving int => float conversions" refers to the read-back
//!   (`reinterpret_cast<float*>(ip)[2]`). Reading it as a bit-cast instead would
//!   make the weight ~1e-45, collapsing `thold` to zero and reducing the VNG
//!   average to a handful of neighbours — a plausible-looking but wrong image.
//! * **No `-ffast-math` upstream** (`RTENGINE_CXX_FLAGS="-ftree-vectorize"`), so
//!   `0 * (1 / 0) = NaN` really can occur: the VNG average divides by `num`,
//!   which is 0 when no neighbour passes the threshold. Every estimate therefore
//!   goes through [`crate::math::max0`], which reproduces libstdc++'s
//!   `std::max(0.f, NaN) == 0.f` explicitly rather than relying on
//!   `f32::max`'s incidental agreement.
//! * The first-pass tables are indexed `(row & 15, col & 15)` and the VNG code
//!   table `(row & 7, col & 1)`; the sentinel loop terminator is reproduced as a
//!   fixed 32-entry list, since exactly 32 of the 64 `TERMS` rows survive the two
//!   filters for **every** class and **every** Bayer ordering (checked
//!   mechanically; see `no_surviving_term_needs_more_than_two_gradients` and
//!   `log/vng4_termcount.py`).

use rayon::prelude::*;

use crate::array2d::Array2D;
use crate::border::border_interpolate;
use crate::cfa::CfaDesc;
use crate::math::{max0, max_n, min_n};
use crate::{Error, Rgb};

/// The 64 `(y1, x1, y2, x2, weight, gradient-mask)` predicates of
/// `vng4_demosaic_RT.cc:79-102`, transcribed verbatim and checked against
/// `dcraw.c:4424-4447`.
const TERMS: [[i32; 6]; 64] = [
  [-2, -2, 0, -1, 0, 0x01],
  [-2, -2, 0, 0, 1, 0x01],
  [-2, -1, -1, 0, 0, 0x01],
  [-2, -1, 0, -1, 0, 0x02],
  [-2, -1, 0, 0, 0, 0x03],
  [-2, -1, 0, 1, 1, 0x01],
  [-2, 0, 0, -1, 0, 0x06],
  [-2, 0, 0, 0, 1, 0x02],
  [-2, 0, 0, 1, 0, 0x03],
  [-2, 1, -1, 0, 0, 0x04],
  [-2, 1, 0, -1, 1, 0x04],
  [-2, 1, 0, 0, 0, 0x06],
  [-2, 1, 0, 1, 0, 0x02],
  [-2, 2, 0, 0, 1, 0x04],
  [-2, 2, 0, 1, 0, 0x04],
  [-1, -2, -1, 0, 0, 0x80],
  [-1, -2, 0, -1, 0, 0x01],
  [-1, -2, 1, -1, 0, 0x01],
  [-1, -2, 1, 0, 1, 0x01],
  [-1, -1, -1, 1, 0, 0x88],
  [-1, -1, 1, -2, 0, 0x40],
  [-1, -1, 1, -1, 0, 0x22],
  [-1, -1, 1, 0, 0, 0x33],
  [-1, -1, 1, 1, 1, 0x11],
  [-1, 0, -1, 2, 0, 0x08],
  [-1, 0, 0, -1, 0, 0x44],
  [-1, 0, 0, 1, 0, 0x11],
  [-1, 0, 1, -2, 1, 0x40],
  [-1, 0, 1, -1, 0, 0x66],
  [-1, 0, 1, 0, 1, 0x22],
  [-1, 0, 1, 1, 0, 0x33],
  [-1, 0, 1, 2, 1, 0x10],
  [-1, 1, 1, -1, 1, 0x44],
  [-1, 1, 1, 0, 0, 0x66],
  [-1, 1, 1, 1, 0, 0x22],
  [-1, 1, 1, 2, 0, 0x10],
  [-1, 2, 0, 1, 0, 0x04],
  [-1, 2, 1, 0, 1, 0x04],
  [-1, 2, 1, 1, 0, 0x04],
  [0, -2, 0, 0, 1, 0x80],
  [0, -1, 0, 1, 1, 0x88],
  [0, -1, 1, -2, 0, 0x40],
  [0, -1, 1, 0, 0, 0x11],
  [0, -1, 2, -2, 0, 0x40],
  [0, -1, 2, -1, 0, 0x20],
  [0, -1, 2, 0, 0, 0x30],
  [0, -1, 2, 1, 1, 0x10],
  [0, 0, 0, 2, 1, 0x08],
  [0, 0, 2, -2, 1, 0x40],
  [0, 0, 2, -1, 0, 0x60],
  [0, 0, 2, 0, 1, 0x20],
  [0, 0, 2, 1, 0, 0x30],
  [0, 0, 2, 2, 1, 0x10],
  [0, 1, 1, 0, 0, 0x44],
  [0, 1, 1, 2, 0, 0x10],
  [0, 1, 2, -1, 1, 0x40],
  [0, 1, 2, 0, 0, 0x60],
  [0, 1, 2, 1, 0, 0x20],
  [0, 1, 2, 2, 0, 0x10],
  [1, -2, 1, 0, 0, 0x80],
  [1, -1, 1, 1, 0, 0x88],
  [1, 0, 1, 2, 0, 0x08],
  [1, 0, 2, -1, 0, 0x40],
  [1, 0, 2, 1, 0, 0x10],
];

/// The eight neighbour directions of `chood` (`vng4_demosaic_RT.cc:103`), read
/// as `(dy, dx)` pairs.
const CHOOD: [[i32; 2]; 8] = [[-1, -1], [-1, 0], [-1, 1], [0, 1], [1, 1], [1, 0], [1, -1], [0, -1]];

/// `prow` — the VNG code table is indexed `code[row & prow][col & pcol]`.
const PROW: usize = 7;
/// `pcol`.
const PCOL: usize = 1;

/// The three non-native channel levels, in the ascending order upstream appends
/// them to its code buffer.
const LEVELS: usize = 4;

/// One surviving gradient term, pre-resolved for a `(row & PROW, col & PCOL)`
/// class.
#[derive(Clone, Copy, Debug)]
struct Term {
  dy1: i32,
  dx1: i32,
  dy2: i32,
  dx2: i32,
  /// CFA level both offsets read (equal by the table's first filter).
  ch: usize,
  /// `1 << weight`, already a float. Upstream stores it through a `float`
  /// lvalue (`*reinterpret_cast<float*>(ip++) = 1 << weight`), which is an
  /// int→float **conversion** — `1.0` or `2.0`, never a reinterpreted bit pattern.
  weight: f32,
  g0: i32,
  /// Second gradient bucket, when the term has one.
  g1: Option<i32>,
}

/// One `chood` entry.
#[derive(Clone, Copy, Debug)]
struct Chood {
  dy: i32,
  dx: i32,
  /// Colour of the same-level pixel two steps away, when there is one —
  /// upstream's non-zero second word.
  far: Option<usize>,
}

/// The precomputed VNG terms plus `chood` for one `(row & PROW, col & PCOL)`
/// class.
#[derive(Clone, Debug)]
struct VngCode {
  terms: Vec<Term>,
  chood: [Option<Chood>; 8],
}

/// One first-pass cell: the eight neighbour offsets with their weights, and the
/// three channels to fill with their normalising reciprocals.
#[derive(Clone, Copy, Debug)]
struct LinShade {
  /// `(dy, dx, level)` per neighbour.
  pairs: [(i32, i32, usize); 8],
  /// `1 << shift` per neighbour — `2` for an orthogonal neighbour, `1` for a
  /// diagonal one (`shift = (y == 0) + (x == 0)`; the centre, `shift == 2`, is
  /// skipped). Upstream's 2:1, kept verbatim.
  mul: [f32; 8],
  /// The three levels this cell does not sample, ascending.
  channels: [usize; 3],
  /// `1 / (sum of the neighbourhood weight of that level)`.
  csum: [f32; 3],
}

/// `RawImageSource::vng4_demosaic(rawData, red, green, blue)`.
///
/// # Errors
/// [`Error::UnsupportedCfa`] for a CFA that is not a three-colour RGB Bayer
/// pattern, [`Error::Shape`] if the mosaic is too small.
pub fn bayer_vng4_demosaic(cfa: &CfaDesc, raw: &Array2D<f32>) -> Result<Rgb, Error> {
  let (w, h) = (raw.width(), raw.height());
  if w < 4 || h < 4 {
    return Err(Error::Shape(format!("bayer_vng4: mosaic too small: {w}x{h}")));
  }
  if cfa.has_fourth_colour() {
    // Upstream means to reject this too, but tests the folded mask and so never
    // fires (`vng4_demosaic_RT.cc:67-76`). Rejecting it here is the same intent
    // with a check that works.
    return Err(Error::UnsupportedCfa("bayer_vng4"));
  }

  let shade = build_lin_shade(cfa);
  let codes = build_vng_code(cfa);

  // The working image: four interleaved `f32` per pixel, i.e. upstream's
  // `float (*image)[4]`. The flat layout is load-bearing — `chood` addresses the
  // pixel two steps away as `(dy * width + dx) * 8`, which is `2 * pixel * 4`.
  let mut image = vec![0f32; 4 * w * h];

  // --- first pass, phase 1: scatter every sample into its native channel ------
  image.par_chunks_mut(4 * w).enumerate().for_each(|(row, row_px)| {
    for col in 0..w {
      row_px[col * 4 + cfa.fc_pre(row, col) as usize] = raw.at(row, col);
    }
  });

  // --- first pass, phase 2: linear interpolation of the other three levels ----
  //
  // Rows are independent: the write targets the three channels the pixel does
  // not sample, the read targets a neighbour's *native* channel. Upstream
  // pipelines this against the scatter above; running it afterwards is the same
  // arithmetic.
  image.par_chunks_mut(4 * w).enumerate().for_each(|(row, row_px)| {
    if row == 0 || row == h - 1 {
      return;
    }
    for col in 1..w - 1 {
      let sh = &shade[(row & 15) * 16 + (col & 15)];
      let mut sum = [0f32; LEVELS];
      for i in 0..8 {
        let (dy, dx, level) = sh.pairs[i];
        // Upstream reads `pix[(width * dy + dx) * 4 + level]`, which is exactly
        // this neighbour's native channel — i.e. this mosaic sample.
        sum[level] += raw.at((row as i32 + dy) as usize, (col as i32 + dx) as usize) * sh.mul[i];
      }
      for i in 0..3 {
        let c = sh.channels[i];
        row_px[col * 4 + c] = sum[c] * sh.csum[i];
      }
    }
  });

  let mut out = Rgb::new(w, h);

  // --- VNG pass: green everywhere, then red/blue from that green -------------
  //
  // Green is read by nothing else in this loop, so rows are independent.
  // Upstream's `row in 2..h-2` / `col in 2..w-2` window is kept exactly; the
  // two-pixel frame it leaves is `border_interpolate`'s job.
  out.green.par_rows_mut().enumerate().for_each(|(row, g_row)| {
    if row < 2 || row + 2 >= h {
      return;
    }
    for col in 2..w - 2 {
      let code = &codes[(row & PROW) * (PCOL + 1) + (col & PCOL)];
      // The **unfolded** level, so green is `1` *or* `3` and `color & 1` really
      // does mean "this pixel samples a green" — the whole point of VNG4.
      let color = cfa.fc_pre(row, col) as usize;
      let mut gval = [0f32; 8];

      for t in &code.terms {
        let a = image[flat_index(w, row, col, t.dy1, t.dx1) + t.ch];
        let b = image[flat_index(w, row, col, t.dy2, t.dx2) + t.ch];
        let diff = (a - b).abs() * t.weight;
        gval[t.g0 as usize] += diff;
        if let Some(g1) = t.g1 {
          gval[g1 as usize] += diff;
        }
      }

      let thold = min_n(&gval) + max_n(&gval) * 0.5;
      let greenval = image[flat_index(w, row, col, 0, 0) + color];
      let (mut sum0, mut sum1, mut num) = (0f32, 0f32, 0i32);

      if color & 1 != 0 {
        // Centre is a green: switch to the *other* green level.
        let other = color ^ 2;
        for (g, ch) in code.chood.iter().enumerate() {
          let Some(ch) = ch else { continue };
          if gval[g] <= thold {
            if let Some(far) = ch.far {
              sum0 += greenval + image[flat_index(w, row, col, ch.dy * 2, ch.dx * 2) + far];
            }
            sum1 += image[flat_index(w, row, col, ch.dy, ch.dx) + other];
            num += 1;
          }
        }
        sum0 *= 0.5;
      } else {
        // Centre is red or blue: average both greens at the neighbours.
        for (g, ch) in code.chood.iter().enumerate() {
          let Some(ch) = ch else { continue };
          if gval[g] <= thold {
            if let Some(far) = ch.far {
              sum0 += greenval + image[flat_index(w, row, col, ch.dy * 2, ch.dx * 2) + far];
            }
            let base = flat_index(w, row, col, ch.dy, ch.dx);
            sum1 += image[base + 1] + image[base + 3];
            num += 1;
          }
        }
      }

      g_row[col] = max0(greenval + (sum1 - sum0) / (2 * num) as f32);
    }
  });

  // Rows 3..h-4 are the ones a single-threaded upstream run reaches through its
  // row pipeline; 2 and h-3 fall inside `border_interpolate`'s frame.
  //
  // The green plane is finished by now and only read here, so it is borrowed
  // separately from the two planes being written — the three are disjoint.
  let green = &out.green;
  out
    .red
    .par_rows_mut()
    .zip(out.blue.par_rows_mut())
    .enumerate()
    .for_each(|(row, (red_row, blue_row))| {
      if row < 3 || row + 3 >= h {
        return;
      }
      let (pg, cg, ng) = (green.row(row - 1), green.row(row), green.row(row + 1));
      interpolate_row_redblue(cfa, raw, red_row, blue_row, pg, cg, ng, row, w);
    });

  border_interpolate(cfa, raw, &mut out.red, &mut out.green, &mut out.blue, 3);

  Ok(out)
}

/// Flat index of pixel `(row + dy, col + dx)` — *without* the channel, so it can
/// be reused with `+ ch`.
///
/// The caller guarantees the result is inside the image; the VNG window of two
/// pixels exactly matches the largest table offset.
#[inline(always)]
fn flat_index(width: usize, row: usize, col: usize, dy: i32, dx: i32) -> usize {
  (((row as i32 + dy) as usize) * width + (col as i32 + dx) as usize) * 4
}

/// `vng4interpolate_row_redblue` (`vng4_demosaic_RT.cc:34-56`).
///
/// On a blue row the two outputs swap, so the "first" output is always the
/// non-green colour that row does not sample.
#[allow(clippy::too_many_arguments)]
fn interpolate_row_redblue(
  cfa: &CfaDesc,
  raw: &Array2D<f32>,
  red_row: &mut [f32],
  blue_row: &mut [f32],
  pg: &[f32],
  cg: &[f32],
  ng: &[f32],
  i: usize,
  width: usize,
) {
  let (ar, ab) = if cfa.is_blue(i, 0) || cfa.is_blue(i, 1) { (blue_row, red_row) } else { (red_row, blue_row) };

  // RGRGR or GRGRGR line.
  for j in 3..width.saturating_sub(3) {
    if !cfa.is_green(i, j) {
      // Keep the sampled value, and cross-interpolate the opposite colour.
      ar[j] = raw.at(i, j);
      let mut rb = raw.at(i - 1, j - 1) - pg[j - 1] + raw.at(i + 1, j - 1) - ng[j - 1];
      rb += raw.at(i - 1, j + 1) - pg[j + 1] + raw.at(i + 1, j + 1) - ng[j + 1];
      ab[j] = max0(cg[j] + rb * 0.25);
    } else {
      // Linear other-colour-minus-green horizontally, and the opposite
      // vertically.
      ar[j] = max0(cg[j] + (raw.at(i, j - 1) - cg[j - 1] + raw.at(i, j + 1) - cg[j + 1]) / 2.0);
      ab[j] = max0(cg[j] + (raw.at(i - 1, j) - pg[j] + raw.at(i + 1, j) - ng[j]) / 2.0);
    }
  }
}

/// Build the 16x16 first-pass table (`lcode`/`mul`/`csum` of
/// `vng4_demosaic_RT.cc:124-155`), indexed `(row & 15) * 16 + (col & 15)`.
fn build_lin_shade(cfa: &CfaDesc) -> Vec<LinShade> {
  let mut table = Vec::with_capacity(256);
  for row in 0..16i32 {
    for col in 0..16i32 {
      let mut pairs = [(0i32, 0i32, 0usize); 8];
      let mut mul = [0f32; 8];
      let mut weight = [0i32; LEVELS];
      let mut n = 0usize;

      for y in -1..=1i32 {
        for x in -1..=1i32 {
          let shift = i32::from(y == 0) + i32::from(x == 0);
          if shift == 2 {
            continue;
          }
          let level = cfa.fc_pre_i(row + y, col + x) as usize;
          pairs[n] = (y, x, level);
          mul[n] = (1i32 << shift) as f32;
          weight[level] += 1 << shift;
          n += 1;
        }
      }
      debug_assert_eq!(n, 8, "3x3 minus centre is eight neighbours");

      let centre = cfa.fc_pre_i(row, col) as usize;
      let mut channels = [0usize; 3];
      let mut csum = [0f32; 3];
      let mut k = 0usize;
      for c in 0..LEVELS {
        if c == centre {
          continue;
        }
        channels[k] = c;
        // Exactly upstream, including the degenerate case: a level with no
        // neighbour divides by zero and yields `inf`, which then turns the
        // (zero) weighted sum into a NaN. For a Bayer CFA all four levels occur
        // as neighbours, so it cannot happen; keeping the division literal
        // documents what upstream does rather than quietly diverging.
        csum[k] = 1.0 / weight[c] as f32;
        k += 1;
      }
      debug_assert_eq!(k, 3, "four levels minus the centre leaves three");

      table.push(LinShade { pairs, mul, channels, csum });
    }
  }
  table
}

/// Build the VNG code table (`vng4_demosaic_RT.cc:235-289`), indexed
/// `(row & PROW) * (PCOL + 1) + (col & PCOL)`.
fn build_vng_code(cfa: &CfaDesc) -> Vec<VngCode> {
  let mut table = Vec::with_capacity((PROW + 1) * (PCOL + 1));

  for row in 0..=PROW as i32 {
    for col in 0..=PCOL as i32 {
      let mut terms = Vec::with_capacity(64);

      for t in TERMS {
        let [y1, x1, y2, x2, weight, grads] = t;
        let color = cfa.fc_pre_i(row + y1, col + x1);
        if cfa.fc_pre_i(row + y2, col + x2) != color {
          continue;
        }
        let diag = if cfa.fc_pre_i(row, col + 1) == color && cfa.fc_pre_i(row + 1, col) == color { 2 } else { 1 };
        if (y1 - y2).abs() == diag && (x1 - x2).abs() == diag {
          continue;
        }

        // Upstream writes every set bit and then closes the entry with -1; its
        // reader only consumes the first two (see the module note).
        let mut bits = (0..8i32).filter(|&g| grads & (1 << g) != 0);
        let g0 = bits.next().expect("no TERMS row has an empty gradient mask");
        let g1 = bits.next();

        terms.push(Term {
          dy1: y1,
          dx1: x1,
          dy2: y2,
          dx2: x2,
          ch: color as usize,
          weight: (1i32 << weight) as f32,
          g0,
          g1,
        });
      }

      let color = cfa.fc_pre_i(row, col);
      let mut chood = [None; 8];
      for (g, d) in CHOOD.iter().enumerate() {
        let (y, x) = (d[0], d[1]);
        // The same level two pixels away — `(width * y + x) * 8 + color` in the
        // flat image, i.e. pixel `(row + 2y, col + 2x)`.
        let far = if cfa.fc_pre_i(row + y, col + x) != color && cfa.fc_pre_i(row + y * 2, col + x * 2) == color {
          Some(color as usize)
        } else {
          None
        };
        chood[g] = Some(Chood { dy: y, dx: x, far });
      }

      table.push(VngCode { terms, chood });
    }
  }

  table
}

#[cfg(test)]
mod tests {
  use super::*;

  fn rggb() -> CfaDesc {
    CfaDesc::bayer_from_2x2([[0, 1], [1, 2]])
  }

  /// A flat grey field must survive both stages unchanged, frame included.
  /// This is the test that catches a wrong CFA level mapping: the VNG average
  /// only cancels to `greenval` when the two greens are distinct channels and
  /// both are filled by the first pass.
  #[test]
  fn flat_field_is_flat() {
    let (w, h) = (16usize, 16usize);
    let cfa = rggb();
    let raw = Array2D::filled(w, h, 0.25);

    let rgb = bayer_vng4_demosaic(&cfa, &raw).expect("demosaic");
    for i in 0..h {
      for j in 0..w {
        assert!((rgb.red.at(i, j) - 0.25).abs() < 1e-5, "R at {i},{j} = {}", rgb.red.at(i, j));
        assert!((rgb.green.at(i, j) - 0.25).abs() < 1e-5, "G at {i},{j} = {}", rgb.green.at(i, j));
        assert!((rgb.blue.at(i, j) - 0.25).abs() < 1e-5, "B at {i},{j} = {}", rgb.blue.at(i, j));
      }
    }
  }

  /// The first-pass table must fill **exactly** the three levels a cell does not
  /// sample, and normalise each by the total neighbourhood weight of that level.
  ///
  /// A wrong CFA level mapping surfaces here at once: `channels` stops being the
  /// complement of the centre's level, or a level ends up with no neighbour and
  /// upstream's `1.f / sum[c]` divides by zero (the degenerate case the builder
  /// deliberately keeps literal).
  #[test]
  fn lin_shade_fills_exactly_the_three_unsampled_levels() {
    let cfa = rggb();
    let shade = build_lin_shade(&cfa);
    assert_eq!(shade.len(), 256);

    for row in 0..16i32 {
      for col in 0..16i32 {
        let sh = &shade[(row as usize & 15) * 16 + (col as usize & 15)];
        let centre = cfa.fc_pre_i(row, col);
        let cell = format!("cell ({row},{col})");

        // `channels` is the ascending complement of the centre's own level.
        let expected: Vec<usize> = (0..LEVELS).filter(|&c| c as u32 != centre).collect();
        assert_eq!(sh.channels.as_slice(), expected.as_slice(), "{cell} channels");

        // `mul` is `1 << shift`: four orthogonal neighbours at 2, four diagonals
        // at 1 (upstream's 2:1, not a 4:1 bilinear kernel).
        let orth = (0..8).filter(|&n| sh.mul[n] == 2.0).count();
        let diag = (0..8).filter(|&n| sh.mul[n] == 1.0).count();
        assert_eq!((orth, diag), (4, 4), "{cell} neighbour weights");

        // The three non-centre levels are *all* reachable in a Bayer 3x3, so no
        // `csum` may be infinite; and it must be the reciprocal of that level's
        // total weight.
        let mut seen = 0usize;
        for (k, &c) in sh.channels.iter().enumerate() {
          assert!(c as u32 != centre, "{cell}: the sampled level must be skipped");
          let wsum: f32 = (0..8).filter(|&n| sh.pairs[n].2 == c).map(|n| sh.mul[n]).sum();
          assert!(wsum > 0.0, "{cell}: level {c} has no neighbour");
          assert!((sh.csum[k] - 1.0 / wsum).abs() < 1e-6, "{cell}: level {c} csum");
          seen += 1;
        }
        assert_eq!(seen, 3, "{cell}: four levels minus the centre leaves three");
      }
    }
  }

  /// A mosaic that is an exact linear ramp must come back as that same ramp on
  /// all three planes, over the whole interior.
  ///
  /// This is the strongest end-to-end check available without a golden image:
  /// every weighted mean in the first pass reproduces a linear function exactly
  /// (symmetric weights), VNG's `sum1 - sum0` cancels identically on a ramp, and
  /// `interpolate_row_redblue`'s difference terms cancel too. So any stage that
  /// mis-indexes a neighbour, picks the wrong channel, or drops a shading weight
  /// lands outside tolerance. The flat-field test above cannot catch most of
  /// those, because every candidate value there is equal.
  ///
  /// The frame ring is deliberately excluded. `border_interpolate` fills it with
  /// a **clamped** mean over the in-bounds neighbours, and its edge tests are
  /// intentionally asymmetric, so it does not reproduce a ramp: green at (0, 0)
  /// comes out as `(ramp(0, 1) + ramp(1, 0)) / 2 = 0.2575`, not `0.25`. Red and
  /// blue are pure kernel output for rows `3..h-4` and columns `3..w-4`, and
  /// green's VNG window is wider still, so that rectangle is the region where
  /// every plane is untouched by the border pass.
  #[test]
  fn linear_ramp_is_reproduced_exactly() {
    let (w, h) = (16usize, 16usize);
    let cfa = rggb();
    let ramp = |i: usize, j: usize| 0.25 + 0.01 * i as f32 + 0.005 * j as f32;

    let mut raw = Array2D::new(w, h);
    for i in 0..h {
      for j in 0..w {
        raw.set(i, j, ramp(i, j));
      }
    }

    let rgb = bayer_vng4_demosaic(&cfa, &raw).expect("demosaic");
    for i in 3..h - 3 {
      for j in 3..w - 3 {
        let want = ramp(i, j);
        for (name, plane) in [("R", &rgb.red), ("G", &rgb.green), ("B", &rgb.blue)] {
          let got = plane.at(i, j);
          assert!((got - want).abs() < 1e-5, "{name} at {i},{j}: {got} vs {want}");
        }
      }
    }
  }

  /// Upstream's gradient parser consumes **at most two** gradient indices per
  /// term (`ip += 5`, plus one conditional `ip++`), whereas dcraw loops over all
  /// of them. That shortcut is only sound if no *surviving* term carries a third
  /// bit — which is what this checks, for every class and every Bayer ordering.
  ///
  /// It pins the term count too: 64 `TERMS` rows go in and exactly **32** survive
  /// the two filters, for every class, so the `Vec` is always the same size and
  /// upstream's 1280-byte-per-class buffer cannot overflow (32*6 + 8*2 + 1 = 209
  /// of its 320 `int32` slots). A wrong CFA level mapping moves this number.
  #[test]
  fn no_surviving_term_needs_more_than_two_gradients() {
    for (name, pattern) in [
      ("RGGB", [[0, 1], [1, 2]]),
      ("BGGR", [[2, 1], [1, 0]]),
      ("GRBG", [[1, 0], [2, 1]]),
      ("GBRG", [[1, 2], [0, 1]]),
    ] {
      let cfa = CfaDesc::bayer_from_2x2(pattern);
      let mut widest = 0u32;

      for row in 0..=PROW as i32 {
        for col in 0..=PCOL as i32 {
          // Re-run upstream's two filters over the raw table, so the third (and
          // any further) gradient bit is still visible here even though `Term`
          // only stores two.
          let mut surviving = 0usize;
          for [y1, x1, y2, x2, _weight, grads] in TERMS {
            let color = cfa.fc_pre_i(row + y1, col + x1);
            if cfa.fc_pre_i(row + y2, col + x2) != color {
              continue;
            }
            let diag = if cfa.fc_pre_i(row, col + 1) == color && cfa.fc_pre_i(row + 1, col) == color { 2 } else { 1 };
            if (y1 - y2).abs() == diag && (x1 - x2).abs() == diag {
              continue;
            }
            surviving += 1;
            widest = widest.max(grads.count_ones());
          }
          assert_eq!(surviving, 32, "{name}: class ({row},{col}) term count");
        }
      }

      assert!(widest <= 2, "{name}: a surviving term needs {widest} gradient bits");
    }
  }

  /// The precomputed tables have upstream's shape and hold indices the kernels
  /// can use without a bounds check.
  #[test]
  fn tables_have_the_upstream_shape() {
    assert_eq!(TERMS.len(), 64);
    assert_eq!(CHOOD.len(), 8);

    let shade = build_lin_shade(&rggb());
    assert_eq!(shade.len(), 256);

    let codes = build_vng_code(&rggb());
    assert_eq!(codes.len(), (PROW + 1) * (PCOL + 1));
    for (idx, code) in codes.iter().enumerate() {
      assert_eq!(code.terms.len(), 32, "code {idx}");
      // All eight directions are always present; only the `far` word may be 0.
      assert_eq!(code.chood.len(), 8);
      assert!(code.chood.iter().all(Option::is_some), "code {idx}");
      for t in &code.terms {
        // Gradient buckets stay inside `gval[0..8]`, and the offset level inside
        // the four-channel working pixel.
        assert!(t.g0 < 8, "code {idx}: gradient index");
        assert!(t.g1.map_or(true, |g| g < 8), "code {idx}: second gradient index");
        assert!(t.ch < LEVELS, "code {idx}: level");
      }
    }
  }
}
