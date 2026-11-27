# External module study — NikonCameraControl: USB PTP, Wi-Fi PTP/IP and photo enumeration

- ID: FOTLAB-SURVEY-000004
- Status: Draft
- Priority: P2
- Created: 2026-11-27
- Owner: —
- Related: [`FOTLAB-SURVEY-000002`](FOTLAB-SURVEY-000002.md) (Picture Transfer Protocol overview), [`FOTLAB-SURVEY-000003`](FOTLAB-SURVEY-000003.md) (PTP/IP transport and PIMA 15740 core details), [`FOTLAB-FEATUR-000001`](../../DESIGN/detail/FOTLAB-FEATUR-000001.md) (camera PTP/IP feature), [`FOTLAB-DATABS-000004`](../../DESIGN/detail/FOTLAB-DATABS-000004.md) (transfer queue object identity)
- Source: tracked submodule `external/NikonCameraControl` at commit `ad6f481221d86261784a16c2497275a0c6728f32`. All line references below are relative to that commit.
- Cross-check: PIMA 15740:2000 clause text (§9.4.1.4, §10.4.7, §5.5.2) for the normative parent/association wording; [`FOTLAB-SURVEY-000003`](FOTLAB-SURVEY-000003.md) for the recorded PTP/IP transport details and the CIPA DC-X005 framing semantics; the `GetObjectHandles` parent constants in libgphoto2 (`camlibs/ptp2/ptp.h`) and the Android MTP API contract (`MtpDevice.getObjectHandles`) for independent confirmation of the same direction; `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/` for the current implementation in this repository.

> **Terminology — which "PTP"?** PTP in this repository is **Picture Transfer Protocol (ISO/PIMA 15740)**, the camera file-transfer protocol under `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/`. It is not IEEE 1588 Precision Time Protocol.

## Background & Goal

`external/NikonCameraControl` is a working camera-control application with a reworked Android native transport layer. It is useful as an implementation reference because it exercises both bearers used by this repository's camera feature:

- **USB PTP** (`UsbPtpPlugin.java` + `PtpUsbSession` in `ptpip.js`), and
- **Wi-Fi / PTP-IP** (`TcpSocketPlugin.java` + `PtpIpSession` in `ptpip.js`).

It also contains a complete file-listing path (`camera.getImages()`) that has been used on a Nikon Z30 and reports 292 enumerated items. Of particular interest is its use of `0xFFFFFFFF` and `0` for the `GetObjectHandles` parent/association argument, and how that relates to the PIMA §10.4.7 wording verified in `FOTLAB-SURVEY-000003`.

This document records what the module actually does, separates the maintained mobile path from retained desktop implementations, and maps the findings back to our camera stack. It is reference-only; no code change is proposed here.

## Requirement

This document records:

- the module's layering and the three connection modes (`usb`, `wifi`, `sta`);
- the USB Host transport: permission, endpoint selection, bulk exchange, container parsing, stale-response handling and session recovery;
- the PTP/IP transport: dual TCP channels, Wi-Fi network binding, packet framing, init handshake, command/response/data phases, heartbeat and disconnect handling;
- the photo enumeration call chain, storage-ID candidate construction, object-info parsing, filters and the flat UI grid;
- the request-side and metadata-side meaning of parent/association handles, checked against the normative PIMA §10.4.7 wording and against the conventions used by the module, libgphoto2 and the Android MTP API;
- silent-empty-list paths and implementation caveats that should not be copied into this repository;
- the mapping to `feature/camera` and the open questions that require real-hardware verification.

## Source scope and authority

| Area | Source in `external/NikonCameraControl` | Authority / limit |
| --- | --- | --- |
| Native USB transport | `app/android/app/src/main/java/com/nikon/camera/control/UsbPtpPlugin.java` | Maintained mobile path; comment at lines 38-44 records device-specific workarounds. |
| Native TCP transport | `app/android/app/src/main/java/com/nikon/camera/control/TcpSocketPlugin.java` | Maintained mobile path; lines 26-30 state the two-channel model. |
| JS session layer | `app/src/ptpip.js` | Maintained mobile path; both `PtpIpSession` (283-569) and `PtpUsbSession` (602-865). |
| Connection / file API | `app/src/api.js` | Maintained mobile path; `connectCamera` at 549-581, `getImages` at 1074-1122. |
| UI | `app/src/screens/ConnectScreen.jsx`, `CameraPhotosScreen.jsx` | Maintained mobile UI; flat photo grid, not a folder browser. |
| Retained desktop backends | `app/server.cjs`, `app/main-process/main.cjs` | Kept for compatibility; different PTP/IP framing, not the mobile behaviour. Do not merge their numeric assumptions with the mobile path. |
| Project notes | `HANDOFF.md`, `docs/RELEASE_NOTES_v1.12.3.md` | Author's own verification notes; useful for intent and real-device claims, not a protocol specification. |

The module is an implementation, not a normative standard. Where its behaviour disagrees with PIMA 15740 §10.4.7 or CIPA DC-X005, the disagreement is recorded rather than silently resolved.

## Architecture map

| Layer | File | Responsibility |
| --- | --- | --- |
| UI | `app/src/screens/ConnectScreen.jsx:15-67` | IP / port / mode entry, status and diagnostics. |
| UI | `app/src/screens/CameraPhotosScreen.jsx:44-81,204-229` | Photo grid, `all/photo/video` filter, thumbnail loading. |
| API facade | `app/src/api.js:480-1293` | Unified `camera` object: connect, capture, live view, properties, files. |
| Session | `app/src/ptpip.js:283-569` | `PtpIpSession`: PTP/IP handshake, command queue, data phase, heartbeat. |
| Session | `app/src/ptpip.js:602-865` | `PtpUsbSession`: 12-byte USB containers, response matching, session recovery. |
| Native bridge | `TcpSocketPlugin.java:39-289` | Two named TCP channels (`command`, `event`). |
| Native bridge | `UsbPtpPlugin.java:58-943` | USB Host permission, interface claim, bulk exchange, state events. |
| Native registration | `MainActivity.java:7-15` | Registers `TcpSocketPlugin`, `UsbPtpPlugin`, `CameraUiPlugin`, `AppUpdaterPlugin`. |
| Permissions | `AndroidManifest.xml:40-49` | Network permissions; `android.hardware.usb.host` declared optional, requested at runtime. |

Connection dispatch is in `api.js:549-581`: `mode` defaults to `wifi`, port defaults to `15740`; native mobile routes `usb` to `connectUsbDirect`, `sta` to `connectWifiDirect` with a `device`/`pc` profile, and `wifi` to `connectWifiDirect` with a default host `192.168.1.1`.

Session state lives in module-level variables at `api.js:29-47`; auto-reconnect uses backoff `[1000, 2000, 4000, 8000, 12000] ms`, at most five attempts (`api.js:111-148`). USB explicitly does **not** auto-reconnect; the user is asked to replug (`api.js:113-116`).

## USB PTP implementation

### Device discovery and permission

- `UsbPtpPlugin.java:62` fixes `NIKON_VID = 0x04B0`. `connect()` iterates `UsbManager.getDeviceList()` and selects the first matching VID (`112-137`); no device emits `usb_device_missing` and rejects (`139-151`).
- `listDevices()` reports `vendor`, `product`, `name`, `isNikon` and `hasPermission` (`87-109`).
- Permission is requested dynamically at `180-216`: Android 12+ adds `PendingIntent.FLAG_MUTABLE`; Android 13+ uses `Context.RECEIVER_EXPORTED` in `registerSafeReceiver()` (`218-224`). Denial emits `usb_permission_denied` (`196-203`).
- Attach/detach broadcasts emit `usb_devices` and `state=device_changed` (`165-178`); the JS layer also polls every 1200 ms as a fallback (`api.js:1242-1255`).

### Interface and endpoint selection

- `selectInterface()` (`UsbPtpPlugin.java:881-900`) prefers `USB_CLASS_STILL_IMAGE` / subclass 1 / protocol 1 with a bulk in+out pair, then a vendor-specific class with a bulk pair, then interface 0. `hasBulkPair()` requires both directions to be `USB_ENDPOINT_XFER_BULK` (`902-912`).
- `openConnection()` (`253-299`) opens the device, calls `claimInterface(chosen, true)`, falls back to `claimInterface(chosen, false)` once (`268-272`), discovers bulk IN/OUT endpoints (`279-283`), clears endpoint halt (`289-290`, implementation at `914-930`) and prepares the request objects.
- **Only bulk endpoints are used.** No interrupt endpoint is discovered or consumed. The control transfer is limited to `CLEAR_FEATURE` on the two bulk endpoints.

### Bulk exchange and serialization

- `prepareRequests()` (`305-325`) prefers `UsbRequest.initialize()` on both endpoints; on failure it sets `useRequestMode = false` and falls back to `bulkTransfer`. The header comment (`38-44`) records that some Xiaomi/Redmi phones return `-1` from `bulkTransfer` on camera PTP endpoints.
- `request()` (`436-486`) submits one complete transaction to a single-thread executor and synchronizes on `ioLock`, so write + optional data-out + read are never overlapped on the same interface (`431-435`). Default timeout is 8000 ms with a 500 ms floor (`440-443`).
- `writeAll()` (`492-534`) keeps a direct `ByteBuffer` strong reference (`pendingOutBuffer`, `503-508`) and waits for the OUT request to complete before continuing (`524-530`). `awaitOutCompletion()` (`537-560`) ignores non-OUT completion events; `cancelPendingOut()` (`562-567`) cancels and drains.
- `armInRequest()` (`573-580`) documents that an IN request should be queued **before** writing the command because some cameras drop the reply if no IN transfer is pending. The actual `requestModeExchange()` does the opposite: it calls `writeAll(command)`, optional `writeAll(extra)`, then `armInRequest()` (`690-696`). This is an internal inconsistency in the external implementation; it is recorded as a caveat, not adopted here.
- `drainInput()` (`626-667`) discards stale IN data with an idle timeout (default 250 ms, floor 50 ms, hard cap 4000 ms) before the session handshake. `resetUsb()` (`341-369`) releases and re-claims the interface to clear a stale responder-side session.
- `startReader()` (`371-393`) polls `bulkTransfer(inEp, ..., 3000)` and emits `state=disconnected` on a negative read. In request mode the JS-facing `onData`/`onState` are no-ops (`ptpip.js:224-225`).

### 12-byte container and response matching

`PtpUsbSession.buildUsbContainer()` (`ptpip.js:616-624`) writes `[u32 length][u16 type][u16 code][u32 transactionId][payload]`, with type `1=Command`, `2=Data`, `3=Response`, `4=Event`. `nextContainer()` validates `12 <= length <= 64 MiB` (`633-649`). `parseUsbContainers()` (`259-281`) re-aligns byte-by-byte when stale padding produces an invalid length or type.

Native side `hasResponseFor()` (`UsbPtpPlugin.java:673-684`) walks containers by length and treats `type == 3` with a matching (or wildcard) transaction ID as completion. `legacyExchange()` (`738-764`) uses the same 12-byte threshold and `type == 3` rule. `readU16LE/readU32LE` are at `791-800`.

In the JS session, `nextResponse()` skips in-band event containers (`ptpip.js:660`) and consumes Data containers before the Response (`661-670`). The request-mode path prefers a Response whose `transactionId` equals the current one to avoid late responses poisoning a new transaction (`699-708`). **USB events are currently discarded, not dispatched.**

### Session recovery ladder

`PtpUsbSession.open()` (`ptpip.js:737-829`) uses the following escalation:

1. `OpenSession(0x1002, [1])` with 6 s timeout (`752-761`, `780`).
2. On `0x2019` / `0x201E`, send `CloseSession(0x1003)`, wait 300 ms, retry (`763-778`).
3. On continued failure, send `DeviceReset(0x1010)`, wait 400 ms, retry with 8 s timeout (`782-793`).
4. On continued failure, call `resetUsb()` to re-claim the interface, drain again, retry (`795-811`).
5. As a diagnostic, send `GetDeviceInfo(0x1001)` to distinguish "camera silent" from "session not open" (`813-823`).
6. On success set `sessionId = 1` and `opened = true` (`825-826`).

### Nikon vendor constraint on USB

`prepareForControl()` defaults to `applicationMode = false` on USB (`ptpip.js:831-849`). `api.js:674-679` passes that default explicitly and documents why: on a Nikon Z30 the `ChangeApplicationMode(0x9435)` request answers over PTP/IP but **times out on USB PTP**, leaving an unfinished OUT transfer that corrupts later reads. The same method defaults to `applicationMode = true` on PTP/IP (`ptpip.js:511-529`) and is disabled there only for the `sta` + `device` profile (`api.js:626-630`).

## Wi-Fi / PTP-IP implementation

### Two TCP channels and Wi-Fi binding

`TcpSocketPlugin.java:26-38` states the model: `command` carries operations/responses, `event` carries asynchronous events. Channel names are created in `ptpip.js:162-163`; the transport exposes `connectEvent`, `writeEvent`, `onEventData` and `onEventState` (`164-174`).

`connect()` (`TcpSocketPlugin.java:60-110`) opens a socket, binds it to a Wi-Fi `Network` before connecting (`81`), connects with 8000 ms timeout, then sets `setReuseAddress(true)`, `setTcpNoDelay(true)` and `setKeepAlive(true)` (`83-85`). `bindSocketToCameraNetwork()` (`203-241`) enumerates networks with `TRANSPORT_WIFI`, prefers one whose IPv4 prefix contains the camera host (`networkHandlesHost()`, `243-260`), and otherwise uses any Wi-Fi network as a fallback. This avoids Android routing camera-hotspot traffic over cellular when the camera AP has no Internet route.

`startReader()` (`149-178`) forwards every TCP chunk as a base64 `data` event tagged with its channel; an unexpected EOF emits `state=disconnected` (`169-174`). `write()` serializes writes per channel (`112-136`), and `disconnect()` with no channel closes all channels (`138-147`).

There is no discovery scan in the mobile app: the user enters the camera IP (`ConnectScreen.jsx:170-175`). The retained desktop backend has a `192.168.1.1-5` / `192.168.0.1-5` probe on port 15740 (`server.cjs:789-851`), but its frame format is different and is covered separately below.

### Packet framing and types

`sendPacket()` (`ptpip.js:310-317`) writes `[u32 length LE][u32 type LE][payload]`. `nextPacket()` (`319-327`) accepts `8 <= length <= 128 MiB` and requires exact reads via `ByteBuffer.readExact()` (`ptpip.js:100-113`). `awaitPacket()` (`329-339`) uses `Promise.race` for timeouts; the timeout rejects the caller but does not cancel the underlying socket read.

Observed packet types in `ptpip.js:411-486`:

| Type | Meaning | Evidence |
| --- | --- | --- |
| 1 | Init Command Request | `461` |
| 2 | Init Command Ack | `468` |
| 3 | Init Event Request | `477` |
| 4 | Init Event Ack | `480` |
| 5 | Init Failed (first 4 payload bytes = reason) | `464-467` |
| 6 | Command Request | `411` |
| 7 | Command Response | `428-446` |
| 8 | Event (ignored on command channel) | `420` |
| 9 | Start Data | `421`, `351-355` |
| 10 | Data | `422-425`, `363-366` |
| 12 | End Data | `422-425`, `361-369` |
| 13 | Ping | `484` |
| 14 | Pong | `485-486` |

Type 11 is unused by this implementation. This type set is consistent with `PtpIpType` in this repository (`feature/camera/protocol/PtpContainer.kt:24-39`).

### Init handshake

`PtpIpSession.open()` (`ptpip.js:454-505`) orders the handshake as **command Init → event Init → Ping/Pong → GetDeviceInfo → OpenSession**:

1. `buildInitCommand()` (`374-384`) builds `[16B GUID][UTF-16LE name][2B NUL][2B minor=0][2B major=1]`. The GUID is random and persisted in `localStorage` under `nini_ptp_client_guid` (`237-249`); `clientName` comes from the profile (`api.js:592-594`: `Nikon PC`, `Nikon` or `Nini PTP`).
2. Wait up to 12 s for type 2; type 5 reports the 4-byte reason; type 2 payload is parsed by `parseInitResponse()` (`386-396`) into `sessionId` (offset 0) and responder name (offset 20, UTF-16LE).
3. Connect the event channel, send type 3 with only the 4-byte `sessionId`, expect type 4 within 6 s (`473-480`).
4. Ping/Pong on the event channel with a 1.5 s timeout; failure is non-fatal (`482-489`).
5. `GetDeviceInfo(0x1001)` with 5 s timeout is non-fatal (`491-495`); `OpenSession(0x1002, [1])` with 10 s timeout accepts `0x2001` or `0x201E` (`497-503`).
6. Start a 30 s heartbeat (`535-548`) that sends `GetDeviceInfo(0x1001)` with a 4 s timeout and an in-flight guard. This is a PTP command heartbeat, **not** a periodic PTP/IP Ping. TCP keep-alive is also enabled at `TcpSocketPlugin.java:85`.

`close()` (`558-568`) stops the heartbeat, marks the close intentional, sends `CloseSession(0x1003)` with a 1.5 s timeout, unsubscribes listeners and closes both channels. `openSession()` wires transport `disconnected`/`error` to `onLost` (`582-591`), which drives the API-level reconnect state machine.

### Command, response and data phase

`buildCommand()` (`ptpip.js:341-349`) writes `[u32 dataPhase][u16 opcode][u32 tx][u32 params...]`; `dataPhase = 1` means no data-out, `2` means data-out follows. `_command()` (`408-449`) writes a type-6 packet, optionally writes data-out packets, then loops over at most 64 packets:

- type 8 / 9 are skipped (`420-421`);
- type 10 / 12 payloads whose first u32 equals the current transaction are concatenated after dropping that u32 (`422-425`);
- only type 7 with a matching transaction is accepted as the response (`428-437`);
- the returned `payload` is the concatenated data phase (`443`), while response parameters are returned separately (`436-437`).

There is no symbol named `DataPhaseInfo` in the module. The equivalent declared length is in the StartData payload: `buildDataOutPackets()` (`351-372`) writes `[u32 tx][u32 totalDataLength]` in type 9, then uses type 12 alone for payloads shorter than 128 bytes, or type 10 followed by type 12 for larger payloads. The inbound path does **not** validate the declared total length; it simply concatenates up to 64 packets. This is a robustness gap, not a behaviour to copy.

### Retained desktop backend is a different protocol variant

`app/server.cjs:157-183` builds a 12-byte big-endian IP header plus a 30-byte little-endian PTP command body and marks every command as packet type 1; `wifiConnect()` (`196-270`) uses a single TCP connection with empty type-1/type-3 packets as a handshake. `app/main-process/main.cjs:65-105` follows the same legacy format. These paths are not interchangeable with `PtpIpSession` and must not be used as evidence for mobile PTP/IP frame values.

## Photo enumeration

### Main mobile call chain

The maintained path is:

```text
CameraPhotosScreen.jsx:72-75  useEffect → load()
  api.js:1075              camera.getImages()
    api.js:1078              GetStorageIDs (0x1004)
    api.js:1082-1095          parse count + build storage-ID candidates
    api.js:1100-1109          for each candidate → enumerateImageObjects()
      api.js:363-414            walk(0xFFFFFFFF, 0)
        api.js:346-357            GetObjectHandles(storageId, 0, association)
        api.js:338-344            GetObjectInfo(0x1008) per handle
        api.js:275-288            directory / image filters
        api.js:398-401            recurse into associations, collect images
        api.js:407-412            sort by sequenceNumber desc, handle desc
    api.js:1110-1119          map to {handle, fileName, size, width, height, formatCode, thumbSize, sequenceNumber}
```

`parseObjectInfo()` (`api.js:239-258`) reads `storageId` (0), `formatCode` (4), size (8), thumbnail fields (12-22), image dimensions (26/30), **`parentObject` (38)**, `associationType` (42), `sequenceNumber` (48) and the PTP string filename (52).

`readObjectHandles()` (`api.js:346-357`) sends `[storageId, 0, value]` with a 12 s timeout. If `association === 0xFFFFFFFF`, it tries `[0xFFFFFFFF, 0]`; otherwise it sends only the requested association. It stops on the first `0x2001`, even if the data phase is empty. `enumerateImageObjects()` (`363-414`) skips handles `0` and `0xFFFFFFFF`, de-duplicates handles, limits recursion depth to 4, and skips objects whose `GetObjectInfo` fails (`390`, `397`).

Directory detection is `formatCode === 0x3001 || associationType === 0x0001 || 0x0002` (`275-279`). Image detection is an extension regex (`JPE?G|NEF|NRW|TIF?F|PNG|HEIC|HEIF`) with a fallback of positive width/height/size for vendor format codes (`281-288`). The UI filter is only `all/photo/video` by extension (`CameraPhotosScreen.jsx:10-18,77-81`); there is no folder navigation or breadcrumb.

### Storage-ID candidates and the missing GetStorageInfo

`getImages()` explicitly treats `GetStorageIDs` payload as `UINT32 count` followed by IDs (`api.js:1082-1087`). It then builds candidates by adding `id + 1`, a byte-swapped ID, `swapBytes(id) + 1`, and the aggregate sentinel `0xFFFFFFFF`, de-duplicating and dropping zero (`1088-1095`). The release notes give the motivation: Nikon slot IDs `0x00010001 / 0x00020001` were not being recognised, and a Z30 then enumerated 292 photos/videos (`docs/RELEASE_NOTES_v1.12.3.md:20-24`).

`GetStorageInfo (0x1005)` is absent: it is not in the `OC` table (`api.js:50-70`) nor in `cameraAdapters.js:3-16`. The module therefore cannot inspect filesystem type, access type or free space; it guesses storage IDs instead.

### Retained desktop enumeration

`app/server.cjs:540-554` and `app/main-process/main.cjs:506-527` send `GetStorageIDs`, then a single `GetObjectHandles(sid, 0, 0xFFFFFFFF)`, then map response **parameters** to fake `DSC_${h}.NEF` entries; both use `catch { result = [] }`. Because `GetStorageIDs`/`GetObjectHandles` results live in the data phase rather than response parameters (see `FOTLAB-SURVEY-000003` §8), these backends can only see a truncated set of words and have no `0` fallback or recursive walk. They are not a reliable enumeration reference.

### Silent-empty and caveat list

These paths make "device has no photos" indistinguishable from "enumeration failed":

1. `api.js:1096`: `if (!storageIds.length) return []`; effectively unreachable because `0xFFFFFFFF` is always appended (`1094`).
2. `api.js:352-354`: a `0x2001` response with an empty data phase returns `[]`; `walk()` treats it as a successful empty folder (`371-386`).
3. `api.js:390,397`: `GetObjectInfo` failures silently skip the handle.
4. `api.js:1102-1108`: a per-storage failure is only written to `console.log`.
5. `api.js:353` passes `parseU32List(resp.payload)` directly to the handle loop, whereas `GetStorageIDs` strips the leading count (`1084-1087`). A conforming `GetObjectHandles` data phase starts with `UINT32 count`, so the count word is treated as a handle. It is usually removed later by `GetObjectInfo` failure, but it can produce an invalid extra request or a false match. Do not copy this parsing.
6. `CameraPhotosScreen.jsx:62-66` clears the list on error and shows `loadError`; `GalleryScreen.jsx:17-18` swallows errors entirely.

## Parent / association handle direction

The normative PIMA 15740:2000 §10.4.7 text for `GetObjectHandles` parameter 3 reads (clause text, parameter 3):

> The third parameter is optional, and may be used to request only a list of the handles of objects that belong to a particular association. If the third parameter is a valid ObjectHandle for an Association, this operation should return only a list of ObjectHandles of objects that are direct children of the Association ... If a list of only those ObjectHandles corresponding to objects in the "root" of a store is desired, this parameter may be set to 0xFFFFFFFF. ... If the third parameter is unused, this operation returns ObjectHandles aggregated across the entire device (modified by the second parameter), and the third parameter should be set to 0x00000000.

PIMA §9.4.1.4 Scenario 4 states the same thing operationally: `GetObjectHandles` with `StorageID of selected store`, `0x00000000`, `0xFFFFFFFF` returns the "ObjectHandle Array for objects in root of selected store".

The PIMA §5.5.2 ObjectInfo dataset defines the metadata side separately: `ParentObject` is field 12, and "if the object is in the 'root' of the hierarchical store, then this value should be set to 0x00000000".

Putting those three statements together, the **request-side** and **metadata-side** conventions are deliberately different, and that is the source of most of the confusion in this area:

| Context | `0x00000000` | `0xFFFFFFFF` | Valid association handle |
| --- | --- | --- | --- |
| PIMA §10.4.7 request parameter 3 | parameter unused; handles aggregated across the entire device | objects in the **root** of the store | direct children of that association |
| PIMA §5.5.2 `ObjectInfo.ParentObject` metadata | the object sits in the store root | not used as a metadata sentinel | the actual parent association handle |
| libgphoto2 `PTP_GOH_ALL_ASSOCS` / `PTP_GOH_ROOT_PARENT` | `0x00000000` | `0xffffffff` | — |
| Android MTP API (`MtpDevice.getObjectHandles`) | "zero for all objects" | "-1 for the storage root" | — |
| NikonCameraControl request-side `GetObjectHandles` parameter 3 | aggregate fallback (second attempt) | the **primary** value used for every listing, root or not | — |
| This repository's constants | `PTP_PARENT_ALL` | `PTP_PARENT_ROOT` | folder handle |

Consequences:

- **PIMA and NikonCameraControl agree on direction.** `0xFFFFFFFF` is the store root and `0x00000000` is the device-wide aggregate in both. The module's release note ("recursively read the memory card with `GetObjectHandles(StorageID, 0, 0xFFFFFFFF)`") and the module's constant-free use of `0xFFFFFFFF` as the listing entry point are consistent with PIMA §10.4.7, not opposed to it.
- What differs is **how the two are used together**. The module always lists with `0xFFFFFFFF` and, on a non-`0x2001` answer, retries with `0` (`api.js:346-357`). Because `0xFFFFFFFF` means "root", the module then walks the association tree explicitly (`api.js:368-404`, depth limit 4, `0x3001`/association-type detection at `275-279`). The `0` retry is a whole-device aggregate, which is why it is a fallback and not the normal path.
- The retained desktop backends (`server.cjs:548-549`, `main.cjs:518-519`) send a single `GetObjectHandles(sid, 0, 0xFFFFFFFF)` and treat the answer as the complete photo list. Under PIMA that query is only the **store root**, so those backends are inconsistent with the standard as well as with the mobile path; they also read results from response parameters instead of the data phase (see the enumeration section).
- The module never consumes `ObjectInfo.ParentObject` for navigation; its tree is built from the requested association handle alone (`api.js:398-399`), and there is no consistency check between the requested parent and the returned metadata.
- In this repository, `objects()` starts with `PTP_PARENT_ROOT = 0xFFFFFFFF` and only falls back to `PTP_PARENT_ALL = 0` when the first query returns no handles (`PtpFileOperations.kt:135-142`). That matches PIMA §10.4.7 exactly, and it matches the module's primary value. The difference from the module is that the module recurses into associations to reach nested photos, while this repository asks for one level at a time and lets the UI navigate (`CameraScreen.kt:170,182,235`).
- Because the two conventions coincide in this area, the earlier internal note that read `{-1, 0, 0}` as "root" was the mislabelled one: with `parent = 0` that request is the device-wide aggregate, not the root. `FOTLAB-SURVEY-000003:455` records the same conflict and is left open for real-hardware confirmation of the *aggregate* semantics, but the `0xFFFFFFFF` = root direction is now confirmed by the normative text.

## Relevance to this repository

| Topic | Repository location | What this study adds |
| --- | --- | --- |
| USB events | `feature/camera/transport/UsbPtpTransport.kt` | Confirms the 12-byte container layout and `offset 0` event parsing; shows an alternative implementation that skips in-band events entirely, useful when an interrupt endpoint is unavailable. |
| USB bulk transport | `feature/camera/transport/UsbPtpTransport.kt` | Records `UsbRequest`-first with `bulkTransfer` fallback and single-thread transaction serialization; `armInRequest` comment/implementation mismatch is a caveat. |
| PTP/IP framing | `feature/camera/protocol/PtpContainer.kt`, `transport/IpPtpTransport.kt` | Independent implementation agrees on 8-byte packet header, packet types 1-10/12-14, StartData/Data/EndData and transaction-ID matching on responses. |
| Init handshake | `feature/camera/transport/IpHandshake.kt` | Shows a persisted per-client GUID and a variable-length friendly name; our implementation uses profile-specific fixed GUIDs and `CameraClient`. Both approaches exist in the wild. |
| Data phase | `feature/camera/transport/IpPtpTransport.kt` | Confirms data-in must be collected from command-channel DATA/END_DATA packets; the module's lack of total-length validation should not be copied. |
| Enumeration | `feature/camera/operation/PtpFileOperations.kt` | Shows storage-ID candidate expansion (id+1, byte swap, aggregate) and recursive association walking as an alternative to our flat listing + fallbacks. |
| Parent sentinel | `feature/camera/model/CameraModels.kt`, `PtpFileOperations.kt` | Confirms the same direction as PIMA §10.4.7: `0xFFFFFFFF` = store root, `0x00000000` = device-wide aggregate, and the request sentinels are distinct from the `ObjectInfo.ParentObject` metadata field whose root is `0x00000000`. |
| Object info | `feature/camera/operation/ObjectInfoDecoder.kt` | Confirms `ParentObject` at dataset offset 38; the module parses but never consumes it, matching the observation that metadata parent is not required for a flat grid. |
| Diagnostics | `feature/camera/` | The external module logs storage IDs, associations, response codes, handle lists and ObjectInfo previews. Our camera feature currently has none; this is the concrete shape of logging to add if enumeration remains empty. |

## Candidate direction (deferred, not approved): use the Android platform MTP API

Recorded here as an open direction only. No requirement is changed by this section and no implementation is approved.

Why it is worth considering:

- The platform ships a public `android.mtp` API. `android.mtp.MtpConstants` carries the standard format / object-format / storage constants, and `android.mtp.MtpDevice` implements the USB PTP host side: device discovery, session open/close, `getStorageIds`, `getStorageInfo`, `getObjectHandles`, `getObjectInfo`, `getDeviceInfo`.
- `MtpDevice.getObjectHandles` documents the same parent convention confirmed above: parent `0` means "all objects", parent `-1` means "the root of the storage". Using the platform API would remove hand-written format constants and a hand-written container/array decoder from `feature/camera`.
- `android.mtp.MtpObjectInfo` exposes the decoded ObjectInfo dataset, which would replace the manual `ObjectInfoDecoder` offset arithmetic.
- The platform API also removes the USB permission dance, interface-claiming and bulk-transfer details currently implemented in `transport/UsbPtpTransport.kt`.

Open risks and unanswered points:

- `android.mtp.MtpDevice` is USB-only in the public API. Whether the platform offers any supported PTP/IP (MTP over IP) entry point for third-party apps is **not yet researched**; this must be checked before assuming PTP/IP must remain hand-written.
- `MtpDevice` also does USB-specific things this repository does not need (it is a UsbDevice-bound host); adopting it partially constrains the transport abstraction that currently covers USB and PTP/IP behind one interface.
- Moving to `MtpDevice` changes where the "empty enumeration" failure surface lives, which interacts with the open question about adding structured logging.
- The platform API covers only the object model and USB transport. The PTP/IP extension — init handshake, packet framing, data phase — would remain our own code even in the most favourable outcome.

Status: research not started; no decision recorded. Tracked in the Open Questions section below.

## Constraints

- This is a survey item. It records external implementation facts and does not approve a camera feature change.
- `external/` modules are fixed constraints; this document does not propose edits to `external/NikonCameraControl`.
- The retained desktop backends are deliberately separated from the maintained mobile path. Their packet framing and enumeration parsing are not evidence for mobile PTP/IP behaviour.
- Real-device claims in the module notes (for example, 292 items on a Nikon Z30) are the module author's observations, not independently reproduced here.
- No numeric value was inferred from a diagram or from a field whose value is not present in the code.
- PTP here always means Picture Transfer Protocol, never IEEE 1588 Precision Time Protocol.

## Acceptance Criteria

- USB and PTP/IP implementations are described with their native/JS file boundaries and line-level evidence.
- Photo enumeration is traced from UI load to `GetObjectInfo`, including filters, sorting and silent-empty paths.
- The `0` / `0xFFFFFFFF` parent mapping is tabulated for the module, the `ObjectInfo` metadata field, PIMA §10.4.7 clause text, libgphoto2 and the Android MTP API, and this repository.
- Implementation caveats that should not be copied are listed explicitly.
- The document maps findings to existing `feature/camera` files without changing a requirement.
- `rules/STRUCT/index.md` has exactly one new row for this item, and the next-sequence comment is advanced.

## Impacted Modules

- `rules/STRUCT/detail/FOTLAB-SURVEY-000004.md` — this document.
- `rules/STRUCT/index.md` — item table and next-sequence comment.
- Reference-only: `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/` (no code changes in this item); `external/NikonCameraControl` (no changes).

## Open Questions

- Does the target camera implement the store root exactly as PIMA §10.4.7 describes it, i.e. `0xFFFFFFFF` returning only the root level? A captured request/response pair for both sentinel values would settle the aggregate semantics too.
- Should this repository adopt the module's recursive association walk, or keep one-level-at-a-time listing with UI navigation?
- Should this repository adopt storage-ID candidate expansion (`id + 1`, byte swap, aggregate) from `api.js:1088-1095`, or keep its current aggregate fallback?
- Should the camera UI keep a file-manager style folder tree, or add a flat "all objects" view as `CameraPhotosScreen.jsx` does?
- Should the persisted random GUID approach replace the current profile-specific fixed GUIDs?
- Should `ObjectInfo.ParentObject` be consumed for breadcrumbs or consistency checks, given that the external module does not use it?
- Should structured logging of storage IDs, response codes and handle counts be added to `feature/camera` to make "empty result" and "failed enumeration" distinguishable?
- Should `feature/camera` adopt the Android platform MTP API (`android.mtp.MtpConstants`, `android.mtp.MtpDevice`, `android.mtp.MtpObjectInfo`) for constants and for the USB PTP host stack, keeping only the PTP/IP extension hand-written? Sub-questions: (a) does the platform expose a supported PTP/IP entry point for third-party apps at all, or is PTP/IP necessarily our own code; (b) can `MtpDevice` be adopted without breaking the single transport abstraction shared by USB and PTP/IP; (c) which of our custom constants and manual decoders (`ObjectInfoDecoder`, handle-array decoding, format constants) become redundant; (d) how does the migration interact with the outstanding "empty enumeration" diagnostics question. Research not started; nothing implemented.

## Change History

- 2026-11-27 — Created from the tracked `external/NikonCameraControl` module at commit `ad6f481`. Records the USB PTP and Wi-Fi PTP/IP transports, the maintained photo-enumeration path, the retained desktop backends, implementation caveats, and the `GetObjectHandles` parent-sentinel direction checked against the PIMA 15740:2000 clause text, libgphoto2 constants and the Android MTP API. Documentation only; no requirement changed.
- 2026-11-27 — Added the deferred "use the Android platform MTP API" candidate direction and its matching open question (constants, USB host stack, PTP/IP availability, migration impact). Research and implementation not started; no requirement changed.
