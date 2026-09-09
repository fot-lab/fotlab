# GitHub Actions Workflow Architecture Principles

- ID: GITHUB-ACTION-000002
- Status: Approved
- Priority: P3
- Created: 2026-09-09
- Owner: —
- Related: `rules/ACTION.md`, `.github/workflows/build.yaml`, `.github/workflows/build_gradle.yaml`, `.github/workflows/release_github.yaml`, `.github/actions/install_jdk/action.yml`, `.github/actions/install_sdk/action.yml`, `.github/actions/install_ndk/action.yml`

## Background & Goal

The CI pipeline is composed from a small set of GitHub Actions files. These
principles govern how those files are structured and named so the pipeline stays
legible and easy to extend. They are deliberately free of concrete steps,
versions or `if` conditions — those live in the workflow files themselves (and in
`rules/ACTION.md`).

## Principles

### P1 — One responsibility per workflow
Every workflow owns a single, named concern, and the file name states that
concern directly (build stage, release stage, orchestrator). The orchestrator
contains no build or release steps of its own; it only wires stages together.

### P2 — Reusability through `workflow_call`
Build/release stages are reusable workflows (`on: workflow_call`) invoked by the
orchestrator with `uses:`. Anything a caller may vary — tasks, artifact names,
retention, flags — is a `workflow_call` input, never a hard-coded value inside
the stage.

### P3 — Shared toolchain belongs in composite actions, not a second workflow
Steps reused across workflows (e.g. toolchain installation + caching) live in a
composite action, called as a **step** so they run inside the caller's job and
share its runner. A reusable *workflow* gets its own runner and cannot share an
environment, so "environment setup" must not be spun out as a separate workflow
when its only purpose is to be reused by the build. Each toolchain component is
its own composite action (`install_jdk` / `install_sdk` / `install_ndk`), so a
caller pulls in exactly the pieces it needs — e.g. the Kotlin build uses
JDK+SDK, a native build additionally uses NDK — instead of toggling a component
with a parameter switch.

### P4 — Native builds are split by language
Native integration, when it exists, is divided into per-language build workflows
(cmake, python, perl, rust, …). A generic "native" catch-all is not used; each
language is its own reusable workflow. Until the native code exists, no such
workflow is created — placeholders are not kept.

### P5 — Per-component caching
Cachable toolchain components are cached independently with their own keys, so
each can be versioned, reused and invalidated on its own.

### P6 — Pinned versions are centralized
Fixed toolchain versions are defined in one place and read by every consumer. A
composite action has no top-level `env`, so the calling job injects the variables
it needs; versions are maintained at that single injection point.

### P7 — Stages communicate by artifact and output
Workflows pass data to each other through artifacts (e.g. the built APK) and
`workflow_call` outputs (e.g. the version name), never through a shared
filesystem. The release stage consumes the build's artifact rather than
re-building it.

### P8 — Release logic is decoupled from build logic
The release stage only "publish the artifact as a Release". Whether that release
is a pre-release or a formal release is decided from the version string's suffix,
not from any build detail — the release stage does not need to know how the
artifact was produced.

## Change History

- 2026-09-09 — Initial encoded rule. Records the architecture principles for the
  GitHub Actions pipeline (`build.yaml` orchestrator + reusable `build_gradle.yaml`
  / `release_github.yaml` + the `install_jdk` / `install_sdk` / `install_ndk`
  composite actions), per the
  "name the stages clearly and reuse them" directive. Principle-level only;
  concrete design lives in the workflow files and `rules/ACTION.md`.
