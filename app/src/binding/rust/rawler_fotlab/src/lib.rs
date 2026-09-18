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
//!   * `develop`        — render call: decode + demosaic + calibrate into a **linear**
//!     RGB image (`LinearImage`), with no sRGB/BT.709 gamma applied. The pixel→display
//!     transform (gamma) is owned by the client (`FOTLAB-RAWLER-000003`).
//!   * `develop_to_png` — same develop pipeline as `develop`, but the `LinearImage` is
//!     encoded straight to PNG by `bound::linearimage_to_png` (still linear, no gamma).
//!     This is what Studio renders after the user picks a demosaic algorithm from the
//!     bottom-bar menu.
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

use decode::decode_to_rawimage;
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
/// Orchestrates the three stages of the canonical RAW intermediate spec
/// (`FOTLAB-FOTRAW-000001`): decode → `RawImage`, project → `FotRaw`, encode →
/// PNG. The encode ([`bound::fotraw_to_png`]) is a preview only — it applies no
/// demosaic / white-balance / colour / gamma, so the result is the undeveloped
/// sensor dump shown as luminance. Made only after the route resolved to the raw
/// path, which is why it takes the bytes directly instead of re-running
/// identification. Any rawler panic is caught and reported as
/// `RawlerFotlabError::Decode` so the FFI call always returns rather than aborts.
#[uniffi::export]
pub fn decode_to_png(raw: &[u8]) -> Result<Vec<u8>, RawlerFotlabError> {
    if raw.is_empty() {
        return Err(RawlerFotlabError::Decode("empty input".to_string()));
    }
    panic::catch_unwind(AssertUnwindSafe(|| {
        let image = decode_to_rawimage(raw)?;
        let pixel = intermediate::rawimage_to_fotraw(image)?;
        bound::fotraw_to_png(&pixel).map_err(RawlerFotlabError::Decode)
    }))
    .unwrap_or_else(|_| {
        Err(RawlerFotlabError::Decode(
            "rawler panicked during decode".to_string(),
        ))
    })
}

/// Render call — develop the already-identified RAW and encode it straight to PNG.
///
/// Runs the full develop pipeline ([`develop`]: decode + black/white scaling +
/// demosaic + crop + calibrate) into a **linear** RGB image, then hands it to
/// [`bound::linearimage_to_png`], which writes it to PNG **without** applying the
/// sRGB/BT.709 gamma — the client owns the display transform
/// (`FOTLAB-RAWLER-000003`). This is the image Studio renders after the user picks
/// a demosaic algorithm from the bottom-bar menu. Re-runs the whole pipeline
/// (decode included) on every call, exactly like [`develop`]. Any rawler panic is
/// caught and reported as `RawlerFotlabError::Decode`.
#[uniffi::export]
pub fn develop_to_png(raw: &[u8], params: DevelopParams) -> Result<Vec<u8>, RawlerFotlabError> {
    if raw.is_empty() {
        return Err(RawlerFotlabError::Decode("empty input".to_string()));
    }
    panic::catch_unwind(AssertUnwindSafe(|| {
        let image = develop::develop(raw, params)?;
        bound::linearimage_to_png(&image).map_err(RawlerFotlabError::Decode)
    }))
    .unwrap_or_else(|_| {
        Err(RawlerFotlabError::Decode(
            "rawler panicked during develop".to_string(),
        ))
    })
}

uniffi::setup_scaffolding!();
