// ===========================================================================
// build.rs — compile the C++ shim + vendored DCP/LCP *parsers* and link them
// into the `rawtherapee_fotlab` native lib.
//
// What this does:
//   1. Compile the cxx bridge (`src/lib.rs`) into a C++ translation unit.
//   2. Compile the C++ adapter (`cxx/rt_deprofile_shim.cc`) that calls the
//      vendored RawTherapee DCP/LCP decoders.
//   3. Compile the vendored decoders themselves:
//        cxx/vendor/rtengine/dcp.cc   — DCP (TIFF) profile parser
//        cxx/vendor/rtengine/lcp.cc   — LCP (XML)  profile parser
//        cxx/vendor/expat/expat_minimal.cc — self-contained SAX XML reader
//   4. Hand the combined object file to the linker as `rawtherapee_fotlab`.
//
// IMPORTANT: this crate links NO `librtengine`, glibmm, lcms2, exiv2, etc.
// The vendored decoders are trimmed to constructors + getters + calcParams +
// prepareParams (apply methods removed; `Glib::ustring` -> `std::string`;
// expat replaced by `expat_minimal`). So the only runtime dependency is the
// C++ standard library. This is what keeps the crate clear of the GPL
// `librtengine` link. See README.md (Licensing).
//
// No `RAWTHERAPEE_SRC` / `RAWTHERAPEE_LIB` environment variables are read — the
// decoded sources are vendored into this crate and built directly.
// ===========================================================================

use std::env;

fn main() {
    let manifest = env!("CARGO_MANIFEST_DIR");

    let mut build = cxx_build::bridge("src/lib.rs");

    // --- include paths -------------------------------------------------------
    // Crate root: the bridge's `include!("rt_deprofile_shim.h")` (and the shim's
    // own include of it) resolves against the crate root, exactly like
    // rawalchemy_fotlab.
    build.include(manifest);
    // Vendored RT decoder headers (dcp.h / lcp.h / rt_math.h live here).
    build.include(format!("{manifest}/cxx/vendor/rtengine"));
    // expat_minimal.h, included by lcp.h.
    build.include(format!("{manifest}/cxx/vendor/expat"));

    // --- sources -------------------------------------------------------------
    // The C++ adapter (thin wrapper over the vendored decoders).
    build.file("cxx/rt_deprofile_shim.cc");
    // Vendored RawTherapee decoders (decode ONLY — no apply, no glibmm).
    build.file("cxx/vendor/rtengine/dcp.cc");
    build.file("cxx/vendor/rtengine/lcp.cc");
    // Self-contained expat replacement (drives lcp.cc's SAX handlers).
    build.file("cxx/vendor/expat/expat_minimal.cc");

    build.flag_if_supported("-std=c++17");
    // No -fopenmp: the vendored decode paths contain no OpenMP loops (the
    // parallelised loops lived in the apply code, which we do not vendor).
    build.compile("rawtherapee_fotlab");

    // --- C++ standard library ------------------------------------------------
    // This crate is an rlib consumed by `rawler_fotlab`'s cdylib, so the C++
    // stdlib must reach the final artifact via `rustc-link-lib` (per cargo
    // #9554, `rustc-link-arg` would not propagate). cxx-build adds it too, but
    // be explicit to mirror the working rawalchemy_fotlab crate.
    let target = env::var("TARGET").unwrap_or_default();
    let cxx_stdlib = if target.contains("android") {
        "c++"
    } else {
        "stdc++"
    };
    println!("cargo:rustc-link-lib=dylib={cxx_stdlib}");

    // --- rebuild triggers ----------------------------------------------------
    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=cxx/rt_deprofile_shim.h");
    println!("cargo:rerun-if-changed=cxx/rt_deprofile_shim.cc");
    println!("cargo:rerun-if-changed=cxx/vendor/rtengine/dcp.h");
    println!("cargo:rerun-if-changed=cxx/vendor/rtengine/dcp.cc");
    println!("cargo:rerun-if-changed=cxx/vendor/rtengine/lcp.h");
    println!("cargo:rerun-if-changed=cxx/vendor/rtengine/lcp.cc");
    println!("cargo:rerun-if-changed=cxx/vendor/rtengine/rt_math.h");
    println!("cargo:rerun-if-changed=cxx/vendor/expat/expat_minimal.h");
    println!("cargo:rerun-if-changed=cxx/vendor/expat/expat_minimal.cc");
}
