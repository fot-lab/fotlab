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
//! * [`graded_to_png`] — the **rawalchemy result**. Takes the graded float buffer (log-encoded when a
//!   log space was selected) and quantizes it directly (clamp + ×255), applying **no** transfer
//!   function — the grade already encoded the image, and Kotlin consumes it as-is
//!   (`FOTLAB-RAWLER-000006` decision 4). This is what Studio renders after a Boost/LOG/LUT change.
//!
//! Per `rules/STRUCT/detail/FOTLAB-FOTRAW-000001.md` R2/R2b, the geometry needed to read the
//! [`FotRaw`] buffer is **not** carried by [`FotRawData`]; it is resolved from the tag
//! namespaces via the conservative fallback `isodng` → `fotlab` → `dnglab` ([`read_shape`]). No
//! default shape is assumed.

use rayon::prelude::*;

use image::codecs::png::{CompressionType, FilterType, PngEncoder};
use image::{ExtendedColorType, ImageEncoder};
use rawler::imgop::srgb::srgb_apply_gamma;

use crate::develop::RawlerImageDeveloped;
use crate::intermediate::{read_shape, FotRaw, FotRawBuffer};

/// The PNG parameters used by every preview encoder below.
///
/// Preview PNGs are a **one-way intermediate**: they are handed straight to Coil in
/// the same process and never persisted, so paying for compression is waste
/// (`rules/REVIEW/detail/ACTION-PERFOR-000004.md`).
///
/// `image` 0.25 already defaults `PngEncoder::new` to `CompressionType::Fast`
/// (flate level 1 — there is no "store" level exposed by `image`/`png`), so the
/// knob that is actually still spendable here is the **filter**: the default
/// `FilterType::Adaptive` runs a per-scanline heuristic over the whole image
/// before deflating, which costs O(pixels) extra passes. `NoFilter` skips that
/// entirely — combined with level 1 this is as close to "no compression" as the
/// crate's public API allows.
fn png_encoder<'a>(out: &'a mut Vec<u8>) -> PngEncoder<&'a mut Vec<u8>> {
    PngEncoder::new_with_quality(out, CompressionType::Fast, FilterType::NoFilter)
}

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
    // Per-pixel and dependency-free, so it is parallelised with rayon: at 50 MP this
    // is ~50M iterations of pure arithmetic over a ~200 MB output buffer
    // (`rules/REVIEW/detail/ACTION-PERFOR-000007.md`).
    let px_count = (w as usize) * (h as usize);
    let mut rgba: Vec<u8> = vec![0u8; px_count * 4];

    // Grayscale raw preview: the undeveloped sensor dump has had no demosaic / calibrate, so it is
    // shown as luminance. A CFA mosaic (`cpp == 1`) collapses to its single channel; a pre-coloured
    // buffer (`cpp >= 3`) collapses via Rec.709 luma.
    match &pixel.data.buffer {
        FotRawBuffer::Integer(data) => {
            data.par_chunks(cpp)
                .zip(rgba.par_chunks_exact_mut(4))
                .for_each(|(px, out)| {
                    let gray = if cpp >= 3 {
                        luma8(shrink_u16(px[0]), shrink_u16(px[1]), shrink_u16(px[2]))
                    } else {
                        shrink_u16(px.first().copied().unwrap_or(0))
                    };
                    out.copy_from_slice(&[gray, gray, gray, 255]);
                });
        }
        FotRawBuffer::Float(data) => {
            data.par_chunks(cpp)
                .zip(rgba.par_chunks_exact_mut(4))
                .for_each(|(px, out)| {
                    let gray = if cpp >= 3 {
                        luma_f32(px[0], px[1], px[2])
                    } else {
                        shrink_f32(px.first().copied().unwrap_or(0.0))
                    };
                    out.copy_from_slice(&[gray, gray, gray, 255]);
                });
        }
    }

    let mut out: Vec<u8> = Vec::new();
    png_encoder(&mut out)
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

    // Per-pixel, order-independent: parallelised with rayon (this is the ~50 MP
    // gamma + RGBA expansion, see `ACTION-PERFOR-000007`).
    let mut rgba: Vec<u8> = vec![0u8; (w as usize) * (h as usize) * 4];
    image
        .rgb
        .par_chunks_exact(3)
        .zip(rgba.par_chunks_exact_mut(4))
        .for_each(|(px, out)| {
            out.copy_from_slice(&[encode_srgb(px[0]), encode_srgb(px[1]), encode_srgb(px[2]), 255]);
        });

    let mut out: Vec<u8> = Vec::new();
    png_encoder(&mut out)
        .write_image(&rgba, w, h, ExtendedColorType::Rgba8)
        .map_err(|e| e.to_string())?;
    Ok(out)
}

/// Encode a graded float RGB buffer straight to an RGBA8 PNG by **direct
/// clamp-to-[0,1] + ×255 quantization — no transfer function**.
///
/// This is the presentation of the rawalchemy output and is deliberately a
/// third encoder next to [`fotraw_to_png`] / [`rawlerimagedeveloped_to_png`]:
/// `applyGradingFused` already applied the chosen log OETF (and the ProPhoto→
/// target-gamut matrix) when a log space was selected, so running the sRGB
/// gamma here would double-encode. Per `FOTLAB-RAWLER-000006` decision 4 Kotlin
/// consumes the graded result as-is; this is the "bit-shift to PNG" step. With
/// no log space selected the buffer is linear and the same direct quantization
/// applies — the caller (Studio) only reaches this encoder on an explicit grade
/// action, while the develop presentation branch keeps
/// [`rawlerimagedeveloped_to_png`] and its sRGB transfer function.
pub(crate) fn graded_to_png(width: u32, height: u32, rgb: &[f32]) -> Result<Vec<u8>, String> {
    if width == 0 || height == 0 {
        return Err("graded image has no pixels".to_string());
    }
    let expected = (width as usize) * (height as usize) * 3;
    if rgb.len() != expected {
        return Err(format!(
            "graded image buffer length {} != expected {} ({}x{}x3)",
            rgb.len(),
            expected,
            width,
            height
        ));
    }

    // Per-pixel quantization with no cross-pixel dependency: parallelised with rayon
    // (`ACTION-PERFOR-000007`).
    let mut rgba: Vec<u8> = vec![0u8; (width as usize) * (height as usize) * 4];
    rgb.par_chunks_exact(3)
        .zip(rgba.par_chunks_exact_mut(4))
        .for_each(|(px, out)| {
            out.copy_from_slice(&[
                shrink_f32(px[0]),
                shrink_f32(px[1]),
                shrink_f32(px[2]),
                255,
            ]);
        });

    let mut out: Vec<u8> = Vec::new();
    png_encoder(&mut out)
        .write_image(&rgba, width, height, ExtendedColorType::Rgba8)
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
