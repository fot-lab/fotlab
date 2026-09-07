# ACTION — Build & CI/CD Behaviour

> Version: 1.1
> Updated: 2026-09-07

> **Redirect**: this file is the entry point of the build and CI rules. It has no
> index folder yet — build rules are few and stable enough to live in one file.
> If encoded entries ever become necessary, they follow the same three-layer
> layout as `DESIGN/` (`ACTION/index.md` + `ACTION/detail/FOTLAB-XXXXXX-NNNNNN.md`).
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
| Build system | Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`), Gradle wrapper `8.9`, AGP `8.7.3` |
| Modules | `:app` (shell), `:core:ui`, `:core:data`, `:feature:*` |
| Native | **None yet.** The first-party native-integration module is designed by the `NATIVE` items (`rules/DESIGN/detail/FOTLAB-NATIVE-000001.md` R1) and does not exist; until it is created there is nothing for a native job to build. |
| Upstream | git submodules under `external/` — inventory in `docs/external/index.md` |
| Default branch | `main` |

## CI/CD Workflow — `.github/workflows/build.yml`

### Trigger Rules

| Event | Jobs | Behaviour |
| --- | --- | --- |
| push to `main` (no `v*` tag) | `apk` | Kotlin/Compose compile + debug APK + unit tests |
| Pull Request to `main` | `apk` | same as above, no release |
| push to `main` touching `external/**` (submodule pointer bump) | `apk` **and** `native` | An upstream bump changes third-party source, so it must not be validated by a Kotlin-only build. See [Submodule Bumps](#submodule-bumps). |
| push `v*` tag | `native` → `apk` → `github-release` | native build (when native source exists) + release APK + GitHub Release |
| `workflow_dispatch` (`build_native=true`) | `native` → `apk` | manual native verification |
| `workflow_dispatch` (`release=true`) | `native` → `apk` → `github-release` | manual release |

Native compilation is expensive: it runs on tags, manual dispatch and submodule
bumps only. A normal push stays on the fast Kotlin path.

### Submodule Bumps

A bump of a gitlink under `external/` upgrades third-party source. It is treated
as a **native-relevant change**:

- `external/**` is **not** in the ignore list — such a push triggers CI.
- The `native` job runs so the upstream change is compiled/verified, not just
  linked against a Kotlin-only build.
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
| `build-native.log` | `native` job | 7 days |

### Key Configuration

| Item | Value |
| --- | --- |
| Runner | `ubuntu-24.04` |
| JDK | 17 (Temurin) |
| Android SDK | `platforms;android-36`, `build-tools;36.0.0` |
| `compileSdk` / `targetSdk` | `36` / `36` (set in every module's `build.gradle.kts`) |
| NDK | `28.2.13676358` (native job only) |
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

### Native Job Scope

- No native source exists yet, so the job has no build steps to run. It stays
  wired into the pipeline (triggers, artifact slot, NDK setup) and becomes
  effective when the native-integration module lands.
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

1. **Read CI logs** — `build-gradle.log`, `build-native.log` artifacts.
2. **Read `.github/workflows/*.yml`** — to understand what CI does.
3. **Read `VERSION_NAME` / `VERSION_CODE`** — to learn the current version.
4. **Edit `VERSION_NAME` / `VERSION_CODE`** — only when the user explicitly asks,
   and always under [`rules/VERSION.md`](rules/VERSION.md) (a bump without a `v*` tag is
   incomplete).

### Verification Loop

1. Commit and push the change — push/PR triggers the `apk` job.
2. Wait for CI.
3. Download `build-gradle.log` and read the compile errors.
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

### Release Procedure

Split across the two rule files, on purpose:

1. **Version content** ([`rules/VERSION.md`](rules/VERSION.md)): update `VERSION_NAME` and
   `VERSION_CODE` — only when the user asks.
2. Commit and push.
3. Create the `v{VERSION_NAME}` tag and push it (mandatory, per VERSION.md).
4. **Pipeline** (this file): CI runs native → APK → GitHub Release automatically.

## Open Questions

- Q1 — CI must confirm that AGP `8.7.3` accepts `compileSdk = 36`. If AGP rejects
  it, AGP and the Gradle wrapper are upgraded **together** (their versions are
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
