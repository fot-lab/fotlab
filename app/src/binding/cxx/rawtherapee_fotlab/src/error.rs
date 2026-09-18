//! Error type surfaced across the FFI boundary (mirrors rawler_fotlab's pattern).

use thiserror::Error;

/// Errors returned by the rawtherapee_fotlab demosaic entry point.
#[derive(Debug, Clone, Error, PartialEq, Eq)]
pub enum RtDemosaicError {
    /// Caller passed a malformed CFA / geometry (e.g. length mismatch, zero size,
    /// or an X-Trans request without a 6x6 pattern).
    #[error("invalid input: {0}")]
    InvalidInput(String),

    /// RawTherapee's demosaic algorithm rejected the call (message comes from
    /// `RawImageSource::demosaic_external` / the C++ shim).
    #[error("demosaic failed: {0}")]
    Demosaic(String),
}
