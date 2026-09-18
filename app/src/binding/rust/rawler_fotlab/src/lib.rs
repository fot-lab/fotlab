//! rawler_fotlab — FotLab's first-party native binding over the upstream `rawler` crate.
//!
//! This is the only native library we ship, and the only one that carries a FotLab name:
//! **`librawler_fotlab.so`**. Upstream `rawler` is linked in from `external/dnglab/rawler`
//! as its original crate and keeps its own name (`FOTLAB-NATIVE-000001` R4 — upstream is
//! read-only; we never edit or re-publish it).
//!
//! It exposes four UniFFI functions, matching the native calls of the raw render
//! path (`FOTLAB-STUDIO-000001` R8):
//!   * `identify`       — call #1: format identification only, never pixel decode.
//!   * `decode_to_png`  — call #2: decode the already-identified RAW and encode a
//!     **grayscale raw preview** PNG (no demosaic / calibrate) via `bound::fotraw_to_png`.
//!     This is what Studio shows on first open, before any demosaic choice.
//!   * `develop`        — *editing* branch: decode + demosaic + calibrate into a
//!     **linear ProPhoto D50** RGB image (`RawlerImageDeveloped`), unclipped, for the
//!     rawalchemy pipeline. Wide gamut; negatives and >1 survive
//!     (`FOTLAB-RAWLER-000005`). No gamma — ProPhoto is a linear editing space.
//!   * `develop_to_png` — *presentation* branch: same develop pipeline, but built in
//!     sRGB D65 and then finished into a display-ready PNG by
//!     `bound::rawlerimagedeveloped_to_png`, which applies the sRGB transfer function (gamma)
//!     and clips to [0,1]. This is what Studio renders after the user picks a
//!     demosaic algorithm from the bottom-bar menu.
//!   * `develop_and_grade` — *grading* branch (behind the `rawalchemy` feature, on by
//!     default): same develop as above, but the linear ProPhoto-D50 buffer is handed
//!     to the rawalchemy grading engine in the same call and the **graded** float
//!     buffer comes back. Which stages run is chosen entirely by `GradeParams`, whose
//!     optional fields deliberately expose upstream's full parameter surface
//!     (`FOTLAB-RAWLER-000006`). The resident object additionally exposes
//!     `develop_and_grade_to_png` / `..._at_kelvin`, which quantize the graded
//!     buffer straight to a display PNG (no transfer function), and
//!     `supported_log_spaces` lists the log curves the Studio LOG chooser offers.
//!     Studio's grade bar drives the PNG variants; changing a develop parameter
//!     (demosaic / exposure / WB) re-renders the sRGB fork above, changing a grade
//!     parameter (Boost / LOG / LUT) re-renders the graded PNG fork.
//!
//! # Pipeline split (`FOTLAB-FOTRAW-000001`)
//!
//! `decode_to_png` used to be a single monolithic function. It is now a thin
//! orchestrator over the three stages of the canonical RAW intermediate spec:
//!
//! 1. [`decode::decode_to_rawimage`] — decode the RAW into rawler's `RawImage`.
//! 2. [`intermediate::rawimage_to_fotraw`] — project `RawImage` into our canonical
//!    IR `FotRaw` (pure pixel buffer + three tag namespaces).
//! 3. [`bound::fotraw_to_png`] — bit-shift preview encode `FotRaw` → PNG.
//!
//! The `FotRaw` IR does **not** cross any FFI boundary yet; it is an in-Rust
//! intermediate and only PNG bytes are returned to Kotlin. `intermediate.rs` is the
//! Rust implementation of `rules/STRUCT/detail/FOTLAB-FOTRAW-000001.md`.
//!
//! # Crash hardening (FOTLAB-CRASH-000001)
//!
//! Rawler's decoders call `panic!` / `unreachable!` / index out of bounds on input they
//! do not expect (truncated files, formats they half-support, non-RAW bytes probed by the
//! sniffer). A Rust panic that unwinds across the `extern "C"` FFI frame is **undefined
//! behaviour** and the runtime aborts the whole process (SIGABRT). Kotlin's
//! `runCatching` only catches JVM `Throwable`, so it *cannot* catch this — which is exactly
//! why every image, RAW or PNG, used to crash the app the moment rawler was called.
//!
//! The fix is to wrap every rawler entry point in [`std::panic::catch_unwind`] so a panic
//! is contained inside Rust and turned into a normal return value (a `None` / `Err`) that
//! crosses the FFI boundary safely. This is FFI-mechanism-independent: JNI or a hand-rolled
//! C ABI would have crashed identically. UniFFI is therefore kept; only the panic boundary
//! is hardened. The whole split pipeline runs inside one `catch_unwind` boundary.

use std::panic::{self, AssertUnwindSafe};

use rawler::decoders::RawDecodeParams;
use rawler::rawsource::RawSource;

mod bound;
mod calibrate;
mod decode;
mod demosaic;
mod develop;
mod intermediate;
mod loaded;
mod wb;

use develop::DevelopParams;

/// Error type surfaced to Kotlin over UniFFI.
///
/// `thiserror` provides the `Display` impl UniFFI needs to carry the message across the
/// FFI boundary; the variant itself becomes a Kotlin `sealed class` case.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum RawlerFotlabError {
    /// rawler does not recognize the input as a camera RAW it supports.
    #[error("unsupported input: {0}")]
    Unsupported(String),
    /// rawler recognized the input but failed while decoding it (incl. a caught panic).
    #[error("decode failed: {0}")]
    Decode(String),
}

/// Call #1 — identification only.
///
/// Returns `make/model` (the format label the app routes on) when rawler recognizes the
/// bytes, else `None`. Never decodes pixels — this is the cheap probe that runs in
/// parallel with the Coil-side sniffer inside `FormatSniffer.sniff`.
///
/// Identification is done with `rawler::get_decoder` + `Decoder::raw_metadata`, NOT
/// `rawler::decode_dummy`. `decode_dummy` runs the *full* decoder — it still parses and
/// walks the compressed pixel data to size its output buffer — so it requires the entire
/// RAW on hand and fails when fed the 1 MiB sniff header `StudioEngine` provides. That
/// failure was silent: the probe returned `None`, so large RAWs (Nikon NEF, Canon CR2)
/// were wrongly demoted to the Coil branch. `get_decoder` only matches the
/// container/format and `raw_metadata` reads the EXIF block at the file head, so both
/// settle from the header alone (`FOTLAB-STUDIO-000001` R8).
///
/// The rawler work is wrapped in `catch_unwind`: a panic (e.g. on malformed/non-RAW input)
/// degrades to `None` instead of aborting the process. Empty input is rejected outright to
/// avoid any unwrap-panic inside `RawSource::new_from_slice`.
#[uniffi::export]
pub fn identify(raw: &[u8]) -> Option<String> {
    if raw.is_empty() {
        return None;
    }
    panic::catch_unwind(AssertUnwindSafe(|| {
        let src = RawSource::new_from_slice(raw);
        let decoder = rawler::get_decoder(&src).ok()?;
        let meta = decoder.raw_metadata(&src, &RawDecodeParams::default()).ok()?;
        Some(format!("{}/{}", meta.make, meta.model))
    }))
    .unwrap_or(None)
}

/// Call #2 — decode the already-identified RAW to a **grayscale raw preview** PNG.
///
/// Now delegates to [`loaded::RawlerImageLoaded`]: it decodes once and returns the
/// resident object, then previews from the cached decode (`FOTLAB-RAWLER-000004`).
/// The stateless free function keeps its signature so existing callers/tests are
/// unaffected; the cached-decode path is what `StudioEngine` drives.
#[uniffi::export]
pub fn decode_to_png(raw: &[u8]) -> Result<Vec<u8>, RawlerFotlabError> {
    let loaded = loaded::decode_rawler_image(raw)?;
    loaded.preview_png()
}

/// Render call — develop the already-identified RAW and encode it straight to PNG.
///
/// Now delegates to [`loaded::RawlerImageLoaded`]: decodes once and develops from
/// the cached decode (`FOTLAB-RAWLER-000004`). The stateless free function keeps
/// its signature for existing callers; `StudioEngine` drives the cached path.
#[uniffi::export]
pub fn develop_to_png(raw: &[u8], params: DevelopParams) -> Result<Vec<u8>, RawlerFotlabError> {
    let loaded = loaded::decode_rawler_image(raw)?;
    loaded.develop_to_png(params)
}

/// Names of the log spaces the rawalchemy grading engine accepts (`"F-Log"`,
/// `"S-Log3"`, `"Arri LogC4"`, …), sorted for a stable Studio LOG menu. The list
/// is single-sourced from upstream's `LOG_SPACES` map — the glue only
/// enumerates its keys (`FOTLAB-RAWLER-000006`). Gated on the `rawalchemy`
/// feature (on by default); without the feature the export is not compiled.
#[cfg(feature = "rawalchemy")]
#[uniffi::export]
pub fn supported_log_spaces() -> Vec<String> {
    let mut spaces = rawalchemy_fotlab::log_spaces();
    spaces.sort();
    spaces
}

uniffi::setup_scaffolding!();
