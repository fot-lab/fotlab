//! Ported **Bayer** demosaic kernels — one file per algorithm, each named after
//! the RawTherapee source it came from.
//!
//! Upstream defines these as `RawImageSource` members in `rtengine/*.cc`; here
//! each is a free function taking `(&CfaDesc, &Array2D<f32>, …)`, which is the
//! whole of what the kernels actually touch (`FOTLAB-NATIVE-000004` D2).

pub mod ahd;
pub mod bilinear;
pub mod dcb;
pub mod hphd;
pub mod igv;
pub mod interp;
pub mod lmmse;
pub mod rcd;
pub mod vng4;
