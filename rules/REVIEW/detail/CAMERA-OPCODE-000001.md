# PTP opcode transport affinity — vendor extensions are USB-only; GetObjectHandles parent semantics must follow PIMA 15740 §10.4.7

- ID: CAMERA-OPCODE-000001
- Status: Implemented
- Priority: P1
- Created: 2026-11-28
- Owner: —
- Related: [`CAMERA-QUEUED-000001`](CAMERA-QUEUED-000001.md), [`CAMERA-CONNCT-000001`](CAMERA-CONNCT-000001.md)
- Survey references:
  - [`FOTLAB-SURVEY-000002`](../../STRUCT/detail/FOTLAB-SURVEY-000002.md) — Picture Transfer Protocol (ISO 15740) overview, including the 16-bit opcode split (`0x1xxx` standard vs `0x9xxx` vendor) and its historical origin in PIMA 15470 / ISO 15740:2000.
  - [`FOTLAB-SURVEY-000003`](../../STRUCT/detail/FOTLAB-SURVEY-000003.md) — PIMA 15740:2000 core protocol model with clause references. The `parentObject` semantics here (§10.4.7) are normative for both USB and PTP/IP — the transport layer is independent of operation semantics.
  - [`FOTLAB-SURVEY-000004`](../../STRUCT/detail/FOTLAB-SURVEY-000004.md) — NikonCameraControl reference implementation: uses `0xFFFFFFFF` as the GetObjectHandles parent entry point (`api.js:346-357`), only root requests may fall back to aggregate (`api.js:347`), and walks association handles recursively (`api.js:398-401`).

## Background & Goal

Canon and Nikon cameras connected over **PTP/IP** were able to enumerate storage (standard PTP worked) but:

1. `GetObjectHandles` returned valid-looking lists but the app showed 10-digit-integer file names (handle.toString() fallback), 0 B size, no thumbnails, no file-type suffix.
2. Downloads started (camera card LED confirmed real transfer, progress counters matched expected size) but files never landed on the phone.

Sony USB worked with vendor 0x92xx opcodes — the same profile family was reused wholesale for IP. The app ran, listed "something", looked plausible, but almost nothing was correct.

The goal was to get a **standard PTP / IP-only connection** to list real files with correct names, sizes, thumbnails, and land downloaded bytes on disk.

## Finding — four layered failures rooted in opcode transport affinity

All four trace back to the same root cause: **vendor extension opcodes are not transport-agnostic**. The profile resolver tagged each vendor with their extension family (Sony 0x92xx, Canon 0x91xx, Nikon 0x90C7/0x9431) and the operation layer skipped the standard opcode path entirely when a profile match was detected. PTP/IP connections silently got empty or malformed replies from commands the camera never advertised.

Per `FOTLAB-SURVEY-000002`, the opcode split is itself normative:

| Range | Meaning |
|---|---|
| `0x1000`–`0x1FFF` | Standard PTP operations (ISO 15740) |
| `0x9000`–`0xFFFF` | Vendor extensions |

What the standard does **not** guarantee is that a vendor extension that works over USB also works over PTP/IP. Sony 0x92xx and Canon 0x91xx families have been confirmed **USB-only** on the devices tested; the same is suspected of Nikon's 0x9431/0x90C7 variants.

### Failure A — vendor opcode path used first, standard path skipped

`storages()`, `objectHandles()`, `objectInfo()`, `thumbnail()`, `download()` all had:

```kotlin
val skipSony = profile.objectHandlesOpcode == PtpOpcode.Sony.CONTENT_LIST
val result = executeWithBusyRetry(
    PtpOpcode.GET_OBJECT_HANDLES,       // standard 0x1007
    profile.objectHandlesOpcode,         // Sony 0x923C
    ...
    skipStandard = skipSony,             // skip → go straight to vendor
)
```

On PTP/IP the camera has never heard of 0x923C. It returns `OPERATION_NOT_SUPPORTED (0x2005)` or an empty response, which the code silently swallowed or turned into a null `PtpObjectInfo` → the caller fell back to `handle.toString()` for the file name.

### Failure B — GetObjectHandles `parentObject` default was the aggregate sentinel

`objectHandles(storageId, format, parent = PTP_PARENT_ALL)` defaulted to `0x00000000`. Per **PIMA 15740 §10.4.7** (cited in `FOTLAB-SURVEY-000003` and verified against the NikonCameraControl reference in `FOTLAB-SURVEY-000004`):

| `parentObject` | Standard meaning |
|---|---|
| `0xFFFFFFFF` | Objects in the **store root** of `storageId` (one level, direct children) |
| `0x00000000` | **"Parameter unused"** — aggregate across the **entire device**, all storages, no folder structure |
| valid association handle | Direct children of that association (e.g. a DCIM folder handle) |

NikonCameraControl (`api.js:346-357`), libgphoto2, and Android's MTP API all use `0xFFFFFFFF` as the entry point. Our default gave every listing request a flat cross-device dump as baseline, then compounded it with **Failure C**.

### Failure C — aggregate fallback fired for *every* empty result, not just root

```kotlin
if (handles.isEmpty() && parent != PTP_PARENT_ALL) {
    handles = objectHandles(storageId, parent = PTP_PARENT_ALL)
}
```

Condition was "if `parent` is **not** aggregate, fall back to aggregate". So a request for a **specific DCIM folder** (a real association handle) that genuinely had no immediate children would silently widen into "dump every object on the device". The flat aggregate list then collided with the per-handle `GetObjectInfo` calls — some handles were from other storages, so their info replies were wrong or missing.

Per §10.4.7 and NikonCameraControl `api.js:347`: only a request for the **store root** (`0xFFFFFFFF`) should fall back to aggregate. A folder-specific query returning empty genuinely means that folder has no direct children — do not pretend otherwise.

### Failure D — download `objectInfo` was re-queried mid-transfer

`CameraCore.download()` built a `PtpObject` with name/format/size from `objectInfo(handle)` and passed it to `CameraFileRepository.download()`. The repository **queried `objectInfo` again** for the same handle, built the output sink from the *second* query's `format` and `sizeBytes`, and ran the download loop against those values. Two round trips later, if the camera state shifted even slightly, the sink was created with the wrong format and the loop wrote the wrong number of bytes → progress looked correct (data was transferred from the camera) but the sink received a truncated or wrongly-routed stream → file disappeared after close/finish.

## Impact / Conflict

Four concrete symptoms on Canon EOS / Nikon Z-series over PTP/IP:

| Symptom | Root failure |
|---|---|
| 10-digit-integer file names | A → empty `PtpObjectInfo.name` → fallback `handle.toString()` |
| 0 B size, no thumbnails | A → zero-value `PtpObjectInfo` fields propagated |
| All items in one flat list, wrong files mixed | B + C → aggregate fallback on every query |
| Download transfers but no file lands | D → re-read `objectInfo` mismatch between repository and orchestrator |

No test coverage existed that exercises PTP/IP against a real camera; the only real-device validation was Sony over USB, where vendor opcodes *do* work.

## Recommendation

1. **Standard-first strategy per operation.** Every `PtpFileOperations` method (storages, objectHandles, objectInfo, thumbnail, download) MUST try the ISO/PIMA 15740 opcode first. Vendor extension is only attempted on explicit `OPERATION_NOT_SUPPORTED (0x2005)` or `PARAMETER_NOT_SUPPORTED (0x2006)` error. Never skip the standard path by default.

2. **Transport-aware vendor opcode filtering.** A `ptpStdOnly` preference (default **true**) forces every path to use only standard opcodes and skip every vendor extension. When a camera family is confirmed to support its extensions on both USB *and* IP, the profile can opt out of the skip — but IP-first testing should be the rule. Sony 0x92xx family and Canon 0x91xx are confirmed USB-only on the devices tested; Nikon 0x9431 is suspected USB-only as well.

3. **Follow PIMA 15740 §10.4.7 exactly for `parentObject`.** Default `objectHandles(parent = PTP_PARENT_ROOT)` (`0xFFFFFFFF`). Only a **store-root** request may fall back to aggregate (`0x00000000`). Any other `parent` is a specific association handle — empty result means empty, do not widen scope.

4. **Do not re-query `objectInfo` mid-transfer.** If `CameraCore.download()` already resolved name/format/size and packaged it as `PtpObject`, the repository must consume that package directly. Any state-dependent retry stays inside the single object that already issued the round trip.

5. **Verification gate.** After any camera-related change, test against at least one PTP/IP device (Canon, Nikon) in addition to whatever USB device the developer has. PTP/IP compatibility is the hard constraint — it is what new buyers will hit first.

## Change History

- 2026-11-28 — Created. Findings A–D triggered a run of five commits (`a84c12c`, `9cc5e99`, `333d25a`, `c5aedec`, `b773074`) that implement recommendations 1–4. `ptpStdOnly` default true; standard-first wrapper added to all five operations; `objectHandles()` default flipped to `PTP_PARENT_ROOT`; aggregate fallback gated to store-root requests only; repository stopped re-querying `objectInfo` mid-transfer and added post-write `OpenableColumns.SIZE` verification to catch silent drop. Verified on Canon EOS and Nikon Z-series over PTP/IP; USB path unaffected because the standard-first wrapper works equally well on transports where vendor extensions *do* respond, and the wrapper's fallback catches the OPERATION_NOT_SUPPORTED reply cleanly.
- 2026-11-28 — Renamed from `CAMERA-PTOPCD-000001` to `CAMERA-OPCODE-000001`. Category shortened from `PTOPCD` to `OPCODE`. Survey reference block added, linking to the three PTP-related STRUCT surveys: `FOTLAB-SURVEY-000002` (ISO 15740 overview), `FOTLAB-SURVEY-000003` (PIMA 15740:2000 core clauses), `FOTLAB-SURVEY-000004` (NikonCameraControl reference implementation).
