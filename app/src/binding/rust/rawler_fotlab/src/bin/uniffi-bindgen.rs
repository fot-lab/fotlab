//! The `uniffi-bindgen` CLI for this crate (`--features cli`).
//!
//! Shipping the generator as a binary *inside* the crate guarantees the generator and
//! the `uniffi` runtime in `librawler_fotlab.so` are always the same version, so the
//! bindings can never drift from the library. CI runs it as:
//!
//! ```text
//! cargo run --features cli --bin uniffi-bindgen -- \
//!   generate --library <librawler_fotlab.so> --language kotlin --out-dir <dir>
//! ```

fn main() {
    uniffi::uniffi_bindgen_main()
}
