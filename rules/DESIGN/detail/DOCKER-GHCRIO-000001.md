# Docker-Android Emulator Migration & ghcr.io Image Strategy

- ID: DOCKER-GHCRIO-000001
- Status: Implemented
- Priority: P1
- Created: 2026-10-13
- Owner: —
- Related: `ACTION-PREPIN-000002` (pre-implementation review of the docker-android swap), `FOTLAB-RELEAS-*` (CI behaviour)

## Background & Goal

The smoke-test stage of CI previously cached a self-built **Ubuntu + Android emulator + AVD** stack
through GitHub Actions cache. The cache key `ubuntu-emulator-<api>-<target>-<arch>` held the AVD
image, a quick-boot snapshot, system-images, the emulator binary and build-tools — roughly
**4–6 GB per key**, the dominant consumer of the 10 GiB Actions cache quota.

Goal: replace that self-built cache with a **prebuilt emulator container image** pulled per job.
Pulling (~2.5 GB compressed) occupies runner local disk, **not** the Actions cache quota, so the
quota pressure disappears. The image chosen was `halimqarroum/docker-android:api-33`.

This document records the full simplification journey: the migration, every pitfall hit and how it
was resolved, the AI's judgments, and the manual operations / explicit decisions made by the user.

## Decision / Approach (final state)

1. **Drop the self-built Ubuntu+emulator+AVD cache.** Each smoke shard starts a
   `docker-android` container (`--privileged --device /dev/kvm`), `adb connect`s, polls boot, and
   runs `connectedDebugAndroidTest` directly. No AVD, no cached emulator.
2. **Image source evolved in three phases:**
   - Phase A — pull the upstream **Docker Hub** prebuilt `halimqarroum/docker-android:api-33`.
   - Phase B — fork the source to `github.com/fot-lab/docker-android`, add it as a git submodule
     under `external/docker-android` (branch `main`, shallow), build the image in CI from our fork
     and **publish to `ghcr.io/fot-lab/docker-android` (private)**.
   - Phase C — flip the ghcr package to **public** and **remove all token passthrough** (security).
     Smoke jobs pull the public image anonymously.
3. **Test API level downgraded 36 → 33** (the highest prebuilt tag upstream ships; no api-34/35/36
   image exists, building one would re-introduce build cost). This was a deliberate, human-signed
   downgrade of test coverage, not a silent change.

## Constraints

- **KVM passthrough is mandatory.** Without `/dev/kvm` the container's qemu falls back to TCG and is
  unusably slow; the runner must expose KVM.
- **Image must boot headless** with `-gpu swiftshader_indirect -no-window -no-boot-anim`, matching
  the previous emulator options.
- **Composite GitHub Actions cannot read the `secrets` context** — doing so fails workflow
  compilation (`startup_failure`). Any credential must be passed in via `inputs`.
- **A reusable workflow may only request permissions its caller also grants.** A caller whose
  effective token lacks a scope cannot escalate a callee that asks for it.
- **ghcr.io requires an authenticated pull even for public packages** — but a *public* package can
  be pulled with an anonymous/any authenticated token, whereas a *private* package requires a token
  with `packages: read`. This drove the Phase B→C reversal.
- **Org package visibility cannot be flipped via the REST API** from the CI bot token (HTTP 404) or
  from the local token (no `read:packages` scope) — it is a GitHub UI-only operation.

## The Migration Process

**Phase A — Docker Hub image (`halimqarroum/docker-android:api-33`)** — replaced the workflow,
kept no fallback. Shipped in `2fa6242` (migration body) and four follow-up fixes.

**Phase B — Fork + submodule + self-built ghcr image (private).**
- `3a446de` add `external/docker-android` submodule (relative url `../docker-android.git`, branch
  `main`, shallow clone).
- `2fd0271` check out the submodule before the image build step.
- `13689d8` decouple the image build from CI and publish to `ghcr.io/fot-lab/docker-android`.
- `7ee8c01` attempt to authenticate smoke jobs to ghcr — **this commit was broken** (see Pitfalls #6).
- `66ae9b1` rework auth as an input passthrough — fixed the `secrets` problem but still failed
  compilation (see Pitfalls #7).
- `7eba231` grant `packages: read` to the caller — finally compiled.

**Phase C — Security cleanup (public image, no token).** Per user instruction after the org package
was flipped public: removed the `ghcr-token` input, the `docker login` step, the `packages: read`
permissions, and corrected the stale comments. Applied 2026-10-13 (see Change History).

## Pitfalls (what broke, root cause, fix)

| # | Symptom | Root cause | Fix | Commit / Run |
| --- | --- | --- | --- | --- |
| 1 | Shard step aborts ~93 s into a 450 s boot window | Boot poll `st="$(adb … \| tr -d '\r')"` under `set -euo pipefail`; adb fails while emulator is down → pipe non-zero → `set -e` kills the step on first iteration | Guard with `{ adb … \|\| true; }`; widen window; assert `adb` present | `297a092` |
| 2 | `Emulator did not finish booting`; `docker logs` empty | Container main process died immediately — only `--device /dev/kvm` given, missing `--privileged` (official compose uses both) | Add `--privileged` + console port; enrich failure diagnostics (`docker ps -a`, `docker inspect` State) | `0cb0eef` |
| 3 | Boot reaches `ANDROID_BOOTING` then `ANDROID_STOPPED`; `unknown option: -partition-size=8192` | README's default `EXTRA_FLAGS` is stale; the api-33 image's bundled emulator (36.5.11.0) rejects `-partition-size` | Drop `-partition-size`; keep only `-no-metrics -no-audio` | `55e885c` |
| 4a | 42 MP Sony ARW test process crash (OOM) | `MEMORY` lowered from image default 8192 → 4096; full-frame as-shot demosaic of a 42 MP RAW does not fit | `MEMORY=8192` | `bbd5feb` |
| 4b | `upload-artifact` fails, masking the real failure | `ANDROID_SERIAL=127.0.0.1:5555` yields `127.0.0.1:5555_profile.pb` — colon is an illegal NTFS filename | Sanitize `:`→`_` in result filenames before upload | `bbd5feb` |
| 5 | Many Build runs: `error from registry: unauthorized` pulling `ghcr.io/fot-lab/docker-android:api-33` | Package published **private** (Phase B design); smoke job pulled anonymously | Auth passthrough (Phase B) → later made public (Phase C) | `7ee8c01`+`66ae9b1`+`7eba231` |
| 6 | Build `startup_failure` ("workflow file issue") | `7ee8c01` put `echo "${{ secrets.GITHUB_TOKEN }}" \| docker login` **inside a composite action** — composite actions cannot read `secrets` | Move credential into an `inputs.ghcr-token` passed by the caller | `66ae9b1` |
| 7 | Build still `startup_failure` after #6 | Callee `smoke_emulator.yaml` requested `permissions: packages: read`, but caller `build.yaml` only had `contents: read` → effective `packages: none`; GitHub rejects the escalation at compile time | Add job-level `packages: read` to the `emulator-smoke` job (mirrors the `github-release` job) | `7eba231` |
| 8 | Cannot make the ghcr package public via API | Org package-visibility REST API blocked for both CI bot (404) and local token (no `read:packages` scope) | **User flipped it in the GitHub UI** (Packages → docker-android → Settings → Change visibility) | manual |

Green milestone: run `38138502801` (Phase A) — `test_app_smoke` + `test_image_loader` both pass,
`gh cache list` shows `ubuntu-emulator-*` gone (only `rawdb-samples-v1` 246 MB). Run `38270007834`
(Phase B/C) — `startup_failure` gone, `test_app_smoke` green (proves private pull worked); a
**new, unrelated** loader-shard test failure appeared (see Open Questions).

## AI's Judgments

- **Chose api-33 over building api-36.** No prebuilt tag exists above api-33; building one would
  re-introduce the very build cost the migration aimed to remove. Flagged the resulting coverage
  downgrade as requiring human sign-off (gate discipline), not a silent decision.
- **Diagnosed every failure from the real CI log, never from guesswork** — e.g. `docker inspect`
  returning `exit=0 oom=false` distinguished "parameter parse failure" (Pitfall #3) from a crash.
- **Correctly identified** that composite actions cannot read `secrets` (Pitfall #6) and that a
  reusable workflow's requested permissions must be ≤ its caller's (Pitfall #7), after `actionlint`
  gave false positives (it mis-parsed the composite action as a workflow and flagged the real
  `ubuntu-26.04` runner as unknown). The real YAML error text was obtained by fetching the run page.
- **Wrong assumption, later reversed:** initially assumed ghcr always requires a token passthrough,
  and built the `ghcr-token` input mechanism. Once the user made the package public, the AI agreed
  the passthrough was unnecessary and a security liability, and removed it (Phase C).
- **Correctly refused to treat the loader OOM as a config issue after the memory fix** — once
  `MEMORY=8192` made the Sony ARW pass, the remaining loader-shard failure on `38270007834` was
  reclassified as a product/native test issue, not a CI-config issue (Open Questions).

## User's Manual Operations (actions the AI could not perform)

- Created the fork `github.com/fot-lab/docker-android.git` and instructed the submodule to be added
  via `git submodule` (relative path `../docker-android.git`, branch `main`, shallow clone + init).
- **Flipped the ghcr org package visibility to public in the GitHub UI** — impossible via API from
  either the CI bot or the local token (scope/404).
- Manually released / bumped versions that triggered the builds under test.

## User's Decisions (explicit sign-offs)

1. **Allow the test API level to drop 36 → 33** (coverage downgrade accepted).
2. **Replace the original workflow directly; do not retain the old VM/emulator path.**
3. **Mirror the image to `ghcr.io/fot-lab/docker-android`** and build it from our own fork source
   (avoid Docker Hub anonymous rate limits of 100 pulls / 6 h / IP).
4. **Make the package public and drop the token passthrough** for security, once the org allowed
   public packages — smoke jobs pull the public image anonymously, no credential in the composite
   action.

## Acceptance Criteria / Outcome

- **Cache quota win achieved:** `ubuntu-emulator-*` cache entries eliminated; only
  `rawdb-samples-v1` (246 MB) remains. ~4–6 GB/key no longer consumed.
- **End-to-end smoke works on the container:** `test_app_smoke` (7 tests) and `test_image_loader`
  (20 RAW tests) pass on `docker-android:api-33`.
- **No credential leakage:** composite action no longer touches `secrets` or any token; a public
  image is pulled anonymously. `grep` confirms no `ghcr-token` / `docker login ghcr` /
  `packages: read` remain in `.github` (only `packages: write` for the push side).
- **Build compiles:** no `startup_failure` from permission/secret mismatches.

## Impacted Modules

- `.github/workflows/smoke_emulator.yaml` (emulator-image input, four shard calls, permissions)
- `.github/actions/emulator_smoke_shard/action.yml` (container start/stop, login step, inputs)
- `.github/workflows/build.yaml` (caller permissions for the smoke reusable workflow)
- `.github/workflows/docker-android.yaml` (fork build + ghcr publish)
- `external/docker-android` (git submodule, branch `main`)
- `rules/ACTION.md` (Emulator Smoke Test table, CI cache layers, Emulator flags)
- `rules/REVIEW/detail/ACTION-PREPIN-000002.md` (pre-implementation review, status Implemented)

## Open Questions

- `test_image_develop` / `test_image_alchemy` shards remain **OFF** by default (heavy; not assumed
  viable under `MEMORY=8192`).
- Loader-shard failure on run `38270007834` is **unresolved and unrelated to image access** — it is
  a product/native RAW-routing test failure, to be diagnosed separately from the CI migration.
- Whether the api-33 coverage downgrade is acceptable long-term, or a custom api-34/35/36 build is
  eventually warranted (re-introduces build cost — out of scope of this decision).

## Change History

- **2026-10-11** — Phase A implemented: replaced the Ubuntu+emulator+AVD cache with
  `halimqarroum/docker-android:api-33`. Fixed Pitfalls #1–#4 across runs
  `38120163276`→`38138502801` (commits `2fa6242`, `297a092`, `0cb0eef`, `55e885c`, `bbd5feb`).
  Recorded pre-implementation review as `ACTION-PREPIN-000002`.
- **2026-10-12** — Phase B: forked source, added `external/docker-android` submodule (`3a446de`),
  built and published a **private** ghcr image (`2fd0271`, `13689d8`). Attempted ghcr auth
  (`7ee8c01` broken by `secrets` in composite), reworked as input passthrough (`66ae9b1`), then
  fixed the caller-permission `startup_failure` (`7eba231`). Run `38270007834` compiled and pulled
  the private image; a new loader-shard test failure surfaced (separate issue).
- **2026-10-13** — Phase C security cleanup: after the user flipped the ghcr package **public** in
  the GitHub UI, removed the `ghcr-token` input, the `docker login` step and `packages: read`
  permissions; corrected stale comments. Encoding rule corrected so `FOTLAB` is documented as an
  example, not a mandated project-code prefix (this item uses `DOCKER-GHCRIO`). This DESIGN doc
  created.
