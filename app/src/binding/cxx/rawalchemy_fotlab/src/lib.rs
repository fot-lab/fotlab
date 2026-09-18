//! cxx bridge into the RawAlchemyCpp grading engine.
//!
//! [`grade`] takes the linear **ProPhoto D50** float buffer produced by
//! `rawler_fotlab`'s editing branch and runs the upstream fused grading pipeline
//! (`rawalchemy::applyGradingFused`: gain → saturation/contrast → gamut → log →
//! optional LUT). The C++ side lives in `cpp/rawalchemy_shim.cc`.
//!
//! # Thin pass-through, upstream owns the defaults
//!
//! The glue is deliberately NOT a policy layer. It constructs `GradingParams`
//! with its own upstream defaults and overrides only the fields the caller
//! actually set; every `Option::None` in [`GradeOverrides`] means "leave the
//! upstream default" and every empty string means "skip that stage". Nothing here
//! re-states a value upstream owns, so upstream default changes flow through
//! (`rules/REVIEW/detail/FOTLAB-RAWLER-000006.md`).
//!
//! This crate deliberately does NOT modify the RawAlchemyCpp submodule: it only
//! calls the public grading API (`applyGradingFused`, `LOG_SPACES`,
//! `loadCubeLUT`, `computeAutoGain`, `GradingParams`, `ImageBuffer`) and
//! re-implements the small parameter assembly that the submodule's
//! file-decoding C API keeps in an anonymous-namespace helper.

#[cxx::bridge]
mod ffi {
    // `unsafe extern "C++"` (not a bare block): a block containing at least one
    // safe-to-call signature must be written `unsafe extern`, as an item-level
    // assertion that each one really is safe to call from Rust. Ours are: the
    // shim validates the buffer length and reports every other failure by throwing.
    unsafe extern "C++" {
        // Names the header carrying the matching C++ declaration. cxx's generators
        // do NOT read it — it gets #include'd and used in static assertions — and
        // for a bridge made purely of extern "C++" declarations there is no
        // generated `.rs.h` to include instead (cxx only emits one for an
        // `extern "Rust"` block or for shared structs). Hence our own header.
        include!("cpp/rawalchemy_api.h");

        /// Run the fused grading pipeline over a linear ProPhoto-D50 RGB buffer.
        ///
        /// `data` is row-major interleaved `width*height*3` float32 — the same
        /// layout as `rawler_fotlab::RawlerImageDeveloped.rgb`. Returns the graded
        /// buffer in that same layout.
        ///
        /// "Unset" is encoded out of band, because cxx has no `Option<f32>`:
        /// `log_space` / `lut_path` / `metering_mode` empty = that stage is
        /// skipped; `gain` / `target_gray` / `saturation` / `contrast` / `pivot`
        /// = NaN means "leave the upstream `GradingParams` default";
        /// `enable_boost` is a tri-state `-1` unset / `0` off / `1` on.
        ///
        /// `Result` must be written WITHOUT a second type parameter in a bridge:
        /// cxx turns a thrown C++ exception into `Err(cxx::Exception)` by itself,
        /// so the error type is fixed and not ours to name.
        fn grade(
            data: &[f32],
            width: u32,
            height: u32,
            log_space: &str,
            lut_path: &str,
            metering_mode: &str,
            gain: f32,
            target_gray: f32,
            enable_boost: i32,
            saturation: f32,
            contrast: f32,
            pivot: f32,
        ) -> Result<Vec<f32>>;
    }
}

/// Sentinel for "the caller did not set `enable_boost`" — leave the upstream
/// default. Mirrored by `kBoostUnset` in `cpp/rawalchemy_shim.cc`.
pub const BOOST_UNSET: i32 = -1;
/// Explicitly switch the saturation/contrast boost off.
pub const BOOST_OFF: i32 = 0;
/// Explicitly switch the saturation/contrast boost on.
pub const BOOST_ON: i32 = 1;

/// Optional overrides for upstream's `rawalchemy::GradingParams`.
///
/// **Every field defaults to `None`, i.e. "the engine decides".** The struct
/// carries no default *values* — only the absence of an opinion, which is what
/// keeps upstream the single owner of its own defaults.
#[derive(Debug, Clone, Default)]
pub struct GradeOverrides {
    /// Log space name (e.g. `"F-Log"`, `"S-Log3"`). Also selects the
    /// ProPhoto→target gamut matrix. `None` = skip gamut **and** log encode.
    pub log_space: Option<String>,
    /// `.cube` 3D LUT path, applied to the log-encoded image. `None` = no LUT.
    pub lut_path: Option<String>,
    /// Metering mode for `computeAutoGain` (e.g. `"matrix"`). `None` = no
    /// automatic metering; the metered base stays at unity.
    pub metering_mode: Option<String>,
    /// Upstream's raw `GradingParams::gain` exposure multiplier — **not** an EV
    /// and **not** the front end's develop exposure. `DevelopParams.exposure_ev`
    /// is applied by rawler to the mosaic before demosaic and never reaches this
    /// stage; this multiplier only scales the linear ProPhoto data handed to the
    /// grading loop. `None` = upstream default (unity) = touch nothing.
    /// Combined with metering as `metered_base * gain`, which is upstream's own
    /// expression (`computeAutoGain(...) * 2^evOffset`) minus our EV wrapping.
    pub gain: Option<f32>,
    /// Target gray for `computeAutoGain`. `None` = upstream default (`0.18`).
    /// Only meaningful together with `metering_mode`.
    pub target_gray: Option<f32>,
    /// Saturation/contrast boost switch. `None` = upstream default.
    pub enable_boost: Option<bool>,
    /// Saturation multiplier. `None` = upstream default.
    pub saturation: Option<f32>,
    /// Contrast multiplier. `None` = upstream default.
    pub contrast: Option<f32>,
    /// Contrast pivot point. `None` = upstream default.
    pub pivot: Option<f32>,
}

/// Rust wrapper around the cxx `grade` call: flattens [`GradeOverrides`] onto the
/// sentinel-encoded bridge signature documented above.
///
/// The bridge hands back `Err(cxx::Exception)` for anything the C++ side throws
/// (`std::runtime_error` on an unknown log space, an unreadable `.cube`, an
/// unsupported metering mode, a bad buffer length). Its `Display` is the
/// exception's `what()`, so it flattens to a plain `String` here and the caller
/// never has to know which FFI mechanism produced the failure.
pub fn grade(
    data: &[f32],
    width: u32,
    height: u32,
    overrides: &GradeOverrides,
) -> Result<Vec<f32>, String> {
    ffi::grade(
        data,
        width,
        height,
        overrides.log_space.as_deref().unwrap_or(""),
        overrides.lut_path.as_deref().unwrap_or(""),
        overrides.metering_mode.as_deref().unwrap_or(""),
        overrides.gain.unwrap_or(f32::NAN),
        overrides.target_gray.unwrap_or(f32::NAN),
        match overrides.enable_boost {
            None => BOOST_UNSET,
            Some(false) => BOOST_OFF,
            Some(true) => BOOST_ON,
        },
        overrides.saturation.unwrap_or(f32::NAN),
        overrides.contrast.unwrap_or(f32::NAN),
        overrides.pivot.unwrap_or(f32::NAN),
    )
    .map_err(|e| e.to_string())
}
