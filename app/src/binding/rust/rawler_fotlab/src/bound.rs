//! Stage 4 of the raw render path — encode a [`RawPixel`] to PNG bytes.
//!
//! Preview quality only: the decoded samples are shifted down to 8-bit without a
//! demosaic / white-balance / gamma pass, so a CFA mosaic (`cpp == 1`) shows as
//! grayscale and RGB (`cpp >= 3`) as RGB. The precise develop pipeline is future
//! work (`FOTLAB-STUDIO-000001` R4, `FOTLAB-NATIVE-000001`).
//!
//! Per `rules/STRUCT/detail/FOTLAB-IPIXEL-000001.md` R2/R2b, the geometry needed
//! to read the buffer is **not** carried by [`RawPixelData`]; it is resolved from
//! the tag namespaces via the conservative fallback `isodng` → `fotlab` →
//! `dnglab` ([`read_shape`]). No default shape is assumed.

use image::codecs::png::PngEncoder;
use image::{ExtendedColorType, ImageEncoder};

use crate::rawpixel::{read_shape, RawPixel, RawPixelBuffer};

/// Encode a decoded [`RawPixel`] to PNG.
///
/// Returns `Err` when no tag namespace supplies a complete geometry or when the
/// image is empty — the buffer alone cannot be interpreted (doc R2).
pub(crate) fn rawpixel_to_png(pixel: &RawPixel) -> Result<Vec<u8>, String> {
    let shape = read_shape(pixel)
        .ok_or_else(|| "RawPixel: no complete shape in any tag namespace (isodng/fotlab/dnglab)".to_string())?;

    let (w, h) = (shape.width, shape.height);
    if w == 0 || h == 0 {
        return Err("decoded image has no pixels".to_string());
    }
    let cpp = shape.cpp.max(1) as usize;
    let mut rgba: Vec<u8> = Vec::with_capacity((w as usize) * (h as usize) * 4);

    match &pixel.data.buffer {
        RawPixelBuffer::Integer(data) => {
            for px in data.chunks(cpp) {
                let r = shrink_u16(px.first().copied().unwrap_or(0));
                let g = if cpp > 1 { shrink_u16(px.get(1).copied().unwrap_or(0)) } else { r };
                let b = if cpp > 2 { shrink_u16(px.get(2).copied().unwrap_or(0)) } else { r };
                rgba.extend_from_slice(&[r, g, b, 255]);
            }
        }
        RawPixelBuffer::Float(data) => {
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
