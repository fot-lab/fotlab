# Running Python on Android — Open-Source, Royalty-Free

- ID: FOTLAB-NATIVE-000002
- Status: Draft
- Priority: P2
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-NATIVE-000001` (third-party location rule; `colour` is Q8), `FOTLAB-NATIVE-000001` Q8 (whether `colour` needs runtime Python at all)

## Background & Goal

`external/colour` is a Python colour-science library (BSD-3-Clause). Android ships **no** Python
runtime, so executing its code on device requires bringing an interpreter in. `FOTLAB-NATIVE-000001`
Q8 leaves the consumption shape open; this item evaluates the *how* under one hard constraint:
the chosen approach must be **open-source and royalty-free** — no proprietary dependency, no
per-seat or per-app fee — and its licence must be compatible with our GPL-3.0 redistribution.

Goal: pick a single, documented integration approach (or confirm that none is needed) before any
Python execution is wired into the build.

## Requirement

### R1 — Open-source and royalty-free, non-negotiable

- No commercial or proprietary dependency, and no royalty of any kind (per-seat, per-app, or
  distribution-based).
- The licence must be OSI-approved and compatible with GPL-3.0 redistribution. Acceptable licences
  include MIT, BSD-2/3-Clause, Apache-2.0 and the PSF Licence (CPython).
- An option that gates commercial or redistribution use behind a paid licence is **rejected**; the
  rejection reason is recorded in this item's Change History before any other work proceeds.

### R2 — Runs on supported Android ABIs

- Must execute on the supported Android ABIs (arm64-v8a, armeabi-v7a, and x86_64 for emulation) on
  non-rooted devices, and must not require a separately installed user app (e.g. Termux is out).

### R3 — One integration point

- Whatever is chosen is reached only through a dedicated first-party native-integration module (the
  same single point mandated by `FOTLAB-NATIVE-000001`); the Python source stays under
  `external/colour` and is never copied into first-party code.

### R4 — Size and build budget set before wiring

- The acceptable APK size increase and build-time cost are fixed as thresholds here before the
  interpreter is integrated, so the decision is measurable, not subjective.

### R5 — Licence compliance of the embedded interpreter

- If an interpreter is embedded, its licence text and copyright notices ship with the application.
  The PSF Licence (CPython) is permissive and compatible with GPL-3.0; no additional obligation
  beyond shipping it arises.

## Options (to evaluate)

- **O1 — Embed CPython (self-built).** Compile `libpythonX.Y.so` with the NDK and call it through
  JNI/FFI from the first-party integration module. Fully open (PSF Licence), zero fee, maximum
  control. Cost: build the interpreter, package the stdlib, cover the ABI matrix.
- **O2 — python-for-android (MIT).** Mature CPython-on-Android build pipeline, but oriented to
  standalone APKs; reusing it as a library inside our host app needs packaging work. Open and free.
- **O3 — BeeWare / Briefcase + Rubicon (BSD).** Targets standalone apps; listed for completeness, not
  a natural host-app embedder.
- **O4 — PyOxidizer (MIT).** Packs Python into a single binary/library; Rust-oriented, so it would
  add a Rust toolchain dependency alongside `external/dnglab`.
- **O5 — Chaquopy Community.** Easiest Gradle integration, but its licence for commercial
  redistribution must be read first — if it gates paid use, it fails R1 and is excluded.
- **O6 — No on-device Python (build-time/offline).** Run `colour` on CI to generate LUTs/constants
  committed as Kotlin/C++ resources; runtime consumes precomputed data only. Already the default in
  Q8. Chosen here automatically if on-device execution proves unnecessary.

## Constraints

- C1 — Open-source and royalty-free only (R1).
- C2 — Supported Android ABIs, no root, no separate app (R2).
- C3 — Single first-party integration point; `external/colour` source stays untouched (R3, per
  `FOTLAB-NATIVE-000001`).
- C4 — Embedded interpreter is redistributed under a GPL-3.0-compatible licence with its notice
  shipped (R5).

## Acceptance Criteria

- AC1 — The selected option's licence is OSI-approved and its full text is present in the repository;
  a review note states it is royalty-free for our distribution.
- AC2 — A minimal Python snippet executes on each supported ABI in CI/emulator without root.
- AC3 — No proprietary or paid dependency appears in the dependency report; Chaquopy (or similar) is
  excluded unless its licence is confirmed royalty-free.
- AC4 — `external/colour` Python source is not copied into first-party modules; it is referenced only
  through the integration module.
- AC5 — The shipped APK size increase and build-time cost are within the R4 thresholds.

## Impacted Modules

- `external/colour` — the Python source, left untouched
- A future first-party native-integration module — the only code allowed to reach Python
- `settings.gradle.kts` — a Gradle plugin (e.g. Chaquopy) is declared here only after it passes R1
- CI — builds the interpreter, or runs the offline generation of O6

## Open Questions

- Q1 — Is on-device Python actually required, or does build-time/offline (O6) cover every need?
  **TBD.** Decide this before investing in O1–O5.
- Q2 — Which CPython version, and which stdlib packaging strategy (full vs. minimal frozen modules)?
- Q3 — Is Chaquopy Community royalty-free for our GPL-3.0 redistribution? Needs a licence reading
  before it can be selected (otherwise it fails R1).
- Q4 — Acceptable APK size increase and build-time budget — the R4 thresholds.
- Q5 — Does embedding CPython create any GPL-3.0 obligation beyond shipping the PSF licence?
  **Likely no** (PSF is permissive); confirm with a licence check.

## Change History

- 2026-09-07 — Initial draft. Opened the evaluation of running Python on Android under a hard
  open-source/royalty-free constraint, listed the candidate approaches (embed CPython,
  python-for-android, BeeWare, PyOxidizer, Chaquopy pending a licence check, or build-time/offline),
  and the licence/ABI/single-integration-point constraints. Tied to `FOTLAB-NATIVE-000001` Q8, and
  created alongside removal of the non-existent `core:harness` module from `settings.gradle.kts`.
