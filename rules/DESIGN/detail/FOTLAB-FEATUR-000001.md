# Camera Feature — Connection Management and File Management

- ID: FOTLAB-FEATUR-000001
- Status: Draft
- Priority: P1
- Created: 2026-11-25
- Owner: —
- Related: `FOTLAB-UIXDES-000001` (the destination set and the content-region contract), `FOTLAB-UIXDES-000002` (per-module fun bar and drawer), `FOTLAB-UIXDES-000003` (strings blocks and locale parity), `FOTLAB-STRUCT-000001` (one `feature/<name>/` package per destination), `FOTLAB-STRUCT-000002` (package dependency direction), `FOTLAB-STRUCT-000003` (role-based naming), `FOTLAB-PRIVCY-000001` (permissions and network use), `FOTLAB-COMPAT-000001` (per-device protocol compatibility)

## Background & Goal

The Camera destination is the third top-level destination of the app, alongside the Library and the
Studio. It exists so a user can reach the pictures and movies that still live on a camera body, and
move them into the app's own library — the camera is a **source of media**, not a place to browse
forever. The device is reachable over two bearers: a USB cable (OTG) and the local network.

This item defines the capability boundary of the destination for this iteration. It covers exactly
two capability areas, and they are the two that make the destination usable end to end:

- **Connection management** — finding a camera, establishing a session with it, keeping that
  session honest, and ending it cleanly.
- **File management** — seeing what is on the camera, and moving media between the camera and the
  device.

Everything else about a camera — live view, remote shutter and exposure control, remote capture,
multi-camera sessions — is **out of scope** and is not started by this item. The absence is
deliberate and is not a placeholder: the destination ships as a *file transfer* surface, and the
live-view work is a separate item if it happens at all.

Goals:

- G1 — A user can find a camera, connect to it, browse its contents, download what they want and
  upload what they have, without leaving the destination.
- G2 — Connection failures are reported as connection failures. A camera that was unplugged must
  not leave the UI claiming it is still connected.
- G3 — The listing reflects the camera's real state. A photo taken on the camera body, or a file
  deleted from it, shows up without the user having to guess whether the screen is stale.
- G4 — The capability boundary is explicit, so that the next iteration has a defined base to grow
  from and does not have to rediscover what is deliberately absent.

## Requirement

### R1 — Connection management

**Discovery.**

- Two bearers are supported: **USB** and **network**. A USB camera is an attached device whose
  vendor id is in the known-camera table; a network camera is found by a local-network probe, and
  can always be added by typing its address.
- Discovery is **on demand**. The screen refreshes the device list when the user asks for it, and
  a USB camera that is plugged in while the app runs is picked up automatically (R1.4). There is no
  background polling.
- The device list is the union of what discovery found and what the user added by hand; a device
  appears once even when both routes report it.

**Tuning the discovery window.**

- The **mDNS wait window** is a user setting, exposed in the destination's own drawer
  (`FOTLAB-UIXDES-000002` R5 — module-private content). It bounds how long the network probe waits
  for cameras to answer, so a user on a slow or crowded network can shorten the wait and reach the
  device list sooner.
- The **keepalive interval** is a user setting as well. It bounds how often a connected network
  camera is probed to keep the link from going idle.
- Both settings have a **defined default that applies when the user has never changed them**
  (mDNS 10 s, keepalive 20 s), and both persist across launches. A fresh install therefore behaves
  exactly like an install whose stored value equals the default.
- A change takes effect **without requiring a reconnect** where that is meaningful: the keepalive
  interval applies to a live link immediately, and the mDNS window applies to the next discovery.
- The USB bearer needs neither setting: the device list comes from the platform, and a USB link is
  physical, so there is nothing to time out and nothing to keep alive.

**Establishing the session.**

- Connecting is a single user action on one device. It resolves that device's **profile** — the
  set of command codes, chunk sizes and session id it needs — builds the transport for its bearer,
  and opens the session.
- The session is **serialized**: one transaction at a time, and the session-open command always
  precedes every other command, so no command can overtake it.
- **At most one session is open at a time.** Connecting to a second camera closes the first. Two
  half-open links against the same camera body make both unusable.
- A failed connection reports the device and the reason, and leaves nothing open behind.

**Keeping the session honest.**

- A **broken link is not a failed command.** These are two different outcomes and the destination
  must distinguish them:
  - *The device answered and refused* (a non-OK response code) — the link is fine, this particular
    request was not. The session stays open and the user sees an ordinary error.
  - *The link broke* (socket closed, USB endpoint gone, truncated packet) — the session is dead.
    It is released, and the destination reports a failed connection.
- A **USB camera that is unplugged** while it is connected ends its session, rather than leaving a
  screen that claims a live connection to a device that is gone.
- A **camera that changes by itself** — a photo taken on the body, a file deleted from it —
  publishes an event that the listing reacts to, so the screen does not show a stale directory.

**Ending the session.**

- Disconnecting closes the session politely (the protocol's own close command) and then releases
  the link, whichever bearer it runs over.
- Ending a session also clears everything that belonged to it: the listing, the current folder,
  the selection and the transfer progress.

### R2 — File management

**Seeing the contents.**

- The camera's storages are listed, and one of them is browsed at a time. A device with a single
  storage does not show a storage switcher; a device with several does.
- The listing is **scoped to a folder**. The top level of a storage is shown first; entering a
  folder shows that folder's contents; going back returns to the parent. The current folder is
  always visible, so the user knows where they are.
- Folders and media are distinguished: a folder is entered, a media object is selected.
- The listing can be **filtered** to all media, to photos, or to videos. The filter narrows what
  is shown; it never changes what the camera holds.
- When a device ignores the folder argument and answers with its whole tree, the listing falls
  back to that flat answer instead of showing an empty screen.

**Moving media out of the camera.**

- Downloading is available for any selection of media objects. It runs in the **background**: it
  continues when the user leaves the destination, and its progress is visible in a notification.
- The user picks the destination folder in the system folder picker; the files land there and are
  only published as complete when the last byte has arrived — a half-written file is never left
  visible in the user's library.
- The folder picker remembers where the user was: the URI of the last file a download wrote is
  kept, and the next picker opens in that file's folder. The system keeps only one global last
  folder for the whole app, so the camera module keeps its own.
- Progress is reported per object, so a multi-object download shows which file it is on.

**Moving media into the camera.**

- Uploading takes a file the user picks from the device and sends it to the camera.
- The file lands in the **folder that is currently open**, so the result is where the user was
  looking. Uploading into a storage that was never listed is not possible.

**Removing media from the camera.**

- Deleting is available for a selection, is destructive, and therefore **always asks for
  confirmation first** and states how many objects are affected.
- A deletion that the camera refuses is reported as an error; the listing is not changed on the
  user's behalf.

**Scope of the file surface.**

- Only media is selectable for transfer. A folder is navigated, never downloaded or deleted as a
  unit.

### R3 — The destination is a file-transfer surface

- Live view, remote shutter and exposure control, remote capture and multi-camera sessions are
  **not part of this item** and are not implemented.
- The destination therefore owns exactly one activity shape: connect → browse → transfer.
- A future item that adds any of the excluded capabilities does so by extending this one, not by
  replacing it.

### R4 — Copy and presentation

- Every user-visible string of the destination — labels, actions, messages, empty states, content
  descriptions — comes from the resources, in the destination's own block, in both shipped
  locales, with identical key sets (`FOTLAB-UIXDES-000003` R2/R5).
- The navigation-bar label belongs to the shell's block; shared accessibility text belongs to the
  common block. No string is shared between two destinations' blocks.
- Device names, file names, protocol identifiers and runtime error text are **data**, not copy:
  they are shown as they are and are not translated.

## Constraints

- C1 — The destination is implemented as one `feature/camera/` package holding the screen and its
  lower layer, assembled by the shell through `navigation/camera/` (`FOTLAB-STRUCT-000001` R2/R4).
- C2 — The feature package imports neither the shell's `ui` package nor `navigation`; the
  dependency direction is one-way and acyclic (`FOTLAB-STRUCT-000002` R2).
- C3 — The screen owns its fun bar and its drawer, and follows the shared behaviour contract
  (`FOTLAB-UIXDES-000002` R1–R6).
- C4 — All user-visible copy resolves through resources; no literal text reaches a composable
  (`FOTLAB-UIXDES-000003` R6). Locale files carry the same key set.
- C5 — At most one camera session is open at a time, and connecting closes any previous one (R1).
- C6 — A broken link ends the session and is reported as a failed connection; a device-level
  refusal does not (R1).
- C7 — Transfers run outside the screen's lifetime and are reported through a notification (R2).
- C8 — Downloads publish only complete files; a partial file is never visible as a finished one (R2).
- C9 — Deleting asks for confirmation before it runs (R2, `FOTLAB-UIXDES-000002` R4).
- C10 — The excluded capabilities of R3 are not started: no live view, no remote capture, no
  parameter control, no multi-camera session code.
- C11 — The network bearer's traffic is plain-text PTP over the local network, which the manifest
  declares accordingly; the USB bearer needs no network permission at all.
- C12 — Nothing about how a device's behaviour was learned belongs in the source, the copy or these
  rules. The feature is described by what it does, never by where the knowledge came from.

## Acceptance Criteria

- AC1 — With a camera attached over USB, the destination lists it without the user pressing
  refresh, and connecting to it opens a session that can list its storages.
- AC2 — With a camera reachable on the local network, the destination finds it by discovery and
  offers it for connection; a camera that discovery does not find can still be added by address.
- AC3 — Connecting to a second camera closes the first session; only one session is ever open.
- AC4 — After a successful connection, the destination shows the camera's storages and the objects
  of the selected one.
- AC5 — With more than one storage, the destination offers a storage switcher and switching shows
  that storage's objects; with exactly one storage, no switcher is shown.
- AC6 — A folder in the listing can be entered, and the listing then shows that folder's contents;
  the back affordance returns to the parent and to the top level in turn.
- AC7 — The current folder's name is visible while a folder is open.
- AC8 — Filtering to photos shows only photo objects, filtering to videos only video objects, and
  the unfiltered view shows both.
- AC9 — Selecting media objects and downloading writes complete files into the folder the user
  picked; a file that is still being written is not visible as a finished one. The next download's
  folder picker opens in the folder of the file the previous download wrote.
- AC10 — A download continues after the user leaves the destination, and its progress is visible in
  a notification.
- AC11 — Uploading a picked file places it in the folder that is currently open.
- AC12 — Deleting a selection asks for confirmation naming the number of objects, and a deletion
  the camera refuses leaves the listing unchanged and reports the error.
- AC13 — Unplugging a connected USB camera returns the destination to the disconnected state
  instead of leaving it claiming a live connection.
- AC14 — A camera-side change (a new capture, a deleted object) refreshes the listing without the
  user re-entering the folder.
- AC15 — A failed connection shows which device failed and why, and leaves no session open.
- AC16 — The destination contains no live-view, capture, parameter-control or multi-camera session
  code.
- AC17 — Every string of the destination resolves from resources, and the two shipped locale files
  carry the same key set for this destination's block.
- AC18 — The feature package imports neither `ui` nor `navigation`, and the package graph within
  the feature is acyclic.
- AC19 — The destination's drawer exposes the mDNS wait window and the keepalive interval; on a
  fresh install they show their defaults (10 s and 20 s), and a changed value survives a relaunch.
- AC20 — Changing the keepalive interval while a network camera is connected changes the probe
  schedule of that live link without requiring a reconnect.

## Impacted Modules

- `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/` — the whole feature: screen, fun
  bar and drawer, the process facade, the file repository, and the protocol/discovery/transport
  layers below it
- `app/src/main/kotlin/io/github/fotlab/fotlab/navigation/camera/` — the route and the graph entry
  point the shell assembles
- `app/src/main/kotlin/io/github/fotlab/fotlab/navigation/TopLevelDestination.kt` — the destination
  entry
- `app/src/main/res/values/strings.xml` and `app/src/main/res/values-zh/strings.xml` — the
  destination's copy block
- `app/src/main/AndroidManifest.xml` — the permissions and the background-transfer service
- `FOTLAB-UIXDES-000001` / `FOTLAB-UIXDES-000002` / `FOTLAB-UIXDES-000003` — the shell contract,
  the fun-bar contract and the copy rules this destination implements
- `FOTLAB-STRUCT-000001` / `FOTLAB-STRUCT-000002` / `FOTLAB-STRUCT-000003` — the package layout,
  the dependency direction and the naming rules this destination follows

## Open Questions

- Q1 — Should the destination offer a **live view** surface, and if so does it belong in this
  destination or in a separate one? **TBD.** R3 excludes it for now, so the question is open
  rather than answered.
- Q2 — Should a **remote capture** (shutter on the camera body, triggered from the app) be added,
  and does the capture event then need to reach the library automatically? **TBD.**
- Q3 — Should the destination support **more than one camera at a time** — for example a camera on
  USB and one on the network — given that C5 allows only one session? **TBD.**
- Q4 — Should a **camera-side file deletion** also be offered, so a file can be removed from the
  camera once it is safely in the library? R2 covers deleting from the camera, but the workflow
  that pairs download and cleanup is not specified. **TBD.**
- Q5 — Should the listing show **thumbnails** rather than names and sizes? The protocol carries
  them, but a thumbnail grid changes the shape of the listing and the amount of data pulled over
  the link. **TBD.**
- Q6 — Should a **camera's own storage quota** (free space) be surfaced next to the storage
  switcher? The data is already read. **TBD.**
- Q7 — Should the destination support **reconnecting automatically** after a transient drop, or
  does the user always reconnect explicitly? **TBD.**

## Change History

- 2026-11-25 — Initial draft. Defined the Camera destination's capability boundary for this
  iteration as two areas — connection management (R1: discovery, session establishment, link-failure
  handling, hot-plug, events, clean disconnect) and file management (R2: storages, folder-scoped
  browsing, media filtering, background download, folder-targeted upload, confirmed deletion). R3
  excludes live view, remote capture, remote parameter control and multi-camera sessions explicitly,
  so the next iteration starts from a stated base instead of an implied one. Recorded the
  copy-and-presentation rules (R4), the constraints (C1–C12), the verifiable acceptance criteria
  (AC1–AC18), and left the open product questions — live view, remote capture, multi-camera,
  camera-side deletion, thumbnails, quota display and automatic reconnect — as Q1–Q7.
- 2026-11-26 — Added the tunable discovery window: the mDNS wait window and the keepalive
  interval are now user settings in the destination's own drawer, each with a defined default that
  applies when the user has never changed them (10 s and 20 s) and each persisted across launches.
  A keepalive change applies to a live network link immediately; the mDNS window applies to the
  next discovery. Added AC19 and AC20 to make both halves checkable. The USB bearer is explicitly
  out of scope for both settings — the device list comes from the platform and a USB link is
  physical.
- 2026-11-26 — R2's download paragraph and AC9 follow the owner's decision that a download asks
  the user for the destination folder: the files land in the picked folder instead of a fixed
  public-downloads folder, and the picker reopens in the folder of the last file written. The
  fun bar of the camera destination was rebuilt on the same decision — the leading cluster carries
  the drawer toggle, the list/grid layout switch and the refresh of the open folder, the trailing
  cluster carries the selection trio in the overflow and two slots whose glyph depends on the
  selection (upload / new folder with an empty selection, download / delete with a non-empty one).
