# External C/Rust library internal-call detail differences — lesson from the rawler_fotlab binding

- ID: FOTLAB-RAWLER-000001
- Status: Observation
- Priority: P2
- Created: 2026-09-17
- Owner: —
- Related: `DNGLAB-RAWLER-000001` (preview is an unprocessed full-sensor dump), `DNGLAB-RAWLER-000002` (rewrite rawler in Kotlin), `ACTION-KOTLIN-000007` (platform & data layer — FFI plumbing)

## Background & Goal

The first-party Rust binding `rawler_fotlab` (`app/src/binding/rust/rawler_fotlab`) calls into the external `dnglab` rawler decoder — a C/Rust native library vendored under `external/dnglab/rawler`. During the RAW-identification rework we hit a failure that was invisible from the function names: two functions that "look" like they both just identify a RAW file behave completely differently internally. The goal of this item is to record the lesson so future work that integrates external C/Rust libraries does not repeat the same class of bug.

## Finding

External C/Rust libraries routinely expose several functions whose **names** suggest the same job but whose **internal behaviour** differs in ways that matter:

- `rawler::decode_dummy(&src)` and `rawler::get_decoder(&src)` + `Decoder::raw_metadata(&src, &params)` both appear to "identify" a RAW file. In reality:
  - `decode_dummy` runs the **full** decoder path — it still parses and walks the compressed pixel data to size its output buffer, so it **requires the entire file** on hand and **fails silently** (returns `None`) when fed only the 1 MiB sniff header that `StudioEngine` provides.
  - `get_decoder` only **matches the container / format**; `raw_metadata` **reads the EXIF block at the file head**. Both settle from the header alone.
  - The bug that demoted large RAWs (CR2 / NEF / ARW / RW2) to the Coil branch — where Coil can only pull an embedded preview, never a full-frame demosaic — came **entirely** from this name-vs-behaviour mismatch (fixed by `fa1db62`, `get_decoder` + `raw_metadata` replacing `decode_dummy` in `identify`).

General pattern observed across external C/Rust calls: two functions with similar names may differ in:

1. **Input contract** — header-only vs. whole buffer required.
2. **Work performed** — whether pixels are decoded / buffers allocated.
3. **Error contract** — graceful `Result`/return vs. panic/abort on malformed input.
4. **Return-on-NA** — what they yield when the input is "not applicable" (e.g. `None` vs. an error vs. a partial struct).

## Additional Finding — `RawImage` is generated inside the binding but never crosses the FFI

A second lesson from the same `rawler_fotlab` binding: **the binding *does* obtain a `RawImage` from rawler, but it is never surfaced to the Kotlin side — the only things that cross the UniFFI boundary are a make/model string and PNG bytes.**

- `decode_to_png` (`app/src/binding/rust/rawler_fotlab/src/lib.rs:87-103`) calls `rawler::decode(&src, …)`, which returns a `rawler::RawImage` (`lib.rs:94`). That object is a **local variable**; it is handed straight to the local `encode_png(&img)` (`lib.rs:96`, defined as `fn encode_png(img: &RawImage)` at `lib.rs:111`) and dropped after the 8-bit shift-to-PNG. It is never returned.
- The binding exports exactly two `#[uniffi::export]` functions (`lib.rs:68` `identify`, `lib.rs:87` `decode_to_png`). Their return types are `Option<String>` (make/model label) and `Result<Vec<u8>, RawlerFotlabError>` (PNG bytes). **Neither returns a `RawImage`** — nor any raw pixel buffer.
- Structurally `RawImage` cannot cross the boundary as-is: it is an **upstream rawler type** (imported at `lib.rs:34`), not a `#[uniffi::export]` type in `rawler_fotlab`, and it carries a large `RawImageData` (`Vec<u16>`/`Vec<f32>`) plus `HashMap`/`enum` fields that UniFFI does not auto-serialize. There is currently no exported wrapper for it.
- Consequence: the studio render path only ever receives an **unprocessed, bit-shifted PNG** (`encode_png` is preview-only — no demosaic / white-balance / gamma, `lib.rs:105-110`). It cannot obtain the mosaic `RawImage` to run its own develop, nor can it ask rawler to develop and receive anything other than PNG. The full develop pipeline (`develop_intermediate`, `rawler/src/imgop/develop.rs:167-327`) exists in rawler but is **not wired into `rawler_fotlab`** at all.

## Impact / Conflict

- Choosing the wrong internal call produces symptoms that look like a routing/decoding bug but are really an **API-selection** bug. Here the symptom was RAW files silently routed to Coil, which renders only an embedded preview, not a demosaiced frame.
- Because a Rust panic across `extern "C"` **aborts the process** and Kotlin `runCatching` cannot catch it, a wrong call choice can also escalate from "wrong result" to a **hard crash** if the native side panics (the binding wraps every entry in `catch_unwind` precisely to contain this).
- The non-exposure of `RawImage` (Additional Finding above) means any future "true developed image" work (the R4 goal) cannot start from the mosaic on the Kotlin side: it must either drive rawler's own `develop_intermediate`, or add a new exported function that surfaces the pixel buffer (`Vec<u16>`/`Vec<f32>` + dimensions + `cpp` + metadata). Both are changes to the binding, not to external source.
- Principle 5 of `rules/REVIEW.md` holds: `external/` is a fixed constraint. This item records **how to call it correctly**, not a change to its source.

## Recommendation

When any first-party code (Kotlin, Rust binding, or JNI) calls an external C/Rust library, before picking between apparently-similar functions:

1. **Read the library source or doc comment** for what each function *internally* does — do **not** infer behaviour from the name.
2. **Confirm the input contract**: header-only vs. full buffer; whether pixels are decoded.
3. **Confirm the error contract**: graceful `Result`/return vs. panic/abort; and whether a cross-FFI panic can take down the process.
4. **Prefer the cheapest correct call** for the job — e.g. header-only identification over a full `decode_dummy` — especially on constrained paths such as the sniffer that only hold a 1 MiB header.
5. When a probe must stay cheap, assert in a comment (and ideally a test) the exact input size it is allowed to receive, so a future "improvement" cannot silently swap in a heavier internal call.
6. Before assuming an external library object can be returned to first-party code, check whether it is an exported (`#[uniffi::export]` / serializable) type. Upstream types like `rawler::RawImage` are not, so the binding must either wrap them in an exported struct or keep them Rust-side only (as `encode_png` does today).

## Change History

- 2026-09-17 — Recorded as an Observation. Lesson derived from the rawler RAW-identification fix (commit `fa1db62`): `rawler_fotlab::identify` switched from `decode_dummy` to `get_decoder` + `raw_metadata` because the two name-similar internal calls have different input/behaviour requirements, and the heavier one silently mis-routed CR2/NEF/ARW/RW2 to the Coil preview branch. File created at `rules/REVIEW/detail/FOTLAB-RAWLER-000001.md` per explicit user naming; row appended to `rules/REVIEW/index.md`; `RAWLER` added to the `rules/REVIEW.md` category table.
- 2026-09-17 — Added **Additional Finding**: the `rawler_fotlab` binding *does* obtain a `rawler::RawImage` (via `rawler::decode` at `app/src/binding/rust/rawler_fotlab/src/lib.rs:94`) but never surfaces it — `decode_to_png` (`lib.rs:87-103`) hands it to the local `encode_png` (`lib.rs:111`) which bit-shifts to PNG and drops it. Only two `#[uniffi::export]` functions exist (`identify` → `Option<String>`, `decode_to_png` → `Vec<u8>` PNG); `RawImage` is an upstream rawler type, not a `#[uniffi::export]` type, so it cannot cross the FFI as-is. Consequence: the studio render path only receives an unprocessed, preview-only PNG (`lib.rs:105-110`), and rawler's full `develop_intermediate` (`rawler/src/imgop/develop.rs:167-327`) is not wired into the binding. Added a matching Impact bullet (R4 "true developed image" must drive `develop_intermediate` or add a new exported pixel-buffer function) and Recommendation item 6 (verify an external type is exported/serializable before returning it across FFI).
