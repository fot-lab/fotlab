# Rewriting external/dnglab (rawler) in Kotlin — cost / benefit assessment

- ID: DNGLAB-RAWLER-000002
- Status: Observation
- Priority: P2
- Created: 2026-09-15
- Owner: —
- Related: `rules/REVIEW/detail/DNGLAB-RAWLER-000001.md` (current preview is unprocessed full-sensor dump), `rules/DESIGN/detail/FOTLAB-NATIVE-000001.md` (R4 — upstream read-only), `rules/DESIGN/detail/FOTLAB-NATIVE-000002.md`, `rules/STRUCT/detail/FOTLAB-STUDIO-000001.md` (native media pipeline), `rules/STRUCT/detail/DNGLAB-SURVEY-000001.md`, `rules/STRUCT/detail/DNGLAB-SURVEY-000002.md`, `rules/STRUCT/detail/DNGLAB-SURVEY-000003.md`, `rules/STRUCT/detail/DNGLAB-RAWDEV-000001.md`

## Background & Goal

This review records an assessment of whether `external/dnglab` (the Rust RAW → DNG toolkit, core crate `rawler`) should be **reimplemented in Kotlin** as first-party code, removing the Rust engine and the `librawler_fotlab.so` we currently ship.

**Motivation for the evaluation.** At the time this document was written, invoking the Rust library from the app caused the application to **crash**. That crash — not a desire for a different feature set — is what triggered the rewrite evaluation. This must be read against `FOTLAB-CRASH-000001`, which already hardens the FFI boundary in `app/src/binding/rust/rawler_fotlab/src/lib.rs` with `std::panic::catch_unwind` so a rawler panic degrades to `None`/`Err` instead of aborting the process. Whether the observed crash is a *caught-but-mishandled* panic, an ABI/linker issue outside the panic path, or a regressions in a newer submodule pin is **not yet root-caused** and is a precondition for any decision here.

**Scope the rewrite would have to cover.** We intend to use dnglab's Rust *fully* later, not just the current `identify` / `decode_to_png`:
- RAW decode across all formats — `rawler::decode` (`DNGLAB-SURVEY-000002` §1);
- the develop pipeline `rawler::imgop::develop` (black-level, white balance, demosaic, camera→sRGB, gamma) — `DNGLAB-RAWDEV-000001` §4;
- DNG conversion `rawler::dng::convert` (self-built TIFF container + LJPEG-92) — `DNGLAB-SURVEY-000002` §4.

Goal: lay out the cost and benefit so the rewrite question is decided on record, not in chat.

## Finding

### 1. Magnitude of the surface to reimplement

- `rawler/src` alone is **~38,987 lines across 172 `.rs` files** (measured: `Get-ChildItem external/dnglab/rawler -Filter *.rs -Recurse` line count). This is bit-level decoder work: packed-bit unpacking, LJPEG-92, CRX (a JPEG-XL variant), deflate, and X-Trans schemes (`DNGLAB-SURVEY-000002` §1.2). Many decompressors are explicitly dcraw / LibRaw-derived (`000002` §3) — a faithful port must reproduce those algorithms.
- The camera database is **738 `data/cameras/*.toml` files**, concatenated at build time into `cameras.toml` via `include_str!` (`DNGLAB-SURVEY-000003` §1). This is *data* and could be kept as resources, but the parse/lookup layer must be rewritten.
- `rawler::imgop` adds the develop pipeline (PPG demosaic after Chuan-kai Lin; X-Trans Markesteijn after `naorunaoru/demosaic`) and `rawler::dng` adds the TIFF/DNG writer.
- Upstream ships ~6,497 `.txt` + ~1,634 `.yaml` test fixtures — a Kotlin port would need an equivalent correctness harness or it cannot claim parity.

Net: a faithful port is on the order of **tens of thousands of lines of systems-level code**, plus an ongoing per-camera/per-format maintenance burden.

### 2. Cost (cons)

1. **Effort & correctness.** Months of implementation, and the hard part is *verifying* the output of every format/decoder against reference samples. A crash-free Kotlin port that produces subtly wrong pixels is worse than a crashing Rust one.
2. **Permanent orphaning from upstream.** `rawler` is actively maintained; today we get new-camera support and bug fixes by pinning a newer commit (`FOTLAB-NATIVE-000001` R4). Rewriting means every new body / lens / format is *our* work forever. This directly undermines the "use dnglab's Rust fully" intent.
3. **Performance regression (most serious).** Current Rust uses `multiversion` SIMD (`aarch64+neon` on the shipping `arm64-v8a` ABI) plus `rayon` (`DNGLAB-RAWDEV-000001` §6). Full-sensor develop allocates tens–hundreds of MB of transient `f32` buffer. Kotlin/JVM has no SIMD and pays GC on those buffers — 12–24 MP demosaic would be materially slower and risk OOM on low-end devices. Kotlin/Native cannot host the existing Compose / Android SDK stack, so it is not a viable target. A "Kotlin rewrite" therefore either *keeps* a native hot path (defeating the purpose) or *accepts* a large speed/quality regression.
4. **Algorithm provenance.** Because many decompressors derive from dcraw/LibRaw, a from-scratch port either copies (license exposure) or clean-room reimplements (correctness risk).
5. **Rule conflict.** `FOTLAB-NATIVE-000001` R4 treats `external/` as read-only and forbids in-place edits. A rewrite inverts that relationship and requires an explicit rule change before it can proceed.

### 3. Benefit (pros)

1. **No Rust toolchain / cross-compile CI.** `.github/workflows/build_rust.yaml` installs the NDK, runs `cargo ndk` across four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`), and regenerates the UniFFI Kotlin bindings. A Kotlin implementation deletes that entire job.
2. **Smaller APK.** The multi-ABI `librawler_fotlab.so` artifacts are gone.
3. **Removes the LGPL-2.1 compliance burden.** `rawler`/`dnglab` are LGPL-2.1; the survey already flags open questions — Android static-link compliance path (`DNGLAB-SURVEY-000001` Q2) and the missing `license` field on `dnglab_lib` (Q6). First-party Kotlin code ships under our GPL-3.0 with no LGPL relink obligation.
4. **Single-language codebase.** The team is Kotlin-first; dropping the Rust binding crate and `FOTLAB-CRASH-000001` panic-hardening glue lowers the maintenance tax and widens who can contribute.

## Impact / Conflict

- Conflicts with `FOTLAB-NATIVE-000001` R4 (upstream read-only). Recorded here rather than silently overriding; a rewrite needs that rule amended first.
- Does **not** conflict with `FOTLAB-CRASH-000001` — that rule hardens the FFI, it does not mandate Rust. The crash that triggered this evaluation is precisely the kind of failure that rule was written to prevent, so the first action should be to confirm whether the hardening is actually in the failing path before assuming a rewrite is required.
- Per `REVIEW.md` principle 5, `external/` modules are normally out of scope for change; this item is an *exception* because it records a decision about whether to stop using one, not a change *to* its source.

## Recommendation

**Do not rewrite in Kotlin at this time.** The benefits (no Rust CI, smaller APK, no LGPL) are real but are outweighed by the cost of reimplementing ~40 kLOC of algorithm-heavy code, permanently losing upstream camera/format support, and a near-certain performance regression on device.

**Before any rewrite decision, first root-cause the crash** that prompted it: verify the `catch_unwind` boundary in `app/src/binding/rust/rawler_fotlab/src/lib.rs` is on the failing call path, check the ABI/`.so` packaging in `app/build.gradle.kts` (`jniLibs` srcDir) and the `build_rust.yaml` four-ABI artifacts, and confirm the submodule pin. Many "Rust crash" failures are FFI/linker/ABI issues, not rawler logic — and are cheaper to fix than a rewrite.

**The better path is to deepen, not replace, the native integration** (`DNGLAB-RAWDEV-000001` §7): the develop pipeline is already inside the `rawler` crate we compile, so calling `RawDevelop::default().develop_intermediate(&img)` and `rawler::dng::convert` from the existing `rawler_fotlab` binding adds the "full use" we want **with no new dependency**. This keeps upstream maintenance, SIMD, and the camera database, while the only first-party work is the FFI seam.

A Kotlin rewrite is justifiable **only** under a hard constraint of zero native dependency *and* acceptance of a smaller camera/format coverage and lower develop fidelity — which contradicts the stated "use dnglab's Rust fully" plan.

## Change History

- 2026-09-15 — Review recorded. Assessed rewriting `external/dnglab`/`rawler` in Kotlin. Noted the evaluation was triggered by a crash when invoking the Rust library (precondition: root-cause against `FOTLAB-CRASH-000001` hardening). Quantified the port surface (`rawler/src` ~38,987 LOC / 172 `.rs`, 738 camera `.toml`, develop + DNG writer, test fixtures), listed costs (effort/correctness, upstream orphaning, SIMD/GC performance regression, dcraw provenance, conflict with `FOTLAB-NATIVE-000001` R4) and benefits (no Rust CI, smaller APK, no LGPL-2.1 burden, single-language stack), and recommended against a rewrite in favour of deepening the existing native-binding seam per `DNGLAB-RAWDEV-000001` §7. Row appended to `rules/REVIEW/index.md`.
