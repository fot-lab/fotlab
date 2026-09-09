# Preflight toolchain caching audit — SDK/NDK/Gradle cached; Rust NDK & python-for-android pending

- ID: ACTION-PREPIN-000001
- Status: Observation
- Priority: P3
- Created: 2026-09-09
- Owner: —
- Related: `rules/ACTION.md` (Native Job Scope, Wrapper policy), `rules/DESIGN/detail/FOTLAB-NATIVE-000002.md`, `rules/DESIGN/detail/FOTLAB-NATIVE-000003.md`, `.github/workflows/gradle.yaml`, `.github/workflows/build.yaml`

## Background & Goal

Audit the runner's toolchain bootstrap — the `gradle.yaml` reusable workflow, which acts as the
de-facto preflight — to confirm the dev build tools are installed **and cached**, so the ubuntu
runner does not re-download them on every run. The stated requirement: cache not only the Android
SDK, but also the NDK, Gradle, the Rust cross-compile toolchain (Rust + Android NDK targets),
python-for-android (p4a), and other native build tools.

## Audit findings

| Tool | Installed in CI | Cached | Where |
| --- | --- | --- | --- |
| Android SDK (`platforms;android-36`, `build-tools;36.0.0`) | Yes | Yes | `actions/cache` on `$ANDROID_HOME` — `gradle.yaml:114-120` |
| NDK (`28.2.13676358`) | Yes (only when `install-ndk: true`) | Yes (inside `$ANDROID_HOME`) | `gradle.yaml:127-129` |
| Gradle `8.14.5` | Yes | Yes | `gradle/actions/setup-gradle` default enhanced cache (distribution + dependency caches) — `gradle.yaml:131-139` |
| JDK 17 (Temurin) | Yes | No (setup-java built-in; small) | `gradle.yaml:105-109` |
| **Rust cross-compile** (`cargo` + `aarch64-linux-android`, `x86_64-linux-android`) | **No** | **No** | not present |
| **python-for-android (p4a)** | **No** | **No** | not present |
| CMake / Perl / other native tools | **No** | **No** | not present |

Conclusion: SDK, NDK and Gradle are installed and cached correctly — the "no re-download" goal is
met for those three. The Rust NDK toolchain and p4a are **not installed at all**, so there is
nothing to cache yet.

## Policy conflict — why Rust NDK / p4a are absent

`rules/ACTION.md` ("Native Job Scope", lines ~120-129) states the native toolchains are
*"to be added with that module … Steps are appended when decided — never invented earlier."* The
corresponding `NATIVE` design items (`FOTLAB-NATIVE-000002`, `FOTLAB-NATIVE-000003`) are still
**Draft** with open questions (CPython version, ABI coverage, p4a version; see
`FOTLAB-NATIVE-000003.md` Q1-Q6).

Therefore preemptively installing and caching Rust/p4a now would (a) violate the "never invented
earlier" rule, and (b) cache a toolchain configuration that is still undecided (versions/ABIs
open), wasting runner time and risking churn.

## Recommendation

- Keep the current SDK/NDK/Gradle caching as-is — it is correct.
- Defer the Rust NDK and p4a toolchains until the native-integration module lands. When it does,
  add them to `gradle.yaml` (gated behind `install-ndk`) and cache them as below.

### Cache plan for when the native toolchains are added

- **Rust NDK**: cache `$CARGO_HOME` and `$RUSTUP_HOME`. Install the pinned toolchain (dnglab
  declares `rust-version = "1.89"`, see `DNGLAB-SURVEY-000001`) and add Android targets
  `aarch64-linux-android` + `x86_64-linux-android`; point the linker at NDK `28.2.13676358`.
- **python-for-android**: `pip install python-for-android`; cache its home (e.g.
  `~/.local/share/python-for-android` / `P4A_ROOT`). Build only `arm64-v8a` + `x86_64` per
  `FOTLAB-NATIVE-000003` R6.

## Optional robustness improvement (unrelated to the above)

The `$ANDROID_HOME` cache key currently uses
`hashFiles('gradle/libs.versions.toml', '**/*.gradle.kts')` (`gradle.yaml:118`). Because the key is
tied to Gradle source files, any change to those files invalidates the cached SDK/NDK and forces a
re-download. Consider pinning the key to the already-fixed SDK/NDK versions (e.g.
`platforms;android-36` / `ndk;28.2.13676358`) so the SDK/NDK cache is stable and not needlessly
invalidated.

## Change History

- 2026-09-09 — Initial audit recorded. Confirmed SDK/NDK/Gradle are installed and cached in `gradle.yaml`; Rust NDK and python-for-android are absent (deferred per `rules/ACTION.md` Native Job Scope, "never invented earlier"). Filed as `ACTION-PREPIN-000001`; entry added to `rules/REVIEW/index.md`.
