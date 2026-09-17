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

## Impact / Conflict

- Choosing the wrong internal call produces symptoms that look like a routing/decoding bug but are really an **API-selection** bug. Here the symptom was RAW files silently routed to Coil, which renders only an embedded preview, not a demosaiced frame.
- Because a Rust panic across `extern "C"` **aborts the process** and Kotlin `runCatching` cannot catch it, a wrong call choice can also escalate from "wrong result" to a **hard crash** if the native side panics (the binding wraps every entry in `catch_unwind` precisely to contain this).
- Principle 5 of `rules/REVIEW.md` holds: `external/` is a fixed constraint. This item records **how to call it correctly**, not a change to its source.

## Recommendation

When any first-party code (Kotlin, Rust binding, or JNI) calls an external C/Rust library, before picking between apparently-similar functions:

1. **Read the library source or doc comment** for what each function *internally* does — do **not** infer behaviour from the name.
2. **Confirm the input contract**: header-only vs. full buffer; whether pixels are decoded.
3. **Confirm the error contract**: graceful `Result`/return vs. panic/abort; and whether a cross-FFI panic can take down the process.
4. **Prefer the cheapest correct call** for the job — e.g. header-only identification over a full `decode_dummy` — especially on constrained paths such as the sniffer that only hold a 1 MiB header.
5. When a probe must stay cheap, assert in a comment (and ideally a test) the exact input size it is allowed to receive, so a future "improvement" cannot silently swap in a heavier internal call.

## Change History

- 2026-09-17 — Recorded as an Observation. Lesson derived from the rawler RAW-identification fix (commit `fa1db62`): `rawler_fotlab::identify` switched from `decode_dummy` to `get_decoder` + `raw_metadata` because the two name-similar internal calls have different input/behaviour requirements, and the heavier one silently mis-routed CR2/NEF/ARW/RW2 to the Coil preview branch. File created at `rules/REVIEW/detail/FOTLAB-RAWLER-000001.md` per explicit user naming; row appended to `rules/REVIEW/index.md`; `RAWLER` added to the `rules/REVIEW.md` category table.
