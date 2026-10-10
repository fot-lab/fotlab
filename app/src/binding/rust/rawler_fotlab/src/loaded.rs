//! `RawlerImageLoaded` — a RAW decoded exactly once and kept resident inside the
//! Rust process so Kotlin can drive repeated renders without re-decoding or
//! re-crossing the (potentially 100s-of-MB) pixel buffer across the FFI. See
//! `rules/REVIEW/detail/FOTLAB-RAWLER-000004.md`.
//!
//! Kotlin holds the *object* (a UniFFI `Arc` handle), never the bytes: the slow
//! rawler decode runs once in [`decode_rawler_image`], and every later render
//! reuses the cached [`RawImage`] by cloning it (the develop trunk mutates its
//! input in place — `develop.rs`).
//!
//! # One entry, two caches
//!
//! [`RawlerImageLoaded::render_png`] is the **only** render entry. It replaced the
//! `develop_to_png` / `develop_and_grade_to_png` pair (and their Kelvin variants), which
//! existed only because each output space used to be reached through its own entry and each
//! entry ran the whole pipeline. Now one trunk always runs and the caller's
//! [`PipelineStages`] decides where the output lands — so a develop-parameter change while
//! grading is enabled finishes the grade in the same pass instead of stopping at develop.
//!
//! Two things are cached, and they are cached at different depths:
//!
//! | cache | holds | reused when | reached via |
//! |---|---|---|---|
//! | the decoded [`RawImage`] | every sensor sample | always | internal, never crosses the FFI |
//! | the [`DemosaicedCameraImage`] | camera-space pixels + matrices + crop rect | `stages.develop == false` | Kotlin holds the handle and hands it back |
//!
//! The demosaiced cache is what makes a grade-only edit cheap. Rust keeps the most recent one
//! only so it can be *handed over*; ownership then lives in Kotlin, which drops its previous
//! handle before taking the new one — that is what keeps exactly one alive rather than one per
//! render.

use std::panic::{self, AssertUnwindSafe};
use std::path::Path;
use std::sync::{Arc, Mutex};

use rawler::rawsource::RawSource;
use rawler::RawImage;

use crate::bound;
use crate::calibrate::WorkingSpace;
use crate::camera_space::DemosaicedCameraImage;
use crate::defringe_prophoto_unpurple::defringe_prophoto;
use crate::develop::{
  develop_image, develop_to_camera_image, oklab_switches, DevelopParams, GradeParams, OutputTransfer,
  PipelineStages,
};
use crate::intermediate;
use crate::RawlerFotlabError;

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
    /// Capture focal length (mm) decoded from the RAW EXIF — the second priority
    /// in the LCP effective-focal chain (`crate::develop`): user override > this >
    /// LCP built-in focal > `DEFAULT_LCP_FOCAL_MM`. `None` when the file surfaced
    /// no focal length (surfaced to Kotlin via [`Self::focal_length_mm`]).
    focal_length_mm: Option<f64>,
    /// The most recent demosaiced camera-space buffer, published by a `develop = true` render
    /// and **taken away** by [`Self::take_demosaiced_camera_image`].
    ///
    /// A `Mutex<Option<…>>` rather than a plain field because every entry point takes `&self`
    /// (UniFFI objects are shared handles) while this slot is written by a render and read by
    /// a hand-over. Interior mutability is the only way to have both; poisoning is ignored
    /// because the value it guards is a plain buffer, not an invariant — a panic mid-render
    /// must not make every later render fail.
    demosaiced: Mutex<Option<Arc<DemosaicedCameraImage>>>,
}

impl RawlerImageLoaded {
    /// Publish a freshly built demosaiced camera buffer, dropping whatever was there. Called
    /// by the develop half of a render; the caller keeps its own `Arc` alive for this render.
    fn publish_demosaiced(&self, image: &Arc<DemosaicedCameraImage>) {
        *self.demosaiced.lock().unwrap_or_else(|e| e.into_inner()) = Some(Arc::clone(image));
    }
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
        let src = RawSource::new_from_slice(raw);
        let (image, focal) = crate::decode::decode_source_with_focal(&src)?;
        Ok(Arc::new(RawlerImageLoaded {
            inner: Arc::new(image),
            focal_length_mm: focal,
            demosaiced: Mutex::new(None),
        }))
    }))
    .unwrap_or_else(|_| {
        Err(RawlerFotlabError::Decode(
            "rawler panicked during decode".to_string(),
        ))
    })
}

/// Factory variant that decodes a RAW **straight out of a real filesystem path**
/// instead of a byte array — `RawSource::new` memory-maps the file, so none of the
/// source bytes are copied through the FFI or into Rust-owned memory first.
///
/// Kotlin copies the opened document into its own private cache once
/// (`StudioEngine.copySourceToCache`) and hands the path across instead of a
/// `ByteArray`: that removes both the whole-file `readBytes()` copy and
/// `RawSource::new_from_slice`'s second copy
/// (`rules/REVIEW/detail/OPTIMZ-PERFRM-000002.md`). Everything downstream of the
/// decode is unchanged, and the resident handle behaves exactly like
/// [`decode_rawler_image`] (same lifecycle, `FOTLAB-RAWLER-000004` §lifecycle).
#[uniffi::export]
pub fn decode_rawler_image_from_path(path: String) -> Result<Arc<RawlerImageLoaded>, RawlerFotlabError> {
    if path.is_empty() {
        return Err(RawlerFotlabError::Decode("empty path".to_string()));
    }
    panic::catch_unwind(AssertUnwindSafe(|| {
        let src = crate::decode::open_source_file(Path::new(&path))?;
        let (image, focal) = crate::decode::decode_source_with_focal(&src)?;
        Ok(Arc::new(RawlerImageLoaded {
            inner: Arc::new(image),
            focal_length_mm: focal,
            demosaiced: Mutex::new(None),
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

    /// Hand the cached demosaiced camera buffer over to Kotlin, leaving `None` behind.
    ///
    /// Call this once after every render that ran the develop half (`stages.develop == true`).
    /// Ownership moving to Kotlin is the point: it lets Kotlin decide — from its own parameter
    /// bookkeeping — whether the next render can set `develop = false` and pass the handle back,
    /// and dropping its previous handle before taking the new one is what bounds the cache to a
    /// single buffer. Rust keeps nothing, so a Kotlin that stops asking simply lets the memory go
    /// on the next GC of the handle (or on a file switch).
    ///
    /// Returns `None` when the last render did not produce one (e.g. it was itself a
    /// `develop = false` render, or nothing has been rendered yet).
    pub fn take_demosaiced_camera_image(&self) -> Option<Arc<DemosaicedCameraImage>> {
        self.demosaiced.lock().unwrap_or_else(|e| e.into_inner()).take()
    }

    /// As-shot white-balance multipliers (RGBE order) decoded from the file —
    /// rawler's `RawImage.wb_coeffs`. Passing `wb = None` reuses exactly these. rawler
    /// stores **no** separate as-shot exposure scale (the as-shot exposure is the raw pixel
    /// data itself), so the as-shot exposure is unity — i.e.
    /// `DevelopParams { exposure_ev: None, .. }`. Kotlin reads this to surface the as-shot
    /// state (`FOTLAB-RAWLER-000004` §as-shot).
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

    /// Whether this decode can be developed at **quarter resolution** — i.e. whether
    /// `DemosaicAlgorithm::Superpixel` will actually resolve to superpixel for it rather than
    /// falling back to the CFA default. Answered from the decoded metadata (sensor type, CFA
    /// pattern, Fuji rotation), so the UI can grey that entry out instead of letting the pick
    /// silently produce a full-resolution frame. The guard is shared with the pipeline itself
    /// (`demosaic::supports_downsample`), so the answer and the behaviour cannot drift apart.
    /// `false` for a non-CFA (pre-coloured) image as well.
    /// See `rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`.
    pub fn supports_downsample(&self) -> bool {
        crate::demosaic::supports_downsample(&self.inner)
    }

    /// Capture focal length (mm) decoded from the RAW EXIF — the lens focal the
    /// shot was taken at. `None` means the file carried no focal length. Consumed
    /// by the LCP stage as the **second** priority after any user override:
    /// effective focal = user → raw (this) → LCP built-in → `DEFAULT_LCP_FOCAL_MM`
    /// (`crate::develop`).
    pub fn focal_length_mm(&self) -> Option<f64> {
        self.focal_length_mm
    }

    /// The single render entry: run the trunk and return the finished PNG.
    ///
    /// Replaces the old `develop_to_png` / `develop_and_grade_to_png` pair (and their Kelvin
    /// variants). Those existed because each output space had its own entry *and* each entry ran
    /// the entire pipeline; now there is one trunk and [`stages`] says where the output lands:
    ///
    /// * `grade = false` → develop, project into sRGB D65, encode the presentation PNG with the
    ///   transfer function [`DevelopParams::output_transfer`] asks for.
    /// * `grade = true` → develop, project into ProPhoto D50, hand the buffer to rawalchemy,
    ///   encode the graded result (never with a second transfer function).
    ///
    /// [`stages`].`oklab` sits between the two halves and gates the highlight roll-off, so the
    /// trunk is develop / oklab / grade. Because the OKLab stage runs *above* the cache, changing
    /// an OKLab switch re-renders without re-developing — only [`stages`].`develop` decides that.
    ///
    /// [`cache`] is how the develop half is skipped: pass back the handle
    /// [`Self::take_demosaiced_camera_image`] previously handed you and the whole
    /// decode→demosaic→white-balance stretch is skipped. The contract is that a caller may only
    /// pass `develop = false` when it holds a cache built from the same develop-stage parameters —
    /// and, per `PipelineStages`, `develop` must be `true` whenever no cache is held. A
    /// `develop = false` render that arrives without a usable cache does **not** fail: it falls
    /// back to a full develop, because a wrong-but-complete render beats an error the user cannot
    /// act on.
    ///
    /// [kelvin] (`> 0`) overrides the white balance with the multipliers for that colour
    /// temperature, as the retired `*_at_kelvin` variants did; `0` (or less) leaves it as-shot.
    /// The projection happens natively; only the `f32` crosses the FFI.
    ///
    /// Wrapped in `catch_unwind` (`FOTLAB-CRASH-000001`).
    pub fn render_png(
        &self,
        params: DevelopParams,
        stages: PipelineStages,
        grade_params: GradeParams,
        cache: Option<Arc<DemosaicedCameraImage>>,
        kelvin: f32,
    ) -> Result<Vec<u8>, RawlerFotlabError> {
        let space = stages.working_space();
        panic::catch_unwind(AssertUnwindSafe(|| {
            let params = if kelvin > 0.0 {
                DevelopParams {
                    wb: Some(crate::wb::wb_from_color_temp(&self.inner, kelvin)),
                    ..params
                }
            } else {
                params
            };

            // --- develop half (camera space, cacheable) ---------------------------------
            // The OKLab stage deliberately sits *after* this line: the cached buffer is pre-roll-off,
            // so the same one serves either setting and either output space.
            let camera_image = match (stages.develop, cache) {
                (false, Some(cached)) => cached,
                // `develop = true` (or the contract was broken and there is nothing to reuse):
                // run it, and publish the result so Kotlin can pick the handle up afterwards.
                _ => {
                    let fresh = Arc::new(develop_to_camera_image((*self.inner).clone(), &params)?);
                    self.publish_demosaiced(&fresh);
                    fresh
                }
            };

            // --- oklab + output --------------------------------------------------------
            // Clipping is the editing path's own business: the presentation PNG is finished by
            // `bound`, which clips after the transfer function on its own.
            let mut linear = camera_image.to_working_space(
                space,
                stages.oklab,
                oklab_switches(&params),
                params.clip_to_gamut && space == WorkingSpace::ProPhotoD50,
            )?;

            // --- ProPhoto-space defringe (unpurple) ------------------------------------
            // Runs **after** prophoto clipping and **before** the rawalchemy hand-off, on the
            // linear ProPhoto-D50 buffer. `defringe_prophoto` is in place and only touches the
            // R/B channels; `None` (the default, see `DevelopParams::defringe_prophoto`) is an
            // identity no-op, so a graded render with the stage off is bit-for-bit unchanged.
            // Restricted to the graded (ProPhoto) path: the colour space the Unpurple core
            // expects is exactly the ProPhoto-D50 buffer, so the sRGB presentation path — which
            // has its own OKLab defringe (`defringe_oklab_aca.rs`) — is deliberately left to that.
            if space == WorkingSpace::ProPhotoD50 {
                if let Some(dp_settings) = &params.defringe_prophoto {
                    defringe_prophoto(&mut linear.rgb, linear.width as usize, linear.height as usize, dp_settings);
                }
            }

            // --- output -------------------------------------------------------------------
            if stages.grade {
                #[cfg(feature = "rawalchemy")]
                {
                    let overrides = rawalchemy_fotlab::GradeOverrides::from(&grade_params);
                    let graded =
                        rawalchemy_fotlab::grade(&linear.rgb, linear.width, linear.height, &overrides)
                            .map_err(|e| RawlerFotlabError::Decode(format!("rawalchemy grade failed: {e}")))?;
                    bound::graded_to_png(linear.width, linear.height, &graded).map_err(RawlerFotlabError::Decode)
                }
                #[cfg(not(feature = "rawalchemy"))]
                {
                    let _ = &grade_params;
                    Err(RawlerFotlabError::Decode(
                        "a graded render was requested but this build has no rawalchemy feature".to_string(),
                    ))
                }
            } else {
                // The transfer fallback for an omitted `output_transfer` lives here — the single
                // branch point that reads the value. The default is `Linear` (no sRGB OETF): a
                // graded/editing render must not be gamma-encoded, and an explicit `Gamma` from
                // Kotlin overrides this for the presentation PNG.
                bound::rawlerimagedeveloped_to_png(
                    &linear,
                    params.output_transfer.unwrap_or(OutputTransfer::Linear).applies_gamma(),
                )
                .map_err(RawlerFotlabError::Decode)
            }
        }))
        .unwrap_or_else(|_| Err(RawlerFotlabError::Decode("rawler panicked during render".to_string())))
    }
}

// The rawalchemy-gated entry points live in their own exported impl block. A
// `#[cfg]` on a *method* inside a `#[uniffi::export]` block does not reach the
// scaffolding uniffi generates for that method, so with `--no-default-features`
// the scaffolding still calls `Arc<RawlerImageLoaded>::{develop_and_grade,…}`
// while the methods themselves are stripped — E0599 at each definition. Gating
// the whole block removes the generated scaffolding along with the methods.
#[cfg(feature = "rawalchemy")]
#[uniffi::export]
impl RawlerImageLoaded {
    /// Develop the cached decode into linear ProPhoto-D50 and hand it straight to
    /// the rawalchemy grading engine — a single Rust→cxx hop with no Kotlin buffer
    /// copy (`rules/REVIEW/detail/FOTLAB-RAWLER-000006`). Requires the `rawalchemy`
    /// feature.
    ///
    /// [`GradeParams`] decides which grading stages run; an all-`None` record runs
    /// upstream's defaults untouched (`None` = "the engine decides" for every
    /// field — no default is pinned on this side).
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

    /// Auto-exposure metering of the cached decode with rawalchemy's 5-strategy meter, returned
    /// as an **EV offset in stops**, or `Err` when the meter rejects the mode.
    ///
    /// Runs exactly the same `develop_image` (linear ProPhoto-D50, unclamped) the grade fork uses,
    /// then hands that buffer to [`rawalchemy_fotlab::compute_auto_gain_ev`], which converts
    /// rawalchemy's linear gain `g` to `log2(g)` stops. Because the buffer is developed with
    /// `params` — which carry the currently-applied `exposure_ev` — the returned offset is
    /// *relative to the current image*: the caller is expected to add the recorded applied
    /// `exposure_ev` to obtain the absolute stop value to show.
    ///
    /// Metering never applies anything and does not depend on whether the exposure stage is
    /// enabled; that decision stays with the caller. Requires the `rawalchemy` feature.
    pub fn meter_auto_exposure(
        &self,
        params: DevelopParams,
        mode: String,
        target_gray: Option<f32>,
    ) -> Result<f32, RawlerFotlabError> {
        panic::catch_unwind(AssertUnwindSafe(|| {
            let image = (*self.inner).clone();
            let dev = develop_image(image, params, WorkingSpace::ProPhotoD50)?;
            rawalchemy_fotlab::compute_auto_gain_ev(
                &dev.rgb,
                dev.width,
                dev.height,
                &mode,
                target_gray,
            )
            .map_err(|e| RawlerFotlabError::Decode(format!("auto exposure metering failed: {e}")))
        }))
        .unwrap_or_else(|_| {
            Err(RawlerFotlabError::Decode(
                "rawler panicked during meter_auto_exposure".to_string(),
            ))
        })
    }
}
