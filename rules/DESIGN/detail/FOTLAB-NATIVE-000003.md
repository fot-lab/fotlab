# Embedding Python in the Host App via p4a-Built CPython & Packages

- ID: FOTLAB-NATIVE-000003
- Status: Draft
- Priority: P2
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-NATIVE-000002` (the option evaluation this design operationalises — O1 + O2 hybrid), `FOTLAB-NATIVE-000001` (single first-party integration point; `external/` stays out of Gradle), `FOTLAB-UIXDES-000001` (C4 — UI never calls native directly), `FOTLAB-DATABS-000001` (C7 — native code never touches the database)

## Background & Goal

`FOTLAB-NATIVE-000002` evaluates how to run Python on Android under a hard open-source /
royalty-free constraint. Its O1 (self-built embedded CPython) and O2 (python-for-android, p4a)
are each weak alone: O1 is heavy because it must cross-compile the interpreter **and** every C
extension (notably OpenBLAS + numpy) by hand; O2 is a clean cross-compile pipeline but ships a
**standalone APK** with an SDL2/WebView bootstrap that does not fit our Kotlin/Compose host app.

This item drills into the hybrid that the previous discussion converged on: **use p4a only as the
cross-compile pipeline**, take its build products (libpython, the stdlib, and every needed C
extension such as numpy+OpenBLAS), and **discard p4a's APK/bootstrap**. Those artifacts are wired
into our own first-party native-integration module (the single point mandated by
`FOTLAB-NATIVE-000001`) and started through JNI inside the existing host app. The goal is a
concrete design for that route, so `000002` can move from options to a decision.

## Requirement

### R1 — Open-source and royalty-free, inherited

- Every artifact used (CPython under the PSF Licence, OpenBLAS under BSD-3-Clause, numpy under
  BSD-3-Clause, and any other p4a recipe output) must satisfy `FOTLAB-NATIVE-000002` R1. p4a
  itself is MIT and is only a build-time tool; it never ships inside the APK.

### R2 — Products come from the p4a cross-compile pipeline (or an equivalent NDK flow)

- The interpreter and all C/Fortran extensions are produced by p4a's recipe system (or a
  documented equivalent using the NDK directly). No proprietary build tool or paid dependency is
  introduced.
- p4a's bootstrap/APK packaging is explicitly **not** used; only its compiled artifacts are kept.

### R3 — Integration boundary stays single and first-party

- The interpreter is started and driven only by the dedicated first-party native-integration
  module, reached through JNI. UI code and database code never touch the interpreter or FFI
  directly (per `UIXDES-000001` C4 and `DATABS-000001` C7).
- `external/colour` Python source is **not** copied into first-party modules; it ships as a
  resource and is added to `sys.path` at runtime (see R4).

### R4 — Layered packaging and runtime layout

- Native libraries (`libpythonX.Y.so`, `libopenblas.so`, `numpy*.so`, …) go into the integration
  module's `jniLibs/<abi>/` and are loaded by the OS loader.
- Pure-Python code (the `colour` subset we actually need, plus the stdlib) is packaged as an
  asset and extracted at first run into the app's private data directory; that directory is then
  placed on `sys.path`.
- No third-party `.py` source is committed under `app/`, `core/`, or `feature/`.

### R5 — Interpreter lifecycle and the GIL

- A single interpreter instance is owned by the integration module and bound to an explicit scope
  (application-scoped singleton or a managed session). teardown calls `Py_Finalize` cleanly.
- Calls across the Kotlin↔Python boundary are serialised with respect to the GIL; threading rules
  (which thread holds the interpreter lock, how Kotlin threads invoke Python) are documented before
  any concurrent use.

### R6 — ABI coverage

- `arm64-v8a` is mandatory; `x86_64` is built for the emulator. `armeabi-v7a` is optional and decided
  by the size budget in `FOTLAB-NATIVE-000002` R4.

## Approach (primary route)

1. **Build with p4a** — invoke p4a (or its recipe mechanism directly) to cross-compile CPython
   plus the recipes we need (at minimum `numpy`, which pulls in `OpenBLAS`; add others only when a
   feature requires them). Output: `libpythonX.Y.so`, the stdlib, per-package `.so` files, and
   `libopenblas.so`.
2. **Extract, don't package** — take only those artifacts; leave p4a's SDL2/WebView bootstrap and
   APK assembly behind.
3. **JNI wrapper in the integration module** — `System.loadLibrary("pythonX.Y")` (and the other
   `.so` files), then `Py_Initialize()`, then a documented entry point (`PyRun_SimpleString` /
   `PyImport` of a bootstrap module). Expose a narrow Kotlin↔Python API (self-written JNI, or the
   pyjnius approach adapted in-house — pyjnius itself is MIT).
4. **Ship and mount** — `.so` files in `jniLibs`; pure-Python assets extracted to private storage
   and prepended to `sys.path` so `import colour` / `import numpy` resolve.
5. **Expose narrowly** — the integration module presents a typed Kotlin API; feature modules and UI
   consume that API and never see FFI.

## Constraints

- C1 — Open-source / royalty-free only; p4a is build-time only (R1).
- C2 — Use p4a's compiled artifacts, not its APK/bootstrap (R2).
- C3 — Single first-party integration point; `external/` source stays out of first-party code (R3).
- C4 — One interpreter instance, GIL/threading rules defined (R5).
- C5 — ABI coverage per R6.

## Acceptance Criteria

- AC1 — Every shipped artifact's licence (PSF, BSD-3, MIT) is present and traceable to its p4a
  recipe / upstream source.
- AC2 — A minimal script that `import numpy` and `import colour` executes on `arm64-v8a` in the
  emulator, with no third-party app installed.
- AC3 — No proprietary dependency appears in the dependency/build report; p4a is provably absent
  from the final APK (only its build products remain).
- AC4 — `external/colour` ships as an asset, not as a copied source tree in a first-party module.
- AC5 — No UI or database code calls the interpreter or FFI directly; the integration module is the
  sole bridge (verified by dependency/architecture review).
- AC6 — Interpreter start/stop is deterministic within the defined lifecycle (no leak across
  activity recreation).

## Impacted Modules

- `external/colour` — Python source, shipped as a resource, never copied into first-party code
- A new first-party native-integration module — the only code that loads and drives the interpreter
- `settings.gradle.kts` — `ndk { abiFilters }` set to the covered ABIs
- CI — runs the p4a build and publishes the compiled artifacts as inputs to the app build

## Open Questions

- Q1 — Which CPython version, and how much of the stdlib to ship (full vs. a slim/frozen subset)?
- Q2 — How to select and trim the `colour` pure-Python subset we actually need at runtime?
- Q3 — JNI bridge shape: self-written JNI vs. an in-house pyjnius-style adapter; what the Kotlin
  API surface looks like.
- Q4 — Interpreter instance scope: process-wide singleton vs. on-demand session; teardown triggers.
- Q5 — Real APK size from the artifacts (feeds `FOTLAB-NATIVE-000002` R4 thresholds).
- Q6 — Prerequisite: this design is only actionable once `FOTLAB-NATIVE-000002` selects the O1+O2
  hybrid; until then it stays a draft.

## Change History

- 2026-09-07 — Initial draft. Operationalises the O1+O2 hybrid from `FOTLAB-NATIVE-000002`: use
  p4a purely as the cross-compile pipeline for CPython + numpy/OpenBLAS, discard its
  bootstrap/APK, and wire the artifacts into our own first-party integration module via JNI. Defines
  the artifact source, integration boundary, layered packaging/runtime layout, interpreter
  lifecycle/ABI constraints, and acceptance criteria. Created alongside the removal of the
  non-existent `core:harness` module.
