# ACTION — Build & CI/CD Behaviour

> Version: 1.2
> Updated: 2026-09-09

> **Redirect**: this file is the Layer 1 entry of the build and CI rules. The
> master item table lives in [`rules/ACTION/index.md`](rules/ACTION/index.md); encoded
> detail specs live in [`rules/ACTION/detail/`](rules/ACTION/detail/). Build rules
> that are few and stable enough to state inline stay here; anything that warrants
> an encoded ID is extracted to a detail file. ACTION-area IDs use the form
> `GITHUB-ACTION-NNNNNN` (see [`rules/ACTION/index.md`](rules/ACTION/index.md)).
>
> **Scope boundary**: this file governs *how the software is built, verified and
> released*. It does **not** govern version numbering — that belongs to
> [`rules/VERSION.md`](rules/VERSION.md), which owns `VERSION_NAME`, `VERSION_CODE`, the
> `-rc` suffix and the tag rules. Where both apply, VERSION.md wins on version
> content and this file wins on pipeline behaviour.

## Core Principle

**This repository has no local build toolchain. Every build runs in cloud CI.
Agents must never run a Gradle build locally.**

A local build would silently diverge from CI (different JDK, SDK and NDK) and
its result would prove nothing.

## What CI Has to Build

| Aspect | Value |
| --- | --- |
| Language | Kotlin (Compose); native code not yet present |
| Build system | Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`), Gradle `8.14.5` installed & cached by CI (not committed as a wrapper), AGP `8.7.3` |
| Modules | `:app` — the only module; layers are the packages `ui`, `navigation`, `data` (`rules/STRUCT/detail/FOTLAB-STRUCT-000001.md`) |
| Native | **None yet.** The first-party native-integration module is designed by the `NATIVE` items (`rules/DESIGN/detail/FOTLAB-NATIVE-000001.md` R1) and does not exist; until it is created there is nothing for a native job to build. |
| Upstream | git submodules under `external/` — inventory in `docs/external/index.md` |
| Default branch | `main` |

**Wrapper policy:** The Gradle Wrapper is intentionally *not* committed — there is no `gradlew`, `gradlew.bat` or `gradle-wrapper.jar` in the repository (the binary wrapper jar is rejected by repo policy). CI installs the exact Gradle version via `gradle/actions/setup-gradle`'s `gradle-version` input (pinned to `8.14.5`) and caches it; builds invoke `gradle` directly. Reproducibility is pinned by that input rather than by a wrapper.

## CI/CD Workflow — `.github/workflows/build.yaml`

| File | Role |
| --- | --- |
| `.github/workflows/build.yaml` | **Orchestrator** — triggers, `paths-ignore`, job order, release decisions. Contains no build steps. |
| `.github/workflows/build_gradle.yaml` | **Reusable workflow** (`on: workflow_call`) — Android dev env (via the composite action) + Gradle build + APK/log artifact upload. |
| `.github/workflows/release_github.yaml` | **Reusable workflow** (`on: workflow_call`) — download the APK artifact and publish a GitHub Release (pre-release on `-rc`). |
| `.github/actions/install_jdk/action.yml`, `.github/actions/install_sdk/action.yml`, `.github/actions/install_ndk/action.yml` | **Composite actions** — the toolchain is split per component: `install_jdk` (JDK), `install_sdk` (SDK + caches), `install_ndk` (NDK + caches). Each runs in-job and is reused by `build_gradle.yaml` (JDK+SDK) and by future per-language native workflows (JDK+SDK+NDK), so no step is duplicated and NDK is pulled in only when needed. |

The orchestrator composes the build and release workflows; the shared toolchain
steps live in the composite action, so every pinned toolchain version and every
build step is in one place and a second caller (e.g. a future emulator job, or a
`build_cmake` / `build_rust` native workflow) reuses it instead of copying.
Everything a caller may vary — tasks, submodules, artifact names and retention —
is a `workflow_call` input.

### Trigger Rules

| Event | Jobs | Behaviour |
| --- | --- | --- |
| push to `main` (no `v*` tag) | `apk` | Kotlin/Compose compile + debug APK + unit tests |
| Pull Request to `main` | `apk` | same as above, no release |
| push to `main` touching `external/**` (submodule pointer bump) | `apk` | An upstream bump triggers a rebuild; the native job (when added) would compile the changed third-party source. See [Submodule Bumps](#submodule-bumps). |
| push `v*` tag | `apk` → `github-release` | release APK + GitHub Release |
| `workflow_dispatch` (`release=true`) | `apk` → `github-release` | manual release |

A normal push stays on the fast Kotlin path; only a `v*` tag or `release=true`
produces a Release.

### Submodule Bumps

A bump of a gitlink under `external/` upgrades third-party source. While there is
no native job yet, the bump still triggers a rebuild so the change is not
silently ignored:

- `external/**` is **not** in the ignore list — such a push triggers CI (`apk`).
- When a native-integration module lands, a dedicated native workflow (split by
  language, e.g. `build_cmake`) would compile/verify the upstream change instead
  of a Kotlin-only build (see [Native Job Scope](#native-job-scope)).
- A submodule bump alone must never produce a GitHub Release: release still
  requires a `v*` tag or `release=true`.

### Path Filters

Changes to these paths do **not** trigger CI:

`**.md` · `docs/**` · `rules/**` · `LICENSE*` · `.gitignore` · `.readthedocs.yaml`

Everything else triggers, `external/**` included.

### Artifacts

| Artifact | Condition | Retention |
| --- | --- | --- |
| `fotlab-release-apk` | tag push / `release=true` dispatch | 30 days |
| `debug-apks` | `apk` job success | 1 day |
| `build-gradle.log` | `apk` job | 7 days |
| `build_log_gradle.log` | `build_gradle.yaml` gradle step runs (apk job) — gradle-only log, separate from the full log | 7 days |

### Key Configuration

| Item | Value |
| --- | --- |
| Runner | `ubuntu-26.04` |
| JDK | 17 (Temurin) |
| Android SDK | `platforms;android-36`, `build-tools;36.0.0` |
| `compileSdk` / `targetSdk` | `36` / `36` (set in every module's `build.gradle.kts`) |
| NDK | `28.2.13676358` (pinned for future native builds; installed by the `install_ndk` composite action when a native workflow runs) |
| Gradle tasks — push/PR | `testDebugUnitTest` `assembleDebug` |
| Gradle tasks — release | `assembleRelease` |
| Release APK output | `app/build/outputs/apk/release/*.apk` |
| Debug APK output | `app/build/outputs/apk/debug/*.apk` |

### Version Handling

- `app/build.gradle.kts` reads `VERSION_NAME` and `VERSION_CODE` from the
  repository root at configuration time, so CI never injects them.
- Format, `-rc` suffix and the **bump ⇒ tag** rule are owned by
  [`rules/VERSION.md`](rules/VERSION.md); this file does not restate them.
- The `apk` job validates `VERSION_NAME` against
  `^\d{4}\.\d{2}\.\d{2}\.\d{2}\.\d{2}(-rc)?$` and fails fast otherwise.
- Current: `VERSION_NAME` = `2026.09.07.05.48-rc`, `VERSION_CODE` = `1`.
- Release APK is renamed to `FotLab-{VERSION_NAME}-arm64-v8a-release.apk`.
- A `VERSION_NAME` ending in `-rc` publishes the GitHub Release as a
  **pre-release** (`gh release create --prerelease`); without the suffix it is a
  formal release. The meaning of `-rc` is owned by [`rules/VERSION.md`](rules/VERSION.md).

### Native Job Scope

- No native source exists yet, so there is **no native job** in `build.yaml`
  (the prior `native` placeholder was removed as redundant). When the
  native-integration module lands, add per-language build workflows — e.g.
  `build_cmake`, `build_python`, `build_perl`, `build_rust` — each a reusable
  `on: workflow_call` workflow that calls the `install_jdk`, `install_sdk` and
  `install_ndk` composite actions (in that order) and compiles its slice. Do not
  add them until the native code exists.
- Toolchains to be added with that module: CMake for the JNI bridge, Rust
  cross-compilation for `dnglab`, and whatever the `NATIVE` items decide for
  `exiftool` (`docs/architecture.md` records the layering; the integration
  route is still undecided). Steps are appended when decided — never invented
  earlier.

### Test Strategy

- Unit tests (`testDebugUnitTest`) run on the JVM: Room DAO tests against an
  in-memory database (`FOTLAB-DATABS-000001` R8), no device needed.
- Instrumentation tests: **no source set and no cases exist yet**, so no emulator
  job is configured. Adding one (a workflow that downloads `debug-apks` instead
  of rebuilding) is a later decision — see Open Questions.

## Agent Behaviour Rules

### Prohibited

1. **No local Gradle build** — `assemble*`, `build`, `compile*`, `test*`,
   `lint*` are all forbidden locally.
2. **No local toolchain setup** — do not install or configure Android SDK, NDK,
   JDK, Rust or Perl for building.
3. **Do not weaken CI to make a failure disappear** — no relaxing trigger
   filters, no disabling tasks, no `continue-on-error`.
4. **No secrets in the repository** — keystores, passwords and signing configs
   come from CI secrets only.

### Allowed

1. **Read CI logs** — `build-gradle.log` and `build_log_gradle.log` artifacts.
2. **Read `.github/workflows/*.yaml`** — to understand what CI does.
3. **Read `VERSION_NAME` / `VERSION_CODE`** — to learn the current version.
4. **Edit `VERSION_NAME` / `VERSION_CODE`** — only when the user explicitly asks,
   and always under [`rules/VERSION.md`](rules/VERSION.md) (a bump without a `v*` tag is
   incomplete).

### Verification Loop

1. Commit and push the change — push/PR triggers the `apk` job.
2. Wait for CI.
3. If CI failed, download `build-gradle.log` (and `build_log_gradle.log` for the gradle portion) into the gitignored `log/` directory (see `.gitignore`) and read the compile errors — never commit the logs. A `success` run needs no log download.
4. Fix, push again.

### Querying CI Status

1. GitHub REST API:
   `GET https://api.github.com/repos/{owner}/{repo}/actions/runs?per_page=1`
2. Extract from the response:
   - `workflow_runs[0].status` — `queued` / `in_progress` / `completed`
   - `workflow_runs[0].conclusion` — `success` / `failure` / `cancelled`
   - `workflow_runs[0].html_url` — browser link
   - `workflow_runs[0].name` — workflow name
3. Never record GitHub usernames or personal account information in these rules.

### Viewing Remote CI Results

Specified as an encoded rule: [`rules/ACTION/detail/GITHUB-ACTION-000001.md`](rules/ACTION/detail/GITHUB-ACTION-000001.md).
When the user explicitly asks to view remote CI results, the agent calls the `gh` CLI; its
location (environment-dependent) and the useful `gh run` commands are documented there.

### Release Procedure

Split across the two rule files, on purpose:

1. **Version content** ([`rules/VERSION.md`](rules/VERSION.md)): update `VERSION_NAME` and
   `VERSION_CODE` — only when the user asks.
2. Commit and push.
3. Create the `v{VERSION_NAME}` tag and push it (mandatory, per VERSION.md).
4. **Pipeline** (this file): CI runs APK → GitHub Release automatically.

## Open Questions

- Q1 — CI must confirm that AGP `8.7.3` accepts `compileSdk = 36`. If AGP rejects
  it, AGP and Gradle are upgraded **together** (their versions are
  coupled); neither is bumped alone. **TBD.**
- Q2 — Is R8 minification enabled for release? Currently `isMinifyEnabled = false`
  in `app/build.gradle.kts`. **TBD.**
- Q3 — Signing: which keystore, injected through which secret, and is release
  signing part of the first release? **TBD.**
- Q4 — ABI policy: release `arm64-v8a` only; does debug need `x86_64` for a
  future emulator job? **TBD.**
- Q5 — When are instrumentation tests introduced, and does the emulator workflow
  reuse `debug-apks` instead of rebuilding? **TBD.**
- Q6 — Does a submodule bump need the *full* native toolchain (Rust/Perl), or is
  a compile-only check of the bridge enough? Depends on the pending `NATIVE`
  decisions. **TBD.**

## Change History

| Date | Description |
| --- | --- |
| 2026-09-07 | Cross-references rewritten to start at the repository root (`docs/architecture.md`, `rules/VERSION.md`, `app/build.gradle.kts`), following the path convention now stated in `AGENTS.md`. |
| 2026-09-07 | Submodule bumps now trigger CI and run the `native` job (`external/**` is explicitly excluded from the path ignore list); a bump alone never creates a release. Also declared the scope boundary against `VERSION.md` — version content is owned there, pipeline behaviour here. |
| 2026-09-07 | `compileSdk` / `targetSdk` set to `36` in every Gradle module, matching the CI SDK baseline; AGP compatibility left to CI verification (Q1). Native job re-scoped: no native source exists yet, so it stays wired but inactive until the native-integration module designed by the `NATIVE` items is created. Cross-references adjusted — superseded the same day by the repository-root convention. |
| 2026-09-07 | Initial creation. Declares the no-local-toolchain rule, the `build.yml` trigger matrix for `main`, the SDK/NDK baseline, artifact set and retention, version handling delegated to `VERSION.md`, JVM-only unit testing, and the agent prohibited/allowed list. |
| 2026-09-07 | Multi-module build collapsed into the single module `:app` (`rules/STRUCT/detail/FOTLAB-STRUCT-000001.md`): `:core:ui`, `:core:data` and `:feature:library` merged into `app/`, with layers expressed as the packages `ui`, `navigation` and `data`. Gradle task set, SDK baseline and triggers are unchanged. |
| 2026-09-08 | CI split into the orchestrator `.github/workflows/build.yaml` and the reusable `.github/workflows/gradle.yaml` (`on: workflow_call`). Triggers, path filters, the `external/**` submodule-bump condition and release publishing stay in the orchestrator; toolchain setup, the Gradle invocation and artifact upload move into the reusable workflow. Trigger matrix, artifact set with retention, SDK/NDK baseline and task set are unchanged; the release is published with `gh` instead of a third-party action. |
| 2026-09-08 | GitHub Release now honours the `-rc` suffix: a `VERSION_NAME` ending in `-rc` is published with `--prerelease` (and an existing release is edited to match), a formal version is published as a normal release. |
| 2026-09-09 | Added "Viewing Remote CI Results (gh CLI)": when the user explicitly asks to view remote CI results, the agent calls the `gh` CLI; documents its environment-dependent location (e.g. `C:\Program Files\GitHub CLI\gh.exe` on Windows, or locate via `where gh` / `Get-Command` / common install dirs) plus useful `gh run` commands. |
| 2026-09-09 | Per AGENTS.md three-layer layout: extracted the GH CLI rule into the encoded detail file `rules/ACTION/detail/GITHUB-ACTION-000001.md`, created `rules/ACTION/index.md` as the Layer 2 master table, and replaced the inline section in `rules/ACTION.md` with a brief reference. `rules/ACTION.md` is now Layer 1 only. |
| 2026-09-09 | CI logs must be downloaded into the gitignored `log/` directory (`.gitignore`) and never committed; documented in the Verification Loop and in `rules/ACTION/detail/GITHUB-ACTION-000001.md`. |
| 2026-09-09 | Added a separate `build_log_gradle.log` artifact from the gradle step (in addition to the full `build-log.txt` log), so tooling can fetch the gradle portion independently; 7-day retention. Documented in the Artifacts table. |
| 2026-09-09 | Verification Loop now states a `success` run needs no log download; only `failure` runs warrant fetching logs into the gitignored `log/`. |
| 2026-09-09 | CI restructured into the orchestrator `build.yaml` plus three reusable workflows — `devenv_android.yaml` (env only), `build_gradle.yaml` (Gradle build + artifacts), `release_github.yaml` (GitHub Release). The shared toolchain steps moved into the composite action `.github/actions/devenv-android/action.yml` so env setup is defined once and reused. Naming clarified: env setup / Gradle build / release are now separate, reusable and composed in `build.yaml`. |
| 2026-09-09 | Removed the redundant `devenv_android.yaml` reusable workflow and the `native` placeholder job in `build.yaml`; the shared toolchain steps now live in per-component composite actions (`install_jdk` / `install_sdk` / `install_ndk`), reused in-job by `build_gradle.yaml`. Native builds, when needed, will be added as per-language workflows (`build_cmake`/`build_python`/`build_perl`/`build_rust`) — not yet present. Architecture principles extracted to `rules/ACTION/detail/GITHUB-ACTION-000002.md`. |
| 2026-09-09 | Split the monolithic `devenv-android` composite action into three per-component composite actions — `install_jdk` (JDK), `install_sdk` (SDK + per-component cache), `install_ndk` (NDK + per-component cache) — so NDK install is no longer a parameter switch. `build_gradle.yaml` now calls `install_jdk` + `install_sdk`; future native workflows add `install_ndk`. The old `devenv-android/action.yml` is deleted. |
