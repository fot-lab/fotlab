//! rawler_fotlab — FotLab's first-party native binding over the upstream `rawler` crate.
//!
//! This is the only native library we ship, and the only one that carries a FotLab name:
//! **`librawler_fotlab.so`**. Upstream `rawler` is linked in from `external/dnglab/rawler`
//! as its original crate and keeps its own name (`FOTLAB-NATIVE-000001` R4 — upstream is
//! read-only; we never edit or re-publish it).
//!
//! It exposes two UniFFI functions, matching the two separate native calls of the raw
//! render path (`FOTLAB-STUDIO-000001` R8):
//!   * `identify`      — call #1: format identification only, never pixel decode.
//!   * `decode_to_png` — call #2: decode the already-identified RAW to PNG bytes.
//!
//! The Kotlin side of the seam is the hand-written facade
//! `app/src/kotlin/io/github/fotlab/fotlab/binding/dnglab/rawler_fotlab/RawlerFotlabBridge.kt`.

use image::codecs::png::PngEncoder;
use image::{ExtendedColorType, ImageEncoder};

use rawler::decoders::RawDecodeParams;
use rawler::rawsource::RawSource;
use rawler::{RawImage, RawImageData};

/// Error type surfaced to Kotlin over UniFFI.
///
/// `thiserror` provides the `Display` impl UniFFI needs to carry the message across the
/// FFI boundary; the variant itself becomes a Kotlin `sealed class` case.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum RawlerFotlabError {
    /// rawler does not recognize the input as a camera RAW it supports.
    #[error("unsupported input: {0}")]
    Unsupported(String),
    /// rawler recognized the input but failed while decoding it.
    #[error("decode failed: {0}")]
    Decode(String),
}

/// Call #1 — identification only.
///
/// Returns `make/model` (the format label the app routes on) when rawler recognizes the
/// bytes, else `None`. Never decodes pixels — this is the cheap probe that runs in
/// parallel with the Coil-side sniffer inside `FormatSniffer.sniff`.
#[uniffi::export]
pub fn identify(raw: &[u8]) -> Option<String> {
    let src = RawSource::new_from_slice(raw);
    match rawler::decode_dummy(&src) {
        Ok(img) => Some(format!("{}/{}", img.make, img.model)),
        Err(_) => None,
    }
}

/// Call #2 — decode the already-identified RAW to PNG-encoded bytes.
///
/// Made only after the route resolved to the raw path, which is why it takes the bytes
/// directly instead of re-running identification.
#[uniffi::export]
pub fn decode_to_png(raw: &[u8]) -> Result<Vec<u8>, RawlerFotlabError> {
    let src = RawSource::new_from_slice(raw);
    let img = rawler::decode(&src, &RawDecodeParams::default())
        .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;
    encode_png(&img).map_err(RawlerFotlabError::Decode)
}

/// Encode a decoded [`RawImage`] to PNG.
///
/// Preview quality only: the 16-bit linear samples are shifted down to 8-bit without a
/// demosaic/white-balance/gamma pass, so bayer data (`cpp == 1`) shows as grayscale and
/// RGB (`cpp >= 3`) as RGB. The precise develop pipeline is future work
/// (`FOTLAB-STUDIO-000001` R4, `FOTLAB-NATIVE-000001`).
fn encode_png(img: &RawImage) -> Result<Vec<u8>, String> {
    let (w, h) = (img.width as u32, img.height as u32);
    if w == 0 || h == 0 {
        return Err("decoded image has no pixels".to_string());
    }
    let cpp = img.cpp.max(1);
    let mut rgba: Vec<u8> = Vec::with_capacity((w as usize) * (h as usize) * 4);

    match &img.data {
        RawImageData::Integer(data) => {
            for px in data.chunks(cpp) {
                let r = shrink_u16(px.first().copied().unwrap_or(0));
                let g = if cpp > 1 { shrink_u16(px.get(1).copied().unwrap_or(0)) } else { r };
                let b = if cpp > 2 { shrink_u16(px.get(2).copied().unwrap_or(0)) } else { r };
                rgba.extend_from_slice(&[r, g, b, 255]);
            }
        }
        RawImageData::Float(data) => {
            for px in data.chunks(cpp) {
                let r = shrink_f32(px.first().copied().unwrap_or(0.0));
                let g = if cpp > 1 { shrink_f32(px.get(1).copied().unwrap_or(0.0)) } else { r };
                let b = if cpp > 2 { shrink_f32(px.get(2).copied().unwrap_or(0.0)) } else { r };
                rgba.extend_from_slice(&[r, g, b, 255]);
            }
        }
    }

    let mut out: Vec<u8> = Vec::new();
    PngEncoder::new(&mut out)
        .write_image(&rgba, w, h, ExtendedColorType::Rgba8)
        .map_err(|e| e.to_string())?;
    Ok(out)
}

/// 16-bit linear sample -> 8-bit (`>> 8`), saturating.
fn shrink_u16(v: u16) -> u8 {
    (v >> 8).min(255) as u8
}

/// Normalized float sample -> 8-bit, clamped.
fn shrink_f32(v: f32) -> u8 {
    (v.clamp(0.0, 1.0) * 255.0) as u8
}

uniffi::setup_scaffolding!();
