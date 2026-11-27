# Camera Transfer Queue — `camera_file_queue` schema and queue semantics

- ID: FOTLAB-DATABS-000004
- Status: Draft
- Priority: P1
- Created: 2026-11-26
- Owner: —
- Related: `FOTLAB-DATABS-000001` (Room persistence discipline), `FOTLAB-FEATUR-000001` (camera feature boundary), `FOTLAB-UIXDES-000002` (module chrome: drawer, fun bar), `rules/REVIEW/detail/CAMERA-QUEUED-000001.md` (the audit that motivated this)

> **Terminology — "PTP" in this repository:** **PTP** always means **Picture Transfer Protocol
> (ISO 15740)**, the camera file-transfer protocol implemented in
> `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/`. It does **not** mean
> Precision Time Protocol (IEEE 1588), the network clock-synchronisation standard; the two share
> only the acronym.

## Background & Goal

`CAMERA-QUEUED-000001` audited the camera feature's download path and found no queue at all: a
multi-file transfer was a one-shot in-memory batch, a broken link discarded the whole batch, and
nothing survived the process. This item settles the **persistence and semantics** of the queue so
that a queued transfer is a durable fact rather than a temporary list.

Goal: one agreed table, and one agreed reading of it, for the camera feature's transfer queue —
the table is the queue's memory, and the service that runs transfers is only its runner.

## Requirement

### R1 — The queue has its own database

The queue lives in `CameraDatabase` (database name `camera`), owned by `feature/camera`. It does
**not** live in the library's `fs_node` database.

The two features have different lifetimes and different failure modes: the library is a file tree
the user browses, the queue is a cache of what the user asked to transfer. A queue table has no
business in the file tree's schema, and a schema change in one must not force a migration of the
other.

Per `FOTLAB-DATABS-000001` R5: `exportSchema = false`, and no destructive-migration fallback —
a schema change ships an explicit migration rather than silently dropping the queue's history.

### R2 — The table

One table, `camera_file_queue`, one row per queued transfer.

| Column | Type | Required | Meaning |
| --- | --- | --- | --- |
| `queue_id` | INTEGER PRIMARY KEY | yes | Row identity |
| `timestamp_batchadd` | TEXT | yes | Batch key: the rows sharing it render as one card |
| `timestamp_itemfinish` | TEXT | no | `NULL` = still queued; otherwise how the item left the queue (R3) |
| `transfer_direction` | TEXT | yes | `cameraTOphone` or `phoneTOcamera` |
| `uri_camera` | TEXT | yes | The camera-side object; its last path segment is the file name |
| `uri_phone` | TEXT | yes | The phone-side target (download) or source (upload) |

Indexes:

- `(transfer_direction, uri_camera, uri_phone)` — **UNIQUE**; this triple is the transfer's
  identity (R4).
- `(timestamp_itemfinish)` — plain; the pending-rows query filters on it.

The DAO is a file of its own (`CameraFileQueueDao`): the queue is a self-contained concern and
shares no DAO with any other feature.

### R3 — The timestamp columns hold text, not numbers

Both timestamp columns are `TEXT`, because a row has to record **when** something happened *and*
**what** happened to it, and the two cannot share one numeric column.

`timestamp_itemfinish` reads as follows:

| Value | Meaning |
| --- | --- |
| `NULL` | queued, not finished — the row is pending |
| a decimal millisecond timestamp | the transfer finished at that moment |
| `paused` | stopped on the user's request, and may be picked up again |
| `canceled` | stopped at the user's request |
| `failed` | the transfer failed; the row will not run again as it stands |

The same flexibility applies to `timestamp_batchadd`, which groups the rows of one user action
into a batch.

### R4 — Identity, and what "already queued" means

A transfer is identified by the triple `(transfer_direction, uri_camera, uri_phone)`. The same
file going to the same place is the same transfer, whatever else the table holds.

Appending a transfer whose identity is already present is a **duplicate**: the user is asked
whether to overwrite it. Overwriting means the old row is deleted and a fresh one is appended;
declining means nothing happens at all — not even the transfers in the same selection that were
not duplicates.

### R5 — The two URIs

`uri_camera` is a `ptp://` URI. Its last segment is the file name — which is exactly what the
queue list shows, since the list shows the file name only, not the full URI.

- `ptp://<storageId>/<parent>/<handle>/<name>` — an object that is read from the device.
- `ptp://<storageId>/<parent>/<name>` — the place an upload writes to.

The download shape carries the storage and the folder as well as the handle, and that is
deliberate: **a PTP handle is a device-local number**, so a row queued on one camera cannot be
transferred to another by its handle alone. With the folder and the file name in the row, the
transfer can be re-resolved against whichever camera is connected now (R8).

`uri_phone` is the local side: for a download, the destination folder plus the file name; for an
upload, the source document.

### R6 — A near-stateless machine

Everything the user can observe is a row. Only two things are held in memory:

- the **running** item — the transfer in flight, because "running" is a property of this process,
  not of the transfer;
- the database handle itself.

There is no in-memory list of pending transfers, no in-memory batch structure, and no in-memory
status enum. A row survives the process, the screen and the camera link.

### R7 — The table is the driver; the service is only the runner

`CameraTransferService` reads the pending rows back and executes them one at a time. It carries
no payload: it is told "run what is queued", not "run this".

The consequence that matters is what happens when the link dies mid-transfer:

- a row that completes is stamped with the finishing time;
- a row the device **refuses** (`PtpResponseException`) is stamped `failed`, because it will
  never succeed as it stands;
- a row whose **link** dies is left **unfinished**. It stays in the table, the run stops, and the
  next time a camera is connected the queue picks it up again.

The last rule is the fix for the audit's central finding: the queue outlives the camera.

### R8 — The link's fate decides the queue's fate

The queue reads the link's state, not just its own rows. `CameraCore` publishes a **retry
state** next to the connection state, and the queue follows it:

| Retry state | What it means | What the queue does |
| --- | --- | --- |
| `Idle` | no recovery is running | nothing |
| `Retrying(attempt, maxAttempts)` | a recovery attempt is running | the rows keep waiting; the run resumes when the link comes back |
| `Exhausted` | the recovery was given up | **every row that is still queued is stamped `failed`** |

The `Exhausted` rule is the end of the road: the rows that never ran would otherwise wait
forever for a camera that is not coming back. They are stamped `failed` — and **the user
decides from there**, which is also how a batch built on one camera is carried over to another:
the user resumes the rows (R9) on the camera that has the files, and the transfer is re-resolved
by name (R5).

An explicit disconnect is *not* a failure: the rows stay queued and wait for the next camera.

The re-resolution itself belongs to the runner: the stored handle is checked first — it still
names the same file — and the file is looked up **by name** in the folder the row remembers
when it does not. A file that is not on the connected camera at all leaves the row `failed`,
with the reason stated, rather than silently transferring the wrong object.

### R9 — The user's controls over the queue

Every state a row can be in is reachable by hand:

| Action | Effect on the row |
| --- | --- |
| **pause** | `timestamp_itemfinish = 'paused'` |
| **resume** | `timestamp_itemfinish = NULL` — the row waits again |
| **retry** | as resume, and the runner is started when a camera is connected |
| **cancel** | `timestamp_itemfinish = 'canceled'` |
| **delete** | the row is removed |

A paused row is the user's own decision, so a later link failure does not touch it: only the
rows that are still **queued** (`NULL`) are stamped `failed` when a recovery is given up.

### R10 — The queue view

The camera drawer's top carries two tabs, `Storage` and `Queue`, and picking one switches the
content region (the way the library's drawer switches between its views).

The queue view lists the rows as **cards**, one card per `timestamp_batchadd`, newest batch
first. The card's right-hand arrow expands it to the individual files, each shown by file name
only. The list is a read of the table, so it shows the same thing whether the app was just
started or has been running for hours.

The view also states what the link is doing with the queue — connected, waiting, reconnecting
(attempt and total), or given up — because that is what decides whether the transfers in it
will move.

**Multi-selection.** A long press on a card takes the whole batch, a long press on a row takes
that row; the fun bar then offers the three actions that apply to the selection — cancel, retry
and delete — next to the drawer toggle and the overflow menu, which also carries the two
remaining states, pause and resume.

The fun bar's storage actions (download, upload, delete, refresh, grid, new folder) act on the
storage view only.

## Open Questions

1. **Cross-camera resumption is solved by name, with two limits.** The row remembers the
   storage, the folder and the file name, so the file is found again on another camera. What
   is *not* solved: a camera whose folders or names differ (the file is simply not there, and
   the row is left `failed` with the reason), and the case of two cameras that hold a file of
   the same name in different folders. The device exposes no stable serial in
   `PtpDeviceIdentity`, so the row cannot tell the two apart.
2. **Byte-level resume.** A row that was interrupted restarts from the beginning; the byte
   offset is not persisted. The half-written file is deleted, as it always was.
3. **SAF tree permission.** The destination tree's persistable grant is taken when the folder
   is picked. A grant the user later revokes in system settings leaves the row queued and
   failing, rather than failing permanently.
4. **The HTTP transport is not part of this queue.** Sony's JSON-RPC API has its own
   transport, session and content API, but they are not wired into the browse/download
   pipeline, so an HTTP-only camera cannot be connected at all — and therefore cannot be the
   other end of a queued transfer.

## Change History

- 2026-11-26 — Created. Initial schema, the text-valued timestamp columns, the identity rule,
  the runner/queue split, and the queue view.
- 2026-11-26 — Added R8 (the link's fate decides the queue's fate: the retry state, the
  `failed` stamp when a recovery is given up, and the by-name re-resolution that carries a
  batch to another camera), R9 (the user's controls: pause, resume, retry, cancel, delete),
  and the multi-selection in R10. Uploads are queued through the same table; the download URI
  now carries the storage and the folder so the file can be found again by name.
- 2026-11-27 — Added the top-of-document terminology note: PTP here means Picture Transfer
  Protocol (ISO 15740), not Precision Time Protocol (IEEE 1588). Documentation only; no
  requirement changed.
