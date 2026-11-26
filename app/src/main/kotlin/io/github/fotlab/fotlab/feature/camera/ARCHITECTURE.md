# Camera feature — architecture

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
| `CameraTransferService.kt` | Foreground service wrapper so a transfer survives UI teardown; delegates to the repository. |
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
- **Device events.** The session's event channel is republished as `CameraCore.events`,
  so the screen can react to a camera that changes on its own (a new capture, a deleted
  object) instead of showing a stale directory.

## Known gaps (within scope, not yet implemented)

- **Sony HTTP transport.** `SsdpProbe` does not parse the `X_ScalarWebAPI_*` /
  `ActionList_URL` headers, and no HTTP transport exists yet. The PTP Sony profile
  (`0x92xx` opcodes) is complete and still the preferred path for Sony cameras that
  support it.

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
