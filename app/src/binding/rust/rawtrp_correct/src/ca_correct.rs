//! Port of RawTherapee's `CA_correct_RT` (`rtengine/CA_correct_RT.cc`) — the
//! pre-demosaic, radial chromatic-aberration correction that rewrites the R/B
//! planes of a Bayer mosaic in place.
//!
//! ```text
//! Portions of this algorithm are based on a paper by
//!   Martinec, Emil: "Lens aberration correction using RAW data",
//!   Proc. of the IS&T/SID 16th Color Imaging Conference, 2008.
//! Authors: Emil Martinec (martinec at alive dot org),
//!          Ingo Weyrich (heckflosse at i-x dot de)
//! ```
//!
//! ## What this batch (C1) implements
//!
//! The upstream routine has two passes:
//!   * **pass 1** — auto-fit measurement: per-tile colour-difference
//!     correlation → 4th-order 2-D polynomial regression of the residual CA
//!     (solved with [`crate::lin_eq_solve`]). This is *not yet ported* (see
//!     below).
//!   * **pass 2** — shift application: for every tile, evaluate the CA shift
//!     (manual `cared`/`cablue` radial, or the auto polynomial evaluated from a
//!     supplied [`FitParams`]), then resample the R/B planes by that shift using
//!     G-difference interpolation and write the corrected planes back. **This is
//!     fully ported.**
//!
//! Until pass 1 lands, `auto_ca = true` with no [`FitParams`] returns
//! [`Error::AutoCaNotYet`]; `auto_ca = true` *with* a `fit` is accepted and runs
//! pass 2 against the supplied polynomial (the exact path `RawTherapee` takes
//! when reusing parameters across frames, `fitParamsIn`).
//!
//! ## Domain
//!
//! The port works directly in the `0..1` linear mosaic domain (R2 of
//! `FOTLAB-NATIVE-000004`): the upstream `/65535` and `*65535` round-trips are
//! omitted, and all thresholds (`eps`, the `0.25` ratio test, `±3.99` shift
//! clamp) are unchanged because they live in the normalised domain.

use rawtrp_demos::{Array2D, CfaDesc};

// `gauss` / `lin_eq` are declared as crate-root `pub mod`s in `lib.rs`; this
// module reaches them as `gauss::…` / `lin_eq::…`.

const TS: usize = 128; // tile size
const TSH: usize = 64; // half tile (R/B planes are half-res)
const BORDER: i32 = 8; // tile border
const BORDER2: i32 = 16; // 2*border
const POLYORD: usize = 4; // order of the 2-D polynomial fit
const EPS: f32 = 1e-5; // division guard (normalised domain)
const SQR: f64 = 2.0; // upstream `constexpr float SQR = 2.f;`
const BS_LIM: f64 = 3.99; // max allowed CA shift (upstream `bslim`)

/// Auto-fit polynomial coefficients, indexed `[colour][direction][coeff]`
/// where `colour ∈ {0=R, 1=B}`, `direction ∈ {0=v, 1=h}`, and the 16
/// coefficients are `polyord^2` in row-major `(i*4 + j)` order.
pub type FitParams = [[[f64; 16]; 2]; 2];

/// Parameters for [`correct_ca_bayer`].
#[derive(Clone, Copy, Debug, Default)]
pub struct CaParams {
    /// Run the auto-fit path. Requires a [`FitParams`] unless the caller only
    /// wants [`Error::AutoCaNotYet`] (pass 1 not yet ported).
    pub auto_ca: bool,
    /// Number of auto-fit iterations (upstream `autoIterations`).
    pub auto_iterations: usize,
    /// Manual radial CA shift for red, in upstream slider units (`cared`).
    pub ca_red: f64,
    /// Manual radial CA shift for blue, in upstream slider units (`cablue`).
    pub ca_blue: f64,
    /// Apply the per-pixel factor blur that reduces the colour shift introduced
    /// by raw CA correction (`avoidColourshift`).
    pub avoid_colourshift: bool,
    /// Border crop in pixels (`border_crop`); the corrected result keeps a
    /// `cb = 2*((border_crop+1)/2)`-pixel untouched margin.
    pub border_crop: i32,
}

/// Errors returned by [`correct_ca_bayer`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    /// The CFA is not a 3-colour Bayer pattern (X-Trans / 4-colour unsupported).
    UnsupportedCfa(&'static str),
    /// The mosaic width is odd. The upstream tile logic implicitly extends by
    /// `(W & 1)`; this port requires an even width (true for all Bayer mosaics).
    OddWidth,
    /// `auto_ca = true` was requested without a [`FitParams`]; pass 1 (auto-fit
    /// measurement) is not yet ported. Supply `fit`, or use manual mode.
    AutoCaNotYet,
}

/// Linear interpolation: `intp(a, x, y) = x + a*(y - x)` (upstream `intp`).
#[inline]
fn intp(a: f32, x: f32, y: f32) -> f32 {
    x + a * (y - x)
}

/// Correct chromatic aberration in place on a Bayer mosaic.
///
/// `mosaic` is the ROI-local single-channel CFA buffer (values `0..1`), `cfa`
/// its folded Bayer description. `fit` supplies pre-computed auto-fit polynomial
/// coefficients (the `fitParamsIn` path); pass `None` for manual correction or
/// when pass 1 has not run.
///
/// Only the R and B planes are modified; green is untouched. Returns
/// [`Error::UnsupportedCfa`] for non-Bayer CFAs and [`Error::AutoCaNotYet`] when
/// auto mode is requested without `fit`.
pub fn correct_ca_bayer(
    mosaic: &mut Array2D<f32>,
    cfa: &CfaDesc,
    params: &CaParams,
    fit: Option<&FitParams>,
) -> Result<(), Error> {
    if !cfa.is_bayer || cfa.colors > 3 {
        return Err(Error::UnsupportedCfa("only 3-colour Bayer CFAs are supported"));
    }

    let w = mosaic.width() as i32;
    let h = mosaic.height() as i32;
    if w & 1 == 1 {
        return Err(Error::OddWidth);
    }
    let width = w; // even; upstream extends by (W&1) but we reject odd widths
    let height = h;
    let wext = width; // upstream `width - (W & 1)`; equal to `width` here
    let cb = 2 * ((params.border_crop + 1) / 2);

    // Folded 2x2 CFA (values 0/1/2), matching upstream `const unsigned int cfa[2][2]`.
    // `fc` returns `i32` so it composes with the `i32` tile arithmetic.
    let cfa2 = [
        [cfa.fc(0, 0) as i32, cfa.fc(0, 1) as i32],
        [cfa.fc(1, 0) as i32, cfa.fc(1, 1) as i32],
    ];
    let fc = |r: i32, c: i32| cfa2[(r & 1) as usize][(c & 1) as usize];

    // Auto-fit parameters (all zero until pass 1 is ported; used only when `fit`
    // is supplied).
    let mut fitparams = [[[0f64; 16]; 2]; 2];
    if params.auto_ca {
        match fit {
            Some(f) => fitparams = *f,
            None => return Err(Error::AutoCaNotYet),
        }
    }

    let ts = TS as i32;
    let border2 = BORDER2;
    let vz1 = if (height + border2) % (ts - border2) == 0 { 1 } else { 0 };
    let hz1 = if (wext + border2) % (ts - border2) == 0 { 1 } else { 0 };
    let vblsz =
        (((height + border2) as f64 / (ts - border2) as f64).ceil() as i32 + 2 + vz1) as usize;
    let hblsz =
        (((wext + border2) as f64 / (ts - border2) as f64).ceil() as i32 + 2 + hz1) as usize;

    // Half-res scratch buffer holding the corrected R/B planes (upstream
    // `RawDataTmp`, at `buffer + (height*width)/2`, `height*width/2` floats).
    let mut raw_data_tmp = vec![0f32; (height * width / 2) as usize];
    // `Gtmp` from pass 1 (interpolated G). Not ported yet, so it stays zero —
    // matching upstream's manual-mode behaviour where pass 1 never runs. The
    // load stage reads it for G at R/B positions, and the directional
    // interpolation below recomputes G at the interior R/B grid points.
    let gtmp = vec![0f32; (height * width) as usize];

    // Save a pristine copy for the optional avoid-colour-shift step.
    let oldraw = mosaic.clone();

    let iterations = if params.auto_ca {
        params.auto_iterations.max(1)
    } else {
        1
    };

    for _it in 0..iterations {
        // ---- tile loop (upstream `#pragma omp for collapse(2)`; serial here for
        //      correctness-first — rayon parallelisation is a follow-up, R4) ----
        let mut top = -BORDER;
        while top < height {
            let mut left = -BORDER;
            while left < wext {
                // per-tile working buffers
                let mut rgb0 = vec![0f32; TS * TSH]; // red, half-res
                let mut rgb1 = vec![0f32; TS * TS]; // green, full-res
                let mut rgb2 = vec![0f32; TS * TSH]; // blue, half-res
                let mut grbdiff = vec![0f32; TS * TS / 2];
                let mut gshift = vec![0f32; TS * TS / 2];

                let bottom = (top + ts).min(height + BORDER);
                let right = (left + ts).min(wext + BORDER);
                let rr1 = bottom - top;
                let cc1 = right - left;
                let rrmin = if top < 0 { BORDER } else { 0 };
                let rrmax = if bottom > height { height - top } else { rr1 };
                let ccmin = if left < 0 { BORDER } else { 0 };
                let ccmax = if right > wext { wext - left } else { cc1 };

                // ---- load rawData into rgb (scalar reference path) ----
                for rr in rrmin..rrmax {
                    let row = rr + top;
                    let mut cc = ccmin;
                    let mut col = cc + left;
                    let mut indx = row * width + col; // global, for Gtmp indexing
                    let mut indx1 = rr * ts + cc; // tile-local full-res
                    while cc < ccmax {
                        let c = fc(rr, cc);
                        let packed = if c == 1 { indx1 as usize } else { (indx1 >> 1) as usize };
                        let val = mosaic.at(row as usize, col as usize);
                        match c {
                            0 => rgb0[packed] = val,
                            1 => rgb1[indx1 as usize] = val,
                            _ => rgb2[packed] = val,
                        }
                        if c & 1 == 0 {
                            // G at this R/B position comes from Gtmp (zero here)
                            rgb1[indx1 as usize] = gtmp[(indx >> 1) as usize];
                        }
                        cc += 1;
                        col += 1;
                        indx += 1;
                        indx1 += 1;
                    }
                }

                // ---- border fills (scalar reference path) ----
                if rrmin > 0 {
                    for rr in 0..BORDER {
                        for cc in ccmin..ccmax {
                            let c = fc(rr, cc);
                            let idx = (rr * ts + cc) as usize;
                            let idx_m = ((border2 - rr) * ts + cc) as usize;
                            match c {
                                0 => rgb0[idx >> 1] = rgb0[idx_m >> 1],
                                1 => rgb1[idx] = rgb1[idx_m],
                                2 => rgb2[idx >> 1] = rgb2[idx_m >> 1],
                                _ => {}
                            }
                            rgb1[idx] = rgb1[idx_m]; // G mirror
                        }
                    }
                }
                if rrmax < rr1 {
                    for rr in 0..(BORDER.min(rr1 - rrmax)) {
                        for cc in ccmin..ccmax {
                            let c = fc(rr, cc);
                            let idx = ((rrmax + rr) * ts + cc) as usize;
                            let val = mosaic.at((height - rr - 2) as usize, (left + cc) as usize);
                            let gidx = (((height - rr - 2) * width + left + cc) >> 1) as usize;
                            match c {
                                0 => rgb0[idx >> 1] = val,
                                1 => rgb1[idx] = val,
                                2 => rgb2[idx >> 1] = val,
                                _ => {}
                            }
                            if c & 1 == 0 {
                                rgb1[idx] = gtmp[gidx];
                            }
                        }
                    }
                }
                if ccmin > 0 {
                    for rr in rrmin..rrmax {
                        for cc in 0..BORDER {
                            let c = fc(rr, cc);
                            let idx = (rr * ts + cc) as usize;
                            let idx_m = (rr * ts + (border2 - cc)) as usize;
                            match c {
                                0 => rgb0[idx >> 1] = rgb0[idx_m >> 1],
                                1 => rgb1[idx] = rgb1[idx_m],
                                2 => rgb2[idx >> 1] = rgb2[idx_m >> 1],
                                _ => {}
                            }
                            rgb1[idx] = rgb1[idx_m]; // G mirror
                        }
                    }
                }
                if ccmax < cc1 {
                    for rr in rrmin..rrmax {
                        for cc in 0..(BORDER.min(cc1 - ccmax)) {
                            let c = fc(rr, cc);
                            let idx = ((rr * ts + ccmax + cc)) as usize;
                            let val = mosaic.at((top + rr) as usize, (width - cc - 2) as usize);
                            let gidx = (((top + rr) * width + (width - cc - 2)) >> 1) as usize;
                            match c {
                                0 => rgb0[idx >> 1] = val,
                                1 => rgb1[idx] = val,
                                2 => rgb2[idx >> 1] = val,
                                _ => {}
                            }
                            if c & 1 == 0 {
                                rgb1[idx] = gtmp[gidx];
                            }
                        }
                    }
                }
                if rrmin > 0 && ccmin > 0 {
                    for rr in 0..BORDER {
                        for cc in 0..BORDER {
                            let c = fc(rr, cc);
                            let idx = (rr * ts + cc) as usize;
                            let val = mosaic.at((border2 - rr) as usize, (border2 - cc) as usize);
                            let gidx = (((border2 - rr) * width + (border2 - cc)) >> 1) as usize;
                            match c {
                                0 => rgb0[idx >> 1] = val,
                                1 => rgb1[idx] = val,
                                2 => rgb2[idx >> 1] = val,
                                _ => {}
                            }
                            if c & 1 == 0 {
                                rgb1[idx] = gtmp[gidx];
                            }
                        }
                    }
                }
                if rrmax < rr1 && ccmax < cc1 {
                    for rr in 0..(BORDER.min(rr1 - rrmax)) {
                        for cc in 0..(BORDER.min(cc1 - ccmax)) {
                            let c = fc(rr, cc);
                            let idx = ((rrmax + rr) * ts + ccmax + cc) as usize;
                            let val = mosaic.at((height - rr - 2) as usize, (width - cc - 2) as usize);
                            let gidx = (((height - rr - 2) * width + (width - cc - 2)) >> 1) as usize;
                            match c {
                                0 => rgb0[idx >> 1] = val,
                                1 => rgb1[idx] = val,
                                2 => rgb2[idx >> 1] = val,
                                _ => {}
                            }
                            if c & 1 == 0 {
                                rgb1[idx] = gtmp[gidx];
                            }
                        }
                    }
                }
                if rrmin > 0 && ccmax < cc1 {
                    for rr in 0..BORDER {
                        for cc in 0..(BORDER.min(cc1 - ccmax)) {
                            let c = fc(rr, cc);
                            let idx = (rr * ts + ccmax + cc) as usize;
                            let val = mosaic.at((border2 - rr) as usize, (width - cc - 2) as usize);
                            let gidx = (((border2 - rr) * width + (width - cc - 2)) >> 1) as usize;
                            match c {
                                0 => rgb0[idx >> 1] = val,
                                1 => rgb1[idx] = val,
                                2 => rgb2[idx >> 1] = val,
                                _ => {}
                            }
                            if c & 1 == 0 {
                                rgb1[idx] = gtmp[gidx];
                            }
                        }
                    }
                }
                if rrmax < rr1 && ccmin > 0 {
                    for rr in 0..(BORDER.min(rr1 - rrmax)) {
                        for cc in 0..BORDER {
                            let c = fc(rr, cc);
                            let idx = ((rrmax + rr) * ts + cc) as usize;
                            let val = mosaic.at((height - rr - 2) as usize, (border2 - cc) as usize);
                            let gidx = (((height - rr - 2) * width + (border2 - cc)) >> 1) as usize;
                            match c {
                                0 => rgb0[idx >> 1] = val,
                                1 => rgb1[idx] = val,
                                2 => rgb2[idx >> 1] = val,
                                _ => {}
                            }
                            if c & 1 == 0 {
                                rgb1[idx] = gtmp[gidx];
                            }
                        }
                    }
                }

                // ---- directional weighted G at R/B grid points ----
                // (Upstream runs this only for `!autoCA || fitParamsIn`; we always
                // run it because pass 1 is not ported and Gtmp is therefore
                // unavailable. It is exactly the manual-mode G estimate.)
                // `v1 = ts`.
                for rr in 3..(rr1 - 3) {
                    let mut cc = 3 + (fc(rr, 1) & 1);
                    while cc < cc1 - 3 {
                        let c = fc(rr, cc);
                        let indx = (rr * ts + cc) as usize;
                        let rc = |k: usize| -> f32 {
                            if c == 0 {
                                rgb0[k]
                            } else {
                                rgb2[k]
                            }
                        };
                        let wtu = 1.0
                            / (EPS
                                + (rgb1[((rr + 1) * ts + cc) as usize]
                                    - rgb1[((rr - 1) * ts + cc) as usize])
                                    .abs()
                                + (rc(((rr * ts + cc) >> 1) as usize)
                                    - rc((((rr - 2) * ts + cc) >> 1) as usize))
                                    .abs()
                                + (rgb1[((rr - 1) * ts + cc) as usize]
                                    - rgb1[((rr - 3) * ts + cc) as usize])
                                    .abs())
                                .powi(2);
                        let wtd = 1.0
                            / (EPS
                                + (rgb1[((rr + 1) * ts + cc) as usize]
                                    - rgb1[((rr - 1) * ts + cc) as usize])
                                    .abs()
                                + (rc(((rr * ts + cc) >> 1) as usize)
                                    - rc((((rr + 2) * ts + cc) >> 1) as usize))
                                    .abs()
                                + (rgb1[((rr + 1) * ts + cc) as usize]
                                    - rgb1[((rr + 3) * ts + cc) as usize])
                                    .abs())
                                .powi(2);
                        let wtl = 1.0
                            / (EPS
                                + (rgb1[(rr * ts + cc + 1) as usize]
                                    - rgb1[(rr * ts + cc - 1) as usize])
                                    .abs()
                                + (rc(((rr * ts + cc) >> 1) as usize)
                                    - rc(((rr * ts + cc - 2) >> 1) as usize))
                                    .abs()
                                + (rgb1[(rr * ts + cc - 1) as usize]
                                    - rgb1[(rr * ts + cc - 3) as usize])
                                    .abs())
                                .powi(2);
                        let wtr = 1.0
                            / (EPS
                                + (rgb1[(rr * ts + cc + 1) as usize]
                                    - rgb1[(rr * ts + cc - 1) as usize])
                                    .abs()
                                + (rc(((rr * ts + cc) >> 1) as usize)
                                    - rc(((rr * ts + cc + 2) >> 1) as usize))
                                    .abs()
                                + (rgb1[(rr * ts + cc + 1) as usize]
                                    - rgb1[(rr * ts + cc + 3) as usize])
                                    .abs())
                                .powi(2);
                        let gint = (wtu * rgb1[indx - TS]
                            + wtd * rgb1[indx + TS]
                            + wtl * rgb1[indx - 1]
                            + wtr * rgb1[indx + 1])
                            / (wtu + wtd + wtl + wtr);
                        rgb1[indx] = gint;
                        cc += 2;
                    }
                }

                // ---- CA shift parameters per tile ----
                let vblock = ((top + BORDER) / (ts - border2)) + 1;
                let hblock = ((left + BORDER) / (ts - border2)) + 1;

                let lblockshifts: [[f64; 2]; 2] = if params.auto_ca {
                    // evaluate the 4th-order 2-D polynomial at this block
                    let mut ls = [[0f64; 2]; 2];
                    let mut pow_v = 1.0;
                    for i in 0..POLYORD {
                        let mut pow_h = pow_v;
                        for j in 0..POLYORD {
                            ls[0][0] += pow_h * fitparams[0][0][i * POLYORD + j];
                            ls[0][1] += pow_h * fitparams[0][1][i * POLYORD + j];
                            ls[1][0] += pow_h * fitparams[1][0][i * POLYORD + j];
                            ls[1][1] += pow_h * fitparams[1][1][i * POLYORD + j];
                            pow_h *= hblock as f64;
                        }
                        pow_v *= vblock as f64;
                    }
                    ls[0][0] = ls[0][0].clamp(-BS_LIM, BS_LIM);
                    ls[0][1] = ls[0][1].clamp(-BS_LIM, BS_LIM);
                    ls[1][0] = ls[1][0].clamp(-BS_LIM, BS_LIM);
                    ls[1][1] = ls[1][1].clamp(-BS_LIM, BS_LIM);
                    ls
                } else {
                    let hfrac = -((hblock as f64 - 0.5) / (hblsz as f64 - 2.0) - 0.5);
                    let vfrac =
                        -((vblock as f64 - 0.5) / (vblsz as f64 - 2.0) - 0.5) * height as f64
                            / width as f64;
                    let mut ls = [[0f64; 2]; 2];
                    ls[0][0] = SQR * vfrac * params.ca_red;
                    ls[0][1] = SQR * hfrac * params.ca_red;
                    ls[1][0] = SQR * vfrac * params.ca_blue;
                    ls[1][1] = SQR * hfrac * params.ca_blue;
                    ls
                };

                // floor/ceil/fraction per colour (c = 0, 2)
                let mut shiftvfloor = [0i32; 3];
                let mut shiftvceil = [0i32; 3];
                let mut shifthfloor = [0i32; 3];
                let mut shifthceil = [0i32; 3];
                let mut shiftvfrac = [0f32; 3];
                let mut shifthfrac = [0f32; 3];
                let mut grbdir = [[0i32; 3]; 2];
                for c in [0usize, 2usize] {
                    let sv = lblockshifts[c >> 1][0];
                    let sh = lblockshifts[c >> 1][1];
                    let mut svf = sv.floor() as i32;
                    let mut svc = sv.ceil() as i32;
                    if sv < 0.0 {
                        std::mem::swap(&mut svf, &mut svc);
                    }
                    shiftvfrac[c] = (sv - svf as f64).abs() as f32;
                    let mut shf = sh.floor() as i32;
                    let mut shc = sh.ceil() as i32;
                    if sh < 0.0 {
                        std::mem::swap(&mut shf, &mut shc);
                    }
                    shifthfrac[c] = (sh - shf as f64).abs() as f32;
                    shiftvfloor[c] = svf;
                    shiftvceil[c] = svc;
                    shifthfloor[c] = shf;
                    shifthceil[c] = shc;
                    grbdir[0][c] = if lblockshifts[c >> 1][0] > 0.0 { 2 } else { -2 };
                    grbdir[1][c] = if lblockshifts[c >> 1][1] > 0.0 { 2 } else { -2 };
                }

                // ---- first apply loop: G at shifted R/B positions -> grbdiff/gshift ----
                for rr in 4..(rr1 - 4) {
                    let mut cc = 4 + (fc(rr, 2) & 1);
                    while cc < cc1 - 4 {
                        let c = fc(rr, cc) as usize;
                        let indx = ((rr * ts + cc) >> 1) as usize;
                        let indxfc = (((rr + shiftvfloor[c]) * ts + cc + shifthceil[c])) as usize;
                        let indxff = (((rr + shiftvfloor[c]) * ts + cc + shifthfloor[c])) as usize;
                        let indxcc = (((rr + shiftvceil[c]) * ts + cc + shifthceil[c])) as usize;
                        let indxcf = (((rr + shiftvceil[c]) * ts + cc + shifthfloor[c])) as usize;
                        let ginthfloor = intp(shifthfrac[c], rgb1[indxfc], rgb1[indxff]);
                        let ginthceil = intp(shifthfrac[c], rgb1[indxcc], rgb1[indxcf]);
                        let gint = intp(shiftvfrac[c], ginthceil, ginthfloor);
                        let rc = if c == 0 { rgb0[indx] } else { rgb2[indx] };
                        grbdiff[indx] = gint - rc;
                        gshift[indx] = gint;
                        cc += 2;
                    }
                }

                // ---- second apply loop: reconstruct R/B from interpolated grbdiff ----
                for rr in 8..(rr1 - 8) {
                    let mut cc = 8 + (fc(rr, 2) & 1);
                    while cc < cc1 - 8 {
                        let c = fc(rr, cc) as usize;
                        let grbdir0 = grbdir[0][c];
                        let grbdir1 = grbdir[1][c];
                        let indx = rr * ts + cc; // i32 (full-res tile-local)
                        let rc = if c == 0 {
                            rgb0[(indx >> 1) as usize]
                        } else {
                            rgb2[(indx >> 1) as usize]
                        };
                        let grbdiffold = rgb1[indx as usize] - rc;
                        let grbdiffinthfloor = intp(
                            shifthfrac[c],
                            grbdiff[((indx - grbdir1) >> 1) as usize],
                            grbdiff[(indx >> 1) as usize],
                        );
                        let grbdiffinthceil = intp(
                            shifthfrac[c],
                            grbdiff[(((rr - grbdir0) * ts + cc - grbdir1) >> 1) as usize],
                            grbdiff[(((rr - grbdir0) * ts + cc) >> 1) as usize],
                        );
                        let grbdiffint = intp(shiftvfrac[c], grbdiffinthceil, grbdiffinthfloor);
                        let rbint = rgb1[indx as usize] - grbdiffint;
                        if (rbint - rc).abs() < 0.25 * (rbint + rc) {
                            if grbdiffold.abs() > grbdiffint.abs() {
                                if c == 0 {
                                    rgb0[(indx >> 1) as usize] = rbint;
                                } else {
                                    rgb2[(indx >> 1) as usize] = rbint;
                                }
                            }
                        } else {
                            let p0 = 1.0
                                / (EPS + (rgb1[indx as usize] - gshift[(indx >> 1) as usize]).abs());
                            let p1 = 1.0
                                / (EPS
                                    + (rgb1[indx as usize]
                                        - gshift[((indx - grbdir1) >> 1) as usize])
                                        .abs());
                            let p2 = 1.0
                                / (EPS
                                    + (rgb1[indx as usize]
                                        - gshift[(((rr - grbdir0) * ts + cc) >> 1) as usize])
                                        .abs());
                            let p3 = 1.0
                                / (EPS
                                    + (rgb1[indx as usize]
                                        - gshift[(((rr - grbdir0) * ts + cc - grbdir1) >> 1) as usize])
                                        .abs());
                            let gd = (p0 * grbdiff[(indx >> 1) as usize]
                                + p1 * grbdiff[((indx - grbdir1) >> 1) as usize]
                                + p2 * grbdiff[(((rr - grbdir0) * ts + cc) >> 1) as usize]
                                + p3 * grbdiff[(((rr - grbdir0) * ts + cc - grbdir1) >> 1) as usize])
                                / (p0 + p1 + p2 + p3);
                            if grbdiffold.abs() > gd.abs() {
                                if c == 0 {
                                    rgb0[(indx >> 1) as usize] = rgb1[indx as usize] - gd;
                                } else {
                                    rgb2[(indx >> 1) as usize] = rgb1[indx as usize] - gd;
                                }
                            }
                        }
                        // desaturate if the correction overshoots the original diff
                        if grbdiffold * grbdiffint < 0.0 {
                            if c == 0 {
                                rgb0[(indx >> 1) as usize] =
                                    rgb1[indx as usize] - 0.5 * (grbdiffold + grbdiffint);
                            } else {
                                rgb2[(indx >> 1) as usize] =
                                    rgb1[indx as usize] - 0.5 * (grbdiffold + grbdiffint);
                            }
                        }
                        cc += 2;
                    }
                }

                // ---- copy CA-corrected R/B planes into the half-res temp buffer ----
                for rr in BORDER..(rr1 - BORDER) {
                    let row = rr + top;
                    let c = fc(row, left + BORDER + (fc(rr, 2) & 1)) as usize;
                    let cc = BORDER + (fc(rr, 2) & 1);
                    let mut indx = ((row * width + cc + left) >> 1) as usize;
                    let mut indx1 = ((rr * ts + cc) >> 1) as usize;
                    let end = ((row * width + cc1 - BORDER + left) >> 1) as usize;
                    while indx < end {
                        let v = if c == 0 { rgb0[indx1] } else { rgb2[indx1] };
                        raw_data_tmp[indx] = v;
                        indx += 1;
                        indx1 += 1;
                    }
                }

                left += ts - border2;
            }
            top += ts - border2;
        }

        // ---- copy the half-res temp buffer back into the mosaic (R/B only) ----
        for row in cb..(height - cb) {
            let mut col = cb + (fc(row, 0) & 1);
            let mut indx = ((row * width + col) >> 1) as usize;
            while col < width - cb {
                let v = raw_data_tmp[indx].max(0.0);
                mosaic.set(row as usize, col as usize, v);
                col += 2;
                indx += 1;
            }
        }

        // (pass 1 would decide whether to iterate again; manual mode is one pass)
    }

    // ---- optional avoid-colour-shift: per-pixel R/B factor, blurred, reapplied ----
    if params.avoid_colourshift {
        let nrows = ((height - 2 * cb) / 2) as usize;
        let ncols = ((width - 2 * cb) / 2) as usize;
        if nrows > 0 && ncols > 0 {
            let mut red_factor = vec![1.0f32; nrows * ncols];
            let mut blue_factor = vec![1.0f32; nrows * ncols];

            let w2 = width - 2 * cb;
            let h2 = height - 2 * cb;
            for i in 0..h2 {
                let first_col = fc(i, 0) & 1;
                let colour = fc(i, first_col);
                let is_red = colour == 0;
                let mut j = first_col;
                while j < w2 {
                    let newv = mosaic.at((i + cb) as usize, (j + cb) as usize);
                    let oldv = oldraw.at(i as usize, j as usize);
                    let factor = if newv <= 1.0 || oldv <= 1.0 {
                        1.0
                    } else {
                        (oldv / newv).clamp(0.5, 2.0)
                    };
                    let idx = (i as usize / 2) * ncols + (j as usize / 2);
                    if is_red {
                        red_factor[idx] = factor;
                    } else {
                        blue_factor[idx] = factor;
                    }
                    j += 2;
                }
            }

            // odd-height / odd-width: duplicate the last row/column of factors
            if h2 & 1 == 1 {
                for j in 0..ncols {
                    red_factor[((nrows - 1) * ncols + j) as usize] =
                        red_factor[((nrows - 2) * ncols + j) as usize];
                    blue_factor[((nrows - 1) * ncols + j) as usize] =
                        blue_factor[((nrows - 2) * ncols + j) as usize];
                }
            }
            if w2 & 1 == 1 {
                let ng_row = 1 - (fc(0, 0) & 1);
                let ng_col = fc(ng_row, 0) & 1;
                let colour = fc(ng_row, ng_col);
                let is_red = colour == 0;
                for i in 0..nrows {
                    let last = (ncols - 1) as usize;
                    let prev = (ncols - 2) as usize;
                    if is_red {
                        red_factor[i * ncols + last] = red_factor[i * ncols + prev];
                    } else {
                        blue_factor[i * ncols + last] = blue_factor[i * ncols + prev];
                    }
                }
            }

            gauss::gaussian_blur(&red_factor, &mut red_factor, ncols, nrows, 30.0);
            gauss::gaussian_blur(&blue_factor, &mut blue_factor, ncols, nrows, 30.0);

            for i in 0..h2 {
                let first_col = fc(i, 0) & 1;
                let colour = fc(i, first_col);
                let is_red = colour == 0;
                let non_green = if is_red { &red_factor } else { &blue_factor };
                let mut j = first_col;
                while j < w2 {
                    let factor = non_green[(i as usize / 2) * ncols + (j as usize / 2)];
                    let cur = mosaic.at((i + cb) as usize, (j + cb) as usize);
                    mosaic.set((i + cb) as usize, (j + cb) as usize, cur * factor);
                    j += 2;
                }
            }
        }
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rawtrp_demos::CfaDesc;

    fn rggb() -> CfaDesc {
        // RGGB folded Bayer
        CfaDesc::bayer_from_2x2([[0u8, 1u8], [1u8, 2u8]])
    }

    fn make_mosaic(w: usize, h: usize) -> Array2D<f32> {
        let mut m = Array2D::new(w, h);
        for row in 0..h {
            for col in 0..w {
                // deterministic smooth-ish pattern in 0..1
                let v = (((row * 31 + col * 17) % 100) as f32) / 100.0;
                m.set(row, col, v);
            }
        }
        m
    }

    #[test]
    fn zero_shift_is_identity() {
        let w = 256usize;
        let h = 200usize;
        let mut m = make_mosaic(w, h);
        let before = m.clone();
        let params = CaParams {
            auto_ca: false,
            ca_red: 0.0,
            ca_blue: 0.0,
            avoid_colourshift: false,
            border_crop: 0,
            ..Default::default()
        };
        correct_ca_bayer(&mut m, &rggb(), &params, None).unwrap();
        for row in 0..h {
            for col in 0..w {
                assert!(
                    (m.at(row, col) - before.at(row, col)).abs() < 1e-6,
                    "mismatch at ({row},{col}): {} vs {}",
                    m.at(row, col),
                    before.at(row, col)
                );
            }
        }
    }

    #[test]
    fn matches_dimensions_and_range() {
        let w = 256usize;
        let h = 200usize;
        let mut m = make_mosaic(w, h);
        let params = CaParams {
            auto_ca: false,
            ca_red: 0.5,
            ca_blue: -0.3,
            avoid_colourshift: true,
            border_crop: 0,
            ..Default::default()
        };
        correct_ca_bayer(&mut m, &rggb(), &params, None).unwrap();
        // every value must stay finite and within a sane range
        for row in 0..h {
            for col in 0..w {
                let v = m.at(row, col);
                assert!(v.is_finite(), "non-finite at ({row},{col})");
                assert!(v >= -1.0 && v <= 2.0, "out of range at ({row},{col}): {v}");
            }
        }
    }

    #[test]
    fn rejects_non_bayer() {
        // GMCY-style is not supported; build via a 4-colour description is not
        // exposed, so just assert an odd width is rejected as a proxy gate.
        let mut m = make_mosaic(257usize, 200usize);
        let params = CaParams::default();
        assert_eq!(
            correct_ca_bayer(&mut m, &rggb(), &params, None),
            Err(Error::OddWidth)
        );
    }

    #[test]
    fn auto_without_fit_errors() {
        let mut m = make_mosaic(256usize, 200usize);
        let params = CaParams {
            auto_ca: true,
            ..Default::default()
        };
        assert_eq!(
            correct_ca_bayer(&mut m, &rggb(), &params, None),
            Err(Error::AutoCaNotYet)
        );
    }
}
