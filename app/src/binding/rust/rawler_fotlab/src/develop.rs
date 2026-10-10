//! Develop glue — orchestrates the hand-built develop pipeline and is the FFI
//! entry point Kotlin calls.
//!
//! Pipeline (mirrors rawler's `RawDevelop::develop_intermediate` step ORDER,
//! minus the final sRGB gamma so `develop_image` always returns a **linear**
//! image):
//!
//! 1. `decode`      — `rawler::decode` → rawler `RawImage`
//! 2. rescale       — black/white-level scaling into 0..1 float (rawler)
//! 3. `denoise_strength` / `denoise_bm3d_strength` — two composed pre-demosaic
//!     mosaic denoise sub-stages (`denoise.rs` orchestrates them, in order):
//!     (a) a RawTherapee-style **CFA impulse denoise** (hot/dead-pixel /
//!     salt-and-pepper removal) driven by `denoise_strength`, then (b) a
//!     from-scratch **BM3D-CFA collaborative filter** on the raw mosaic driven
//!     by `denoise_bm3d_strength`. Both are `None` = identity; each photosite is
//!     handled on the normalised 0..1 mosaic, colour-aware on **every** CFA
//!     (2×2 Bayer, 6×6 X-Trans, four-colour, monochrome).
//! 3a. `dehaze_strength` / `dehaze_percentile` — optional pre-demosaic dehaze of
//!     the scaled mosaic (`dehaze.rs`); `None` = identity. The haze floor is
//!     estimated **per CFA colour plane** (R/G/B) as the `dehaze_percentile`
//!     quantile of each plane's 0..1 histogram (clamped to [0,1], default 1%),
//!     then lifted and contrast-restored per pixel, blended back by
//!     `dehaze_strength`. Runs on the *normalised* mosaic because it bins a 0..1
//!     histogram — a positive EV would push values >1.0 into the top bin.
//! 3b. `ca` — optional pre-demosaic chromatic-aberration correction (`ca.rs`,
//!     the `rawtrp_correct` port of RawTherapee's `CA_correct_RT`); `None` =
//!     identity. Bayer-only; runs after dehaze, before exposure.
//! 3c. `exposure_ev` — linear gain `2^exposure_ev` on the **single-channel** scaled
//!     mosaic, *before* demosaic (one mul per photosite instead of per output
//!     channel; demosaic is linear so the result is identical). Applied **last**
//!     among the mosaic stages, after denoise and dehaze have cleaned the
//!     normalised 0..1 source values.
//! 3d. `camera_profile` / `lens_profile` — the deprofile stage. DCP is read-only
//!     here: only the CFA-space **BaselineExposure** scalar (`×= 2^offset`, gated
//!     by `apply_baseline_exposure`) is applied. LCP vignette + distortion are
//!     colour-independent, so they are applied directly to the CFA mosaic by
//!     reusing RawTherapee's `LCPMapper`. Runs *before* exposure on the scaled
//!     mosaic (`FOTLAB-NATIVE-000005` B3/B4).
//! 4. `demosaic`    — selectable debayer + Fuji rotate + active-area crop (ROI). One algorithm
//!    slot: `DemosaicAlgorithm::Superpixel` is rawler's quarter-resolution debayer, so choosing it
//!    makes this stage return an intermediate at half the linear dimensions and every later stage
//!    simply processes fewer pixels (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`).
//! 5. white balance — channel gains on the demosaiced buffer; this is the **last** develop
//!    stage, and the buffer it produces is still camera-space (no colour matrix applied yet).
//!
//! # Three stages, one entry (`FOTLAB-RAWLER-000005`, unified)
//!
//! There used to be a dual fork here — an `sRGB` presentation branch that stopped after
//! calibrate and a `ProPhoto` editing branch that fed rawalchemy — with each branch running
//! its own complete pass over the whole pipeline, and the OKLab roll-off buried inside the
//! colour mapping where it was inseparable from it. Both are gone. There is now a single
//! `RawlerImageLoaded::render_png` entry running one trunk of three stages, and the caller's
//! [`PipelineStages`] says which of them run:
//!
//! | stage | what it does | gate | cached? |
//! |---|---|---|---|
//! | **develop** | steps 1-5 above, ending in [`DemosaicedCameraImage`] | `stages.develop` | yes — the cache |
//! | **oklab** | the camera-space highlight roll-off (`calibrate_oklab.rs`) | `stages.oklab` | no, it is cheap |
//! | **grade** | crop → camera→working matrix → gamut clip → rawalchemy → PNG | `stages.grade` | no |
//!
//! `grade = false` stops after the projection, in **sRGB D65**, and encodes with
//! `bound::rawlerimagedeveloped_to_png`; `grade = true` projects into **ProPhoto D50** and
//! encodes the graded result with `bound::graded_to_png`. The working space is chosen by
//! [`PipelineStages::working_space`] and nowhere else.
//!
//! The develop/oklab split is what the cache is built on: the roll-off is per-pixel and
//! returns most pixels untouched, so it is far cheaper to re-apply than to have baked in. That
//! puts it **above** the cache, which means one cached buffer serves either roll-off setting —
//! flipping an OKLab switch re-renders without re-running decode→demosaic, exactly as changing
//! a grade parameter does not.
//!
//! Every develop-stage parameter change from Kotlin re-runs the trunk (decoding included);
//! changes confined to the two later stages reuse the cache. Identification/sniff/route are
//! not repeated because Kotlin only calls render once the raw path is already chosen.

use std::panic::{self, AssertUnwindSafe};

use rayon::prelude::*;

use rawler::rawimage::{RawImageData, RawPhotometricInterpretation};
use rawler::RawImage;

use crate::ca::{correct_ca, CaSettings};
use crate::camera_space::{to_camera_space, DemosaicedCameraImage, OklabSwitches};
use crate::loca::{correct_loca, LocaSettings};
use crate::calibrate::WorkingSpace;
use crate::decode::decode_to_rawimage;
use crate::dehaze::dehaze;
use crate::dehaze_guided_filter::DehazeMergeMode;
use crate::demosaic::{demosaic, DemosaicAlgorithm};
use crate::denoise::denoise;
use crate::exposure::apply_exposure;
use crate::RawlerFotlabError;

/// Last-resort focal length (mm) used by the LCP stage when no better source is
/// available — i.e. the user did not specify one, the RAW EXIF carried no focal
/// length, and the LCP file itself carries no built-in focal. Mirrors the Kotlin
/// side's `DEFAULT_LCP_FOCAL_MM` (StudioEngine) so the two layers agree on the
/// same fallback. 50 mm ≈ a "normal" prime.
pub const DEFAULT_LCP_FOCAL_MM: f32 = 50.0;

/// The product of the develop pipeline: a linear RGB image (no gamma applied).
///
/// `rgb` is row-major linear RGB float, length `width * height * 3`.
///
/// **Path-independent by contract.** There is one of these regardless of which
/// demosaic path ran — full-resolution PPG/bilinear/X-Trans, or
/// quarter-resolution superpixel. `width`/`height` are the dimensions of the
/// buffer actually produced (post-crop), nothing else records the choice, and
/// no consumer may infer or branch on it: calibrate, crop, the PNG encoder and
/// the rawalchemy grade all see the same structure with the same invariants and
/// simply process fewer pixels when superpixel ran
/// (`rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md`).
#[derive(Debug, Clone, uniffi::Record)]
pub struct RawlerImageDeveloped {
  pub width: u32,
  pub height: u32,
  pub rgb: Vec<f32>,
}

/// DCP camera profile hook for a develop render.
///
/// Per the design (`FOTLAB-NATIVE-000005` rev 3/4/5): DCP is **read-only** here.
/// The only CFA-space op is the scalar `BaselineExposure` (`×= 2^offset`), and
/// even that is gated by [`CameraProfileParams::apply_baseline_exposure`] — when
/// off, the parsed offset is passed through untouched. The colour matrix /
/// HSD / Tone / Look applications live at the RGB calibration stage (B5) and are
/// out of scope for this mosaic stage.
///
/// Requires the `rawtherapee_fotlab` crate (vendored DCP/LCP decode + Rust apply;
/// no librtengine link). Parsing happens once per render here; a later pass can
/// cache parsed profiles by path.
#[derive(Debug, Clone, uniffi::Record)]
pub struct CameraProfileParams {
  /// Path to the `.dcp` file.
  pub path: String,
  /// Apply the CFA-stage BaselineExposure scalar (`×= 2^offset`). Default true;
  /// false keeps the baseline read-only (no change to the mosaic).
  #[uniffi(default = true)]
  pub apply_baseline_exposure: bool,
}

/// LCP lens profile hook for a develop render.
///
/// Vignette and distortion are colour-independent, so they are applied directly
/// to the single-channel CFA mosaic by `rawtherapee_fotlab::apply_lcp_cfa`, which
/// re-implements RawTherapee's `LCPMapper` apply in Rust (vignette radial
/// multiplier + distortion geometric warp) over the decoded LCP coefficients.
/// CA is intentionally skipped (per-channel, belongs to the RGB stage).
/// `raw_rotation_deg` reuses the RAW rotation; `focal_length` is required by the
/// model, the rest default to sensible fallbacks when the caller does not supply
/// EXIF-derived values. The *effective* focal length fed to the model is resolved
/// in [`crate::develop::develop_image`] across a priority chain: a user-specified
/// `focal_length` (this field, when `Some`) wins, then the capture focal length
/// decoded from the RAW (`DevelopParams::raw_focal_length_mm`), then the focal
/// length the LCP file itself carries (built-in), and finally the
/// `DEFAULT_LCP_FOCAL_MM` constant.
#[derive(Debug, Clone, uniffi::Record)]
pub struct LensProfileParams {
  /// Path to the `.lcp` file.
  pub path: String,
  /// Apply LCP vignette in CFA space.
  #[uniffi(default = false)]
  pub apply_vignette: bool,
  /// Apply LCP distortion (geometry) in CFA space.
  #[uniffi(default = false)]
  pub apply_distortion: bool,
  /// Focal length (mm) at capture — user override. `None` means "not specified";
  /// the effective focal length then falls back to the decoded-RAW focal, the
  /// LCP built-in focal, and finally the `DEFAULT_LCP_FOCAL_MM` constant (see the
  /// struct doc). `Some(v)` makes the user value take top priority.
  #[uniffi(default = None)]
  pub focal_length: Option<f32>,
  /// 35mm-equivalent focal length (mm). Defaults to the effective focal length
  /// when omitted.
  #[uniffi(default = None)]
  pub focal_length_35mm: Option<f32>,
  /// Focus distance (m). Defaults to 1.0 (near-infinity) when omitted.
  #[uniffi(default = None)]
  pub focus_dist: Option<f32>,
  /// Aperture (f-number). Defaults to 8.0 when omitted.
  #[uniffi(default = None)]
  pub aperture: Option<f32>,
  /// Raw rotation (degrees) applied before correction. Defaults to 0.
  #[uniffi(default = 0)]
  pub raw_rotation_deg: i32,
}

/// Develop parameters supplied by Kotlin for each render.
#[derive(Debug, Clone, uniffi::Record)]
pub struct DevelopParams {
  /// Demosaic algorithm selection (defaults to rawler's CFA-appropriate choice).
  pub demosaic_algorithm: DemosaicAlgorithm,
  /// Exposure compensation in stops; applied as the linear multiplier
  /// `2^exposure_ev` (the linear `exp_scale`) to the scaled mosaic *before*
  /// demosaic (single-channel). `None` = as-shot: no compensation, unity gain —
  /// exactly mirroring rawler's `RawDevelop::default()` (the pipeline dnglab uses
  /// to render its DNG thumbnail, which applies no exposure step at all;
  /// `FOTLAB-RAWLER-000004` §as-shot).
  #[uniffi(default = None)]
  pub exposure_ev: Option<f32>,
  /// Exposure-stage **lower clip bound** (0..1) for the scaled mosaic, applied
  /// immediately *before* the `2^exposure_ev` gain and fused into the same rayon
  /// pass (`exposure.rs`). Values strictly below this bound are forced up to it.
  /// Gated behind the same enable switch as [`exposure_ev`] on the Kotlin side,
  /// so a stage-off render (`None`) never clips. Defaults to 0.0 — a no-op on
  /// the already black/white-level-normalised 0..1 mosaic.
  #[uniffi(default = 0.0)]
  pub exposure_clip_lower: f32,
  /// Exposure-stage **upper clip bound** (0..1) for the scaled mosaic, applied
  /// immediately *before* the `2^exposure_ev` gain and fused into the same rayon
  /// pass (`exposure.rs`). Values at or above this bound are forced down to it.
  /// Gated behind the same enable switch as [`exposure_ev`] on the Kotlin side.
  /// Defaults to 1.0 — likewise a no-op on the normalised mosaic.
  #[uniffi(default = 1.0)]
  pub exposure_clip_upper: f32,
  /// Optional white-balance multipliers (RGBE order). `None` → rawler's as-shot
  /// `wb_coeffs`.
  pub wb: Option<Vec<f32>>,
  /// Impulse denoise strength for the **impulse** sub-stage of the pre-demosaic
  /// mosaic denoise (`denoise_impulse.rs`), applied **before** exposure on the
  /// normalised 0..1 mosaic. `None` = skip (identity); `0` also collapses to
  /// identity. A RawTherapee-style CFA impulse denoise (hot/dead-pixel /
  /// salt-and-pepper removal): `strength` is a *sensitivity multiplier* on the
  /// detection threshold (`≈1.0` = mild, higher = more aggressive). Supplied from
  /// Kotlin when the Studio denoise impulse control is enabled. Non-2×2-periodic
  /// CFAs (e.g. X-Trans) are handled (per-colour grouping), not skipped.
  #[uniffi(default = None)]
  pub denoise_strength: Option<f32>,
  /// BM3D-CFA denoise strength for the **BM3D** sub-stage of the pre-demosaic
  /// mosaic denoise (`denoise_bm3d_cfa.rs`), applied **before** exposure on the
  /// normalised 0..1 mosaic, *after* the impulse sub-stage. `None` = skip
  /// (identity); `0` also collapses to identity. A from-scratch BM3D-style
  /// collaborative filter that runs directly on the CFA mosaic
  /// (`sigma = 0.02 · strength`); higher strength = more aggressive Gaussian /
  /// shot-noise reduction. Supplied from Kotlin when the Studio BM3D denoise
  /// control is enabled.
  #[uniffi(default = None)]
  pub denoise_bm3d_strength: Option<f32>,
  /// Dehaze strength (0..1) for the pre-demosaic mosaic dehaze stage (`dehaze.rs`),
  /// applied **before** exposure on the normalised 0..1 mosaic. `None` = skip
  /// (identity). Supplied from Kotlin when the Studio dehaze control is enabled; 0
  /// also collapses to identity.
  #[uniffi(default = None)]
  pub dehaze_strength: Option<f32>,
  /// Dehaze haze-floor percentile (0..1) for the pre-demosaic mosaic dehaze
  /// stage (`dehaze.rs`). The haze floor is estimated as this quantile of each
  /// CFA colour plane's histogram; lower is more conservative (closer to a pure
  /// minimum), higher lifts more of the low-tail signal. This is the **floor**
  /// quantile, distinct from [`dehaze_ceiling`] (the guided soft-mask cap).
  /// Arbitrary floats from Kotlin are clamped to `[0,1]` internally. `None` → the
  /// default tail (`0.01`). Supplied from Kotlin when the Studio dehaze control
  /// is enabled.
  #[uniffi(default = None)]
  pub dehaze_percentile: Option<f32>,
  /// Dehaze guided-filter soft-mask ceiling (0..1) for the Studio dehaze stage.
  /// In guided mode this caps the spatial haze floor any region may claim — the
  /// maximum over-dehaze — and is independent of [`dehaze_percentile`] (the
  /// global haze-floor quantile / anchor). `None` → the guided (2D) branch is not
  /// selected and the scalar (global-floor) branch runs instead; with
  /// `dehaze_percentile` also `None` the whole stage is an identity. Supplied
  /// from Kotlin; Kotlin currently routes the single dehaze percentile input to
  /// both fields until a non-guided UI exists.
  #[uniffi(default = None)]
  pub dehaze_ceiling: Option<f32>,
  /// Dehaze guided-filter dark-channel box radius (sub-lattice pixels). `None` →
  /// the engine default (8). Larger = smoother, lower-frequency haze field from
  /// the dark channel; smaller = tighter to local haze boundaries. Supplied from
  /// Kotlin when the Studio dehaze control is enabled.
  #[uniffi(default = None)]
  pub dehaze_radius_dark: Option<i32>,
  /// Dehaze guided-filter window radius (sub-lattice pixels). `None` → the engine
  /// default (8). Controls the edge-aware smoothing extent of the spatial haze
  /// field. Supplied from Kotlin when the Studio dehaze control is enabled.
  #[uniffi(default = None)]
  pub dehaze_radius_guide: Option<i32>,
  /// How the per-plane haze estimates combine into the field applied to pixels
  /// (`dehaze_guided_filter::DehazeMergeMode`). `Each` = every colour plane applies
  /// the filter it estimated for itself (no merge); `Blue` = every plane uses the
  /// blue channel plane's field; `Min` = per-pixel minimum haze across the planes;
  /// `Avg` = per-pixel mean across the planes (the historical shared-field merge).
  /// Defaults to `Min` (the enum's `#[default]`, the most conservative: a pixel is
  /// only dehazed where *every* plane agrees it is hazy). Supplied from Kotlin when
  /// the Studio dehaze control is enabled.
  pub dehaze_merge_mode: DehazeMergeMode,
  /// Chromatic-aberration correction settings (the Studio LCA stage), applied
  /// **before** exposure on the full-frame scaled mosaic, after dehaze — the
  /// port of RawTherapee's `CA_correct_RT` (`rawtrp_correct` crate,
  /// `rules/REVIEW/detail/FOTLAB-RAWLER-000011.md`). `None` = the stage is off
  /// (identity; the default). Auto mode measures the residual-CA polynomial
  /// per render; manual mode uses the radial red/blue strengths. Only 2×2
  /// Bayer CFAs are supported — other CFAs degrade to the uncorrected mosaic.
  #[uniffi(default = None)]
  pub ca: Option<CaSettings>,
  /// Longitudinal-CA (LoCA) fringe-correction settings (the Studio LoCA
  /// stage), applied **before** exposure on the full-frame scaled mosaic,
  /// **immediately after** the lateral-CA (`ca`) stage and before demosaic — the
  /// FotLab first-party `rawtrp_correct::correct_loca_bayer`
  /// (`rules/DESIGN/detail/FOTLAB-RENDER-000003.md`). `None` = the stage is off
  /// (identity; the default); `LocaSettings.enabled == false` (the master
  /// switch) short-circuits identically. Runs its OWN high-contrast edge
  /// detection on every render and does NOT reuse the LCA detector, so LoCA
  /// works whether or not LCA is enabled. Two PEER criteria+behaviour branches
  /// under the master switch, both acting on the **G plane only** (R/B are
  /// never touched): the 去紫边 pair (magenta: R and B both exceed G at a bright
  /// edge) **raises G** — chosen over desaturating R/B because it avoids the
  /// dull "dark-gray edge" (tradeoff: a little more false colour); the 去绿边
  /// pair (G exceeds both R and B at a bright edge) **lowers G** (mirror risk: a
  /// darker edge). Each pair has its own switch and its own luminance threshold
  /// (default 0.5). Only 2×2 Bayer CFAs are supported — other CFAs degrade to
  /// the uncorrected mosaic.
  #[uniffi(default = None)]
  pub loca: Option<LocaSettings>,
  /// **Out-of-gamut clipping** switch for the *editing* branch (the Studio
  /// "Clipping" tool). When `true`, every component of the finished linear
  /// **ProPhoto-D50** image is clamped into `[0,1]` (above 1 → 1, below 0 → 0)
  /// as the **last** step of `develop_image` — after calibrate and crop, before
  /// the buffer leaves Rust — so rawalchemy's grade, the metering pass and the
  /// handle `develop` returns to Kotlin all receive an in-gamut image
  /// (`rules/REVIEW/detail/FOTLAB-RAWLER-000013.md` §F8).
  ///
  /// The *presentation* branch (sRGB D65) is deliberately **not** touched: it is
  /// finished by `bound::rawlerimagedeveloped_to_png`, which applies gamma and
  /// then performs exactly the same per-channel clip, so turning this on changes
  /// only the ProPhoto fork. This is a plain per-channel **clip**, not gamut
  /// mapping: a highlight whose channels clip unequally still rotates in hue —
  /// the point is that the excursion is resolved at the ProPhoto boundary
  /// instead of being handed downstream intact.
  ///
  /// `false` (the default) = today's behaviour: wide gamut, unclamped.
  #[uniffi(default = false)]
  pub clip_to_gamut: bool,
  /// DCP camera profile hook (read-only parse + CFA-space BaselineExposure scalar).
  /// `None` = no camera profile stage. See [`CameraProfileParams`].
  #[uniffi(default = None)]
  pub camera_profile: Option<CameraProfileParams>,
  /// LCP lens profile hook (vignette + distortion in CFA space, reusing RT's
  /// LCPMapper). `None` = no lens profile stage. See [`LensProfileParams`].
  #[uniffi(default = None)]
  pub lens_profile: Option<LensProfileParams>,
  /// Focal length (mm) decoded from the *capture* RAW's EXIF — the lens focal
  /// length the shot was taken at. `None` = the decoder did not surface one.
  /// Consumed by the LCP stage as the second priority after any user override
  /// (see [`LensProfileParams`] doc): effective focal = user → raw → LCP built-in
  /// → `DEFAULT_LCP_FOCAL_MM`.
  #[uniffi(default = None)]
  pub raw_focal_length_mm: Option<f32>,
  /// OKLab highlight-chroma compression for the **sRGB D65 presentation** output
  /// (`rules/DESIGN/detail/FOTLAB-RENDER-000001`). When on *and* the render's output is the sRGB
  /// presentation, a camera-space-in / camera-space-out OKLab block runs before the camera→working
  /// multiply: camera → XYZ(D65) → OKLab → lightness-driven chroma roll-off → XYZ(D65) → camera.
  /// Because the round trip is camera-space in/out and anchored on XYZ(D65), it desaturates
  /// near-clipped highlights *before* they reach the encoder's per-channel clamp, trending a
  /// highlight whose channels clip unevenly toward neutral instead of freezing into magenta/cyan.
  ///
  /// **Sub-switch**: it only takes effect when the render's OKLab stage runs at all
  /// ([`PipelineStages::oklab`]) and when the sub-switch for the *actual* output space is on —
  /// this one for a presentation render, [`Self::oklab_highlight_compress_prophoto`] for a graded
  /// one. Identity pass-through otherwise (bit-for-bit identical to the bypass-off output).
  /// **Default `false`** (off) — every switch defaults off on the Rust/FFI side; Kotlin controls
  /// assembly (`FOTLAB-RENDER-000001` R3/C6).
  #[uniffi(default = false)]
  pub oklab_highlight_compress_srgb: bool,
  /// OKLab highlight-chroma compression for the **ProPhoto D50 graded** output - twin of
  /// [`Self::oklab_highlight_compress_srgb`], applied when the render is a graded one so rawalchemy
  /// receives a desaturated (not clamped) near-clipped-highlight buffer. The math is identical; only
  /// the switch differs, which is how one dialog can address both outputs.
  ///
  /// **Sub-switch**, gated by [`PipelineStages::oklab`] like its sRGB twin. **Default `false`**.
  #[uniffi(default = false)]
  pub oklab_highlight_compress_prophoto: bool,
  /// **ProPhoto-space purple-fringe (unpurple) correction** for the *editing* branch
  /// (`defringe_prophoto_unpurple.rs`), applied **after** prophoto clipping and **before** the
  /// rawalchemy hand-off — i.e. on the linear ProPhoto-D50 buffer the grade consumes. `None` =
  /// the stage is off (identity; this is the default, matching the project's "every switch
  /// defaults off on the Rust/FFI side, Kotlin controls assembly" rule). `Some(settings)` runs
  /// the v4 faithful Unpurple core in place on `RawlerImageDeveloped.rgb`. The JPG-decode half of
  /// the v4 prototype is intentionally *not* wired here — the pipeline already owns the ProPhoto
  /// buffer, so only the core algorithm is needed.
  ///
  /// This is the ProPhoto twin of the OKLab defringe (`defringe_oklab_aca.rs`): that one runs in
  /// camera space before calibration (presentation path), this one runs in the graded working
  /// space. They are independent stages and either may be on or off.
  #[uniffi(default = None)]
  pub defringe_prophoto: Option<crate::defringe_prophoto_unpurple::UnpurpleSettings>,
  /// Output transfer applied when the finished linear buffer is encoded to PNG
  /// (`OutputTransfer`), read by `bound::rawlerimagedeveloped_to_png` — and only there.
  ///
  /// This is deliberately **not** a develop stage: nothing upstream of `bound` may look at it,
  /// so switching it can never change the pixels the grade sees. It only decides whether the
  /// final 8-bit PNG passes through the sRGB OETF or is written straight from the linear
  /// values (clamped to 0..1 either way).
  ///
  /// Kotlin assembles it from app state rather than exposing it as a user choice: a graded
  /// render is already log-encoded by rawalchemy and must not be gamma-encoded again, while
  /// the develop-presentation render is the one that wants the transfer function. It is
  /// carried here (rather than as a separate entry-point argument) so one record fully
  /// describes a render.
  ///
  /// `Option` + `None` default (not an enum-variant default, which UniFFI 0.28 rejects — its
  /// field default only accepts literals): the real fallback lives at the one place the value is
  /// read, `loaded.rs` (the `unwrap_or(OutputTransfer::Linear)` before `applies_gamma`). The
  /// default is `Linear` (no sRGB OETF) — a graded/editing render must not be gamma-encoded — and
  /// any render that wants the transfer function supplies `Gamma` explicitly (Kotlin does, via
  /// `StudioEngine.assembleDevelopParams`).
  #[uniffi(default = None)]
  pub output_transfer: Option<OutputTransfer>,
}

/// Whether the PNG written at the end of the trunk carries the sRGB transfer function.
///
/// Read at exactly one place — `bound::rawlerimagedeveloped_to_png` — so the choice cannot
/// leak into the develop stages and change the pixels the grade receives.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum OutputTransfer {
  /// Apply the sRGB OETF (gamma) before quantizing to 8 bits. Default, and what the
  /// develop-presentation PNG has always used.
  Gamma,
  /// Write the linear values as-is (clamped to `[0,1]`, no OETF).
  Linear,
}

impl OutputTransfer {
  /// Whether `bound` should apply the transfer function. Named so the call site reads as the
  /// decision it is rather than as a string comparison against an enum.
  pub(crate) fn applies_gamma(self) -> bool {
    matches!(self, OutputTransfer::Gamma)
  }
}

/// Which stages this render runs — the two-key dictionary that replaced the old
/// `srgb` / `prophoto` entry fork.
///
/// It is assembled by Kotlin, which is the only layer that knows *why* a re-render was
/// requested, and it does double duty: it decides the output (and with it the working space,
/// see [`PipelineStages::working_space`]) and it decides whether the expensive develop half
/// may be skipped in favour of the cached [`DemosaicedCameraImage`]. The simple rule Kotlin
/// applies is that **`develop` must be `true` whenever no cache is held**; with a cache held
/// it may be `false` whenever the develop-stage parameters are unchanged.
///
/// * `{ develop: true, oklab: <on>, grade: false }` — the common case: re-run develop, apply the
///   roll-off, stop at the sRGB D65 presentation PNG. Also the state of a freshly opened file,
///   where nothing is configured yet.
/// * `{ develop: true, oklab: <on>, grade: true }` — the develop parameters changed *and* grading
///   is enabled: run the whole trunk in one pass instead of stopping at develop and waiting for a
///   second call for the grade.
/// * `{ develop: false, oklab: <on>, grade: true }` — only the grade changed: reuse the cached
///   demosaiced camera buffer and start from it.
/// * `{ develop: false, oklab: <on>, grade: false }` — grading was switched off entirely and
///   develop did not change: reuse the cache and render the presentation PNG from it.
///
/// The OKLab stage sits **above** the cache on purpose: it is cheap, and being above it means one
/// cached buffer serves either roll-off setting — so flipping an OKLab switch does not invalidate
/// the cache, exactly like changing a grade parameter does not.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct PipelineStages {
  /// Run decode → … → demosaic → white balance, producing a fresh
  /// [`DemosaicedCameraImage`]. `false` means "reuse the cached one" — only legal when Kotlin
  /// holds a cache built from the same develop-stage parameters.
  pub develop: bool,
  /// Run the OKLab highlight roll-off stage — a master gate for the whole stage, exactly as `grade`
  /// gates the grading stage, so `false` skips it outright. Which roll-off actually applies is then
  /// decided by the per-output sub-switches on [`DevelopParams`], picked from the output space this
  /// same dictionary selects — so Kotlin derives this as "at least one sub-switch is on", which makes
  /// the stage a guaranteed no-op exactly when it is switched off.
  pub oklab: bool,
  /// Hand the linear working-space buffer to rawalchemy and emit the graded PNG. Also selects
  /// the working space: ProPhoto D50 when on, sRGB D65 when off.
  pub grade: bool,
}

impl Default for PipelineStages {
  /// `develop = true, oklab = false, grade = false` — the safe default: develop everything, touch
  /// nothing optional. A caller that has not yet assembled the dictionary gets a complete
  /// presentation render and never silently skips a stage it did not ask for.
  fn default() -> Self {
    PipelineStages { develop: true, oklab: false, grade: false }
  }
}

impl PipelineStages {
  /// The working space this render's output lives in.
  ///
  /// Grade on → ProPhoto D50 (the editing space rawalchemy expects); grade off → sRGB D65 (the
  /// presentation space the PNG is viewed in). This is the *only* place the two are chosen —
  /// there is no longer an entry point per space.
  ///
  /// `pub(crate)` on purpose: this is a Rust-side derivation, not something Kotlin sends or reads,
  /// and a `pub` method on a Record would be lifted into a second exported function for no gain.
  pub(crate) fn working_space(self) -> WorkingSpace {
    if self.grade {
      WorkingSpace::ProPhotoD50
    } else {
      WorkingSpace::SrgbD65
    }
  }
}

/// Grading parameters supplied by Kotlin for the graded render.
///
/// **Every field is optional, and `None` means "the engine decides"** — either
/// "use upstream's own `rawalchemy::GradingParams` default" or "skip this stage".
/// This Rust side performs **no defaulting of its own**: the values are handed to
/// the glue as "unset" sentinels precisely so that upstream stays the single
/// owner of every default it declares. If upstream changes one, we follow it
/// without touching this crate (`rules/REVIEW/detail/FOTLAB-RAWLER-000006.md`).
///
/// `Default` (every field `None`) is what the unified entry passes when the caller does not
/// want a grade — it is never consulted for that, because `PipelineStages::grade == false`
/// skips the engine entirely; it exists so the stateless helpers can build an inert record.
#[derive(Debug, Clone, Default, uniffi::Record)]
pub struct GradeParams {
  /// Log space selecting the camera log curve and the ProPhoto→target gamut
  /// matrix (e.g. `"FUJIFILM F-Log2 C"`, `"Sony S-Log3"`, `"ARRI LogC4"`), i.e.
  /// the linear→log encode stage. Pass a display name as returned by
  /// `supported_log_spaces()`; the cxx shim also still accepts upstream's
  /// canonical key (`"F-Log2C"`) for values that predate the aliasing, and it is
  /// the shim — not this crate — that spells either vocabulary. `None` = skip
  /// **both** the gamut transform and the log encoding (upstream:
  /// `logSpaceInfo == nullptr`).
  #[uniffi(default = None)]
  pub log_space: Option<String>,
  /// Path to a `.cube` 3D LUT, applied to the log-encoded image. `None` = no LUT.
  #[uniffi(default = None)]
  pub lut_path: Option<String>,
  /// Metering mode for automatic exposure (`computeAutoGain`), e.g. `"matrix"`.
  /// `None` = skip automatic metering, leaving the metered base at unity.
  #[uniffi(default = None)]
  pub metering_mode: Option<String>,
  /// Upstream's raw `GradingParams::gain` **exposure multiplier** — a linear
  /// factor, *not* an EV and **not** [`DevelopParams::exposure_ev`]. The develop
  /// exposure is applied by rawler to the mosaic before demosaic and never
  /// reaches the grading stage; this one scales the linear ProPhoto data the
  /// grading loop receives, so the two are separate controls that must not be
  /// wired to the same UI value. `None` = upstream default (unity) = don't touch
  /// exposure. Metering, when enabled, is the base this multiplier scales.
  #[uniffi(default = None)]
  pub gain: Option<f32>,
  /// Target gray level for `computeAutoGain` (upstream default `0.18`).
  /// `None` = upstream default. Only meaningful with `metering_mode`.
  #[uniffi(default = None)]
  pub target_gray: Option<f32>,
  /// Saturation/contrast boost switch. `None` = upstream default.
  #[uniffi(default = None)]
  pub enable_boost: Option<bool>,
  /// Saturation multiplier. `None` = upstream default.
  #[uniffi(default = None)]
  pub saturation: Option<f32>,
  /// Contrast multiplier. `None` = upstream default.
  #[uniffi(default = None)]
  pub contrast: Option<f32>,
  /// Contrast pivot point. `None` = upstream default.
  #[uniffi(default = None)]
  pub pivot: Option<f32>,
}

/// Lift the FFI record onto the glue's override struct.
///
/// A pure field-for-field mapping — including the `None`s, which stay `None` so
/// the glue can tell "unset" from "explicitly set to the upstream default value".
#[cfg(feature = "rawalchemy")]
impl From<&GradeParams> for rawalchemy_fotlab::GradeOverrides {
  fn from(p: &GradeParams) -> Self {
    Self {
      log_space: p.log_space.clone(),
      lut_path: p.lut_path.clone(),
      metering_mode: p.metering_mode.clone(),
      gain: p.gain,
      target_gray: p.target_gray,
      enable_boost: p.enable_boost,
      saturation: p.saturation,
      contrast: p.contrast,
      pivot: p.pivot,
    }
  }
}

/// FFI entry point: develop `raw` (already routed to the raw path) into a linear
/// **ProPhoto D50** RGB image (`RawlerImageDeveloped`) using `params` — the object handed
/// to the rawalchemy pipeline. This is the *editing* branch of the dual-fork
/// (`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`): wide gamut and **unclamped**,
/// so negative and >1 components survive for downstream tone/exposure work. No
/// gamma is applied — ProPhoto is a linear editing space.
///
/// Re-runs the full pipeline (decode included) on every call; the cached-decode
/// path lives in [`crate::loaded::RawlerImageLoaded`] (`FOTLAB-RAWLER-000004`).
#[uniffi::export]
pub fn develop(raw: &[u8], params: DevelopParams) -> Result<RawlerImageDeveloped, RawlerFotlabError> {
  if raw.is_empty() {
    return Err(RawlerFotlabError::Decode("empty input".to_string()));
  }
  panic::catch_unwind(AssertUnwindSafe(|| {
    let image = decode_to_rawimage(raw)?;
    develop_image(image, params, WorkingSpace::ProPhotoD50)
  }))
  .unwrap_or_else(|_| Err(RawlerFotlabError::Decode("rawler panicked during develop".to_string())))
}

/// Develop `raw` into linear ProPhoto-D50 and immediately hand the buffer to the
/// rawalchemy grading engine, returning the graded float buffer (e.g. F-Gamut +
/// F-Log). This is the single Rust→cxx hop that replaces the earlier
/// Kotlin-mediated handoff (`rules/REVIEW/detail/FOTLAB-RAWLER-000006`): rawler
/// owns decode + develop, `rawalchemy_fotlab` owns the grade, and Kotlin only
/// receives the final `Vec<f32>`.
///
/// Which stages run is decided entirely by [`GradeParams`] — an all-`None`
/// record means "run whatever upstream's defaults say" (gamut + log skipped,
/// LUT skipped, no metering, upstream's boost defaults). Kotlin therefore reaches
/// upstream's full parameter surface; this crate adds no policy of its own.
///
/// Requires the `rawalchemy` feature (which pulls in the `rawalchemy_fotlab` cxx
/// crate + the grading static lib). Without it this entry point is not compiled.
#[cfg(feature = "rawalchemy")]
#[uniffi::export]
pub fn develop_and_grade(
  raw: &[u8],
  params: DevelopParams,
  grade_params: GradeParams,
) -> Result<Vec<f32>, RawlerFotlabError> {
  if raw.is_empty() {
    return Err(RawlerFotlabError::Decode("empty input".to_string()));
  }
  let dev = develop(raw, params)?;
  let overrides = rawalchemy_fotlab::GradeOverrides::from(&grade_params);
  rawalchemy_fotlab::grade(&dev.rgb, dev.width, dev.height, &overrides)
    .map_err(|e| RawlerFotlabError::Decode(format!("rawalchemy grade failed: {e}")))
}

/// Develop an already-decoded [`RawImage`] into a linear RGB image in the requested
/// [`WorkingSpace`] (no transfer function).
///
/// The two trunk halves back to back: [`develop_to_camera_image`], then the OKLab stage and the
/// working-space projection in [`DemosaicedCameraImage::to_working_space`]. It is what the
/// stateless helpers use; the resident path ([`crate::loaded::RawlerImageLoaded`]) calls the
/// halves separately so it can keep the camera-space half between renders — and so it can read
/// the OKLab gate straight off the render's [`PipelineStages`] instead of re-deriving it here.
///
/// The pipeline mutates `image` in place — callers that must keep their `RawImage` must clone
/// it first (`FOTLAB-RAWLER-000004` §clone).
pub(crate) fn develop_image(
  image: RawImage,
  params: DevelopParams,
  space: WorkingSpace,
) -> Result<RawlerImageDeveloped, RawlerFotlabError> {
  let clip = params.clip_to_gamut && space == WorkingSpace::ProPhotoD50;
  let camera_image = develop_to_camera_image(image, &params)?;
  camera_image.to_working_space(space, oklab_enabled(&params), oklab_switches(&params), clip)
}

/// The OKLab stage's master gate, for callers that have no render dictionary to read it from -
/// the stateless entry points (`develop`, `develop_and_grade`, the metering path).
///
/// Derived exactly as Kotlin derives it for a real render: with both per-output sub-switches off
/// the stage is a guaranteed identity whichever space the output lands in, so there is nothing
/// to run. A resident render does not use this - it passes `PipelineStages::oklab` straight
/// through, so the dictionary stays the single source of truth wherever one exists.
pub(crate) fn oklab_enabled(params: &DevelopParams) -> bool {
  params.oklab_highlight_compress_srgb || params.oklab_highlight_compress_prophoto
}

/// The OKLab stage's per-output sub-switches, read off [`DevelopParams`].
///
/// The master gate is *not* here: it belongs to the render dictionary ([`PipelineStages::oklab`]),
/// because "is this stage part of this render" is a property of the render, not of the develop
/// parameters. The sub-switches are per-output parameters, so they travel with everything else the
/// caller assembled.
pub(crate) fn oklab_switches(params: &DevelopParams) -> OklabSwitches {
  OklabSwitches {
    highlight_compress_srgb: params.oklab_highlight_compress_srgb,
    highlight_compress_prophoto: params.oklab_highlight_compress_prophoto,
  }
}

/// The **develop stage** of the trunk: decode-side mosaic work through demosaic and white
/// balance, stopping at the cacheable camera-space boundary.
///
/// Everything the later stages need that is not per-pixel is resolved here and carried inside
/// the returned [`DemosaicedCameraImage`]: the D65 camera matrix the OKLab stage round-trips
/// through, the two camera-to-working matrices, and the crop rectangle. Nothing downstream
/// needs the `RawImage`, which is what lets the resident object drop its decoded pixels sooner
/// and lets a render whose later stages changed skip this half entirely.
///
/// The OKLab roll-off is deliberately NOT applied here - it is a stage of its own and runs
/// above this boundary (`crate::camera_space`).
pub(crate) fn develop_to_camera_image(
    mut image: RawImage,
    params: &DevelopParams,
) -> Result<DemosaicedCameraImage, RawlerFotlabError> {
  image
    .apply_scaling()
    .map_err(|e| RawlerFotlabError::Decode(e.to_string()))?;

  // Move the scaled f32 pixels OUT of the RawImage before demosaic so the
  // ~210 MB (50 MP) buffer is handed over zero-copy instead of duplicated;
  // the now-empty image still carries every metadata field the later stages
  // read (CFA/photometric, color matrix, wb, active/crop areas).
  let mut pixels = take_scaled_pixels(&mut image)?;

  // Pre-demosaic mosaic stages, composed as pure functions (`exposure.rs` /
  // `denoise.rs` / `dehaze.rs` / `ca.rs`). Each consumes the mosaic buffer and
  // returns it; `None` (or a zero strength) is the identity, so an unconfigured
  // stage is free.
  // Order: **Deprofile → Exposure → Denoise → Dehaze → CA**. Deprofile runs
  // first (see block below); exposure is applied next as the channel-uniform
  // linear `2^exposure_ev` gain, so the neighbour-quality
  // stages that follow solve their problems on the exposure-corrected source:
  //   * dehaze now reads the exposure-compensated mosaic, which removes the
  //     exposure-dependent dehaze failure — an underexposed capture no longer
  //     occupies only the low sub-range of [0,1], so its local dark channel is
  //     not globally scaled down (see `rules/REVIEW/detail/FOTLAB-RAWLER-000012.md` F7);
  //   * denoise is scale-invariant under a uniform linear gain (median + neighbour
  //     range scale together), so its result is identical on either side of
  //     exposure;
  //   * exposure is linear, so it still commutes with demosaic.
  // Trade-off: the dehaze histogram is now built on exposure-scaled values that
  // may exceed 1.0 for a positive EV; the existing [0,1] histogram-bin clamp and
  // the `cap_tail` clamp on the haze field keep the estimate bounded.
  let cfa = match &image.photometric {
    RawPhotometricInterpretation::Cfa(config) => Some(config),
    _ => None,
  };

  // Deprofile (pre-demosaic, in CFA mosaic space) — runs BEFORE exposure, matching
  // the design's "exposure 之前、CFA mosaic 空间" staging (`FOTLAB-NATIVE-000005`).
  // All of these are multiplicative on the mosaic, so the exact ordering among
  // them and exposure is immaterial, but placing them first keeps them on the
  // as-scaled raw. Every sub-stage is a no-op when its profile is `None` or its
  // flag is off, so an unconfigured render is unchanged.
  //
  //   * DCP: read-only parse; only the CFA-stage BaselineExposure scalar is
  //     applied here (`×= 2^offset`) when the user enables it. Colour matrix /
  //     HSD / Tone / Look belong to the RGB calibration stage (B5).
  //   * LCP: vignette + distortion are colour-independent, so they are applied
  //     directly to the CFA mosaic by `rawtherapee_fotlab::apply_lcp_cfa` (a Rust
  //     re-implementation of RT's `LCPMapper` apply over the decoded coefficients).
  //     CA is intentionally skipped (per-channel, RGB stage).
  if let Some(cp) = &params.camera_profile {
    match rawtherapee_fotlab::parse_dcp(&cp.path) {
      Ok(dcp) => {
        if cp.apply_baseline_exposure && dcp.has_baseline_exposure {
          let factor = 2.0f32.powf(dcp.baseline_exposure_offset as f32);
          pixels.par_iter_mut().for_each(|v| *v *= factor);
        }
      }
      Err(e) => log::warn!("deprofile: DCP parse failed for {}: {e}", cp.path),
    }
  }
  if let Some(lp) = &params.lens_profile {
    // Resolve the effective focal length across the priority chain documented on
    // `LensProfileParams`: user override (Tier 1) > decoded-RAW focal (Tier 2) >
    // LCP built-in focal (Tier 3) > constant (Tier 4). `parse_lcp` reads the
    // focal length the LCP file itself carries; if it has none, `None` passes
    // through to the constant.
    let lcp_builtin = rawtherapee_fotlab::parse_lcp(&lp.path)
      .ok()
      .and_then(|p| p.focal_length_mm);
    let focal = lp
      .focal_length
      .or(params.raw_focal_length_mm)
      .or(lcp_builtin)
      .unwrap_or(DEFAULT_LCP_FOCAL_MM);
    let focal35 = lp.focal_length_35mm.unwrap_or(focal);
    let focus = lp.focus_dist.unwrap_or(1.0);
    let aperture = lp.aperture.unwrap_or(8.0);
    if let Err(e) = rawtherapee_fotlab::apply_lcp_cfa(
      &lp.path,
      focal,
      focal35,
      focus,
      aperture,
      lp.apply_vignette,
      lp.apply_distortion,
      lp.raw_rotation_deg,
      image.width as usize,
      image.height as usize,
      &mut pixels,
    ) {
      log::warn!("deprofile: LCP apply failed for {}: {e}", lp.path);
    }
  }

  // Exposure first: the stage's min/max clip fused into the same rayon pass
  // (clamp, then scale; `exposure.rs`) followed by the `2^exposure_ev` linear
  // gain on the normalised mosaic, applied before the neighbour-quality stages.
  // Channel-uniform and per-element, so it commutes with demosaic.
  let pixels = apply_exposure(
    pixels,
    params.exposure_ev,
    params.exposure_clip_lower,
    params.exposure_clip_upper,
  );
  // Denoise (pre-demosaic mosaic): orchestrates two composed sub-stages in order
  // — (1) RT-style CFA impulse / hot-dead-pixel removal on `denoise_strength`,
  // then (2) BM3D-CFA collaborative filtering on the raw mosaic on
  // `denoise_bm3d_strength`. Each is independently `None`/zero = identity, so
  // enabling either alone is free. See `denoise.rs`.
  let pixels = denoise(
    pixels,
    image.width,
    image.height,
    params.denoise_strength,
    params.denoise_bm3d_strength,
    cfa,
  );
  // Dehaze: separate haze floor per CFA colour plane, as a configurable
  // `dehaze_percentile` of each plane's histogram, DCP-style contrast restore,
  // blended by `dehaze_strength`; histograms are restricted to the active area
  // so masked borders do not bias the estimate. Runs AFTER exposure so it sees
  // the exposure-corrected mosaic; the [0,1] histogram-bin clamp bounds the
  // estimate for a positive EV.
  let pixels = dehaze(
    pixels,
    image.width,
    image.height,
    params.dehaze_strength,
    params.dehaze_percentile,
    params.dehaze_ceiling,
    cfa,
    image.active_area.map(|r| (r.p.x, r.p.y, r.d.w, r.d.h)),
    params.dehaze_radius_dark,
    params.dehaze_radius_guide,
    params.dehaze_merge_mode,
    // Atmospheric light A = 1.0 (fully-saturated haze / white point). Kept as a
    // parameter at the FFI boundary for later per-channel / non-unity extension;
    // the dehaze apply formula `cleared = (v - A) / (1 - strength*h) + A` carries it
    // through unchanged. Kotlin still passes no A (constant today).
    1.0,
  );
  // CA correction: pre-demosaic radial CA on the full-frame mosaic (after
  // dehaze and exposure — like the other neighbour-quality stages it wants the
  // corrected source values). `None` is the identity; non-Bayer CFAs and kernel
  // failures degrade to the uncorrected mosaic (see `ca.rs`).
  let pixels = correct_ca(pixels, image.width, image.height, params.ca.as_ref(), cfa);

  // LoCA / purple-fringe correction: pre-demosaic, immediately after the lateral-CA
  // stage. Runs its own edge detection (never reuses `correct_ca`'s detector) so it
  // is correct whether or not LCA is enabled; raises G near magenta/bright edges to
  // neutralise the fringe. `None` is the identity; non-Bayer CFAs and kernel
  // failures degrade to the uncorrected mosaic (see `loca.rs`).
  let pixels = correct_loca(pixels, image.width, image.height, params.loca.as_ref(), cfa);

  // Demosaic stage — its ROI is already active_area, exactly like rawler's
  // Demosaic + FujiRotate + CropActiveArea steps. The algorithm alone picks the producer
  // (including quarter-resolution superpixel, `DemosaicAlgorithm::Superpixel`); all of them
  // return one `Intermediate`, which is the convergence point: from here on nothing knows which
  // one ran, and every pixel-count-dependent number is simply whatever the intermediate's
  // dimensions say.
  let intermediate = demosaic(&image, pixels, params.demosaic_algorithm)?;

  let wb = params.wb.as_ref().map(|v| {
    let mut a = [1.0f32; 4];
    for (i, x) in v.iter().take(4).enumerate() {
      a[i] = *x;
    }
    a
  });

  // Hand the debayered buffer across the cacheable boundary. `to_camera_space` applies white
  // balance — the last develop-stage step — and folds in the D65 camera matrix, the two
  // working-space matrices and the crop rectangle, so this function can return without knowing
  // which working space the caller wants. The OKLab roll-off is deliberately NOT applied here: it
  // is its own stage and runs above the cache (`crate::camera_space`).
  to_camera_space(intermediate, &image, wb)
}

/// Take ownership of the scaled f32 pixel buffer from [RawImage] without a
/// copy. [RawImage::apply_scaling] always converts the data to
/// [RawImageData::Float], so an integer buffer here means the scaling contract
/// changed upstream and is reported instead of silently converting.
fn take_scaled_pixels(image: &mut RawImage) -> Result<Vec<f32>, RawlerFotlabError> {
  match std::mem::replace(&mut image.data, RawImageData::Float(Vec::new())) {
    RawImageData::Float(v) => Ok(v),
    RawImageData::Integer(_) => Err(RawlerFotlabError::Decode(
      "scaled RawImage pixels are not f32 — apply_scaling contract changed".to_string(),
    )),
  }
}
