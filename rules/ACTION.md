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
| Language | Kotlin (Compose) + Rust (one native library) |
| Build system | Gradle Kotlin DSL + version catalog (`gradle/libs.versions.toml`), Gradle `8.14.5` installed & cached by CI (not committed as a wrapper), AGP `8.7.3` |
| Modules | `:app` — the only Gradle module; layers are the packages `ui`, `navigation`, `data` (`rules/STRUCT/detail/FOTLAB-STRUCT-000001.md`) |
| Native | `rawler_fotlab` — the first-party binding crate at `app/src/binding/rust/rawler_fotlab` (Rust + UniFFI), built by `build_rust.yaml` against the Android NDK. It consumes upstream source through a *path* dependency on `external/dnglab/rawler`; upstream is never modified (`FOTLAB-STUDIO-000001`, `FOTLAB-NATIVE-000001` R4). |
| Upstream | git submodules under `external/` — inventory in `docs/external/index.md` |
| Default branch | `main` |

**Wrapper policy:** The Gradle Wrapper is intentionally *not* committed — there is no `gradlew`, `gradlew.bat` or `gradle-wrapper.jar` in the repository (the binary wrapper jar is rejected by repo policy). CI installs the exact Gradle version via `gradle/actions/setup-gradle`'s `gradle-version` input (pinned to `8.14.5`) and caches it; builds invoke `gradle` directly. Reproducibility is pinned by that input rather than by a wrapper.

## CI/CD Workflow — `.github/workflows/build.yaml`

| File | Role |
| --- | --- |
| `.github/workflows/build.yaml` | **Orchestrator** — triggers, `paths-ignore`, job order, release decisions. Contains no build steps. |
| `.github/workflows/build_gradle.yaml` | **Reusable workflow** (`on: workflow_call`) — Android dev env (via the composite action) + Gradle build + APK/log artifact upload. Places the `rawler_fotlab` native artifact before Gradle runs. |
| `.github/workflows/build_rust.yaml` | **Reusable workflow** (`on: workflow_call`) — builds `librawler_fotlab.so` for the four ABIs with `cargo ndk` and generates the UniFFI Kotlin bindings with the crate's own `uniffi-bindgen` bin; uploads them as the `rawler_fotlab` artifact. |
| `.github/workflows/release_github.yaml` | **Reusable workflow** (`on: workflow_call`) — download the APK artifact and publish a GitHub Release (pre-release on `-rc`). |
| `.github/workflows/smoke_emulator.yaml` | **Reusable workflow** (`on: workflow_call`) — boot an AVD from the emulator cache and run the instrumented smoke tests (`connectedDebugAndroidTest`) against the debug build. Called on the **non**-release path only; it is the sibling of `release_github.yaml`. See [Emulator Smoke Test](#emulator-smoke-test). |
| `.github/actions/locate_sdk/action.yml`, `.github/actions/install_jdk/action.yml`, `.github/actions/install_sdk/action.yml`, `.github/actions/install_ndk/action.yml` | **Composite actions** — the toolchain is split per component: `locate_sdk` (SDK root resolution), `install_jdk` (JDK), `install_sdk` (SDK), `install_ndk` (NDK). `install_sdk` / `install_ndk` are **preinstalled-first**: they probe the runner's existing `ANDROID_HOME` for the pinned components and only run the cache + `setup-android` / `sdkmanager --install` fallback when something is actually missing, ending with a fail-fast verification. Each runs in-job and is reused by `build_gradle.yaml` (JDK+SDK), `smoke_emulator.yaml` (JDK+SDK) and by future per-language native workflows (JDK+SDK+NDK), so no step is duplicated and NDK is pulled in only when needed. |

The orchestrator composes the build, smoke and release workflows; the shared
toolchain steps live in the composite actions, so every pinned toolchain version
and every build step is in one place and a second caller (the emulator job, or a
`build_cmake` / `build_python` native workflow) reuses it instead of copying.
Everything a caller may vary — tasks, submodules, artifact names and retention —
is a `workflow_call` input.

### Trigger Rules

| Event | Jobs | Behaviour |
| --- | --- | --- |
| push to `main` (no `v*` tag) | `rust` → `apk` → `emulator-smoke` | Rust `librawler_fotlab.so` + Kotlin/Compose compile + debug APK + unit tests, then the emulator smoke tests |
| Pull Request to `main` | `rust` → `apk` → `emulator-smoke` | same as above, no release |
| push to `main` touching `external/**` (submodule pointer bump) | `rust` → `apk` → `emulator-smoke` | An upstream bump triggers a rebuild, including the Rust slice that compiles the changed third-party source. See [Submodule Bumps](#submodule-bumps). |
| push `v*` tag | `rust` → `apk` → `github-release` | release APK + GitHub Release — **no** emulator smoke job |
| `workflow_dispatch` (`release=true`) | `rust` → `apk` → `github-release` | manual release — **no** emulator smoke job |

A normal push stays on the fast Kotlin path; only a `v*` tag or `release=true`
produces a Release. `emulator-smoke` and `github-release` are siblings gated on
the same `release` flag from `preflight`, and they are exact complements: a
release run publishes instead of smoke-testing, a normal run smoke-tests instead
of publishing.

### Emulator Smoke Test

`emulator-smoke` (reusable workflow `.github/workflows/smoke_emulator.yaml`)
answers "does the built application actually run on Android?" on the normal
path, where no release is produced to verify.

| Aspect | Value |
| --- | --- |
| Gate | `preflight.release != 'true'` **and** `apk` succeeded — a release run skips it |
| Runner | `ubuntu-26.04` |
| KVM | **Required, and not granted by default.** `/dev/kvm` exists on the image but is owned by group `kvm`, which the runner user is not in, so the emulator starts with `-accel off` and its adb daemon never comes up (`ProbeKVM: This user doesn't have permissions to use KVM`). The job writes `/etc/udev/rules.d/99-kvm4all.rules` with `MODE="0666"` and reloads udev — the recipe published by the emulator-runner action. Group membership cannot be granted mid-job, because a new supplementary group only applies to a fresh login session. |
| AVD | API `36`, system-image target `google_apis`, arch `x86_64` (an ABI present in `librawler_fotlab.so`) |
| Emulator flags | `-no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none`, defined as job env values (not inputs, which could let a caller break the snapshot invariants). The test run adds `-no-snapshot-save`: it loads the cached snapshot but never overwrites it, so our APK cannot contaminate the cached emulator. |
| Tests | `app/src/androidTest/kotlin` — the AGP-default instrumented source set (Google's recommended app layout); run via `connectedDebugAndroidTest`, which installs the debug APK plus its test APK |
| Job timeout | 45 min (test step 20 min) — a wedged emulator fails rather than holding the runner |

The emulator cache is written **after** the AVD has been created and **before**
the first APK install, so the cached snapshot is a clean warm emulator that every
later build reuses. That ordering is why the job uses the split
`actions/cache/restore` + `actions/cache/save` pair instead of `actions/cache`,
whose save would run as a post-job step — after our APK had been installed. The
entry also carries the SDK packages the AVD boots (see [CI cache layers](#ci-cache-layers-slow--fast-changing)),
so a warm run downloads nothing from `dl.google.com` at all.

See the [Test Strategy](#test-strategy) for what the cases assert.

### Submodule Bumps

A bump of a gitlink under `external/` upgrades third-party source. The bump triggers a
full rebuild — including the Rust slice that compiles that source — so the change is
never silently ignored:

- `external/**` is **not** in the ignore list — such a push triggers CI (`rust` → `apk`).
- The per-language native workflow (`build_rust` today; `build_cmake` etc. later)
  compiles/verifies the upstream change rather than relying on the Kotlin-only
  build (see [Native Job Scope](#native-job-scope)).
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
| `rawler_fotlab` | `rust` job success — `jniLibs/<abi>/librawler_fotlab.so` + the generated UniFFI Kotlin bindings, consumed by the `apk` job | 7 days |
| `build-gradle.log` | `apk` job | 7 days |
| `build_log_gradle.log` | `build_gradle.yaml` gradle step runs (apk job) — gradle-only log, separate from the full log | 7 days |
| `build-smoke.log` | `emulator-smoke` job **failure only** — the Gradle log plus the `connectedAndroidTest` reports/XML under `app/build/{reports,outputs}/androidTest-results` | 7 days |

### Key Configuration

| Item | Value |
| --- | --- |
| Runner | `ubuntu-26.04` |
| JDK | 17 (Temurin) |
| Android SDK home | Runner-provided `ANDROID_HOME` (`/usr/local/lib/android/sdk` on the hosted image); workflows assert it is set and never redirect it to a private `$HOME` tree |
| Android SDK | `platforms;android-36`, `build-tools;36.0.0` — **preinstalled on the image** (with cmdline-tools, platform-tools, licenses accepted); `install_sdk` verifies and installs only what a future image is missing |
| `compileSdk` / `targetSdk` | `36` / `36` (set in every module's `build.gradle.kts`) |
| NDK | `28.2.13676358` — **preinstalled on the image** (among 27.3 / 28.2.13676358 / 29.0); `install_ndk` verifies and installs only when missing |
| Emulator (smoke) | AVD API `36`, target `google_apis`, arch `x86_64`; managed by `reactivecircus/android-emulator-runner` and reused from the emulator cache (AVD + snapshot + the `system-images` / `emulator` / `build-tools` packages). Needs the [KVM udev rule](#emulator-smoke-test); the action additionally force-installs the latest `build-tools` (37.0.0). |
| Gradle tasks — push/PR | `testDebugUnitTest` `assembleDebug` |
| Gradle tasks — emulator smoke | `connectedDebugAndroidTest` |
| Gradle tasks — release | `assembleRelease` |
| Release APK output | `app/build/outputs/apk/release/*.apk` |
| Debug APK output | `app/build/outputs/apk/debug/*.apk` |

#### CI cache layers (slow → fast changing)

1. **Emulator (AVD + the SDK packages it boots)** — one entry covering `~/.android/avd`, `~/.android/adb*`, `$ANDROID_HOME/system-images`, `$ANDROID_HOME/emulator` and `$ANDROID_HOME/build-tools`, owned by `smoke_emulator.yaml` and keyed by API level + system-image target + arch. It changes only when the AVD definition does, so it is the slowest-changing layer of all. It is written **after** the AVD exists and **before** our APK is installed, so the snapshot stays app-free and reusable (`actions/cache/restore` + `actions/cache/save`, not `actions/cache`).
   The AVD and the system image it boots are cached **together on purpose**: an AVD whose system image is missing cannot start, so splitting them would let the AVD hit while the image missed. Caching `system-images` + `emulator` removes ~1.4 GB of `dl.google.com` traffic per run, and restoring them also short-circuits the emulator-runner action's `sdkmanager --install`, because each package's own `package.xml` metadata travels inside the cached directory. `build-tools` is in the entry only because that action force-installs the *latest* build-tools (37.0.0 today) next to the pinned 36.0.0 from `install_sdk`.
2. **NDK** — preinstalled at `$ANDROID_HOME/ndk/<ver>` on the image; the `install_ndk` fallback `actions/cache` is keyed by NDK version alone (`Linux-ndk-<ver>`). Our code and Rust rebuilds never invalidate it.
3. **Rust toolchain** — `~/.rustup` (host `rustc`/`cargo` plus the four Android target std libraries), owned by `build_rust.yaml`. `rust-cache` never covered this, so `dtolnay/rust-toolchain` re-fetched `info: downloading 4 components` (~250 MB) on **every** run. It changes only when the `stable` channel moves (every ~6 weeks), hence its place near the top. The restore is by **prefix** and the save is keyed by the toolchain actually installed (`<os>-rustup-<rustc version>`), because a constant key cannot work: `actions/cache` never re-saves an entry it restored, so a fixed key would freeze the archive at the release current on creation day and re-download the difference forever. Each Rust release therefore mints one new entry; the previous one is dead weight and falls to the quota-hygiene pass. See [the layer's comment](.github/workflows/build_rust.yaml) for the full reasoning.
4. **Rust dependencies** — `$CARGO_HOME/registry`, `/git`, `/bin` (crates.io sources, git deps, the `cargo-ndk` binary) inside `Swatinem/rust-cache`; the key segment is the lockfile/manifest hash.
5. **Rust build products** — the crate's `target/` (four Android ABIs + host bindgen) in the same rust-cache archive; the volatile key tail is `NDK_VERSION MIN_API DNGLAB_SHA`.

`DNGLAB_SHA` is fed via rust-cache `env-vars` (tail of the key), **not** via `key` (which sits before the lockfile segment): on a submodule bump the progressive prefix restore still matches the previous run at the lockfile segment, so layer 4 stays warm and cargo re-fingerprints/rebuilds only rawler + the first-party crate. A mid-chain `key: dnglab-<sha>` would discard the dependency layer on every bump. Layers 4–5 live in one archive because rust-cache always caches `$CARGO_HOME` together with the workspace target; the layering is expressed through key-chain fallback, not separate archives.

Layer 1 belongs to the `emulator-smoke` job; layers 2–5 belong to the `rust` job. The Gradle dependency/output cache restored by `gradle/actions/setup-gradle` is a further, unlisted layer: `smoke_emulator.yaml` restores what the `apk` job wrote, so `connectedDebugAndroidTest` re-runs only the androidTest slice instead of recompiling the application. That layer is real, not theoretical: the `apk` job's log shows most tasks `FROM-CACHE` (resources, manifests, dexing) and `~/.gradle/caches/build-cache-1` inside the restored entry, because `setup-gradle` passes `--build-cache`.

#### Downloads deliberately left uncached

These still cross the network on every run. Each is a conscious trade, not an oversight.

| Download | Cost per run | Why it is not cached |
| --- | --- | --- |
| Git submodules — `external/**` (dnglab, RawTherapee, colour, exiftool, rawloader) | a full clone of each, every run | The checked-out submodule tree is exactly what the Rust slice *compiles*, so a stale entry would silently build the wrong source — the one outcome an `external/**` bump must never produce. `actions/checkout` re-clones instead. |
| The actions themselves — `Download action repository '<owner>/<repo>@<ref>'` | small, per action | Fetching each action's code is inherent to hosted runners; there is nothing in the workflow to cache. |

#### Quota hygiene

The repository has a **10 GiB** Actions-cache budget, and most families key on something that changes: `gradle-transforms-v1-<hash>`, `gradle-dependencies-v1-<hash>` and `gradle-home-v1|…|<commit-sha>` mint a **new generation per build-config change or per commit**, and `<os>-rustup-<rustc version>` mints one **per Rust release**. The superseded entries keep occupying the budget until something evicts them, and `gh cache` never garbage-collects by itself.

This is not theoretical: before this rule was written the repository sat at **8.66 GiB of 10 GiB across 116 entries**, of which 6.27 GiB was superseded Gradle generations — `gradle-transforms-v1-*` alone held 18 entries / 4.11 GiB. At that occupancy GitHub starts evicting, and the first casualties are the expensive layers (`v0-rust-build-*` at 653 MiB, `Linux-ndk-*` at 651 MiB) whose loss costs ~9 minutes per run.

Keep the newest generation of every family and delete the rest. This is safe precisely because `setup-gradle` and `rust-cache` restore by **key prefix** — the surviving newest entry is the one a prefix restore would have picked anyway:

```bash
# dry run first: print what would go
gh cache list --limit 1000 --json id,key,sizeInBytes,createdAt \
  | jq -r 'group_by(.key | sub("[0-9a-f]{6,}.*$"; ""))[] | sort_by(.createdAt) | reverse | .[1:][] | .id' \
  | xargs -r -n1 gh cache delete
```

Do **not** prune to fewer than one entry per family, and leave the deliberate safety nets alone: `Linux-ndk-*` and `Linux-android-sdk-*` exist so a runner image that loses a preinstalled component heals itself instead of failing deep inside Gradle.

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

- The Rust slice exists: `.github/workflows/build_rust.yaml` is its reusable
  `on: workflow_call` workflow. It installs the NDK and the Rust toolchain and
  compiles only its own slice; the Android toolchain + Gradle slice stays in
  `build_gradle.yaml`, and `build.yaml` orders `rust` before `apk` and passes the
  `rawler_fotlab` artifact name down.
- The native sources live in the first-party module, not in `external/`:
  `app/src/binding/rust/rawler_fotlab` (Rust) and
  `app/src/binding/kotlin/io/github/fotlab/fotlab_rawler` (Kotlin
  facade). Upstream source is reached through a path dependency and is never
  edited (`FOTLAB-NATIVE-000001` R4).
- Future native slices follow the same shape: one reusable `build_<language>.yaml`
  per language (`build_cmake`, `build_python`, `build_perl`), never an all-in-one
  job. Add each only when its native code exists.
- Generated output is not committed and is not placed under `src/`: both the UniFFI
  Kotlin bindings and the downloaded `.so` files live in `app/build/generated/`
  (`FOTLAB-STRUCT-000002` R1). See `FOTLAB-STUDIO-000001` §"Native build".

### Test Strategy

- Unit tests (`testDebugUnitTest`) run on the JVM: Room DAO tests against an
  in-memory database (`FOTLAB-DATABS-000001` R8), no device needed.
- Instrumented smoke tests live in the AGP-default instrumented source set
  `app/src/androidTest/kotlin` (Google's recommended app layout — no extra
  `kotlin.srcDir` registration, unlike the hand-written binding facade) and run on
  the emulator via `connectedDebugAndroidTest`, driven by the `emulator-smoke` job
  on the non-release path. They answer "does the built application actually run?",
  so they assert survival and wiring, not feature detail:
  - `MainActivitySmokeTest` — `MainActivity` reaches `RESUMED`, exercising
    `MainApplication`, the Room/DataStore wiring and the JNA load of
    `librawler_fotlab.so`.
  - `RawlerNativeSmokeTest` — the native bridge survives the two call shapes that
    used to abort the process: a PNG and arbitrary non-image bytes, through both
    `identifyFormat` and `decodeRawToPng`. A process-level abort (native panic,
    `SIGSEGV`, `UnsatisfiedLinkError` on the emulator ABI) fails the run by
    construction, because `RawlerFotlabBridge` swallows JVM exceptions with
    `runCatching` — only a process death can be observed. See
    `FOTLAB-CRASH-000001`.
- The emulator job runs on PRs as well as on `main`, since both take the
  non-release path.

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
- Q2 — **RESOLVED.** R8 minification and resource shrinking are enabled for release
  (`isMinifyEnabled` / `isShrinkResources` in `app/build.gradle.kts`). The keep rules JNA and the
  UniFFI bindings need live in `app/proguard-rules.pro` — R8 must not rename the generated JNA
  interface methods, whose names ARE the native symbol names. Only release builds are minified, so a
  debug-only CI run does not exercise this configuration; a release build should be smoke-tested.
- Q3 — Signing: which keystore, injected through which secret, and is release
  signing part of the first release? **TBD.**
- Q4 — **RESOLVED.** Release stays `arm64-v8a` only; the debug build keeps all four
  ABIs, and the emulator job needs `x86_64`, which `build_rust.yaml` already builds
  (`cargo ndk` targets `arm64-v8a armeabi-v7a x86 x86_64`). The smoke workflow pins
  `arch: x86_64` so the AVD and the library agree.
- Q5 — **RESOLVED.** Instrumentation tests are introduced with the `emulator-smoke`
  job: the cases live in `app/src/androidTest/kotlin` and run via
  `connectedDebugAndroidTest`. The job does **not** consume the `debug-apks`
  artifact — Gradle restores the `apk` job's build cache and rebuilds only the
  androidTest slice, which keeps the debug APK and its test APK guaranteed to come
  from one build rather than being paired across artifacts.
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
| 2026-09-14 | Native slice is now real: `.github/workflows/build_rust.yaml` builds `librawler_fotlab.so` (`cargo ndk -o` for the four ABIs) from the first-party crate at `app/src/binding/rust` and generates the Kotlin bindings with the crate's own `uniffi-bindgen` bin, uploading the `rawler_fotlab` artifact. `build_gradle.yaml` places both into `app/build/generated/` (never `src/`), registered as `kotlin.srcDir` / `jniLibs.srcDir`. Trigger table, artifact table, "What CI Has to Build", Native Job Scope and Submodule Bumps updated accordingly. |
| 2026-09-14 | Closed Q2: release builds now run R8 + resource shrinking, with the JNA / UniFFI keep rules in `app/proguard-rules.pro` (the generated JNA interface method names are native symbol names and must not be obfuscated). Static doc drift removed as well: the Submodule Bumps intro no longer claims there is no native job. |
| 2026-09-15 | Toolchain actions switched to **preinstalled-first**: the `ubuntu-26.04` image ships cmdline-tools, platform-tools, `platforms;android-36`, `build-tools;36.0.0` and NDK `28.2.13676358` at its own `ANDROID_HOME` (`/usr/local/lib/android/sdk`, licenses accepted). Workflows no longer redirect `ANDROID_HOME` to an empty `$HOME/Android/Sdk` (which re-downloaded platform-tools on every run); they assert the prebuilt SDK and `install_sdk` / `install_ndk` probe for the pinned components, running the cache + `setup-android` / `sdkmanager --install` fallback only when a component is missing, then fail-fast verify. Dead `NDK_VERSION` env removed from `build_gradle.yaml` (Gradle never uses an NDK). Also fixed `setup-android@v4` failing on Google's removal of the legacy `tools` package by passing `packages: platform-tools`. |
| 2026-09-15 | SDK root resolution made robust on the `ubuntu-26.04` preview image: the runner exports `ANDROID_HOME` only as an inherited process variable (the workflow `env` context evaluates it empty, and an empty composite-step `env:` silently overrides the inherited value). Both reusable workflows now resolve the root in bash (`/usr/local/lib/android/sdk` first, then the well-known fallbacks) and publish it via `$GITHUB_ENV`. Verified against image `ubuntu26/20260907.131.1`: all pinned components present, happy path performs zero downloads and no SDK/NDK cache restore. |
| 2026-09-15 | Rust cache split into logical layers (new "CI cache layers" table under Key Configuration): NDK stays its own image/version-keyed layer; inside rust-cache the dnglab submodule SHA moved from the mid-chain `key` input to `env-vars` (`NDK_VERSION MIN_API DNGLAB_SHA`), so a submodule bump no longer discards the slow-changing `$CARGO_HOME` dependency layer — progressive prefix restore keeps it warm while cargo rebuilds only the changed rawler path-dep and the first-party crate. |
| 2026-09-15 | Emulator smoke test added as the sibling of the release job: `.github/workflows/smoke_emulator.yaml` (reusable) boots an AVD and runs `connectedDebugAndroidTest` against the debug build, and `build.yaml` gains `emulator-smoke` gated on `preflight.release != 'true'` — a release run publishes instead, a normal run smoke-tests instead of publishing. Instrumented cases live in the new AGP-default source set `app/src/androidTest/kotlin` (`MainActivitySmokeTest`, `RawlerNativeSmokeTest`) with `androidx.test` runner/core/ext-junit added to the version catalog and `androidTestImplementation`. The AVD cache uses the split `actions/cache/restore` + `actions/cache/save` pair so it is written after the emulator exists and before any APK is installed. The SDK-root resolution shared by three workflows was extracted into the new `locate_sdk` composite action. Closes Q4 and Q5; the "CI cache layers" table gained the AVD layer and the release/PR trigger rows now run the smoke job. |
| 2026-09-15 | First smoke run failed and its cache audit landed two fixes. (a) The job never enabled KVM: the emulator fell back to `-accel off`, its adb daemon never came up and the AVD-creation step died. The job now writes the emulator-runner action's documented `99-kvm4all.rules` udev rule before starting the emulator. (b) The AVD cache was extended into a single **emulator** cache that also carries `$ANDROID_HOME/system-images`, `$ANDROID_HOME/emulator` and `$ANDROID_HOME/build-tools` — the ~1.4 GB the action otherwise re-downloaded from `dl.google.com` every run; the AVD and its system image must share one entry, since an AVD without its image cannot boot. The test run now adds `-no-snapshot-save` so the cached snapshot is never overwritten by a run that has our APK installed. New "Downloads deliberately left uncached" subsection records what still crosses the network and why (`~/.rustup`, git submodules, the actions' own repositories). |
| 2026-09-15 | Cache-quota audit. The repository was at **8.66 GiB of its 10 GiB budget across 116 entries**, almost all of it superseded Gradle generations (`gradle-transforms-v1-*`: 18 entries / 4.11 GiB; `gradle-dependencies-v1-*`: 6 / 1.52 GiB; `gradle-home-v1\|…\|<commit-sha>`: 31 / 0.64 GiB) plus two orphaned `v0-rust-build-*` and `v0-rust-dnglab-*` archives (~1.9 GiB) left behind by the crate-path moves, whose `lastAccessedAt` equalled `createdAt` — i.e. never restored. 55 dead entries deleted, taking usage to 2.26 GiB, and a new "Quota hygiene" subsection states the rule (keep the newest generation per family, never drop below one, leave the NDK/SDK safety nets) with the dry-run command. |
| 2026-09-15 | Rust toolchain download cached, superseding the previous entry's decision to leave `~/.rustup` uncached. `build_rust.yaml` now restores `~/.rustup` by prefix **before** `Install Rust`, then saves it immediately after the install under `<os>-rustup-<rustc version>` (the key is unknowable beforehand, and a constant key cannot work because `actions/cache` never re-saves a restored entry — the archive would freeze on creation-day `stable` and re-download the difference forever). The save is skipped when `cache-matched-key` already equals the computed key, so it can never collide with an existing entry, and it runs before anything that can fail so a later build failure cannot discard a successful toolchain download. This removes the last recurring download of size in the `rust` job (~250 MB, four Android target std libraries). The rustup layer was inserted as layer 3 of the cache table, the `~/.rustup` row was dropped from "Downloads deliberately left uncached", and quota hygiene now also names `<os>-rustup-<version>` as a per-release family. |
