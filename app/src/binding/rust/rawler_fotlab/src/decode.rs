//! Stage 2 of the raw render path — decode an already-identified RAW into
//! rawler's `RawImage`.
//!
//! This is the "rawImage" end of the split described in
//! `rules/STRUCT/detail/FOTLAB-FOTRAW-000001.md`: it does exactly what the old
//! monolithic `decode_to_png` did up to the point where a decoded image exists,
//! but stops there and hands the rawler `RawImage` to the next stage
//! (`intermediate::rawimage_to_fotraw`).
//!
//! It deliberately does **not** encode anything and does **not** touch the FFI
//! panic boundary: the caller (`crate::decode_to_png`) wraps the whole pipeline
//! in `catch_unwind` so a rawler panic on malformed input degrades to an error
//! instead of aborting the process (`FOTLAB-CRASH-000001`).

use std::path::Path;

use rawler::decoders::RawDecodeParams;
use rawler::rawsource::RawSource;
use rawler::RawImage;

use crate::RawlerFotlabError;

/// Decode `raw` (already routed to the raw path by [`crate::identify`]) into a
/// rawler [`RawImage`].
///
/// Mirrors the decode half of the previous `decode_to_png`: build a
/// [`RawSource`] over the bytes and run the full rawler decode. Returns
/// [`RawlerFotlabError::Decode`] when rawler does not support or cannot decode
/// the input.
///
/// Note this copies the whole source once into Rust-owned memory. Prefer
/// [`decode_source`] + `RawSource::new(path)` for anything already sitting on
/// the filesystem (`rules/REVIEW/detail/ACTION-PERFOR-000002.md`).
pub(crate) fn decode_to_rawimage(raw: &[u8]) -> Result<RawImage, RawlerFotlabError> {
    let src = RawSource::new_from_slice(raw);
    decode_source(&src)
}

/// Open `path` as a **memory-mapped** [`RawSource`].
///
/// Nothing is read eagerly by this call: rawler maps the file and pulls pages on
/// demand (`RawSource::new` uses `mmap` with a sequential-access hint), so opening
/// costs O(1) instead of "read the whole 50 MB file into a buffer first".
pub(crate) fn open_source_file(path: &Path) -> Result<RawSource, RawlerFotlabError> {
    RawSource::new(path).map_err(|e| RawlerFotlabError::Decode(format!("cannot open source file: {e}")))
}

/// Decode an already-materialised [`RawSource`] into a rawler [`RawImage`].
///
/// The shared core behind both entry points: whether the bytes came from Kotlin as
/// a `ByteArray` ([`decode_to_rawimage`]) or from a memory-mapped cache file, the
/// decode itself is identical — and neither path copies pixels again.
pub(crate) fn decode_source(src: &RawSource) -> Result<RawImage, RawlerFotlabError> {
    rawler::decode(src, &RawDecodeParams::default())
        .map_err(|e| RawlerFotlabError::Decode(e.to_string()))
}
