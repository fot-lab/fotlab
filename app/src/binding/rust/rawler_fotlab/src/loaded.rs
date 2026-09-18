//! `RawlerImageLoaded` — a RAW decoded exactly once and kept resident inside the
//! Rust process so Kotlin can drive repeated `develop` / `preview` calls without
//! re-decoding or re-crossing the (potentially 100s-of-MB) pixel buffer across the
//! FFI. See `rules/REVIEW/detail/FOTLAB-RAWLER-000004.md`.
//!
//! Kotlin holds the *object* (a UniFFI `Arc` handle), never the bytes: the slow
//! rawler decode runs once in [`decode_rawler_image`], and every later
//! `develop_to_png` / `preview_png` reuses the cached [`RawImage`] by cloning it
//! (the develop pipeline mutates its input in place — `develop.rs`).

use std::panic::{self, AssertUnwindSafe};
use std::sync::Arc;

use rawler::RawImage;

use crate::bound;
use crate::calibrate::WorkingSpace;
use crate::develop::{develop_image, DevelopParams};
use crate::intermediate;
use crate::{decode::decode_to_rawimage, RawlerFotlabError};

/// A RAW decoded exactly once and kept resident in Rust so Kotlin can re-develop /
/// re-preview it cheaply.
///
/// UniFFI stores the `Arc` in its own global registry and gives Kotlin a handle;
/// the generated Kotlin class holds that handle and its `finalize` drops the `Arc`
/// (releasing the native memory) once Kotlin lets go of it — so releasing on a file
/// switch is just dropping the Kotlin reference (`FOTLAB-RAWLER-000004` §lifecycle).
#[derive(uniffi::Object)]
pub struct RawlerImageLoaded {
    inner: Arc<RawImage>,
}

/// Factory: the only slow step. Runs rawler's full decode once and returns the
/// resident object (a UniFFI `Arc` handle) to Kotlin. Wrapped in `catch_unwind` so
/// a rawler panic degrades to `Err` instead of aborting the process
/// (`FOTLAB-CRASH-000001`).
#[uniffi::export]
pub fn decode_rawler_image(raw: &[u8]) -> Result<Arc<RawlerImageLoaded>, RawlerFotlabError> {
    if raw.is_empty() {
        return Err(RawlerFotlabError::Decode("empty input".to_string()));
    }
    panic::catch_unwind(AssertUnwindSafe(|| {
        let image = decode_to_rawimage(raw)?;
        Ok(Arc::new(RawlerImageLoaded {
            inner: Arc::new(image),
        }))
    }))
    .unwrap_or_else(|_| {
        Err(RawlerFotlabError::Decode(
            "rawler panicked during decode".to_string(),
        ))
    })
}

#[uniffi::export]
impl RawlerImageLoaded {
    /// Grayscale raw-preview PNG from the cached decode — no re-decode. Replaces the
    /// re-decode inside `decode_to_png` (`FOTLAB-RAWLER-000004`).
    pub fn preview_png(&self) -> Result<Vec<u8>, RawlerFotlabError> {
        panic::catch_unwind(AssertUnwindSafe(|| {
            let image = (*self.inner).clone();
            let pixel = intermediate::rawimage_to_fotraw(image)?;
            bound::fotraw_to_png(&pixel).map_err(RawlerFotlabError::Decode)
        }))
        .unwrap_or_else(|_| {
            Err(RawlerFotlabError::Decode(
                "rawler panicked during preview".to_string(),
            ))
        })
    }

    /// Develop the cached decode into a **finished sRGB PNG** using `params` — no
    /// re-decode. This is the *presentation* branch of the dual-fork
    /// (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`): the linear image is built
    /// in sRGB D65, then `bound::rawlerimagedeveloped_to_png` applies the sRGB transfer
    /// function (gamma) and clips to [0,1], yielding a display-ready PNG. Clones
    /// the cached `RawImage` first because the develop pipeline mutates it in
    /// place (`develop.rs`). Wrapped in `catch_unwind` (`FOTLAB-CRASH-000001`).
    pub fn develop_to_png(&self, params: DevelopParams) -> Result<Vec<u8>, RawlerFotlabError> {
        panic::catch_unwind(AssertUnwindSafe(|| {
            let image = (*self.inner).clone();
            let linear = develop_image(image, params, WorkingSpace::SrgbD65)?;
            bound::rawlerimagedeveloped_to_png(&linear).map_err(RawlerFotlabError::Decode)
        }))
        .unwrap_or_else(|_| {
            Err(RawlerFotlabError::Decode(
                "rawler panicked during develop".to_string(),
            ))
        })
    }

    /// As-shot white-balance multipliers (RGBE order) decoded from the file —
    /// rawler's `RawImage.wb_coeffs`. Passing `wb = None` to [`Self::develop_to_png`]
    /// reuses exactly these. rawler stores **no** separate as-shot exposure scale
    /// (the as-shot exposure is the raw pixel data itself), so the as-shot exposure
    /// is unity — i.e. `DevelopParams { exposure_ev: None, .. }`. Kotlin reads this
    /// to surface the as-shot state (`FOTLAB-RAWLER-000004` §as-shot).
    pub fn as_shot_wb(&self) -> Vec<f32> {
        self.inner.wb_coeffs.to_vec()
    }
}
