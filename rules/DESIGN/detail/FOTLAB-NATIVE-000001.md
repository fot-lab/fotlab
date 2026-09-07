# Third-Party Modules — Single Location Under `external/`

- ID: FOTLAB-NATIVE-000001
- Status: Draft
- Priority: P1
- Created: 2026-09-07
- Owner: —
- Related: `FOTLAB-UIXDES-000001` (C4 — UI never calls native code directly), `FOTLAB-DATABS-000001` (C7 — native code never touches the database), `docs/external/index.md` (licences and submodule usage constraints)

## Background & Goal

FotLab depends on upstream projects that are not consumed as Maven artifacts — `dnglab` (Rust,
RAW/DNG processing) and `exiftool` (Perl, metadata read & write). Their source lives inside this
repository, so without a rule they tend to spread: a copied crate here, a patched Perl library
there, a vendored copy inside a feature package. Once scattered, a module can no longer be upgraded,
licensed or stripped for release with confidence, and reviewers cannot tell project code from
imported code.

Goal: **every third-party module that ships as source in this repository lives in exactly one place —
`external/<module-name>/`** — with one introduction method, one ownership boundary and one upgrade
path.

## Requirement

### R1 — `external/` is the only location

- Any third-party module whose source is present in this repository resides in
  `external/<module-name>/`, one top-level directory per module.
- The directory name is the lower-case, hyphen-separated upstream project name (`dnglab`,
  `exiftool`); no version suffix, no vendor prefix such as `third_party` or `vendor`.
- No third-party source is placed inside `app/` — the only first-party module
  (`FOTLAB-STRUCT-000001`) — not even a partial copy of a header, a crate or a script.
- Conversely, `external/` holds **only** third-party code. Project code, patches to the build
  scripts that drive `external/`, and our own wrapper code stay outside it (wrapper and bridge
  code belongs to a dedicated first-party native-integration module, designed in a separate
  `NATIVE` item and not yet created).
- New top-level directories at the repository root are not created to host third-party code.

### R2 — Two introduction methods, nothing else

- **git submodule** (default): the module is pinned to a specific upstream commit; the repository
  stores only the pointer.
- **vendored snapshot**: used only when a submodule is impossible (upstream not reachable in CI,
  mandatory local modifications). The snapshot keeps the upstream licence file and a
  `README.fotlab.md` recording source URL, upstream commit/tag, date and the reason for vendoring.
- Mixing is not allowed per module: a module is either a submodule or a vendored snapshot.
- Libraries distributed as Maven/Gradle artifacts are declared in `gradle/libs.versions.toml` and
  are **not** copied into `external/`; R1 applies to source-level modules only.

### R3 — `external/` stays out of the Gradle build

- Directories under `external/` are not Android/Gradle projects and are not listed in
  `settings.gradle.kts`. They are built, if at all, by their own toolchain (Cargo for `dnglab`,
  Perl runtime for `exiftool`).
- First-party code never imports source from `external/` directly. The concrete calling layer
  (JNI/FFI/process invocation) is designed per third-party module in its own `NATIVE` integration
  item; until that exists, no first-party source may reach into `external/`.
- If a third-party module ever has to become a Gradle module, that exception is recorded in this
  item's Change History before it is implemented.

### R4 — Upstream is read-only

- Upstream sources under `external/` are treated as fixed constraints, never as code to refactor.
- A required change is applied as a patch recorded in the repository (patch file or documented
  procedure) and re-applied on every upgrade; it is never committed as an in-place edit that looks
  like upstream code.
- Submodule pointers move only deliberately; tracking a moving upstream branch is forbidden.

### R5 — Licence and inventory

- Each module keeps its own licence file at `external/<module-name>/LICENSE*` and it is shipped with
  the application.
- `docs/external/index.md` is the inventory: every entry records language, purpose, the paths that
  matter, and the licence. Adding a module means adding an entry in the same change.
- A module whose licence is incompatible with `LICENSE.md` is not added; the decision is recorded
  here first.

### R6 — Release packaging

- Only the paths needed at runtime are packaged; tests, samples and offline documentation of a
  third-party module are stripped. The per-module keep-list lives in `docs/external/index.md`.
- A release does not include the full source tree of a third-party module unless that module is
  distributed as source by design.

### R7 — Build and CI

- Cloning requires `git clone --recurse-submodules` (or `git submodule update --init --recursive`);
  documented in `docs/getting-started.md`.
- CI that builds against `external/` content must initialise submodules explicitly and must not
  fall back to silently building without them.
- Toolchains needed by `external/` modules (Rust, Perl, CMake, NDK) are declared in
  `docs/getting-started.md` before a module that requires them is merged.

## Constraints

- C1 — Third-party source appears only under `external/<module-name>/`; copies inside first-party modules are forbidden.
- C2 — `external/` contains no first-party code; wrapper and bridge code belongs to a dedicated first-party native-integration module (designed separately).
- C3 — `external/` directories are not included in `settings.gradle.kts` and are never imported directly by first-party source.
- C4 — Upstream code is read-only; changes are carried as recorded patches, and submodules are pinned to a commit.
- C5 — Every module carries its own licence file and an inventory entry in `docs/external/index.md`.
- C6 — Release packages include runtime-necessary paths only.
- C7 — Maven-distributed libraries are not vendored into `external/`.

## Acceptance Criteria

- AC1 — Listing the repository root shows all third-party sources under `external/` and nowhere else; a search for known upstream package/licence headers outside `external/` returns nothing.
- AC2 — `settings.gradle.kts` contains no `include()` entry whose path starts with `:external`.
- AC3 — `git submodule status` lists exactly the modules present under `external/`, each pinned to a commit; no submodule tracks a branch head.
- AC4 — `docs/external/index.md` has one entry per directory under `external/`, and the two lists match one-to-one.
- AC5 — Each `external/<module-name>/` directory contains a licence file, and the release artefact contains those licence texts.
- AC6 — A clean clone with `--recurse-submodules` builds; a clone without them fails with an explicit error rather than producing a silently degraded build.
- AC7 — Adding a third-party module outside `external/` is rejected in review by reference to this item.

## Impacted Modules

- `external/` — the single location for all third-party source
- A dedicated first-party native-integration module — the only code permitted to reach into `external/` (its design is a separate `NATIVE` item)
- `settings.gradle.kts` — must keep listing first-party modules only
- `docs/external/index.md`, `docs/getting-started.md`, `README.md` — inventory, environment and licence notices
- CI workflows — submodule initialisation and per-module toolchain provisioning

## Open Questions

- Q1 — Submodule or vendored snapshot as the long-term default? **TBD.** Submodules keep the diff small but require network access in every build environment.
- Q2 — Where do recorded patches live (`external/patches/` or per module), and is there tooling that re-applies them automatically on upgrade?
- Q3 — Is an automated licence-compliance check (SPDX scan of `external/`) part of CI?
- Q4 — Which subset of `exiftool` (`lib/`, `arg_files/`, `config_files/`) is actually required at runtime, and is it copied into the APK or invoked in place?
- Q5 — Is `dnglab` consumed as a Rust static library linked through a native bridge layer, or as a built executable shipped as an asset? This determines the toolchain requirements of R7.
- Q6 — How are submodule upgrades proposed and reviewed — a dedicated `RELEAS`/`NATIVE` item per upgrade, or a recurring task?
- Q7 — In which shape is `RawTherapee` (GPL-3.0, C++/GTK desktop project) actually consumable on Android? **TBD.** Only `rtengine/` looks integratable; its transitive dependencies, binary size and the licences shipped under `external/RawTherapee/licenses/` still need an assessment before any build wiring is specified.
- Q8 — `colour` is a Python library (BSD-3-Clause) and Android ships no Python runtime. **TBD.** Is it consumed only as a build-time/offline reference (generating or verifying colour constants and LUTs that are then committed as Kotlin/C++ data), or is a port required? Until decided, it must not become an APK runtime dependency.

## Change History

- 2026-09-07 — Added `external/colour` as the fourth submodule (`../colour.git`, branch `master`, pinned at `a3bfe349`, describe `v0.4.7`); registered it in `docs/external/index.md` and `docs/getting-started.md`, and recorded its consumption question as Q8.
- 2026-09-07 — Added `external/RawTherapee` as the third submodule (`../RawTherapee.git`, branch `dev`, pinned at `498f6237`, describe `5.13-9-g498f62378`); registered it in `docs/external/index.md` and `docs/getting-started.md`, and recorded the open integration question as Q7.
- 2026-09-07 — Initial draft. Established `external/<module-name>/` as the only location for third-party source, the two permitted introduction methods (pinned git submodule, vendored snapshot with provenance record), the rule that `external/` stays out of the Gradle build and is reached only through a dedicated native-integration layer (designed per module), read-only upstream with recorded patches, per-module licence plus inventory entry in `docs/external/index.md`, runtime-only release packaging, and the clone/CI requirements. Left submodule-vs-vendor default, patch tooling, licence scanning, the exiftool/dnglab packaging shape and the upgrade-review process open as Q1–Q6.
- 2026-09-07 — `core/` and `feature/` no longer exist: `app/` is the only first-party module (`FOTLAB-STRUCT-000001`). R1 updated accordingly. The rule itself — third-party source only under `external/<module-name>/`, never inside first-party code — is unchanged.
