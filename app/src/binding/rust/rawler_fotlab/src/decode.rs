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
pub(crate) fn decode_to_rawimage(raw: &[u8]) -> Result<RawImage, RawlerFotlabError> {
    let src = RawSource::new_from_slice(raw);
    rawler::decode(&src, &RawDecodeParams::default())
        .map_err(|e| RawlerFotlabError::Decode(e.to_string()))
}
