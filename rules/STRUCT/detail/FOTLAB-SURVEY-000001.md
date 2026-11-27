# Precision Time Protocol (IEEE 1588) — PTPv1 and PTPv2 Are Separate Clock Domains

- ID: FOTLAB-SURVEY-000001
- Status: Draft
- Priority: P2
- Created: 2026-11-26
- Owner: —
- Related: [`FOTLAB-FEATUR-000001`](../../DESIGN/detail/FOTLAB-FEATUR-000001.md) (camera PTP/IP feature), [`FOTLAB-STRUCT-000001`](FOTLAB-STRUCT-000001.md)

> **Terminology — which "PTP"?** This document studies **Precision Time Protocol (IEEE 1588)** as
> an external reference only; it is not implemented in this repository. The unqualified acronym
> **PTP** in this repository always means **Picture Transfer Protocol (ISO 15740)** — the camera
> file-transfer protocol in `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/` — never
> Precision Time Protocol. See the terminology note below.

## Background & Goal

Source: techrider.live, *Dante PTPv1 与 PTPv2：保持时钟域兼容* (retrieved 2026-11-26,
<https://techrider.live/zh-cn/learn/basics/dante-ptp-v1-vs-ptp-v2>).

The article's premise is simple and easy to get wrong: **a PTP version is part of the clock-domain
boundary, not a label.** Two endpoints that share a switch, or that can discover the same media
stream, do not thereby belong to the same timing domain. The article therefore treats PTPv1
(IEEE 1588-2002) and PTPv2 (IEEE 1588-2008) as two different clock systems that must be bridged
only by a device, firmware and configuration that explicitly support that role.

Goal: record the version-boundary discipline from that article as a reusable engineering rule, and
state plainly where it does and does not apply to this repository. This is an external study
document — it specifies no change to the camera module.

### Terminology note — PTP means two unrelated things

This repository already uses the acronym **PTP** for a completely different standard:

| Acronym | Standard | Transport | Where it lives here |
| --- | --- | --- | --- |
| PTP = Picture Transfer Protocol | ISO 15740 | USB bulk / PTP-IP over TCP | `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/protocol/`, `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/transport/` |
| PTP = Precision Time Protocol | IEEE 1588 | UDP multicast | **not implemented in this repository** |

The camera module speaks *Picture Transfer Protocol*. This document studies *Precision Time
Protocol*. The two share only an acronym; they share no message set, no port convention, and no
handshake. Any future note that says "PTP" must say which one it means.

### What this repository's PTP actually is — Picture Transfer Protocol (ISO 15740)

When this repository says "PTP", it means the camera **file-transfer** protocol. Concretely:

- **Standard and purpose**: ISO 15740 Picture Transfer Protocol, used to browse a camera's
  storage and move media between the camera and the device — list storages and objects, read
  thumbnails and full objects, download by byte range, delete, upload and create folders. It
  carries image and video files; it carries no clock offsets, leader election, or
  `Sync`/`Announce`/`Delay_Req` messages.
- **Two bearers, one session**: USB bulk (`transport/UsbPtpTransport.kt`) and PTP/IP over TCP
  (`transport/IpPtpTransport.kt`, with the device-bonding handshake in
  `transport/IpHandshake.kt`). Both feed one serialized `protocol/PtpSession.kt`, which opens a
  session with `GetDeviceInfo` (0x1001) and `OpenSession` (0x1002) and dispatches the operations
  defined in `protocol/PtpOpcode.kt`. Network cameras advertise `_ptp._tcp.local.` over mDNS.
- **Where it lives**: `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/` — the
  `protocol/`, `transport/`, `operation/`, `profile/` and `model/` packages. A PTP object handle
  is a device-local number, so a queued transfer also remembers the folder and the file name in
  order to re-resolve the object on another camera (`FOTLAB-DATABS-000004` R5).
- **What the IEEE 1588 rules below do not mean here**: there is no PTPv1/PTPv2 version, no clock
  domain, no leader/follower, no boundary clock, no domain number and no multicast timing traffic
  in this repository. R1–R7 and the constraints below are external engineering discipline
  recorded for reference; they impose no requirement on the camera module's ISO 15740 code.

That is the sense in which "our PTP" is Picture Transfer: every camera-module mention of PTP —
a `ptp://` URI, a name such as `PtpSession` or `PtpFileOperations`, or a discovery record —
refers to this file-transfer stack, never to IEEE 1588.

The transferable content of the article is methodological, not protocol-specific: **do not infer
compatibility from adjacency, define the boundary deliberately, verify it in layers, and keep a
documented baseline to fall back to.**

## Requirement

### R1 — Treat protocol versions as separate domains, never as interchangeable labels

- PTPv1 and PTPv2 use different message formats and different leader-election behaviour. Unless a
  vendor document explicitly states that a product supports a boundary or interop mode, the two must
  be treated as independent clock systems.
- PTPv2 is **not** backward compatible with PTPv1. Where a deployment mixes them, the boundary must
  be carried by a device whose documentation says it supports that exact role.
- PTPv2 adds `Announce` (leader election and property advertisement), `Pdelay_Req`/`Pdelay_Resp`
  (peer delay), transparent and boundary clock roles, and TLV extensions. PTPv1 has only
  `Sync`/`Follow_Up`/`Delay_Req`/`Delay_Resp`, with election information embedded in `Sync` and
  `Follow_Up`, and only master/slave roles.
- PTPv2.1 (IEEE 1588-2019) is compatible with PTPv2 but still not with PTPv1.

**Rule**: never write "the device supports PTP" without a version. State `PTPv1` or `PTPv2`, and
state the domain number when domains are in play.

### R2 — Inventory the endpoints before configuring anything

Before any change, record and keep:

1. Every transmitter, receiver, switch path, firmware version, and enabled interop mode.
2. Each endpoint's sample rate and the PTP behaviour it supports.
3. A separate diagram per clock domain, with its intended leader and followers.
4. Every device that is planned to participate in or bridge more than one timing context.
5. Whether the intended role is explicitly supported by the current vendor documentation.
6. A health baseline: leader/follower state, offset, subscriptions, events, audible output.

**Rule**: do not enable a second protocol mode across the fleet as an exploratory fix. Change only
devices on a documented boundary, and keep a rollback path.

### R3 — Build an intentional timing boundary

- **Name the leader for every domain**, together with the fallback behaviour and the priority
  setting. One bridging device cannot compensate for two unstable election results.
- **Align the media format**: sample rate, codec, channel count and interop mode must match. A
  clock-locked receiver may still refuse an unsupported payload.
- **Control the network scope**: map VLANs, switch ports, multicast forwarding and any routing
  boundary. Separate the primary and redundant networks according to the supported design. A PTP
  experiment must not merge redundant paths or extend timing traffic unintentionally.

### R4 — Verify the boundary in layers

Verify in this order, and do not skip a layer because a lower one already looked healthy:

1. **Timing first** — confirm the intended leader and stable followers on both sides, then check
   offset and events to catch repeated elections or unlocks.
2. **Media** — confirm the stream, the channel mapping and the physical output target.
3. **Audio** — send one identifiable channel at a time.

### R5 — Test loss, recovery and reboot

1. Feed representative audio and record the stable state on both sides.
2. Interrupt the link at an **approved** boundary point.
3. Record leader changes, follower unlocks, muting or artefacting, and event timestamps.
4. Restore the boundary and wait for the clock to re-stabilise before judging the audio.
5. Re-check every subscription and every critical physical output.
6. Reboot in the approved order and confirm the target state is unchanged.
7. Execute the rollback so the team can return to the native baseline.

**Rule**: do not describe recovery as seamless unless the deployed system has been measured under
real load.

### R6 — Boundary acceptance checklist

- Every device, firmware version and supported PTP mode is documented.
- Every clock domain has an intended leader and a documented fallback.
- Every bridging or interop role is vendor-supported.
- Sample rate, codec, channel count and receiver mapping agree.
- VLAN and multicast scope carry timing traffic only where intended.
- Followers stay stable under production load, with acceptable offset.
- Identifiable audio reaches every critical physical output.
- Loss, recovery, reboot persistence and rollback are verified.
- The responsible owner and the accepted evidence are attached to the handover.

### R7 — Record the handover

Record device, firmware, protocol mode, domain, leader, priority, network path, media format,
offset, event evidence, audible result, failure behaviour, reboot order, owner and rollback plan.
Any diagram must distinguish timing domains from media streams.

## Constraints

- C1 — This document describes **Precision Time Protocol (IEEE 1588)**. It does not describe the
  **Picture Transfer Protocol (ISO 15740)** that the camera module implements. Do not extend a rule
  from one to the other without checking the message sets independently.
- C2 — The article's guidance is written for Dante/AES67 deployments. It states general
  engineering discipline, not per-product rules. Device support for a PTP mode, domain behaviour,
  sample rate and AES67 implementation must be confirmed per device and per firmware.
- C3 — The article deliberately does not give a generic "Dante uses PTPv1 or PTPv2" answer. Behaviour
  depends on product, firmware and working mode; native Dante and AES67 interop boundaries may use
  different PTP contexts. Verify actual state rather than relying on a label.
- C4 — Nothing here requires a code change. The camera module's PTP/IP handshake
  (`app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/transport/IpHandshake.kt`) implements
  a different standard's device-bonding handshake: it sends an initiator GUID, and the camera
  answers `Init Command Ack` or `Init Fail`. `IpHandshake` defines several GUID variants and
  `CameraProfileResolver.resolve` takes a `preferAlternateGuid` switch, but as of 2026-11-26 no
  caller passes `preferAlternateGuid = true`, so no automatic GUID fallback chain is wired. The
  mechanism resembles R3's "name the leader and document the fallback" only in intent — it is not
  the same mechanism and must not be cited as evidence that the rule is implemented.
- C5 — Per [`rules/STRUCT.md`](../../STRUCT.md), this document records decisions, not discussions,
  and makes no claim about products or features this repository does not implement.

## Acceptance Criteria

- AC1 — Any future note in this repository that mentions PTP names the version (PTPv1 or PTPv2) and
  states that Picture Transfer Protocol is a distinct standard.
- AC2 — No rule in this document is cited as justification for a change to the camera module's
  ISO 15740 handshake without an independent check against the CIPA PTP-IP specification.
- AC3 — Any deployment plan that mixes PTP versions documents each clock domain, its leader, the
  vendor-supported bridge, and a rollback to the native baseline.
- AC4 — Recovery claims are supported by measurement under load, not by inference from a restored
  link.

## Impacted Modules

- None directly. This is an external study document.
- Read alongside the camera PTP/IP work: [`rules/DESIGN/detail/FOTLAB-FEATUR-000001.md`](../../DESIGN/detail/FOTLAB-FEATUR-000001.md)
  and `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/transport/IpHandshake.kt`, to keep
  the two standards distinct.
- Future reference: any future IEEE 1588 work (audio/video clock distribution, PTP monitoring) would
  live outside the current module layout and needs its own design item.

## Open Questions

- Q1 — If IEEE 1588 support is ever added, which module owns it? The camera module is PTP/IP over
  TCP for image transfer; a clock-distribution stack is a different transport and a different
  failure model, so it should not be folded into `feature/camera`.
- Q2 — Should the repository carry a short "protocol glossary" that disambiguates PTP (Picture
  Transfer) from PTP (Precision Time) for contributors who did not write either module?
- Q3 — Should the CIPA PTP-IP device-bonding rules (initiator GUID allow-list, `Init Fail` reason
  codes) be given their own survey document, given that the camera module already depends on them?
- Q4 — Should `CameraProfileResolver.resolve`'s unused `preferAlternateGuid` switch be wired into
  an automatic GUID fallback chain (try the primary GUID, then the alternate, then the zero GUID),
  and should `Init Fail` reason codes be surfaced to the user instead of a bare
  "Unexpected PTP/IP init response"? **Open** — the article's R3/R5 discipline argues for a
  deliberate, ordered fallback rather than a single hard-coded GUID.

## Change History

- 2026-11-26 — Created from the techrider.live article *Dante PTPv1 与 PTPv2：保持时钟域兼容*
  (retrieved 2026-11-26). Records the version-boundary discipline: treat PTPv1 and PTPv2 as two
  independent clock systems, inventory endpoints before configuring, build an intentional timing
  boundary with a named leader per domain, verify in layers (timing → media → audio), test loss and
  recovery and reboot with a documented rollback, and hold the boundary acceptance checklist. Adds
  an explicit terminology note separating IEEE 1588 Precision Time Protocol from the ISO 15740
  Picture Transfer Protocol already used by the camera module, so that the shared acronym cannot be
  mistaken for a shared protocol.
- 2026-11-27 — Added a prominent top-of-document "which PTP?" note: this paper studies IEEE 1588
  as an external reference, while the repository's PTP always means ISO 15740 Picture Transfer
  Protocol. Documentation only; no rule changed.
- 2026-11-27 — Expanded the terminology note with a concrete description of this repository's
  PTP: ISO 15740 picture transfer over USB bulk / PTP-IP, its code location, the object-handle
  identity rule, and an explicit statement that R1–R7 impose no requirement on the camera
  module. Documentation only; no rule changed.
