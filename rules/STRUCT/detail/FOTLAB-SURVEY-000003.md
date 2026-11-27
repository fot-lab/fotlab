# External study — PTP/IP transport and PIMA 15740:2000 core protocol details

- ID: FOTLAB-SURVEY-000003
- Status: Draft
- Priority: P2
- Created: 2026-11-27
- Owner: —
- Related: [`FOTLAB-SURVEY-000002`](FOTLAB-SURVEY-000002.md) (Picture Transfer Protocol overview), [`FOTLAB-SURVEY-000001`](FOTLAB-SURVEY-000001.md) (IEEE 1588 Precision Time Protocol — a different standard), [`FOTLAB-FEATUR-000001`](../../DESIGN/detail/FOTLAB-FEATUR-000001.md) (camera PTP/IP feature), [`FOTLAB-DATABS-000004`](../../DESIGN/detail/FOTLAB-DATABS-000004.md) (transfer queue object identity)
- Sources:
  - nullobject, “PTPIP协议中文版”, SegmentFault 思否, published 2019-08-02 — a personal Chinese study of PTP/IP 1.0.
  - PIMA 15740:2000, first edition, approved 2000-07-05 — *Photography — Electronic still picture imaging — Picture Transfer Protocol (PTP) for Digital Still Photography Devices*.
- Cross-check: CIPA DC-X005 (PTP-IP) and the repository constants in `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/protocol/` were used only to resolve numeric values not stated explicitly in the published sources.

> **Terminology — which “PTP”?** PTP in this repository is **Picture Transfer Protocol (ISO/PIMA 15740)**, the camera file-transfer protocol under `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/`. It is not IEEE 1588 Precision Time Protocol.

## Background & Goal

[`FOTLAB-SURVEY-000002`](FOTLAB-SURVEY-000002.md) records a high-level overview of Picture Transfer Protocol. This document goes one level deeper and consolidates the details of two external references:

1. **PIMA 15740:2000** is the core, transport-independent PTP standard: device roles, sessions, transactions, datasets, operations, responses, events and device properties.
2. **The Chinese PTP/IP article** is a study of the IP transport specification: the two-TCP-connection model, the init handshake, packet framing, data phases, cancellation, discovery, binding and disconnect handling.

The goal is to keep a single, reviewable place where a protocol question can be answered with enough detail to tell whether a behaviour is required by PTP core, required by PTP/IP, a vendor extension, or merely an implementation choice. This is a documentation survey; it does not change an existing requirement.

## Requirement

This document records:

- the authority and scope of each source;
- the PTP core model from PIMA 15740:2000 with clause references;
- the PTP/IP transport model and packet layouts from the Chinese article, reconciled with CIPA DC-X005 where the article leaves numeric values unstated;
- conflicts or ambiguities between the sources;
- the mapping from those details to this repository’s camera module.

## Source authority and reading limits

| Source | Authority | Use | Limits |
| --- | --- | --- | --- |
| PIMA 15740:2000 | Normative core PTP standard | Definitions, datatypes, datasets, operations, responses, events, device properties | Section numbers are cited; figure-only content is not reproduced here. |
| Chinese PTP/IP article | Secondary study of PTP-IP 1.0 | Transport model, handshake, packet types, connection management | Personal translation; some numeric packet-type and Init Fail reason values are not stated explicitly. Do not treat its wording as normative over PIMA/CIPA. |
| CIPA DC-X005 | Normative PTP-IP transport spec | Numeric packet order and clause cross-check | Used only where the two sources above were ambiguous; not re-summarised in full here. |
| Repository `PtpContainer.kt` / `PtpOpcode.kt` | Actual implementation | Numeric constants used by this app | Implementation choice, not an external authority. |

## Two-layer model: PTP core vs PTP/IP transport

PIMA 15740 defines PTP independently of any wire transport. It says a transport must be reliable, support disconnection notification, support asynchronous events and provide device discovery; it does **not** define TCP ports or packet headers. PTP/IP is one such transport: it carries the PTP operation/response/data/event model over TCP.

| Concern | PIMA 15740:2000 core | PTP/IP 1.0 transport |
| --- | --- | --- |
| Roles | Initiator opens sessions and issues operations; Responder answers and emits events | Same roles; Initiator is the TCP client, Responder is the TCP server |
| Session | Logical connection; `SessionID` is a non-zero UINT32 assigned by OpenSession | No `SessionID` on the wire; two TCP connections are correlated by `Connection Number` from Init Command Ack |
| Transaction | Request → optional data phase → response; one transaction at a time per session | Same phases, mapped to `COMMAND_REQUEST`, `START_DATA`, `DATA`, `END_DATA`, `COMMAND_RESPONSE` packets |
| Events | Event dataset: EventCode, SessionID, TransactionID, up to 3 params | `EVENT` packet: EventCode, TransactionID, up to 3 params; event connection identifies the session |
| Device discovery | Required but transport-specific | Manual address, Zeroconf, UPnP discovery, or another mechanism; default TCP port 15740 |
| Reliability | Required | TCP with Keep-Alive and Nagle disabled |

## PIMA 15740:2000 — core model

### 1. Device roles and sessions (clauses 9.1–9.2)

- A device may be Initiator-only, Responder-only, or both, but the two roles are distinct for a connection.
- The Initiator has extra responsibility for enumeration, transport aggregation, conversation flow and transport-specific session negotiation.
- A session is a logical connection over which `ObjectHandle` and `StorageID` values are persistent.
- `OpenSession` opens a session when it completes with a valid response; `CloseSession` or transport channel closure closes it.
- `GetDeviceInfo` is the only operation allowed both inside and outside a session. All other data/object/descriptor operations require an open session.
- All handles and datasets except `DeviceInfo` are invalid outside the session in which they were returned.
- Multi-session devices must keep sessions asynchronous and opaque to one another.
- `SessionID` is a device-unique non-zero UINT32 assigned by the Initiator as the first parameter of `OpenSession` (§9.2.1).

### 2. Transactions (clauses 9.3–9.3.4)

A transaction is an atomic invocation of one operation request. Within a session, transactions are synchronous and blocking; only one may run at a time. A transaction consists of:

```text
Operation Request → [Data Phase] → Operation Response
```

- The request and response phases are always present; the data phase is optional.
- A data phase is one-way for a given operation: Initiator→Responder (data-out) or Responder→Initiator (data-in), never both.
- Asynchronous operations such as `InitiateCapture` are initiated synchronously; completion is reported by events.
- If a previous async operation is still running, the Responder returns `Device_Busy` and the Initiator must retry later.

**TransactionID (§9.3.1):**

- UINT32, unique within a session, continuous from `0x00000001`.
- `OpenSession` uses `0x00000000`.
- `0xFFFFFFFF` is invalid/reserved.
- After `0xFFFFFFFE`, the device should roll over to `0x00000001`.
- TransactionID lets events refer to a specific earlier operation and lets data objects be correlated with their describing datasets.

**Operation request dataset (§9.3.2, 30 bytes):**

| Field | Bytes | Type |
| --- | --- | --- |
| OperationCode | 2 | UINT16 |
| SessionID | 4 | UINT32 |
| TransactionID | 4 | UINT32 |
| Parameter1–Parameter5 | 5 × 4 | operation-specific |

Unused parameters are zero. If a value is narrower than 32 bits it occupies the low bits with high bits zeroed.

**Response dataset (§9.3.4, 30 bytes):** `ResponseCode` (UINT16), `SessionID`, `TransactionID`, then up to five parameters. The response is status/parameters, **not** the data phase.

**Data phase (§9.3.3):** carries data too large for request/response datasets. The core standard considers object data opaque; wrapping, splitting and reassembly are transport responsibilities.

### 3. Datatypes, codes and encoding (clauses 5.1–5.4)

| Type | Size | Notes |
| --- | --- | --- |
| OperationCode / ResponseCode / EventCode / DevicePropCode / ObjectFormatCode | 2 bytes | 16-bit datacodes with type-defining nibble |
| StorageID | 4 bytes | UINT32; upper 16 = physical store, lower 16 = logical store |
| ObjectHandle | 4 bytes | UINT32; device-unique, session-persistent |
| DateTime | variable | ISO 8601-compatible Unicode string `YYYYMMDDThhmmss.s` with optional `Z`/`±hhmm` |
| DeviceInfo / StorageInfo / ObjectInfo / DevicePropDesc | variable | Datasets |
| Object | variable | Opaque object bytes |

Datacode nibbles (§5.2.1, Table 2):

| High nibble pattern | Meaning |
| --- | --- |
| `0xxx` | Standard: `1` Operation, `2` Response, `3` ObjectFormat, `4` Event, `5` DeviceProp |
| `1xxx` | Vendor-defined counterpart; bit 15 set |
| `0000`, `1000`, `011x`, `111x` | Undefined or reserved |

Handles (§5.3.2):

- 32-bit, unique within the open session and across all physical/logical stores.
- `0x00000000` and `0xFFFFFFFF` never identify a valid object; their meaning is context-specific.
- A handle implies that the device can produce an `ObjectInfo` dataset; association objects are fully described by that dataset and have no separate object bytes.
- Handles are device-local; moving an object does not preserve its handle meaningfully.

Arrays (§5.4): `Num Elements` (UINT32) followed by that many fixed-size entries. Empty array is exactly four zero bytes. Zero-based indexing.

Strings (§5.3.4): one byte character count including the terminating null, then UTF-16 characters. Maximum 255 characters. Empty string is one `0x00` byte.

### 4. Datasets (clause 5.5)

Dataset rules:

- Field order must be preserved; future versions may add fields but never delete or redefine existing ones.
- Unused integer fields default to zero; unused strings to empty string.
- Byte packing/endianness is defined by the transport specification.

**DeviceInfo (§5.5.1, Table 6):** `StandardVersion`, `VendorExtensionID`, `VendorExtensionVersion`, `VendorExtensionDesc`, `FunctionalMode`, `OperationsSupported`, `EventsSupported`, `DevicePropertiesSupported`, `CaptureFormats`, `ImageFormats`, `Manufacturer`, `Model`, `DeviceVersion`, `SerialNumber`. It is obtainable without a session; a capability change must raise `DeviceInfoChanged`.

**ObjectInfo (§5.5.2, Table 8), field order:**

1. `StorageID` (4)
2. `ObjectFormat` (2)
3. `ProtectionStatus` (2; `0` = no protection, `1` = read-only)
4. `ObjectCompressedSize` (4)
5. `ThumbFormat` (2)
6. `ThumbCompressedSize` (4)
7. `ThumbPixWidth` (4)
8. `ThumbPixHeight` (4)
9. `ImagePixWidth` (4)
10. `ImagePixHeight` (4)
11. `ImageBitDepth` (4)
12. `ParentObject` (4)
13. `AssociationType` (2)
14. `AssociationDesc` (4)
15. `SequenceNumber` (4)
16. `Filename` (variable string)
17. `CaptureDate` (DateTime string)
18. `ModificationDate` (DateTime string)
19. `Keywords` (variable string)

Important field semantics:

- Image-specific fields are zero for non-image objects or when unavailable.
- `ParentObject` must be an association handle; root objects use `0x00000000`.
- `AssociationDesc` is only meaningful when `AssociationType != 0`.
- `SequenceNumber` is zero for unordered associations; non-zero values order siblings and need only be monotonically increasing.
- `Filename` contains no path information; paths are reconstructed from `Filename` plus `ParentObject`.

**StorageInfo (§5.5.3, Table 10):** `StorageType` (UINT16), `FilesystemType` (UINT16), `AccessCapability` (UINT16), `MaxCapacity` (UINT64), `FreeSpaceInBytes` (UINT64), `FreeSpaceInImages` (UINT32), `StorageDescription` (string), `VolumeLabel` (string).

- StorageType: `0x0001` fixed ROM, `0x0002` removable ROM, `0x0003` fixed RAM, `0x0004` removable RAM.
- FilesystemType: `0x0001` generic flat, `0x0002` generic hierarchical, `0x0003` DCF.
- AccessCapability: `0x0000` read-write, `0x0001` read-only without object deletion, `0x0002` read-only with object deletion.
- Capacity/free-space fields use `0xFFFFFFFF` when unavailable/useless.

### 5. Object formats and associations (clause 6)

`ObjectFormatCode` is UINT16. Bit 15 marks vendor-defined formats; all image formats also set bit 11.

Selected standard codes from Table 14:

| Code | Meaning |
| --- | --- |
| `0x3000` | Undefined non-image object |
| `0x3001` | Association (folder) |
| `0x3004` | Text |
| `0x300A` / `0x300B` | AVI / MPEG video |
| `0x3800` | Unknown image object |
| `0x3801` | EXIF/JPEG |
| `0x3802` | TIFF/EP |
| `0x3805` | Canon CIFF |
| `0x3808` | JFIF |
| `0x380B` | PNG |
| `0x380D` / `0x380E` | TIFF / TIFF/IT |
| `0x380F` / `0x3810` | JPEG2000 baseline / extended |

Thumbnail formats are compressed JPEG (preferred) or uncompressed TIFF/EP. `GetThumb` may also work for non-still objects if the device can generate one.

Associations (§6.4) are objects with `ObjectFormat = 0x3001`. Their `AssociationType` (Table 15) may be `GenericFolder (0x0001)`, `Album (0x0002)`, `TimeSequence (0x0003)`, panoramas (`0x0004`–`0x0006`) or `AncillaryData (0x0007)`. Folders are unordered associations. The child→parent link is bottom-up: each object’s `ParentObject` names its containing association; root uses `0x00000000`. Only associations may be parents.

### 6. Persistent storage and object handles (clause 8)

- `StorageID` high 16 bits = `PhysicalStorageID`; low 16 bits = `LogicalStorageID`.
- `0x00000000` and `0xFFFFFFFF` are context-specific, not real stores.
- Object handles are globally unique across all stores on a device, valid for at least the exposing session, and never `0x00000000` or `0xFFFFFFFF`.
- A newly added object gets a unique handle and an `ObjectAdded` event; a removed object triggers `ObjectRemoved` (except when removed by an operation in the same session).
- `StorageID` and `ObjectHandle` values are only meaningful within the device/session that issued them.

### 7. Operations (clause 10)

Operation codes are 16-bit with bit 12 set and bits 13–14 clear; bit 15 marks vendor-defined codes.

| Code | Operation | Data direction / result |
| --- | --- | --- |
| `0x1000` | Undefined | — |
| `0x1001` | GetDeviceInfo | R→I `DeviceInfo`; allowed outside session |
| `0x1002` | OpenSession | parameter `SessionID`; no data |
| `0x1003` | CloseSession | no data |
| `0x1004` | GetStorageIDs | R→I `StorageIDArray` |
| `0x1005` | GetStorageInfo | R→I `StorageInfo`, parameter `StorageID` |
| `0x1006` | GetNumObjects | response parameter 1 = count |
| `0x1007` | GetObjectHandles | R→I `ObjectHandleArray` |
| `0x1008` | GetObjectInfo | R→I `ObjectInfo`, parameter `ObjectHandle` |
| `0x1009` | GetObject | R→I object bytes |
| `0x100A` | GetThumb | R→I thumbnail bytes |
| `0x100B` | DeleteObject | no data |
| `0x100C` | SendObjectInfo | I→R `ObjectInfo`; response gives storage, parent, reserved handle |
| `0x100D` | SendObject | I→R object bytes |
| `0x100E` | InitiateCapture | async; `ObjectAdded` then `CaptureComplete` |
| `0x100F` | FormatStore | parameter `StorageID` |
| `0x1010` | ResetDevice | closes sessions |
| `0x1011` | SelfTest | optional SelfTestType |
| `0x1012` | SetObjectProtection | object handle + protection status |
| `0x1013` | PowerDown | closes sessions |
| `0x1014` | GetDevicePropDesc | R→I `DevicePropDesc` |
| `0x1015` | GetDevicePropValue | R→I property value |
| `0x1016` | SetDevicePropValue | I→R property value |
| `0x1017` | ResetDevicePropValue | one property or all with `0xFFFFFFFF` |
| `0x1018` | TerminateOpenCapture | parameter = capture TransactionID |
| `0x1019` | MoveObject | handle + destination storage + parent |
| `0x101A` | CopyObject | response parameter 1 = new handle |
| `0x101B` | GetPartialObject | R→I partial bytes; response parameter 1 = actual byte count |
| `0x101C` | InitiateOpenCapture | open-ended async capture |

**GetObjectHandles (§10.4.7) parameter semantics are critical:**

- Parameter 1 `StorageID`: `0xFFFFFFFF` aggregates all stores.
- Parameter 2 `ObjectFormatCode`: `0x00000000` means the parameter is unused; `0xFFFFFFFF` means “only image objects”; a specific code filters one format. A non-zero unsupported filter may return `Specification_By_Format_Unsupported (0x2014)`.
- Parameter 3 association handle: `0x00000000` means the parameter is unused and handles are aggregated across the device (modified by parameter 2); a valid association handle returns direct children; `0xFFFFFFFF` requests objects in the store root.
- Response data is a PTP array: UINT32 count followed by that many UINT32 handles.

These meanings differ from the labels currently used in this repository’s `PTP_PARENT_ROOT` / `PTP_PARENT_ALL` constants; see “Source conflicts” below.

**Upload sequence (§10.4.12–10.4.13):** `SendObjectInfo` first sends `ObjectInfo` I→R and asks permission; response parameters give the Responder’s chosen `StorageID`, parent handle and reserved object handle. Then `SendObject` sends bytes. The Responder retains only one pending `ObjectInfo` per session; a second `SendObjectInfo` replaces it.

**Capture sequence (§10.4.14):** successful `InitiateCapture` acknowledges only initiation, not completion. Each new object raises `ObjectAdded` carrying the capture TransactionID; `CaptureComplete` ends the sequence. `StoreFull` may appear if media fills.

### 8. Responses (clause 11)

Response codes are 16-bit, bit 13 set, bit 14 clear, bit 15 vendor-defined. Anything other than `OK` indicates failure.

| Code | Name | Practical meaning |
| --- | --- | --- |
| `0x2000` | Undefined | — |
| `0x2001` | OK | success |
| `0x2002` | General Error | no better code |
| `0x2003` | Session Not Open | operation needs a session |
| `0x2004` | Invalid TransactionID | zero/invalid transaction |
| `0x2005` | Operation Not Supported | valid code but unsupported |
| `0x2006` | Parameter Not Supported | non-zero parameter unused by operation |
| `0x2007` | Incomplete Transfer | discard transferred data |
| `0x2008` | Invalid StorageID | re-request storage IDs |
| `0x2009` | Invalid ObjectHandle | re-request handles/info |
| `0x200A` | DeviceProp Not Supported | — |
| `0x200B` | Invalid ObjectFormatCode | unsupported in context |
| `0x200C` | Store Full | — |
| `0x200D` | Object WriteProtected | — |
| `0x200E` | Store Read-Only | — |
| `0x200F` | Access Denied | denied given current state |
| `0x2010` | No Thumbnail Present | — |
| `0x2011` | SelfTest Failed | — |
| `0x2012` | Partial Deletion | some objects protected/read-only |
| `0x2013` | Store Not Available | physically unavailable, not busy |
| `0x2014` | Specification By Format Unsupported | retry without format filter |
| `0x2015` | No Valid ObjectInfo | `SendObject` without `SendObjectInfo` |
| `0x2016` | Invalid Code Format | wrong datacode nibble |
| `0x2017` | Unknown Vendor Code | vendor code not understood |
| `0x2018` | Capture Already Terminated | — |
| `0x2019` | Device Busy | retry later; not store removal |
| `0x201A` | Invalid ParentObject | object is not an association |
| `0x201B` | Invalid DeviceProp Format | wrong property dataset size/format |
| `0x201C` | Invalid DeviceProp Value | value not allowed |
| `0x201D` | Invalid Parameter | expected parameter has illegal value |
| `0x201E` | Session Already Open | response parameter carries existing SessionID |
| `0x201F` | Transaction Cancelled | cancelled by opposing device |
| `0x2020` | Specification of Destination Unsupported | do not specify destination again |

### 9. Events (clause 12)

Event support is mandatory. Most events come from the Responder, but `CancelTransaction` may be sent by either side. In-band event transports must interleave events with data; out-of-band transports use a separate logical channel.

Event dataset fields: `EventCode` (UINT16), `SessionID` (UINT32; `0xFFFFFFFF` = all sessions), `TransactionID` (UINT32; `0xFFFFFFFF` = not transaction-specific), `Parameter1–3`.

| Code | Event | Parameter 1 |
| --- | --- | --- |
| `0x4000` | Undefined | — |
| `0x4001` | CancelTransaction | none; TransactionID identifies target |
| `0x4002` | ObjectAdded | new ObjectHandle |
| `0x4003` | ObjectRemoved | removed ObjectHandle |
| `0x4004` | StoreAdded | StorageID or `0x00000000` if multiple logical stores |
| `0x4005` | StoreRemoved | StorageID; physical removal uses low 16 bits `0xFFFF` |
| `0x4006` | DevicePropChanged | DevicePropCode |
| `0x4007` | ObjectInfoChanged | ObjectHandle |
| `0x4008` | DeviceInfoChanged | none |
| `0x4009` | RequestObjectTransfer | ObjectHandle to fetch |
| `0x400A` | StoreFull | StorageID |
| `0x400B` | DeviceReset | none; sessions about to close |
| `0x400C` | StorageInfoChanged | StorageID |
| `0x400D` | CaptureComplete | TransactionID of capture |
| `0x400E` | UnreportedStatus | none; initiator must resynchronise |

### 10. Device properties (clause 13)

Device properties are device attributes, not object attributes. They are optional, may be read-only or read-write, and are global across sessions. A change in one session must emit `DevicePropChanged` to other sessions; interdependent properties must each emit their own event.

`DevicePropDesc` contains: `DevicePropCode`, `DataType`, `GetSet` (`0x00` Get, `0x01` Get/Set), `FactoryDefaultValue`, `CurrentValue`, `FormFlag` (`0x00` none, `0x01` range, `0x02` enumeration), and the appropriate FORM. Range form is min/max/step; enumeration form is a UINT16 count followed by supported values.

Selected property codes from Table 26: `0x5001` BatteryLevel, `0x5002` FunctionalMode, `0x5003` ImageSize, `0x5004` CompressionSetting, `0x5005` WhiteBalance, `0x5006` RGBGain, `0x5007` FNumber, `0x5008` FocalLength, `0x5009` FocusDistance, `0x500A` FocusMode, `0x500B` ExposureMeteringMode, `0x500C` FlashMode, `0x500D` ExposureTime, `0x500E` ExposureProgramMode, `0x500F` ExposureIndex, `0x5010` ExposureBiasCompensation, `0x5011` DateTime, `0x501C` FocusMeteringMode, `0x501D` UploadURL, `0x501E` Artist, `0x501F` CopyrightInfo.

## PTP/IP 1.0 — transport details from the Chinese article

### 1. Scope and conformance language

The article describes PTP over IP for ISO 15740 / PIMA 15740:2000. It uses RFC 2119-style keywords (`MUST`, `SHOULD`, `MAY`, etc.). Its binary protocol version is `0x00010000`: high 16 bits major, low 16 bits minor. Minor versions are backward-compatible within a major version; major version changes may be incompatible.

### 2. TCP connection model

All communication uses two TCP connections between Initiator and Responder:

- **Command/Data connection** — operation requests, operation responses, data phases, PTP/IP-specific packets.
- **Event connection** — PTP events and PTP/IP-specific packets; asynchronous and separate from command/data.

Both are opened by the Initiator. The Responder is a TCP server; the default port is **15740** when discovery is not used. Both connections should use Keep-Alive and disable Nagle (`TCP_NODELAY`) to avoid command/response delay.

### 3. Connection establishment sequence

1. Initiator opens the Command/Data TCP connection to the Responder.
2. Initiator sends `Init Command Request` with its GUID and friendly name.
3. Responder replies `Init Command Ack` (success) or `Init Fail` (failure). On failure both sides close the connection.
4. Initiator opens a second TCP connection for events.
5. Initiator sends `Init Event Request` containing the `Connection Number` from step 3.
6. Responder replies `Init Event Ack` or `Init Fail`.

The `Connection Number` associates both TCP connections with the same PTP/IP connection and PTP session; it is reusable once both connections have been associated. If the event connection fails, the command/data connection must be closed and the whole PTP/IP connection retried later; the article suggests a 30 s interval.

When the Responder has exhausted concurrent PTP/IP connections, `OpenSession` may answer `Device_Busy`; the Initiator should release the PTP connection and retry later.

### 4. Generic PTP/IP packet framing

All multi-byte fields are little-endian.

| Field | Bytes | Type |
| --- | --- | --- |
| Length | 4 | UINT32, includes the 8-byte header |
| Packet Type | 4 | UINT32 |
| Payload | Length − 8 | type-specific |

Packet types (numeric values from `PtpIpType` in this repository; the article does not state every value explicitly):

| Value | Packet |
| --- | --- |
| `0` | invalid |
| `1` | Init Command Request |
| `2` | Init Command Ack |
| `3` | Init Event Request |
| `4` | Init Event Ack |
| `5` | Init Fail |
| `6` | Operation Request / Command Request |
| `7` | Operation Response / Command Response |
| `8` | Event |
| `9` | Start Data |
| `10` | Data |
| `11` | Cancel |
| `12` | End Data |
| `13` | Probe Request |
| `14` | Probe Response |

### 5. Init packet layouts

**Init Command Request (type 1):** `GUID` (16 bytes), `Initiator Friendly Name` (variable UTF-16, null-terminated, max 40 bytes including null), `Initiator Protocol Version` (UINT32, `0x00010000`). GUID bytes all `0xFF` mean an anonymous Initiator; acceptance is Responder policy.

**Init Command Ack (type 2):** `Connection Number` (UINT32), `Responder GUID` (16 bytes), `Responder Friendly Name` (variable UTF-16, max 40 bytes including null), `Responder Protocol Version` (UINT32).

**Init Event Request (type 3):** `Connection Number` (UINT32) copied from Init Command Ack.

**Init Event Ack (type 4):** header only.

**Init Fail (type 5):** `Reason` (UINT32) only. Symbolic reasons in the source are `FAIL_REJECTED_INITIATOR`, `FAIL_BUSY` and `FAIL_UNSPECIFIED`; numeric reason values are not stated in the article, so do not hardcode them from that source.

After `Init Fail`, the Initiator must close the command/data connection; the Responder should close the rejected TCP connections. Init Fail may travel on either TCP connection.

### 6. Operation request / response / event packets

**Operation Request (type 6) payload:**

```text
DataPhaseInfo UINT32
OperationCode UINT16
TransactionID UINT32
Parameter1..5 UINT32 (as present)
```

`DataPhaseInfo` indicates no/data-in, data-out, or unknown. This repository currently writes `1` for no/data-in and `2` for data-out. If data-out is declared, a `Start Data` packet must follow. An empty data object may be represented either as no/data-in with an immediate response, or data-out with a zero-length `Start Data` and no subsequent `End Data`.

**Operation Response (type 7) payload:** `ResponseCode` UINT16, `TransactionID` UINT32, up to five UINT32 parameters. It terminates a transaction and carries status; it is not a data-set carrier.

**Event (type 8) payload:** `EventCode` UINT16, `TransactionID` UINT32, up to three UINT32 parameters. An event tied to an operation uses that operation’s TransactionID; otherwise `0xFFFFFFFF`.

### 7. Data phase packets

| Packet | Layout | Notes |
| --- | --- | --- |
| Start Data (9) | `TransactionID` UINT32 + `Total Data Length` UINT64 | `0xFFFFFFFFFFFFFFFF` means unknown length (e.g. streaming) |
| Data (10) | `TransactionID` UINT32 + payload | only during data phase; command/data connection |
| End Data (12) | `TransactionID` UINT32 + optional payload | ends data phase and may still carry bytes |
| Cancel (11) | `TransactionID` UINT32 | sent on command/data and event connections |

Normal data-in sequence: `Start Data → Data×N → End Data → Operation Response`. TCP already supplies reliable ordered delivery and flow control, so PTP/IP has no separate flow-control layer and Data packets need no error check.

### 8. Probe, cancellation and disconnect handling

- Probe Request (13) / Probe Response (14) are heartbeat packets; a receiver must answer immediately. Suggested timeout: 10 s. Use sparingly to avoid LAN overload.
- Initiator cancellation: send Cancel on both command/data and event connections; Responder answers `Transaction Cancelled (0x201F)` and drains remaining data packets.
- Responder cancellation: send Operation Response with `Transaction Cancelled` or another interruption code; then discard remaining incoming Data packets for a data-out phase.
- Device/network loss is detected by network-layer notification, loss of either TCP channel, or timeout on any PTP-IP channel.
- PTP/IP connection closure is initiated by the Initiator when no longer needed or when a channel becomes unstable; both TCP connections must be closed.

### 9. Discovery, addressing, binding and security

Address acquisition order: manual configuration, DHCP, then IPv4 link-local (v4LL). Discovery options: manual address, Zeroconf, UPnP discovery only, or another mechanism; GUID should be advertised in discovery packets for filtering. Authentication is not a PTP/IP layer responsibility; device binding can be implemented at the application layer using exchanged GUIDs. Data security is out of scope and delegated to the underlying network.

## Source conflicts and ambiguities to preserve

These are not resolved by silently preferring one source; they are recorded so implementation work can make an explicit decision.

| Topic | Sources | Conflict / ambiguity | Consequence |
| --- | --- | --- | --- |
| `GetObjectHandles` parameter 3 | PIMA §10.4.7 vs earlier internal notes | PIMA says `0x00000000` = unused/aggregate and `0xFFFFFFFF` = store root. Earlier notes label `{-1,0,0}` as “root”. | The clause text is explicit, and libgphoto2 (`PTP_GOH_ROOT_PARENT 0xffffffff`, `PTP_GOH_ALL_ASSOCS 0x00000000`) and the Android MTP API (“-1 for the storage root, or zero for all objects”) agree, so `0xFFFFFFFF` = root is the settled direction; the mislabelled side is the earlier `{-1,0,0}` = “root” note. Only the aggregate behaviour still needs real-hardware confirmation. |
| `GetObjectHandles` parameter 2 | PIMA §10.4.7 vs earlier default | PIMA says `0x00000000` = unused, `0xFFFFFFFF` = images only, not “all formats”. | A non-zero filter can legitimately return `Specification_By_Format_Unsupported`; zero is the safest “no filter”. |
| TransactionID ranges | PIMA §9.3.1 vs Chinese article §§1.6.5, 2.3.6–2.3.7 | PIMA is explicit: `OpenSession = 0`, normal IDs `1..0xFFFFFFFE`, `0xFFFFFFFF` invalid. The article also mentions `0` for GetDeviceInfo and `0xFFFFFFFF` as “not applicable”. | Follow PIMA for core semantics; GetDeviceInfo outside a session uses zeroes as an operation dataset special case. |
| SessionID on the wire | PIMA §9.3.2 vs PTP/IP connection model | PIMA operation/response/event datasets include SessionID; PTP/IP packets omit it. | The two TCP connections plus `Connection Number` identify the session; do not add SessionID to PTP/IP packets. |
| Event dataset | PIMA §12.2 vs PTP/IP Event Packet | PIMA includes SessionID; PTP/IP event packet does not. | PTP/IP event handling must infer session from the event connection. |
| Packet type numeric values | Chinese article / CIPA DC-X005 | The article does not state every numeric value explicitly. | Numeric values used by this project come from `PtpIpType`; order/names come from CIPA/Chinese article. |
| Friendly name limit | PTP/IP §2.3.1–2.3.2 vs PIMA §5.3.4 | PTP/IP init names: max 40 bytes including null; PTP dataset strings: max 255 characters. | Do not apply the PTP string limit to PTP/IP init packets. |
| 55742 data socket | PTP/IP core vs device notes | Not part of the base PTP/IP packet model; appears as a device-specific path for object bytes. | Keep it scoped to object-byte retrieval; browsing operations use command/data data phase. |

## Relevance to this repository

| Topic | Repository location | What this document explains |
| --- | --- | --- |
| Packet types and framing | `feature/camera/protocol/PtpContainer.kt` | `PtpIpType` values, 8-byte IP header, 18-byte command header, 14-byte response/event header, DATA/END_DATA payload offset |
| Operation/response codes | `feature/camera/protocol/PtpOpcode.kt` | Standard `0x10xx` operations, `0x20xx` responses, vendor `0x9xxx` / `0xDxxx` opcodes |
| Wi-Fi handshake | `feature/camera/transport/IpHandshake.kt` | Two TCP connections, init request/ack sequence, connection number, Init Fail, Keep-Alive/Nagle |
| Wi-Fi transactions | `feature/camera/transport/IpPtpTransport.kt` | Data phase handling, response-vs-data distinction, cancellation, device-specific object-byte socket scope |
| USB transport | `feature/camera/transport/UsbPtpTransport.kt` | PTP/USB container header is separate from PTP/IP framing; events use the interrupt endpoint |
| File listing | `feature/camera/operation/PtpFileOperations.kt` | `GetStorageIDs → GetObjectHandles → GetObjectInfo → GetThumb/GetObject`, array decoding and retry semantics |
| Object info | `feature/camera/operation/ObjectInfoDecoder.kt` | Field order and string/date decoding from `ObjectInfo` dataset |
| Object identity | `feature/camera/model/CameraModels.kt`, `FOTLAB-DATABS-000004` | Handles are session-local; store handle beside a resolved folder/name |
| Out of scope | `feature/camera/ARCHITECTURE.md` | Live view, camera-setting writes, MTP and full device-property control are not part of the current feature set |

## Constraints

- This is a survey item. It records external facts and source limits; it does not approve a new camera feature or change an existing requirement.
- PIMA 15740:2000 is the 2000 edition. Later ISO 15740 revisions add optional capabilities; absence here does not prove a capability is invalid in newer devices.
- The Chinese article is a secondary source. Where it conflicts with PIMA/CIPA or the implementation constants, the conflict is recorded rather than hidden.
- Numeric values not stated in the sources were not guessed.
- PTP here always means Picture Transfer Protocol, never IEEE 1588 Precision Time Protocol.

## Acceptance Criteria

- Both references are named with their formal titles and authority level.
- Core PTP roles, sessions, transactions, datatypes, datasets, operations, responses, events and device properties are recorded with clause numbers.
- PTP/IP connection setup, packet framing, packet types, init packets, operation/event/data packets, cancellation, probe and disconnect handling are recorded.
- Source conflicts and stated limits are explicit.
- The document maps protocol details to the existing camera module without adding a new requirement.
- `rules/STRUCT/index.md` has exactly one new row for this item, and the next-sequence comment is advanced.

## Impacted Modules

- `rules/STRUCT/detail/FOTLAB-SURVEY-000003.md` — this document.
- `rules/STRUCT/index.md` — item table and next-sequence comment.
- Reference-only: `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/` (no code changes in this item).

## Open Questions

- Should `PTP_PARENT_ROOT` / `PTP_PARENT_ALL` keep their current names now that the PIMA §10.4.7 clause text and two independent implementations confirm the direction (`0xFFFFFFFF` = store root, `0x00000000` = device-wide aggregate)? The constant values are already correct; only the naming is open.
- Should the project advertise protocol version `0x00010000` strictly and reject unknown responder versions, or continue to ignore that field for compatibility?
- Do target cameras ever use DataPhaseInfo “unknown”, and if so what fallback behaviour is required?
- Are any target cameras relying on the device-specific 55742 channel for browsing operations, or only for object bytes?
- Which RAW/DNG/vender ObjectFormatCodes must be added to the media filter for the intended camera set?

## Change History

- 2026-11-27 — Created from the SegmentFault PTP/IP Chinese article and PIMA 15740:2000. Consolidates core PTP and PTP/IP transport details, records source conflicts and stated limits, and maps the details to `feature/camera`. Documentation only; no requirement changed.
- 2026-11-27 — Clarified the `GetObjectHandles` parameter 3 row and the matching open question after the PIMA 15740:2000 §10.4.7 clause text was re-read directly: `0xFFFFFFFF` = store root and `0x00000000` = device-wide aggregate, matching libgphoto2 constants and the Android MTP API contract. The earlier internal note that labelled `{-1,0,0}` as “root” is the mislabelled side. No protocol requirement changed.
