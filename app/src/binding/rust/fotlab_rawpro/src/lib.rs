//! `fotlab_rawpro` — pure-Rust re-implementation of the ExifTool TIFF / DNG / DCP / LCP
//! profile parsers.
//!
//! ## Scope (per research, 2026-09-28)
//! Extract only the four format parsers we currently get from `external/exiftool`:
//! - **TIFF / DNG / DCP**: TIFF-based container. DCP is a DNG camera profile
//!   (TIFF magic `0x4352`); DNG is identified by the `DNGVersion` tag (`0xC612`).
//!   Shared generic IFD walker + data-driven tag tables.
//! - **LCP**: Adobe Lens Profile = ZIP (`PK`) container wrapping an inner
//!   lens-profile XML.
//!
//! ## Status
//! No parser code has been written yet. This crate is a scaffold created during the
//! market-survey step. See the chat summary for candidate Rust crates
//! (exif-oxide, image-tiff, kamadak-exif, quick-xml, zip, thiserror).
