use std::env;
use std::path::Path;

/// Build the `rawalchemy_fotlab` cxx bridge.
///
/// Two pieces of native code get linked into the final cdylib:
///
/// 1. `librawalchemy_grading.a` — built by `cpp/CMakeLists.txt` from ONLY the
///    grading subset of `external/RawAlchemyCpp` (grading_fused / log_transform /
///    lut_applier / metering). No decode, no demosaic, no NN — those pull in
///    LibRaw / ONNX and are intentionally excluded (`FOTLAB-RAWLER-000006`).
/// 2. The cxx-generated bridge + `cpp/rawalchemy_shim.cc`, which assembles a
///    `GradingParams` and calls the public `rawalchemy::applyGradingFused`.
///
/// The RawAlchemyCpp submodule is never modified: every symbol we touch
/// (`applyGradingFused`, `LOG_SPACES`, `loadCubeLUT`, `computeAutoGain`,
/// `GradingParams`, `ImageBuffer`) is part of its public header API.
fn main() {
    // Locate external/RawAlchemyCpp. Allow an override via RAWALCHEMY_SRC; else
    // anchor on the submodule's own header while walking up from this crate.
    //
    // Do NOT count `..` levels here: this crate already moved once (it lives under
    // binding/cxx/, not as a sibling of rawler_fotlab) and a hard-coded count went
    // stale silently — cmake then just failed deep inside with "cannot find source
    // file". Testing for a file we know exists cannot rot the same way.
    let manifest = env!("CARGO_MANIFEST_DIR");
    let rawalchemy_src = env::var("RAWALCHEMY_SRC").unwrap_or_else(|_| {
        Path::new(manifest)
            .ancestors()
            .map(|dir| dir.join("external/RawAlchemyCpp"))
            .find(|cand| cand.join("include/grading_fused.h").is_file())
            .expect(
                "rawalchemy_fotlab: could not locate external/RawAlchemyCpp above \
                 CARGO_MANIFEST_DIR — check the submodule is checked out, or set RAWALCHEMY_SRC",
            )
            .to_string_lossy()
            .into_owned()
    });

    // Target triple (set by cargo per Android ABI). Used both to select the NDK
    // cmake toolchain below and the C++ stdlib later.
    let target = env::var("TARGET").unwrap_or_default();

    // 1. Grading subset -> static lib (cmake crate runs cpp/CMakeLists.txt).
    // When cross-compiling for Android, point cmake at the NDK toolchain so the
    // grading static lib is built for the right ABI; otherwise cmake silently uses
    // the host compiler and the final cargo-ndk link fails with an arch mismatch.
    // `ANDROID_NDK_HOME` is exported by build_rust.yaml.
    // `Config::define` takes `&mut self` and returns `&mut Self`, so the Config has
    // to own a binding of its own — chaining it off `cmake::Config::new()` makes the
    // builder a temporary that dies at the end of the statement while the returned
    // `&mut` is still live (E0716). Keep the two steps separate.
    let mut cmake_cfg = cmake::Config::new("cpp");
    cmake_cfg.define("RAWALCHEMY_SRC", &rawalchemy_src);
    if target.contains("android") {
        let ndk = env::var("ANDROID_NDK_HOME")
            .or_else(|_| env::var("ANDROID_NDK_ROOT"))
            .expect(
                "rawalchemy_fotlab: ANDROID_NDK_HOME/ANDROID_NDK_ROOT must be set to build for Android",
            );
        let toolchain = Path::new(ndk.as_str()).join("build/cmake/android.toolchain.cmake");
        let abi = match target.as_str() {
            "aarch64-linux-android" => "arm64-v8a",
            "armv7-linux-androideabi" => "armeabi-v7a",
            "i686-linux-android" => "x86",
            "x86_64-linux-android" => "x86_64",
            other => panic!("rawalchemy_fotlab: unsupported Android target `{other}`"),
        };
        let min_api = env::var("MIN_API").unwrap_or_else(|_| "26".to_string());
        cmake_cfg
            .define("CMAKE_TOOLCHAIN_FILE", toolchain)
            .define("ANDROID_ABI", abi)
            // Shared STL. The resulting cdylib has a DT_NEEDED on libc++_shared.so,
            // which AGP does NOT add on its own for hand-produced jniLibs; the CI
            // native job copies the NDK's per-ABI libc++_shared.so next to
            // librawler_fotlab.so in the jniLibs artifact (build_rust.yaml), so
            // the APK ships it. Keep this define in sync with that copy step.
            .define("ANDROID_STL", "c++_shared")
            .define("ANDROID_PLATFORM", format!("android-{min_api}"));
    }
    let dst = cmake_cfg.build();

    // OpenMP, when `cpp/CMakeLists.txt` found a usable runtime, is reported here as raw
    // cargo link directives (`openmp-link.txt`, one per line). They have to be replayed
    // as `rustc-link-*` and NOT as a `rustc-link-arg=-fopenmp`: this crate is an rlib and
    // `rustc-link-arg` does not propagate to the final artifact of a dependent crate
    // (cargo #9554) — only `rustc-link-lib` / `rustc-link-search` reach the cdylib link.
    //
    // A `bundle=<path>` line means the runtime is a SHARED library, so the APK must
    // ship it next to librawler_fotlab.so; build_rust.yaml picks the path up from the
    // same file. A static runtime needs no packaging at all.
    //
    // The `rustc-link-*` lines are COLLECTED here but emitted only AFTER the
    // `static=rawalchemy_grading` directive below — see the replay site for the reason
    // (ld's left-to-right archive resolution makes the order load-bearing).
    let openmp_link = Path::new(&dst).join("openmp-link.txt");
    let mut openmp_directives: Vec<String> = Vec::new();
    if let Ok(text) = std::fs::read_to_string(&openmp_link) {
        for line in text.lines() {
            let line = line.trim();
            if line.is_empty() {
                continue;
            }
            if let Some(path) = line.strip_prefix("bundle=") {
                println!("cargo:warning=rawalchemy_fotlab: OpenMP links the shared runtime — bundle {path} into jniLibs/<abi>/libomp.so");
            } else {
                openmp_directives.push(line.to_string());
            }
        }
    }

    // 2. cxx bridge (Rust side generated by cxx-build) + our shim .cc.
    //
    // The crate root must be on the include path: cxx emits the bridge's
    // `include!("cpp/rawalchemy_api.h")` VERBATIM into the generated
    // `lib.rs.cc` (it does not prepend the crate-name include prefix that
    // `#include "cratename/..."` would use), so the generated translation unit
    // resolves that path against the crate root or not at all. Same spelling in
    // our own shim, so both units agree on one path.
    cxx_build::bridge("src/lib.rs")
        .include(manifest)
        .include(format!("{rawalchemy_src}/include"))
        .flag_if_supported("-std=c++17")
        .file("cpp/rawalchemy_shim.cc")
        .compile("rawalchemy_fotlab");

    // Link the grading static lib and the C++ runtime into the final cdylib.
    println!("cargo:rustc-link-search=native={}/lib", dst.display());
    println!("cargo:rustc-link-lib=static=rawalchemy_grading");

    // OpenMP runtime directives MUST be emitted AFTER `static=rawalchemy_grading`.
    // ld resolves archives strictly left-to-right: an archive is pulled in only to
    // satisfy references already pending when the archive is scanned. The only
    // objects that reference the `__kmpc_*` runtime live INSIDE the grading archive
    // (our own shim is compiled without -fopenmp), so `-lomp` before
    // `-lrawalchemy_grading` scans an empty undefined set, drops the whole runtime
    // archive, and leaves `__kmpc_fork_call` undefined in the cdylib. rustc does not
    // pass `--no-undefined` for cdylibs, so the link then "succeeds" and the failure
    // is deferred to the device: `dlopen failed: cannot locate symbol
    // "__kmpc_fork_call"` (observed on the alchemy smoke shard after d5a4662). With
    // the consumer scanned first, its pending `__kmpc_*` references pull the needed
    // libomp members in on the very next archive. (The shared-runtime form is
    // order-insensitive at link time but is harmless here and bundles via the
    // `bundle=` warning above.)
    for directive in &openmp_directives {
        println!("cargo:{directive}");
    }
    // C++ stdlib.
    //
    // Android: dynamic libc++ (`dylib=c++` -> DT_NEEDED on libc++_shared.so).
    // We tried c++_static: rustc resolves `static=` archives itself (needs an
    // explicit -L into the NDK sysroot), and on armv7 the resulting link line
    // (rust's compiler_builtins versioned __aeabi_* symbols against LIBC_N,
    // plus the -lc++_shared that cxx's link-cplusplus dependency injects by
    // default) fails with `undefined version LIBC_N`. Dynamic links cleanly on
    // every ABI; the only price is shipping libc++_shared.so, which the CI
    // native job copies into the jniLibs artifact so AGP packages it.
    //
    // Desktop targets use the platform libstdc++ as a dylib.
    //
    // NOTE: `rustc-link-lib` propagates to the final artifact of a *dependent*
    // crate; `rustc-link-arg` does not (cargo #9554). Since this crate is an rlib
    // consumed by rawler_fotlab's cdylib, only link-lib can carry the runtime.
    //
    // OpenMP's runtime, when `cpp/CMakeLists.txt` enabled it, is linked by the
    // directives replayed from `openmp-link.txt` immediately above — deliberately
    // after the grading archive (see the ordering note there). When OpenMP is off
    // that file is empty and nothing is emitted — the grading loops then compile as
    // plain single-threaded loops, exactly as before this change
    // (`rules/REVIEW/detail/ACTION-PERFOR-000007.md`).
    let cxx_stdlib = if target.contains("android") {
        "c++"
    } else {
        "stdc++"
    };
    println!("cargo:rustc-link-lib=dylib={cxx_stdlib}");

    println!("cargo:rerun-if-changed=cpp/rawalchemy_api.h");
    println!("cargo:rerun-if-changed=cpp/rawalchemy_shim.cc");
    println!("cargo:rerun-if-changed=cpp/CMakeLists.txt");
    println!("cargo:rerun-if-changed=src/lib.rs");
}
