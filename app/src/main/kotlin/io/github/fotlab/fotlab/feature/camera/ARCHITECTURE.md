# Camera feature — architecture

> **Terminology — "PTP" in this repository:** **PTP** always means **Picture Transfer Protocol
> (ISO 15740)**, the camera file-transfer protocol implemented in this package. It does **not**
> mean Precision Time Protocol (IEEE 1588), the network clock-synchronisation standard; the two
> share only the acronym.

Scope of this iteration: device discovery, link setup, PTP session establishment
(USB bulk and PTP/IP), connection lifecycle (link-failure propagation, hot-plug
handling, event-driven refresh), object browsing by folder, thumbnail/full-object
transfer, remote delete and object upload. Live view, remote capture and
multi-device session modes are intentionally out of scope; no code in this package
depends on them.

## Layering

```
CameraScreen + CameraChrome (UI: discovery / connect / browse / transfer)
   |
   v   (assembled by navigation/camera/CameraGraph, outside this package)
CameraCore (process facade: state, active session, delegate calls)
   |
   +-- CameraQueueManager (transfer queue: rows in CameraDatabase, one running item)
   |        |
   |        +-- CameraQueueRepository (queue reads and writes)
   |        |        |
   |        |        +-- CameraFileQueueDao (the camera_file_queue table)
   |        |        +-- CameraDatabase (the camera feature's own Room database)
   |
   +-- CameraFileRepository (list / download / delete / upload use cases)
   |        |
   |        +-- operation/PtpFileOperations (PTP-level file primitives)
   |        |        |
   |        |        +-- protocol/PtpSession (transaction ids, session guard, op dispatch)
   |        |        +-- protocol/PtpTransport (carrier-neutral contract)
   |        |                 |
   |        |                 +-- profile/CameraProfile (per-brand opcode and chunk profile)
   |        |
   |        +-- model/ (device-independent value types)
   |
   +-- discovery/ (USB enumeration, SSDP, mDNS -> CameraDevice)
   |
   +-- transport/ (PtpTransport: USB bulk or PTP/IP, framed command/data/response)
```

Package dependency direction is one-way and acyclic:
`model` <- `protocol` <- {`profile`, `operation`} <- {`discovery`, `transport`} <-
`CameraFileRepository` <- `CameraCore`. The transport contract lives in `protocol`
so the session does not depend on a carrier; the session takes a session id rather
than a whole profile for the same reason. Nothing in this feature imports the shell's `ui` or
`navigation` packages: the shell assembles this screen through
`navigation/camera/CameraGraph.kt`, in line with `FOTLAB-STRUCT-000001` R3, and no
package imports back into one that depends on it (`FOTLAB-STRUCT-000002` R2).

## Files and responsibilities

| File | Responsibility |
| --- | --- |
| `CameraScreen.kt` | Third top-level screen: module-level `Scaffold` inside a modal drawer; device list, manual address, storage/object list with folder navigation and media filter, selection, list/grid presentation, download dispatch and delete confirmation. |
| `CameraChrome.kt` | Module-owned fun bar and drawer (`FOTLAB-UIXDES-000002`): leading cluster = drawer toggle, list/grid switch and refresh of the open folder; trailing cluster = the selection trio in a drop-up overflow plus two selection-dependent slots (upload / new folder with an empty selection, download / delete with a non-empty one); in-place close row in the drawer. |
| `CameraCore.kt` | Process-wide facade, `StateFlow` of devices and connection state, the device event stream, USB hot-plug watching, the active transport/session, and the link-failure policy. |
| `CameraFileRepository.kt` | API-facing file operations: storage listing, object listing by parent with filtering, thumbnail/full download, delete, upload from a content URI, folder creation. Owns progress and the output side — a SAF tree the user picked, or the public downloads area. |
| `CameraTransferService.kt` | Foreground service that **runs the queue**: it reads the pending rows back and executes them one at a time, so a transfer survives UI teardown and an interrupted transfer stays queued (`FOTLAB-DATABS-000004`). |
| `CameraDatabase.kt` | The camera feature's own Room database (`camera`), holding the transfer queue; the library's `fs_node` schema is untouched. |
| `CameraFileQueue.kt` | The `camera_file_queue` entity: one row per queued transfer, with text-valued timestamp columns. |
| `CameraFileQueueDao.kt` | The queue's own DAO, in a file of its own: reads return `Flow`, writes are `suspend`. |
| `CameraQueueRepository.kt` | Lower-layer access to the queue: maps the entity to the feature's value types and groups the rows into batches. |
| `CameraQueueManager.kt` | The queue's near-stateless machine: everything observable is a row, only the running transfer is in memory. |
| `CameraQueueList.kt` | The queue view: one card per batch, newest first, expandable to the individual file names. |
| `CameraViewMode.kt` | The drawer's two views — the camera's storages and the transfer queue. |
| `CameraSourceName.kt` | The name a source document will carry on the device — an upload is queued before it runs, so the name has to be known when the row is written. |
| `CameraSettings.kt` | The two user-tunable discovery values and the single source of their defaults. |
| `CameraPreference.kt` | Durable `DataStore` for the camera module's own settings (`camera_prefs`), including the URI of the last file a download wrote. |
| `model/CameraModels.kt` | `PtpStorage`, `PtpObject`, `PtpObjectInfo`, `PtpMediaFilter`, `TransferProgress`, `CameraEvent`, format helpers, and the link/response exceptions. |
| `protocol/PtpOpcode.kt` | Standard PTP operation/response/event codes and vendor code constants used by profiles. |
| `protocol/PtpContainer.kt` | USB and PTP/IP container writers/parsers, little-endian primitives. |
| `protocol/PtpSession.kt` | Serializes transactions, guards `OpenSession`, dispatches operations with the profile's session id, and normalises transport failures. |
| `protocol/PtpTransport.kt` | Carrier-neutral transport contract and transaction result type. |
| `profile/CameraProfile.kt` | Value type describing opcode overrides, chunk sizes, session id and handshake variant. |
| `profile/CameraProfileResolver.kt` | Maps manufacturer/model/USB VID to a profile. |
| `model/UsbVendorIds.kt` | USB vendor id table used by discovery and profile fallback. |
| `discovery/CameraDevice.kt` | USB and IP device descriptors. |
| `discovery/UsbCameraDiscovery.kt` | Attached USB camera enumeration, permission request, and hot-plug receiver. |
| `discovery/SsdpProbe.kt` | SSDP M-SEARCH probe and HTTP location parsing. |
| `discovery/MdnsProbe.kt` | `_ptp._tcp.local.` PTR/SRV/TXT/A query and response parsing. |
| `discovery/IpCameraDiscovery.kt` | Runs both IP probes, de-duplicates results, supports manual host entry. |
| `discovery/CameraDiscovery.kt` | Merges USB and IP results into one list. |
| `transport/UsbPtpTransport.kt` | USB interface claim, endpoint discovery, bulk IN/OUT, interrupt events. |
| `transport/IpHandshake.kt` | PTP/IP command and event connection setup, init frames, known GUID values. |
| `transport/IpPtpTransport.kt` | PTP/IP command/data/event packet framing and transaction handling. |
| `operation/ObjectInfoDecoder.kt` | Parses the standard PTP object-info dataset (including the 64-bit size extension). |
| `operation/PtpFileOperations.kt` | High-level PTP primitives for listing by folder, thumbnails, range download, delete, upload and folder creation. |

## Data flow

### Discovery to connection

1. `CameraCore.discover()` launches `CameraDiscovery`, which merges `UsbCameraDiscovery`
   with `IpCameraDiscovery` (SSDP + mDNS running concurrently). The mDNS probe is
   given the user's configured window (`CameraSettings.mdnsTimeoutSeconds`, 10 s on a
   fresh install); the SSDP probe keeps its own shorter socket timeouts.
2. `CameraCore.connect(device)` asks `UsbCameraDiscovery` for permission when the
   descriptor is USB, resolves a `CameraProfile`, builds a `PtpTransport`, and creates a
   `PtpSession`.
3. `PtpSession.open()` reads `GetDeviceInfo` (0x1001) first, then sends
   `OpenSession` (0x1002). Every later operation goes through one serialized path
   that lazily opens the session, so no command can overtake `OpenSession`.
4. `CameraCore.disconnect()` sends `CloseSession` (0x1003) and releases the link.

### The transfer queue

A download no longer starts a one-shot batch: it **appends rows** to the
`camera_file_queue` table, and the transfer service runs whatever is queued. An upload is
queued the same way, with the source document on the phone side and the folder it is to
land in on the camera side.

1. The user picks files and a destination folder; the screen builds one row per file
   (direction, camera-side URI, phone-side URI) and checks the triple against the rows
   that already exist.
2. A transfer whose identity is already queued raises a dialog: overwrite (the old row
   goes, a fresh one is appended) or leave everything alone.
3. The rows are appended with a shared `timestamp_batchadd`, so they render as one card.
4. `CameraTransferService` reads the pending rows and executes them one at a time. A row
   that completes is stamped with the finishing time; a row the device refuses is stamped
   `failed`; a row whose **link** dies is left unfinished, so the next camera picks it up
   again.

The queue outlives the camera, the screen and the process, which is exactly what the
one-shot batch could not do.

### The link decides the queue's fate

`CameraCore` publishes a **retry state** beside the connection state, and the queue
follows it:

- a recovery that is running leaves the rows waiting;
- a recovery that comes back lets the queue run on;
- a recovery that is **given up** stamps every still-queued row `failed` — the rows would
  otherwise wait forever for a camera that is not coming back. An explicit disconnect is
  not a failure: those rows keep waiting for the next camera.

A row the user paused on purpose is never touched by that rule.

### Carrying a batch to another camera

A PTP handle is a device-local number, so a row cannot simply be replayed against another
camera. The row therefore remembers the storage, the folder **and the file name** beside
the handle, and the runner re-resolves the file when the handle no longer names it: the
stored handle is checked first, and the file is looked up by name in the remembered folder
when it does not match. A file that is not on the connected camera leaves the row `failed`
with the reason stated, rather than transferring the wrong object.

Resuming a failed row is therefore how the user moves a batch to the camera that has the
files.

### The queue's own controls

The queue view supports a multi-selection: a long press on a card takes the batch, a long
press on a row takes the row. The fun bar then offers cancel, retry and delete for the
selection, and its overflow menu carries pause and resume. Pausing writes `paused` into
the row, resuming and retrying clear the stamp so the row waits again — retrying also
starts the runner when a camera is connected.

### File operations

- List: `GetStorageIDs` (0x1004) -> `GetObjectHandles` (0x1007) ->
  `GetObjectInfo` (0x1008) in bounded batches -> format filter -> `PtpObject`.
- Browse: the listing is scoped to a parent folder. The root is parent `0`, and entering
  a folder asks for that folder's children; a device that ignores the parent argument
  answers with its whole tree, so an empty scoped listing falls back to the flat listing
  rather than showing an empty screen.
- Thumbnail: `GetThumb` (0x100A), with the profile's thumbnail opcode when the
  device uses a vendor command.
- Download: `GetPartialObject` (0x101B) in profile-sized chunks; each chunk is
  appended to a `MediaStore.Downloads` entry (or a public-downloads `File` below
  API 29), with `is_pending` cleared only after the last chunk. The standard command
  carries a 32-bit byte offset, so an object above 4 GiB is refused with an explicit
  message rather than silently truncated.
- Delete: `DeleteObject` (0x100B), or the vendor opcode in the active profile;
  success is reported by the response code and, when it arrives, by an
  `ObjectRemoved` (0x4002) event.
- Upload: `SendObjectInfo` (0x100C) with storage/parent/name/size, then
  `SendObject` (0x100D) with the file bytes; the new handle comes from the
  `SendObjectInfo` response. The parent is the folder currently open, so a file lands
  where the user is looking.
- Folder: `SendObjectInfo` (0x100C) with the association format, then an empty `SendObject`
  (0x100D) that closes it. Support is device-dependent — a camera that refuses answers with a
  response code, which surfaces as an error rather than a silent no-op.

## Transport details

USB uses the 12-byte bulk container: `u32 length | u16 type | u16 code | u32
transactionId | params`. The control transfer `0x21/0x66` is issued during link
setup; bulk IN reads are assembled by the declared length.

PTP/IP uses the 8-byte packet header `u32 length | u32 type`, with typed payloads:
init command request/ack (1/2), init event request/ack (3/4), command request (6),
command response (7), event (8), start data (9), data (10), cancel (11), end data
(12). Command and data phases share the command TCP connection; the event
connection is opened by the responder. The init request carries a 16-byte GUID
and a UTF-16LE initiator name; the ack carries a session id, a 16-byte GUID and
the responder name. Known GUID values are kept in `IpHandshake` because some
devices only answer to a fixed value.


## Brand profiles

One profile value object carries every device-specific knob used by this
iteration: session id, opcode overrides for storage/list/info/partial/thumb/delete,
the large-object length repair opcode, chunk bounds, and the init GUID variant.
`CameraProfileResolver` picks a profile from the device's reported
manufacturer/model, with USB VID as fallback. Inheritance is expressed as
`copy(...)` from a base profile instead of class hierarchies, so a model family
differs only in the fields it actually changes.

## Deliberate limits

- Vendor list datasets that are not shaped as `u32 count + u32 handles` are not
  decoded here; the profile still selects the vendor opcode, and a decode failure
  is surfaced as an operation error rather than guessed data.
- Nikon's Bluetooth pairing and property/event UI mapping are separate links and
  are not part of this package.
- No live view, capture, or multi-camera session code is present.
- Folder creation is attempted through the standard PTP path only; a device that answers
  `OperationNotSupported` gets no vendor fallback.

## Connection lifecycle

- **One session at a time.** `connect()` closes any previous session first, so the
  camera never has two half-open links competing for it.
- **A dead link ends the session.** The transports raise `CameraLinkException` when the
  wire breaks (socket closed, USB endpoint gone, truncated packet), and `PtpSession`
  passes it through unchanged. `CameraCore.runFileOperation` catches it, releases the
  session and publishes `CameraConnectionState.Failed`. A `PtpResponseException` is
  *not* a dead link — the device answered and refused the request — so it leaves the
  session connected and surfaces as an ordinary error.
- **The download destination is the user's choice.** A download asks for a folder in the system
  picker and writes each object as a document inside it; the URI of the last file written is
  persisted so the next picker opens in that file's folder. The system keeps only one global
  last folder, so the camera module keeps its own.
- **Hot-plug.** `CameraCore` registers a receiver for the USB attach and detach actions.
  Attaching a camera refreshes the device list; detaching the camera that is currently
  connected ends the session.
- **The queue resumes when a camera comes up.** `CameraCore.connect` checks for pending
  rows after a successful connection and starts the transfer service, so a transfer the
  previous link interrupted runs again without the user asking for it twice.
- **Device events.** The session's event channel is republished as `CameraCore.events`,
  so the screen can react to a camera that changes on its own (a new capture, a deleted
  object) instead of showing a stale directory.

## Known gaps (within scope, not yet implemented)

- **Sony's HTTP API is not wired into the pipeline.** `SsdpProbe` does parse the
  `X_ScalarWebAPI_*` headers and records the `ActionList_URL`, `SonyHttpTransport` speaks
  the JSON-RPC dialect and `SonyHttpSession` covers open, storages, listing, download,
  thumbnail and events. What is missing is the layer that turns those into the feature's
  handle-and-storage model: the browse and transfer pipeline runs on PTP handles, and the
  Sony content API addresses files by URI instead. An HTTP-only camera therefore cannot be
  connected — `CameraCore.transportFor` refuses it — and cannot be the other end of a
  queued transfer.
  Two consequences were fixed along the way: a host that answers **both** protocols is now
  discovered as PTP, because preferring the unwired HTTP path made a camera that works
  over PTP unconnectable; and the refusal now says what is actually missing.
- **A queued transfer cannot tell two cameras apart.** The file is found again by name in
  the remembered folder, which carries a batch to another camera — but a camera whose
  folders or names differ simply does not have the file, and the device exposes no stable
  serial to disambiguate (`FOTLAB-DATABS-000004`, Open Question 1).
- **Interrupted transfers restart from the beginning.** The half-written file is deleted,
  as it always was; the byte offset is not persisted.

## Module settings

The camera drawer's **Discovery** section carries the module's own settings
(`FOTLAB-UIXDES-000002` R5 — module-private content, no application-level entries):

| Setting | Default | Range | Effect |
| --- | --- | --- | --- |
| mDNS timeout | 10 s | 1–60 s | The window the mDNS probe is given to collect answers. Lower it to make a scan return sooner, at the cost of fewer chances for a slow camera to answer. |
| Keepalive interval | 20 s | 5–600 s | How often a connected network camera is probed with `GetDeviceInfo`. A live link picks a new value up immediately; USB links are unaffected (they are physical). |

Both live in `CameraSettings` as the single source of their defaults, and are persisted
in the module's own `DataStore` (`camera_prefs`). A value that was never stored is
reported as "unset", so a fresh install runs on the defaults without the defaults ever
being written to disk.

## Copy and localisation

All user-visible copy — the screen, the fun bar/drawer and transfer notifications —
is resolved through the `camera` block of `app/src/main/res/values/strings.xml`,
mirrored key-for-key in `app/src/main/res/values-zh/strings.xml`
(`FOTLAB-UIXDES-000003` R2/R5/R6). The nav-bar label belongs to the shell's `app`
block as `app_nav_camera_label` (Camera / 相机); shared accessibility text uses the
`common` block. Device names, file names, protocol identifiers and runtime error
details are data, not translatable copy. No camera string is placed in another
feature's block.
