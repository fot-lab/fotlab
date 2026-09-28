//! Errors for DCP/LCP deprofile (parse + CFA apply).

use thiserror::Error;

#[derive(Debug, Error)]
pub enum DeprofileError {
    #[error("DCP/LCP parse failed: {0}")]
    Parse(String),

    #[error("LCP CFA apply failed: {0}")]
    Apply(String),

    #[error("invalid deprofile parameters: {0}")]
    Invalid(String),
}
