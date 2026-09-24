//! rawler_fotlab — FotLab's first-party native binding over the upstream `rawler` crate.
//!
//! This is the only native library we ship, and the only one that carries a FotLab name:
//! **`librawler_fotlab.so`**. Upstream `rawler` is linked in from `external/dnglab/rawler`
//! as its original crate and keeps its own name (`FOTLAB-NATIVE-000001` R4 — upstream is
//! read-only; we never edit or re-publish it).
//!
//! It exposes four UniFFI functions, matching the native calls of the raw render
//! path (`FOTLAB-STUDIO-000001` R8):
//!   * `identify`       — call #1: format identification only, never pixel decode.
//!   * `decode_to_png`  — call #2: decode the already-identified RAW and encode a
//!     **grayscale raw preview** PNG (no demosaic / calibrate) via `bound::fotraw_to_png`.
//!     This is what Studio shows on first open, before any demosaic choice.
//!   * `develop`        — *editing* branch: decode + demosaic + calibrate into a
//!     **linear ProPhoto D50** RGB image (`RawlerImageDeveloped`), unclipped, for the
//!     rawalchemy pipeline. Wide gamut; negatives and >1 survive
//!     (`FOTLAB-RAWLER-000005`). No gamma — ProPhoto is a linear editing space.
//!   * `develop_to_png` — *presentation* branch: same develop pipeline, but built in
//!     sRGB D65 and then finished into a display-ready PNG by
//!     `bound::rawlerimagedeveloped_to_png`, which applies the sRGB transfer function (gamma)
//!     and clips to [0,1]. This is what Studio renders after the user picks a
//!     demosaic algorithm from the bottom-bar menu.
//!   * `develop_and_grade` — *grading* branch (behind the `rawalchemy` feature, on by
//!     default): same develop as above, but the linear ProPhoto-D50 buffer is handed
//!     to the rawalchemy grading engine in the same call and the **graded** float
//!     buffer comes back. Which stages run is chosen entirely by `GradeParams`, whose
//!     optional fields deliberately expose upstream's full parameter surface
//!     (`FOTLAB-RAWLER-000006`). The resident object additionally exposes
//!     `develop_and_grade_to_png` / `..._at_kelvin`, which quantize the graded
//!     buffer straight to a display PNG (no transfer function), and
//!     `supported_log_spaces` lists the log curves the Studio LOG chooser offers.
//!     Studio's grade bar drives the PNG variants; changing a develop parameter
//!     (demosaic / exposure / WB) re-renders the sRGB fork above, changing a grade
//!     parameter (Boost / LOG / LUT) re-renders the graded PNG fork.
//!   * `demosaic_candidates` — the algorithm menu: the concatenation of rawler's
//!     own demosaics (`RAWLER …`) and the RawTherapee kernels ported in
//!     `rawtrp_demos` (`RAWTRP …`), filtered to the ones that are actually wired.
//!     It is **not** a develop call: it is called once so `StudioScreen` can build
//!     the dropdown instead of hardcoding it, and each entry carries the
//!     `DemosaicAlgorithm` the menu sends back (`FOTLAB-NATIVE-000004` D5).
//!
//! # Pipeline split (`FOTLAB-FOTRAW-000001`)
//!
//! `decode_to_png` used to be a single monolithic function. It is now a thin
//! orchestrator over the three stages of the canonical RAW intermediate spec:
//!
//! 1. [`decode::decode_to_rawimage`] — decode the RAW into rawler's `RawImage`.
//! 2. [`intermediate::rawimage_to_fotraw`] — project `RawImage` into our canonical
//!    IR `FotRaw` (pure pixel buffer + three tag namespaces).
//! 3. [`bound::fotraw_to_png`] — bit-shift preview encode `FotRaw` → PNG.
//!
//! The `FotRaw` IR does **not** cross any FFI boundary yet; it is an in-Rust
//! intermediate and only PNG bytes are returned to Kotlin. `intermediate.rs` is the
//! Rust implementation of `rules/STRUCT/detail/FOTLAB-FOTRAW-000001.md`.
//!
//! # Crash hardening (FOTLAB-CRASH-000001)
//!
//! Rawler's decoders call `panic!` / `unreachable!` / index out of bounds on input they
//! do not expect (truncated files, formats they half-support, non-RAW bytes probed by the
//! sniffer). A Rust panic that unwinds across the `extern "C"` FFI frame is **undefined
//! behaviour** and the runtime aborts the whole process (SIGABRT). Kotlin's
//! `runCatching` only catches JVM `Throwable`, so it *cannot* catch this — which is exactly
//! why every image, RAW or PNG, used to crash the app the moment rawler was called.
//!
//! The fix is to wrap every rawler entry point in [`std::panic::catch_unwind`] so a panic
//! is contained inside Rust and turned into a normal return value (a `None` / `Err`) that
//! crosses the FFI boundary safely. This is FFI-mechanism-independent: JNI or a hand-rolled
//! C ABI would have crashed identically. UniFFI is therefore kept; only the panic boundary
//! is hardened. The whole split pipeline runs inside one `catch_unwind` boundary.

use std::panic::{self, AssertUnwindSafe};

use rawler::decoders::RawDecodeParams;
use rawler::rawsource::RawSource;

mod bound;
mod ca;
mod calibrate;
mod cfa;
mod decode;
mod dehaze;
mod dehaze_guided_filter;
mod demosaic;
mod denoise;
mod denoise_bm3d_cfa;
mod denoise_impulse;
mod develop;
mod exposure;
mod intermediate;
mod loaded;
mod wb;

use demosaic::DemosaicAlgorithm;
use develop::DevelopParams;

/// Error type surfaced to Kotlin over UniFFI.
///
/// `thiserror` provides the `Display` impl UniFFI needs to carry the message across the
/// FFI boundary; the variant itself becomes a Kotlin `sealed class` case.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum RawlerFotlabError {
    /// rawler does not recognize the input as a camera RAW it supports.
    #[error("unsupported input: {0}")]
    Unsupported(String),
    /// rawler recognized the input but failed while decoding it (incl. a caught panic).
    #[error("decode failed: {0}")]
    Decode(String),
}

/// Call #1 — identification only.
///
/// Returns `make/model` (the format label the app routes on) when rawler recognizes the
/// bytes, else `None`. Never decodes pixels — this is the cheap probe that runs in
/// parallel with the Coil-side sniffer inside `FormatSniffer.sniff`.
///
/// Identification is done with `rawler::get_decoder` + `Decoder::raw_metadata`, NOT
/// `rawler::decode_dummy`. `decode_dummy` runs the *full* decoder — it still parses and
/// walks the compressed pixel data to size its output buffer — so it requires the entire
/// RAW on hand and fails when fed the 1 MiB sniff header `StudioEngine` provides. That
/// failure was silent: the probe returned `None`, so large RAWs (Nikon NEF, Canon CR2)
/// were wrongly demoted to the Coil branch. `get_decoder` only matches the
/// container/format and `raw_metadata` reads the EXIF block at the file head, so both
/// settle from the header alone (`FOTLAB-STUDIO-000001` R8).
///
/// The rawler work is wrapped in `catch_unwind`: a panic (e.g. on malformed/non-RAW input)
/// degrades to `None` instead of aborting the process. Empty input is rejected outright to
/// avoid any unwrap-panic inside `RawSource::new_from_slice`.
#[uniffi::export]
pub fn identify(raw: &[u8]) -> Option<String> {
    if raw.is_empty() {
        return None;
    }
    panic::catch_unwind(AssertUnwindSafe(|| {
        let src = RawSource::new_from_slice(raw);
        let decoder = rawler::get_decoder(&src).ok()?;
        let meta = decoder.raw_metadata(&src, &RawDecodeParams::default()).ok()?;
        Some(format!("{}/{}", meta.make, meta.model))
    }))
    .unwrap_or(None)
}

/// Call #2 — decode the already-identified RAW to a **grayscale raw preview** PNG.
///
/// Now delegates to [`loaded::RawlerImageLoaded`]: it decodes once and returns the
/// resident object, then previews from the cached decode (`FOTLAB-RAWLER-000004`).
/// The stateless free function keeps its signature so existing callers/tests are
/// unaffected; the cached-decode path is what `StudioEngine` drives.
#[uniffi::export]
pub fn decode_to_png(raw: &[u8]) -> Result<Vec<u8>, RawlerFotlabError> {
    let loaded = loaded::decode_rawler_image(raw)?;
    loaded.preview_png()
}

/// Render call — develop the already-identified RAW and encode it straight to PNG.
///
/// Now delegates to [`loaded::RawlerImageLoaded`]: decodes once and develops from
/// the cached decode (`FOTLAB-RAWLER-000004`). The stateless free function keeps
/// its signature for existing callers; `StudioEngine` drives the cached path.
#[uniffi::export]
pub fn develop_to_png(raw: &[u8], params: DevelopParams) -> Result<Vec<u8>, RawlerFotlabError> {
    let loaded = loaded::decode_rawler_image(raw)?;
    loaded.develop_to_png(params)
}

/// Log spaces the rawalchemy grading engine accepts, as UI display names
/// (`"FUJIFILM F-Log2 C"`, `"Sony S-Log3"`, `"ARRI LogC4"`, …), sorted for a
/// stable Studio LOG menu. These are exactly the strings [`GradeParams::log_space`]
/// takes back; the cxx shim owns the display↔canonical alias table, so this crate
/// neither spells the names nor knows upstream's keys (`FOTLAB-RAWLER-000006`,
/// `rules/REVIEW/detail/ACTION-RAWLER-000007.md`). Gated on the `rawalchemy`
/// feature (on by default); without the feature the export is not compiled.
///
/// [`GradeParams::log_space`]: crate::GradeParams::log_space
#[cfg(feature = "rawalchemy")]
#[uniffi::export]
pub fn supported_log_spaces() -> Vec<String> {
    let mut spaces = rawalchemy_fotlab::log_spaces();
    spaces.sort();
    spaces
}

/// Which sensor family a [`DemosaicCandidate`] applies to.
///
/// The UI greyed nothing out before this existed; carrying the kind lets the
/// menu state applicability instead of offering a pick that silently resolves to
/// something else (`FOTLAB-NATIVE-000004` D3).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum DemosaicSensorKind {
    /// A 2x2-periodic Bayer CFA.
    Bayer,
    /// A 6x6 Fujifilm X-Trans CFA.
    XTrans,
}

/// One selectable demosaic algorithm, ready to render as a menu entry.
///
/// Same shape as `rawtrp_demos::algo::Candidate` plus the transport value, so the
/// menu needs no id→algorithm table of its own on the Kotlin side — which is what
/// keeps the list and the dispatch from drifting apart.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct DemosaicCandidate {
    /// Stable machine identifier, never localised (`rawler:ppg`,
    /// `rawtrp:vng4`). The menu's key.
    pub id: String,
    /// Standard display name (`RAWLER …` / `RAWTRP …`).
    pub label: String,
    /// Sensor family this candidate is meant for.
    pub kind: DemosaicSensorKind,
    /// What to hand back in `DevelopParams::demosaic_algorithm`.
    pub algorithm: DemosaicAlgorithm,
}

/// The demosaic algorithms the menu may offer, in display order.
///
/// This is the single source for the Studio dropdown: the concatenation of
/// rawler's own demosaics and the ported RawTherapee kernels
/// (`rawtrp_demos::algo::candidates`), each paired with the [`DemosaicAlgorithm`]
/// that selects it. A kernel that is catalogued but not ported is filtered out
/// upstream in `rawtrp_demos`, so the menu cannot offer a path that would fail.
///
/// Cheap enough to call once at startup, but it does load upstream tables, so the
/// Kotlin side caches the result rather than calling it per recomposition.
///
/// # Panics
///
/// It does not: a candidate with no dispatchable variant is skipped rather than
/// unwrapped. That should never happen — `demosaic_candidates_maps_every_advertised_id_to_a_variant`
/// fails the test suite if it starts to.
#[uniffi::export]
pub fn demosaic_candidates() -> Vec<DemosaicCandidate> {
    rawtrp_demos::candidates()
        .iter()
        .filter_map(|c| match algorithm_for_candidate(c) {
            Some(algorithm) => Some(DemosaicCandidate {
                id: c.id.to_string(),
                label: c.label.to_string(),
                kind: match c.kind {
                    rawtrp_demos::SensorKind::Bayer => DemosaicSensorKind::Bayer,
                    rawtrp_demos::SensorKind::XTrans => DemosaicSensorKind::XTrans,
                },
                algorithm,
            }),
            None => {
                log::warn!("demosaic candidate '{}' has no dispatchable variant; leaving it out of the menu", c.id);
                None
            }
        })
        .collect()
}

/// Fold a catalogue entry back onto the transport enum.
///
/// The RAWTRP half needs no second table: its ids are `rawtrp:` + the upstream
/// method string, so they go through `BayerAlgo::from_original_name` /
/// `XTransAlgo::from_original_name` in `rawtrp_demos::algo`, and the paired
/// `DemosaicAlgorithm::from_rawtrp_bayer` / `from_rawtrp_xtrans` map the kernel
/// onto its variant.
///
/// The two families are told apart by `kind`, **not** by name: `fast` exists on
/// both sides — Bayer's `fast_demosaic` and X-Trans's `fast_xtrans_interpolate` —
/// and the X-Trans id is spelled `rawtrp:xtrans_fast` partly so that stays
/// visible. Resolving by name alone would hand an X-Trans `fast` pick to the
/// Bayer kernel.
///
/// `None` means "no variant to carry this" — a catalogued but unported kernel
/// (`IMPLEMENTED_*` keeps those out of the menu, and this guard test
/// double-checks the pairing).
fn algorithm_for_candidate(candidate: &rawtrp_demos::Candidate) -> Option<DemosaicAlgorithm> {
    if let Some(rest) = candidate.id.strip_prefix("rawtrp:") {
        return match candidate.kind {
            rawtrp_demos::SensorKind::Bayer => {
                rawtrp_demos::BayerAlgo::from_original_name(rest).and_then(DemosaicAlgorithm::from_rawtrp_bayer)
            }
            // The two families are told apart by `kind`, never by name: `fast`
            // exists on both sides (Bayer's `fast_demosaic` and X-Trans's
            // `fast_xtrans_interpolate`), and the X-Trans id is spelled
            // `rawtrp:xtrans_fast` so the split stays visible in the id.
            rawtrp_demos::SensorKind::XTrans => {
                let original = if rest == "xtrans_fast" { "fast" } else { rest };
                rawtrp_demos::XTransAlgo::from_original_name(original).and_then(DemosaicAlgorithm::from_rawtrp_xtrans)
            }
        };
    }

    Some(match candidate.id {
        "rawler:default" => DemosaicAlgorithm::Default,
        "rawler:ppg" => DemosaicAlgorithm::Ppg,
        "rawler:bilinear4" => DemosaicAlgorithm::Bilinear4Channel,
        "rawler:xtrans_bilinear" => DemosaicAlgorithm::XTransBilinear,
        _ => return None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Every candidate the catalogue advertises must be dispatchable. The two
    /// sides are independent — the catalogue is filtered by
    /// `rawtrp_demos::algo::IMPLEMENTED_*`, this resolver by which variants exist
    /// — so a kernel that is ported and advertised while its variant is missing
    /// shows up here rather than as a menu entry that quietly does nothing.
    #[test]
    fn demosaic_candidates_maps_every_advertised_id_to_a_variant() {
        let advertised = rawtrp_demos::candidates();
        assert!(!advertised.is_empty());

        let unresolved: Vec<&str> = advertised.iter().filter(|c| algorithm_for_candidate(c).is_none()).map(|c| c.id).collect();
        assert!(unresolved.is_empty(), "advertised but undispatchable: {unresolved:?}");

        // …and the enumeration must actually be reachable, i.e. the resolver has
        // to know every id the catalogue can mint for the wired families.
        assert_eq!(demosaic_candidates().len(), advertised.len());
    }

    /// The rawler four keep their meaning: they were the whole menu before the
    /// RAWTRP variants were appended, so their ids must still resolve to the
    /// original variants (`FOTLAB-NATIVE-000004` D5 — appended, never interleaved).
    #[test]
    fn the_rawler_four_keep_their_original_variants() {
        assert_eq!(rawtrp_demos::candidates().iter().filter(|c| c.label.starts_with("RAWLER ")).count(), 4);
        assert_eq!(algorithm_for_candidate(&candidate("rawler:default")), Some(DemosaicAlgorithm::Default));
        assert_eq!(algorithm_for_candidate(&candidate("rawler:ppg")), Some(DemosaicAlgorithm::Ppg));
        assert_eq!(
            algorithm_for_candidate(&candidate("rawler:bilinear4")),
            Some(DemosaicAlgorithm::Bilinear4Channel)
        );
        assert_eq!(
            algorithm_for_candidate(&candidate("rawler:xtrans_bilinear")),
            Some(DemosaicAlgorithm::XTransBilinear)
        );
    }

    /// The RAWTRP ids resolve to the appended variants, and the two `fast`s are
    /// kept apart by `kind` rather than by name.
    #[test]
    fn rawtrp_ids_resolve_and_the_two_fasts_stay_apart() {
        let cases = [
            ("rawtrp:bilinear", DemosaicAlgorithm::RawtrpBilinear),
            ("rawtrp:vng4", DemosaicAlgorithm::RawtrpVng4),
            ("rawtrp:rcd", DemosaicAlgorithm::RawtrpRcd),
            ("rawtrp:igv", DemosaicAlgorithm::RawtrpIgv),
            ("rawtrp:lmmse", DemosaicAlgorithm::RawtrpLmmse),
            ("rawtrp:dcb", DemosaicAlgorithm::RawtrpDcb),
            ("rawtrp:hphd", DemosaicAlgorithm::RawtrpHphd),
            ("rawtrp:amaze", DemosaicAlgorithm::RawtrpAmaze),
            ("rawtrp:fast", DemosaicAlgorithm::RawtrpFast),
            ("rawtrp:one_pass", DemosaicAlgorithm::RawtrpXTransOnePass),
        ];
        for (id, expected) in cases {
            assert_eq!(algorithm_for_candidate(&candidate(id)), Some(expected), "{id}");
        }

        // Catalogued but unported (`IMPLEMENTED_*` excludes it, so the
        // catalogue never offers it): the resolver refuses it too — the two guards
        // agree, which is what makes the "one kernel per change" rule safe.
        // EAHD is parked on the colour-matrix criterion, `three_pass` on the
        // same one for X-Trans (`FOTLAB-NATIVE-000004` rev 12).
        let parked = rawtrp_demos::Candidate {
            id: "rawtrp:eahd",
            label: "RAWTRP eahd",
            kind: rawtrp_demos::SensorKind::Bayer,
        };
        assert_eq!(algorithm_for_candidate(&parked), None);
        assert!(
            !rawtrp_demos::candidates().iter().any(|c| c.id == "rawtrp:eahd"),
            "eahd is parked and must not be advertised"
        );

        // The two `fast`s: the Bayer one is `SensorKind::Bayer`, and X-Trans
        // `fast` must not be answered with it — it resolves to its own variant.
        let bayer_fast = candidate("rawtrp:fast");
        assert_eq!(bayer_fast.kind, rawtrp_demos::SensorKind::Bayer);
        assert_eq!(algorithm_for_candidate(&bayer_fast), Some(DemosaicAlgorithm::RawtrpFast));
        let xtrans_fast = candidate("rawtrp:xtrans_fast");
        assert_eq!(xtrans_fast.kind, rawtrp_demos::SensorKind::XTrans);
        assert_eq!(algorithm_for_candidate(&xtrans_fast), Some(DemosaicAlgorithm::RawtrpXTransFast));
    }

    /// Build the same catalogue entry the menu would carry, for the id under test.
    fn candidate(id: &str) -> rawtrp_demos::Candidate {
        rawtrp_demos::candidates()
            .into_iter()
            .find(|c| c.id == id)
            .unwrap_or_else(|| panic!("'{id}' is not advertised"))
    }
}

uniffi::setup_scaffolding!();
