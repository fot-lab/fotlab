//! Demosaic glue — the FFI entry point that runs a RawTherapee demosaic algorithm
//! over a caller-supplied CFA mosaic, and the research notes behind the design.
//!
//! # Research findings (why the glue looks the way it does)
//!
//! The original question was: "RawTherapee's `demosaic()` is a member method, but
//! the algorithms it calls might be free functions — can't we just wrap a free
//! function?" After reading the engine source, the answer is **no, you cannot wrap
//! a free function** — and here is the evidence chain:
//!
//! 1. **The algorithms are members, not free functions.** Every demosaic kernel
//!    in rtengine is a `RawImageSource` member method:
//!    `amaze_demosaic_RT` (amaze_demosaic_RT.cc), `rcd_demosaic` (rcd_demosaic.cc),
//!    `vng4_demosaic`, `lmmse_interpolate_omp`, `igv_interpolate` (demosaic_algos.cc),
//!    `xtrans_interpolate`, `fast_xtrans_interpolate` (xtrans_demosaic.cc), `dcb_demosaic`,
//!    and so on. There are a few `static`/`anonymous-namespace` helpers, but they
//!    still take `const RawImage *ri` as their first argument and read the CFA from
//!    it — they are not standalone pixel kernels you can call with just a buffer.
//!
//! 2. **The CFA pattern comes from `ri`, not from `this`.** `RawImageSource::FC()`
//!    (rawimagesource.cc) is literally `return ri->FC(row, col);`, and
//!    `RawImage::FC` / `RawImage::XTRANSFC` read the `filters` / `xtrans` members
//!    declared on `DCraw` (dcraw.h). So *every* algorithm discovers the Bayer/X-Trans
//!    colour at (row,col) through the `RawImage` (`ri`). Several algorithms
//!    (rcd, igv, xtrans_interpolate, dcb) go further and read the pixel planes
//!    through the **members** `this->rawData / red / green / blue`. You therefore
//!    cannot sidestep constructing a `RawImageSource` + `RawImage`.
//!
//! 3. **`RawImageSource` is `final`** (rawimagesource.h), so subclassing to "feed
//!    data" is out. The minimal, clean hook is the public member we added:
//!    `RawImageSource::demosaic_external` (the hook that must be present in the
//!    external/RawTherapee submodule worktree — applied manually, no patch shipped in
//!    this repo; see README.md). It has access to the protected
//!    algorithm methods and members, sets up the needed state, dispatches to the
//!    chosen algorithm, and interleaves the result.
//!
//! 4. **`array2D<float>` cannot be zero-copy wrapped into a member.** The CFA buffer
//!    the caller owns could be wrapped with `ARRAY2D_BYREFERENCE` (array2d.h:63) for
//!    a *local* view — but `array2D`'s copy ctor/assign (array2d.h:147-164) point
//!    `rows` into an empty `buffer` when the source was by-reference, silently
//!    dropping the wrapped pointer. So assigning a by-reference view into the
//!    `this->rawData` member would yield a dangling/empty plane. We instead **copy**
//!    the CFA into an OWNED `this->rawData` (one copy — exactly what RT's own
//!    `copyOriginalPixels` does). The zero-copy win is still real for callers that
//!    pass the CFA by slice to `rt_demosaic`: no extra per-pixel duplication happens
//!    on the Rust side; only the single engine-internal copy remains.
//!
//! 5. **`initialGain` must be set to 1.0.** The ctor leaves `initialGain = 0.0`
//!    (rawimagesource.cc), but `amaze_demosaic_RT` computes `clip_pt = 1.0 /
//!    initialGain` — a divide-by-zero at 0.0. Our CFA is already black/white scaled
//!    into 0..1, so gain 1.0 is correct. (`border` is set to 0 for the same reason:
//!    the algorithm runs over the full (0,0,W,H) window.)
//!
//! # Data contract
//!
//! *Input* (`cfa`): single-channel CFA mosaic, row-major, length `w*h`, **linear**,
//! already black/white scaled into 0..1, **not white-balanced**. Orientation must
//! match the `filters` bitmask (incl. any Fuji rotation the sensor implies). This is
//! precisely the state our `develop.rs` reaches *before* its own demosaic step.
//!
//! *Output* (`LinearImage.rgb`): interleaved linear RGB, length `w*h*3,` **still in
//! camera/CFA space — NOT white-balanced, no sRGB/BT.709 gamma.** That matches the
//! `RawlerImageDeveloped` contract rawler_fotlab produces, so the downstream pipeline can
//! apply white balance + the cam→ProPhoto(D50) matrix uniformly regardless of which
//! demosaic backend produced the image (recall: rawalchemy's Log pipeline wants
//! Linear ProPhoto D50 input; dnglab's `develop` outputs sRGB D65 — both are
//! downstream concerns, not this crate's).
//!
//! # Licensing
//!
//! RawTherapee is GPL v3. This crate links `librtengine`; the combined binary is a
//! GPL v3 derivative. If fotlab must avoid GPL propagation, isolate the shim in a
//! separate process and call it over files/pipes.

use crate::rt_demosaic_safe;
use crate::RtDemosaicError;

/// Selectable RawTherapee demosaic algorithm.
///
/// `method` codes are passed as `i32` to the engine and MUST stay in sync with
/// `rt_demosaic` in `cxx/rt_demosaic_shim.h` and `RawImageSource::demosaic_external`
/// in the RawTherapee submodule hooks (applied manually — no patch shipped):
///   Bayer:      0 AMAZE, 1 RCD, 2 VNG4, 3 LMMSE, 4 IGV
///   X-Trans:    5 ONE_PASS, 6 THREE_PASS (best), 7 FAST
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum RtDemosaicAlgorithm {
    /// AMAZE — RT's highest-quality adaptive homogenity-directed algorithm (Bayer).
    #[default]
    Amaze,
    /// RCD — Ratio-Corrected Dual-mode (Bayer), fast and very good.
    Rcd,
    /// VNG4 — variable-number-gradients (Bayer).
    Vng4,
    /// LMMSE — 2-iteration least-mean-squares (Bayer).
    Lmmse,
    /// IGV — improved green equilibration (Bayer).
    Igv,
    /// X-Trans single pass (Fuji).
    XtransOnePass,
    /// X-Trans three pass — best quality for Fuji X-Trans.
    XtransThreePass,
    /// X-Trans fast.
    XtransFast,
}

impl RtDemosaicAlgorithm {
    /// Numeric code handed to the C++ engine (see header `rt_demosaic_shim.h`).
    fn code(self) -> i32 {
        match self {
            RtDemosaicAlgorithm::Amaze => 0,
            RtDemosaicAlgorithm::Rcd => 1,
            RtDemosaicAlgorithm::Vng4 => 2,
            RtDemosaicAlgorithm::Lmmse => 3,
            RtDemosaicAlgorithm::Igv => 4,
            RtDemosaicAlgorithm::XtransOnePass => 5,
            RtDemosaicAlgorithm::XtransThreePass => 6,
            RtDemosaicAlgorithm::XtransFast => 7,
        }
    }

    /// Whether this algorithm expects an X-Trans (vs Bayer) CFA pattern.
    fn is_xtrans(self) -> bool {
        matches!(
            self,
            RtDemosaicAlgorithm::XtransOnePass
                | RtDemosaicAlgorithm::XtransThreePass
                | RtDemosaicAlgorithm::XtransFast
        )
    }
}

/// The CFA pattern describing the `cfa` buffer handed to [`demosaic_cfa`].
///
/// * Bayer: supply `filters` (dcraw's 4x4 bitmask; e.g. the classic RGGB/`0x16161616`
///   family) and leave `xtrans` empty.
/// * X-Trans: set `is_xtrans = true`, `filters = 9` (RawImage::isXtrans() returns
///   `filters == 9`), and provide the 6x6 `xtrans` pattern (row-major, 36 entries).
#[derive(Debug, Clone, Copy, Default)]
pub struct CfaPattern {
    /// dcraw 4x4 Bayer bitmask, or `9` for X-Trans.
    pub filters: u32,
    /// True => X-Trans mosaic; `xtrans` is then required.
    pub is_xtrans: bool,
    /// X-Trans 6x6 pattern, row-major (36 entries). Ignored for Bayer.
    pub xtrans: [u8; 36],
}

/// The product of the demosaic: linear RGB, **before** any white balance / gamma.
///
/// Mirrors `rawler_fotlab::develop::RawlerImageDeveloped` so the downstream develop pipeline
/// can consume either backend identically.
#[derive(Debug, Clone)]
pub struct LinearImage {
    pub width: u32,
    pub height: u32,
    /// Row-major linear RGB float, length `width * height * 3`.
    pub rgb: Vec<f32>,
}

/// Run a RawTherapee demosaic algorithm over a caller-supplied CFA mosaic.
///
/// This is the FFI entry point Kotlin / `develop.rs` calls. It validates the inputs,
/// allocates the output buffer, and delegates to the C++ shim
/// (`rt_demosaic`, via [`crate::rt_demosaic_safe`], which carries the panic/crash
/// boundary). The whole call runs inside `catch_unwind` so a Rust or C++ failure
/// degrades to `Err` instead of aborting the process (FOTLAB-CRASH-000001 pattern).
///
/// # Arguments
/// * `cfa` — single-channel CFA mosaic, row-major, length `width*height`, linear and
///   black/white scaled into 0..1, **not** white-balanced.
/// * `width`, `height` — mosaic dimensions.
/// * `pattern` — the CFA pattern (Bayer bitmask or X-Trans 6x6).
/// * `algorithm` — which RT demosaic kernel to run.
///
/// # Returns
/// A [`LinearImage`] (interleaved linear RGB, still camera-space / not WB'd) on
/// success, or [`RtDemosaicError`] describing the failure.
pub fn demosaic_cfa(
    cfa: &[f32],
    width: u32,
    height: u32,
    pattern: CfaPattern,
    algorithm: RtDemosaicAlgorithm,
) -> Result<LinearImage, RtDemosaicError> {
    // --- input validation (cheap, before touching the engine) ---------------
    if width == 0 || height == 0 {
        return Err(RtDemosaicError::InvalidInput("zero-sized mosaic".into()));
    }
    let w = width as usize;
    let h = height as usize;
    if cfa.len() < w * h {
        return Err(RtDemosaicError::InvalidInput(format!(
            "cfa length {} < required {} ({}x{})",
            cfa.len(),
            w * h,
            width,
            height
        )));
    }
    if algorithm.is_xtrans() != pattern.is_xtrans {
        return Err(RtDemosaicError::InvalidInput(format!(
            "algorithm {:?} X-Trans flag ({}) does not match pattern.is_xtrans ({})",
            algorithm,
            algorithm.is_xtrans(),
            pattern.is_xtrans
        )));
    }
    if pattern.is_xtrans && pattern.filters != 9 {
        // RawImage::isXtrans() returns `filters == 9`; the engine branches on it.
        return Err(RtDemosaicError::InvalidInput(
            "X-Trans pattern must set filters = 9".into(),
        ));
    }
    if pattern.is_xtrans && pattern.xtrans.iter().all(|&v| v == 0) {
        return Err(RtDemosaicError::InvalidInput(
            "X-Trans requested but xtrans pattern is empty".into(),
        ));
    }

    // --- allocate the interleaved output (w*h*3) ----------------------------
    let mut rgb = vec![0.0f32; w * h * 3];

    // --- call the C++ engine (panic-safe) -----------------------------------
    // `rt_demosaic_safe` already returns `Result<(), RtDemosaicError>`, so a plain
    // `?` propagates it. (It wraps both C++ exceptions and Rust panics.)
    rt_demosaic_safe(
        algorithm.code(),
        cfa,
        width as i32,
        height as i32,
        pattern.filters,
        pattern.is_xtrans,
        &pattern.xtrans,
        &mut rgb,
    )?;

    Ok(LinearImage {
        width,
        height,
        rgb,
    })
}
