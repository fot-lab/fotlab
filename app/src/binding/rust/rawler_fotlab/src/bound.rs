//! Stage 4 of the raw render path — encode a [`FotRaw`] (or developed [`RawlerImageDeveloped`]) to PNG bytes.
//!
//! Two encoders live here, both producing an uncompressed RGBA8 PNG:
//!
//! * [`fotraw_to_png`] — the **grayscale raw preview**. The decoded samples are shifted down to
//!   8-bit without a demosaic / white-balance / colour pass, so the undeveloped sensor dump is
//!   shown as luminance: a CFA mosaic (`cpp == 1`) collapses to its single channel, a pre-coloured
//!   buffer (`cpp >= 3`) collapses via Rec.709 luma. This is what Studio renders on first open,
//!   before any demosaic choice (`FOTLAB-STUDIO-000001` R4, `FOTLAB-NATIVE-000001`). No display
//!   transform is applied — it is a raw dump.
//! * [`rawlerimagedeveloped_to_png`] — the **developed, display-ready** image. Takes the [`RawlerImageDeveloped`]
//!   produced by the develop pipeline (demosaic + calibrate) and applies the sRGB transfer function
//!   (gamma) + clip to [0,1] before writing PNG — a finished sRGB image for the UI
//!   (`FOTLAB-RAWLER-000005`). This is what Studio renders after the user picks a demosaic algorithm.
//!
//! Per `rules/STRUCT/detail/FOTLAB-FOTRAW-000001.md` R2/R2b, the geometry needed to read the
//! [`FotRaw`] buffer is **not** carried by [`FotRawData`]; it is resolved from the tag
//! namespaces via the conservative fallback `isodng` → `fotlab` → `dnglab` ([`read_shape`]). No
//! default shape is assumed.

use image::codecs::png::PngEncoder;
use image::{ExtendedColorType, ImageEncoder};
use rawler::imgop::srgb::srgb_apply_gamma;

use crate::develop::RawlerImageDeveloped;
use crate::intermediate::{read_shape, FotRaw, FotRawBuffer};

/// Encode a decoded [`FotRaw`] to PNG.
///
/// Returns `Err` when no tag namespace supplies a complete geometry or when the
/// image is empty — the buffer alone cannot be interpreted (doc R2).
pub(crate) fn fotraw_to_png(pixel: &FotRaw) -> Result<Vec<u8>, String> {
    let shape = read_shape(pixel)
        .ok_or_else(|| "FotRaw: no complete shape in any tag namespace (isodng/fotlab/dnglab)".to_string())?;

    let (w, h) = (shape.width, shape.height);
    if w == 0 || h == 0 {
        return Err("decoded image has no pixels".to_string());
    }
    let cpp = shape.cpp.max(1) as usize;
    let mut rgba: Vec<u8> = Vec::with_capacity((w as usize) * (h as usize) * 4);

    // Grayscale raw preview: the undeveloped sensor dump has had no demosaic / calibrate, so it is
    // shown as luminance. A CFA mosaic (`cpp == 1`) collapses to its single channel; a pre-coloured
    // buffer (`cpp >= 3`) collapses via Rec.709 luma.
    match &pixel.data.buffer {
        FotRawBuffer::Integer(data) => {
            for px in data.chunks(cpp) {
                let gray = if cpp >= 3 {
                    luma8(shrink_u16(px[0]), shrink_u16(px[1]), shrink_u16(px[2]))
                } else {
                    shrink_u16(px.first().copied().unwrap_or(0))
                };
                rgba.extend_from_slice(&[gray, gray, gray, 255]);
            }
        }
        FotRawBuffer::Float(data) => {
            for px in data.chunks(cpp) {
                let gray = if cpp >= 3 {
                    luma_f32(px[0], px[1], px[2])
                } else {
                    shrink_f32(px.first().copied().unwrap_or(0.0))
                };
                rgba.extend_from_slice(&[gray, gray, gray, 255]);
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

/// Encode one linear sRGB channel to an 8-bit sRGB byte: apply the sRGB transfer
/// function then clip into [0,1]. Out-of-[0,1] (highlight/shadow excursions from
/// the unclamped develop) are resolved here — the only clip in the UI path.
fn encode_srgb(v: f32) -> u8 {
    shrink_f32(srgb_apply_gamma(v))
}

/// Encode a developed [`RawlerImageDeveloped`] (expected in **linear sRGB D65**) to a
/// finished, display-ready RGBA8 sRGB PNG.
///
/// This is the *presentation* half of the dual-fork
/// (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`): the linear values are run
/// through the sRGB transfer function (`srgb_apply_gamma`) and then clipped to
/// [0,1] — the only place clipping happens. The in-memory editing object
/// (ProPhoto D50, unclamped) is never touched. This is the output side of
/// `rawler_fotlab::develop_to_png`.
pub(crate) fn rawlerimagedeveloped_to_png(image: &RawlerImageDeveloped) -> Result<Vec<u8>, String> {
    let (w, h) = (image.width, image.height);
    if w == 0 || h == 0 {
        return Err("developed image has no pixels".to_string());
    }
    let expected = (w as usize) * (h as usize) * 3;
    if image.rgb.len() != expected {
        return Err(format!(
            "linear image buffer length {} != expected {} ({}x{}x3)",
            image.rgb.len(),
            expected,
            w,
            h
        ));
    }

    let mut rgba: Vec<u8> = Vec::with_capacity((w as usize) * (h as usize) * 4);
    for px in image.rgb.chunks_exact(3) {
        rgba.extend_from_slice(&[encode_srgb(px[0]), encode_srgb(px[1]), encode_srgb(px[2]), 255]);
    }

    let mut out: Vec<u8> = Vec::new();
    PngEncoder::new(&mut out)
        .write_image(&rgba, w, h, ExtendedColorType::Rgba8)
        .map_err(|e| e.to_string())?;
    Ok(out)
}

/// Rec.709 luma of three 8-bit channels.
fn luma8(r: u8, g: u8, b: u8) -> u8 {
    (r as f32 * 0.2126 + g as f32 * 0.7152 + b as f32 * 0.0722).round() as u8
}

/// Rec.709 luma of three normalized (0..1) float channels, clamped to 8-bit.
fn luma_f32(r: f32, g: f32, b: f32) -> u8 {
    ((r.clamp(0.0, 1.0) * 0.2126 + g.clamp(0.0, 1.0) * 0.7152 + b.clamp(0.0, 1.0) * 0.0722) * 255.0) as u8
}
