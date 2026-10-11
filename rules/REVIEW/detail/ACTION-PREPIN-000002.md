# Replace self-built Ubuntu+Android emulator cache with docker-android — cut Actions-cache occupancy

- ID: ACTION-PREPIN-000002
- Status: Implemented
- Priority: P2
- Created: 2026-10-11
- Owner: —
- Related: `rules/ACTION.md` (Emulator Smoke Test, CI cache layers item 2), `ACTION-ROLLBK-000001` (architecture/coverage downgrade needs explicit human sign-off), `.github/workflows/smoke_emulator.yaml`, `.github/actions/emulator_smoke_shard/action.yml`, `.github/actions/install_sdk/action.yml`, `ACTION-PREPIN-000001` (sibling toolchain-cache audit)

## Background & Goal

The emulator smoke test (`smoke_emulator.yaml`) currently self-builds the Android runtime in CI:
`prep-emulator` creates an AVD (API 36, `google_apis`, `x86_64`), warms its quick-boot snapshot,
and caches the AVD together with the SDK packages it boots
(`$ANDROID_HOME/system-images`, `$ANDROID_HOME/emulator`, `$ANDROID_HOME/build-tools`) under the
`ubuntu-emulator-<api>-<target>-<arch>` cache key. That one entry is the dominant consumer of the
repository's 10 GiB Actions-cache budget (see `rules/ACTION.md` "CI cache layers" item 2 and the
"Quota hygiene" section — the emulator/AVD layer is ~4–6 GB per key and is re-invalidated whenever
the AVD definition or image changes).

Goal: research whether a prebuilt Android-emulator Docker image — `halimqarroum/docker-android` —
can replace the self-built Ubuntu+emulator+AVD cache, so the heavy emulator state lives in a pulled
image (occupying runner local disk, **not** the Actions-cache quota) instead of a cache entry that
competes with Gradle/Rust generations for the 10 GiB cap.

## Finding

### Current state — what the self-built cache holds and costs

`smoke_emulator.yaml` `prep-emulator` caches one entry keyed `ubuntu-emulator-<api>-<target>-<arch>`
covering:

- `~/.android/avd/*` — the AVD definition + quick-boot snapshot (several GB)
- `$ANDROID_HOME/system-images` — API 36 `google_apis` `x86_64` (~1.5–2 GB)
- `$ANDROID_HOME/emulator` (~0.6–1 GB)
- `$ANDROID_HOME/build-tools`

Plus the small `rawdb-samples-v1` RAW-corpus cache (~250 MB, immutable fixtures, kept). The
emulator layer is written by `prep-emulator` **after** AVD creation and **before** any APK install
(split `actions/cache/restore` + `actions/cache/save`, so the snapshot stays app-free). The four
parallel shard jobs (`test_app_smoke` / `test_image_loader` / `test_image_develop` /
`test_image_alchemy`) boot the cached AVD and each run one `-e class` slice of
`connectedDebugAndroidTest -PsmokeTestFilter=…`. The shard body lives in
`.github/actions/emulator_smoke_shard/action.yml` and uses `reactivecircus/android-emulator-runner`
plus an in-job KVM udev rule (`99-kvm4all.rules`, `MODE="0666"`).

### docker-android:api-33 — verified image facts

Verified against the image's `Dockerfile`, `scripts/start-emulator.sh`, `docker-compose.yml`, and
Docker Hub tags (not assumed):

| Property | Value | Implication for us |
| --- | --- | --- |
| Default API / image / ABI | `API_LEVEL=33`, `IMG_TYPE=google_apis`, `ARCHITECTURE=x86_64` | Matches `librawler_fotlab.so` (x86_64) ✓ |
| GPU | default `-gpu swiftshader_indirect` | Headless software render — identical to our current `EMULATOR_OPTIONS` |
| Launch flags | `-no-window -no-boot-anim -no-snapshot -ranchu`; emulator auto-boots on container start | Headless; cold boot each run (no snapshot reuse) |
| Auth | `SKIP_AUTH` defaults **true** → passes `-skip-adb-auth` | Host `adb connect` needs no adb-key sharing ✓ |
| Ports | 5554 console / **5555 adb** | `docker run -p 127.0.0.1:5555:5555` |
| KVM | `--device /dev/kvm`; image detects `/dev/kvm` and uses KVM accel | GitHub `ubuntu-26.04` runner exposes `/dev/kvm` |
| Tunable env | `MEMORY` (default 8192 → set 4096), `CORES` (default 4), `EXTRA_FLAGS` (default includes `-no-audio -no-metrics -partition-size=8192`) | RAM / cores / silence controllable |
| Image size | compressed **2.54 GB** / uncompressed **5.84 GB**; needs ~4 GB RAM + ~8 GB disk | Per-job pull magnitude |
| Published pull-run tags | `api-28 … api-33` (also `-playstore` / `-cuda` variants) | **No `api-34/35/36` pull-run tag** — those require a self `docker build` |

Conclusion: the public image is a drop-in for our headless x86_64 smoke run. It removes the
~4–6 GB emulator cache entry from the Actions budget and replaces it with a per-job `docker pull`
(~2.5 GB compressed, ~5.84 GB on runner disk — **outside** the Actions-cache quota). That directly
addresses the "cache storage consumption" pain.

## Impact / Conflict

1. **Coverage downgrade conflict (requires human sign-off).** The pull-run image tops out at
   **API 33**, but the current smoke target is **API 36**. Dropping to API 33 is a *test-coverage
   downgrade*, which `ACTION-ROLLBK-000001` forbids executing without explicit human confirmation.
   Keeping 34/35/36 coverage would mean self-`docker build`ing an image — partially back to the
   self-build origin we are trying to escape. This trade-off is **not** decided here; it is the
   gating question for implementation.
2. **`/data/local/tmp` behaviour differs by API.** `run_smoke_shard.sh` carries an API-36-specific
   SELinux workaround (that path is blocked on API 36). On API 33 the behaviour is different, so the
   `test_image_alchemy` LUT-push path must be re-verified rather than assumed equivalent.
3. **If implemented, `rules/ACTION.md` must be kept in sync.** The "Emulator Smoke Test" table
   (AVD API 36) and "CI cache layers" item 2 (the emulator/AVD cache entry) would change. Those
   edits are deferred until this item is approved — editing them now would pre-empt the sign-off
   gate. `ACTION-PREPIN-000001` is the sibling audit of the rest of the toolchain cache.

## Recommendation

Do **not** modify `smoke_emulator.yaml` or `rules/ACTION.md` yet. Stage the change:

1. **Experimental, non-blocking job first.** Add `smoke_emulator_docker.yaml` (gated off the
   release path, non-blocking) whose four shards boot `halimqarroum/docker-android:api-33` instead
   of a cached AVD. Keep the existing API-36 path green and untouched.
2. **Per-shard container bring-up (replace the AVD boot + emulator-runner):**
   ```bash
   docker run -d --name emu --device /dev/kvm \
     -p 127.0.0.1:5555:5555 \
     -e MEMORY=4096 -e CORES=4 -e SKIP_AUTH=true \
     -e EXTRA_FLAGS="-no-metrics -no-audio -partition-size=8192" \
     halimqarroum/docker-android:api-33

   adb connect 127.0.0.1:5555
   # poll `getprop sys.boot_completed` == 1 (image self-limits boot wait to ~300 s)
   export ANDROID_SERIAL=127.0.0.1:5555

   gradle connectedDebugAndroidTest -PsmokeTestFilter=…   # run_smoke_shard.sh unchanged
   ```
   Always `docker stop emu` in cleanup. Slim `prep-emulator` to warm **only** the `rawdb-samples-v1`
   RAW corpus (drop the AVD half). `install_sdk` keeps installing `platform-tools` + `build-tools` +
   `platforms` but no longer needs `system-images` / `emulator`.
3. **Must-spike before promoting:** KVM passthrough (`docker run --device /dev/kvm` on the runner)
   and measured boot time — without KVM it falls back to TCG and will time out; confirm the Docker
   daemon is ready on `ubuntu-26.04`; confirm host `adb` ↔ container `adbd` version compatibility
   (else use the GHCR-pinned image or `docker exec` the in-container `adb`).
4. **Production pull from GHCR.** Mirror `halimqarroum/docker-android:api-33` to
   `ghcr.io/fot-lab/docker-android:api-33` (one-time mirror) and pull with `GITHUB_TOKEN`, to avoid
   Docker Hub anonymous rate limiting (100 pulls / 6 h / IP — GitHub runners share an egress IP and
   can trip it).
5. **Decision gate (human).** After the experimental job is green and stable, the human chooses:
   (a) adopt API 33 as the primary smoke level (accepting the coverage downgrade — explicit
   sign-off under `ACTION-ROLLBK-000001`), or (b) self-build an API-36 image to retain coverage
   (re-introducing build cost). Only then edit `rules/ACTION.md` and retire/repurpose the API-36
   path.

### Storage ledger

- **Saved:** ~4–6 GB emulator cache entry from the Actions budget; also stops re-invalidation churn
  when the AVD/image changes.
- **Cost:** per-job `docker pull` ~2.5 GB compressed (~5.84 GB on runner disk, **not** in the
  Actions-cache quota).
- **Net:** Actions-cache occupancy falls materially; trade-off is per-run network pull (~30–90 s/job)
  plus ~8 GB free disk on the runner (standard runner is tight — consider a larger runner).

## Change History

- 2026-10-11 — Initial finding recorded. Researched `halimqarroum/docker-android:api-33` as a
  replacement for the self-built Ubuntu+emulator+AVD cache; verified image defaults (API 33 /
  `google_apis` / `x86_64`, `-gpu swiftshader_indirect`, `SKIP_AUTH` true, adb on 5555, `--device
  /dev/kvm`, `MEMORY`/`CORES`/`EXTRA_FLAGS` env), the ~2.54 GB compressed / 5.84 GB image size, and
  that pull-run tags stop at api-33. Flagged the API 36 → 33 coverage downgrade as requiring explicit
  human sign-off under `ACTION-ROLLBK-000001`; recommended a non-blocking experimental job first and
  a GHCR mirror for production. Filed as `ACTION-PREPIN-000002`; row appended to
  `rules/REVIEW/index.md`.
- 2026-10-11 — **Implemented (downgrade approved).** User accepted the API 36 → 33 coverage
  downgrade and directed a direct in-place replacement of `smoke_emulator.yaml` (no legacy emulator
  retained). Changes: (1) `smoke_emulator.yaml` — added `emulator-image` input (default
  `halimqarroum/docker-android:api-33`), slimmed `prep-emulator` to RAW-corpus-only (dropped the KVM
  udev rule, the AVD-cache save/restore and the `reactivecircus/android-emulator-runner` AVD build),
  and switched the four shard `with:` blocks from `api-level`/`target`/`arch` to `emulator-image`.
  (2) `emulator_smoke_shard/action.yml` — dropped the KVM udev rule, emulator-cache restore and cold
  AVD fallback; added a `Start docker-android emulator` step (`docker run -d --device /dev/kvm -p
  127.0.0.1:5555:5555 -e MEMORY=4096 -e CORES=4 -e SKIP_AUTH=true -e
  EXTRA_FLAGS="-no-metrics -no-audio -partition-size=8192"`, `adb connect` + 300 s boot wait,
  `ANDROID_SERIAL` published) and a `Stop docker-android emulator` cleanup (`if: always()`); the
  filtered `connectedDebugAndroidTest` now runs directly via `run_smoke_shard.sh` (no emulator-runner).
  (3) `run_smoke_shard.sh` — reworded the api-36 SELinux note (run-as LUT push is portable to api-33).
  (4) `rules/ACTION.md` — Emulator Smoke Test, Key Configuration, Emulator flags and CI cache layers
  updated to describe the docker-android pull (per-job, on runner disk, outside the 10 GiB budget) and
  the accepted 36 → 33 downgrade. Status set to Implemented.
