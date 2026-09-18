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
use crate::develop::{develop_image, DevelopParams, GradeParams};
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

    /// Estimated as-shot color temperature (Kelvin) of the decoded white balance, projected from
    /// the multipliers through the camera matrix (`wb::as_shot_color_temp_kelvin`). 0.0 means the
    /// value is unavailable (no multipliers / degenerate matrix). Surfaced by the Studio white-balance
    /// control as "As-shot: xxxx K" (`rules/REVIEW/detail/FOTLAB-RAWLER-000004.md` §as-shot).
    pub fn as_shot_color_temp_kelvin(&self) -> f32 {
        crate::wb::as_shot_color_temp_kelvin(&self.inner)
    }

    /// Develop the cached decode into a finished sRGB PNG, overriding the white balance with the
    /// multipliers for a target color temperature ([`kelvin`] Kelvin), reusing the same cached
    /// [`RawImage`]. `kelvin <= 0` leaves the white balance at as-shot. The Kelvin→multiplier
    /// projection stays on the native side; only the `f32` crosses the FFI. Wrapped in `catch_unwind`
    /// (`FOTLAB-CRASH-000001`).
    pub fn develop_to_png_at_kelvin(
        &self,
        params: DevelopParams,
        kelvin: f32,
    ) -> Result<Vec<u8>, RawlerFotlabError> {
        panic::catch_unwind(AssertUnwindSafe(|| {
            let image = (*self.inner).clone();
            let params = if kelvin > 0.0 {
                DevelopParams {
                    wb: Some(crate::wb::wb_from_color_temp(&image, kelvin)),
                    ..params
                }
            } else {
                params
            };
            let linear = develop_image(image, params, WorkingSpace::SrgbD65)?;
            bound::rawlerimagedeveloped_to_png(&linear).map_err(RawlerFotlabError::Decode)
        }))
        .unwrap_or_else(|_| {
            Err(RawlerFotlabError::Decode(
                "rawler panicked during develop".to_string(),
            ))
        })
    }

    /// Develop the cached decode into linear ProPhoto-D50 and hand it straight to
    /// the rawalchemy grading engine — a single Rust→cxx hop with no Kotlin buffer
    /// copy (`rules/REVIEW/detail/FOTLAB-RAWLER-000006`). Requires the `rawalchemy`
    /// feature.
    ///
    /// [`GradeParams`] decides which grading stages run; an all-`None` record runs
    /// upstream's defaults untouched (`None` = "the engine decides" for every
    /// field — no default is pinned on this side).
    #[cfg(feature = "rawalchemy")]
    pub fn develop_and_grade(
        &self,
        params: DevelopParams,
        grade_params: GradeParams,
    ) -> Result<Vec<f32>, RawlerFotlabError> {
        panic::catch_unwind(AssertUnwindSafe(|| {
            let image = (*self.inner).clone();
            let dev = develop_image(image, params, WorkingSpace::ProPhotoD50)?;
            let overrides = rawalchemy_fotlab::GradeOverrides::from(&grade_params);
            rawalchemy_fotlab::grade(&dev.rgb, dev.width, dev.height, &overrides)
                .map_err(|e| RawlerFotlabError::Decode(format!("rawalchemy grade failed: {e}")))
        }))
        .unwrap_or_else(|_| {
            Err(RawlerFotlabError::Decode(
                "rawler panicked during develop_and_grade".to_string(),
            ))
        })
    }
}
