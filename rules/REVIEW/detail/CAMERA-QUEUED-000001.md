# Camera download has no queue — a multi-file transfer is an in-memory one-shot batch that is discarded on link loss, with no resume, no persistence, and no user-facing management

- ID: CAMERA-QUEUED-000001
- Status: Observation
- Priority: P1
- Created: 2026-11-26
- Owner: —
- Related: `rules/DESIGN/detail/FOTLAB-DATABS-000004.md` (the queue schema and semantics this observation motivated)

## Background & Goal

The camera feature (`app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/`) lets the
user pick several objects on a camera and transfer them to the device. The transfer is
dispatched as a foreground service so it survives leaving the screen
(`feature/camera/ARCHITECTURE.md`, "CameraTransferService" row).

This document records what the current implementation actually does with a **multi-file**
transfer when the link breaks mid-way, and what it offers the user to manage the work.
It is a fact/observation record: the code was read and traced, and no behaviour was
changed.

Findings are stated against the current working tree (which already carries the SAF
target-folder work: `download(objects, target)`), not against the last commit.

## Finding

### F1. There is no queue object — a batch is a local list plus a single-slot progress flow

The whole "queue" is two in-memory things:

| Stage | Location | State |
| --- | --- | --- |
| User selection | `CameraScreen.kt:123` `var selection by remember { mutableStateOf<Set<Long>>(emptySet()) }` | UI-local `Set<Long>`, cleared on every listing reload (`:135`, `:149`, `:216`, `:243`) |
| Dispatch | `CameraScreen.kt:190-220` (folder picker → `ACTION_DOWNLOAD` intent) | `LongArray` of handles in an Intent extra |
| Execution | `CameraTransferService.kt:50-86` | **one** `transferJob` per service instance |
| Batch iteration | `CameraFileRepository.kt:66-120` | `objects.forEachIndexed { … }` — strictly serial |
| Progress | `CameraFileRepository.kt:44` `MutableStateFlow<TransferProgress?>` | holds **only the current** object |

`TransferProgress` does carry `objectIndex` / `objectCount` (`model/CameraModels.kt`), but
they are never rendered: the screen shows one line of text
(`CameraScreen.kt:370-385`) and the notification shows `objectName` + percent
(`CameraTransferService.kt:126-130`). Nothing anywhere holds "the list of things still to
transfer", so there is no structure that could represent a pending queue.

### F2. The whole batch is resolved before the first byte moves

`CameraCore.download` (`CameraCore.kt:323-341`) first maps **every** handle through
`operations.objectInfo(handle)`, and only then calls `repository.download(objects, target)`.
If the link dies during that resolution phase, zero bytes have moved and the entire batch
is lost — including the objects that were perfectly reachable a second earlier.

### F3. A link break aborts the batch and discards every remaining object

The failure path, end to end:

1. `PtpSession.link()` normalises any `IOException` into `CameraLinkException`
   (`protocol/PtpSession.kt`, `link` helper).
2. `CameraFileRepository.kt:104-118` catches it: closes the stream, calls
   `sink.discard()` (**deletes the half-written file** — `MediaStore` row deleted on
   API 29+, `File.delete()` below), publishes `TransferState.FAILED`, and **rethrows**.
3. `CameraCore.runFileOperation` catches `CameraLinkException` → `failLink()`: clears
   `active`, cancels the event job, closes the session, sets state to `Failed`.
4. The rethrown exception reaches `CameraTransferService.kt:80` `catch (_: Throwable)`,
   which swallows it; the `finally` block cancels the watcher and calls `finish()`.

Because the iteration is a plain `forEachIndexed` broken by an exception, **the objects
queued behind the failing one are never touched**. They existed only in the local `List`
that has just been garbage-collected. Nothing is written to disk, to a database, or to
`DataStore`.

What survives and what does not:

- **Survives**: files that completed before the break. Their `MediaStore` entry had
  `IS_PENDING` cleared to 0 after the last chunk, so they are complete and visible.
- **Does not survive**: the failing object's partial file (deleted by `sink.discard()`),
  and every not-yet-started object in the batch.

So the answer to "is the queue cleared or retained" is: **cleared**. Not emptied
gracefully — it is aborted and thrown away.

### F4. No resume, no retry, no persistence

- **No byte-level resume.** `PtpFileOperations.download` (`operation/PtpFileOperations.kt:163-182`)
  starts at `offset = 0` for every object and walks up in profile-sized chunks via
  `GetPartialObject`. The offset is a local variable; nothing records how many bytes
  reached the file. Re-running the same object restarts it from byte 0.
- **No persistence.** `CameraPreference` (`CameraPreference.kt`) persists exactly two
  values — `mdns_timeout_seconds` and `keepalive_interval_seconds`. There is no transfer
  task table anywhere in the feature.
- **No restart path.** `CameraTransferService.onStartCommand` returns `START_STICKY`
  (`CameraTransferService.kt:100`), but a system restart delivers a **null intent**;
  `when (intent?.action)` matches no branch, so the service comes back alive doing
  nothing and the batch is gone. `START_STICKY` here buys process-lifetime survival
  only, not task-lifetime survival.
- **The only "retry" in the feature is a connection retry.** `CameraCore.retry()`
  (`CameraCore.kt:475-482`) re-establishes the *session*; it has no knowledge of any
  transfer.

### F5. Switching to another camera — the queue is lost, and handles would be meaningless if it were kept

- `CameraCore.connect` calls `disconnect()` first (`CameraCore.kt:250`). `disconnect()`
  closes the session, so an in-flight `session.execute` hits `check(!closed)` in
  `PtpSession.execute` and throws `IllegalStateException("PTP session is closed")` —
  **not** a `CameraLinkException`. `runFileOperation` only catches `CameraLinkException`,
  so the `failLink()` path is skipped for this case. The end state is still correct
  (`disconnect()` has already cleared `active` and set `Idle`), but the error *type* on
  this path differs from the unplug path, and the `IllegalStateException` is not
  classified as "the link died".
- **Handles are device-local.** A PTP object handle is a number assigned by the camera
  within its own object store. The same numeric handle on a different camera refers to an
  unrelated object (or to nothing). Today this cannot cause a wrong file to be written,
  because the queue is not retained at all. But it is a hard constraint on any future
  design: a persisted queue must key on **semantic identity**
  (`storageId` + parent folder path + filename + size), never on `handle`.
- **Progress subscription is broken across a reconnect.** `CameraCore.connect` creates a
  **new** `CameraFileRepository` instance (`CameraCore.kt:279`), and each repository owns
  its own `progress` StateFlow. The `CameraTransferService` watcher subscribes to the
  repository that existed when the job started
  (`CameraTransferService.kt:66-72`). After an IP auto-reconnect (`handleLinkBroken` →
  `startReconnect`, `CameraCore.kt:401-421`), the new repository's flow has no subscriber,
  so the notification's progress freezes at its last value while the link is actually live.

### F6. There is no way for the user to manage the queue

The only user-facing controls around a transfer are:

- ticking/unticking objects in the grid (`CameraScreen.kt:474-479`, `:521-526`);
- choosing a destination folder, which is also the act of starting the transfer
  (`CameraScreen.kt:190-220`, `:330`).

There is **no** queue list, **no** per-item status, **no** pause/resume, **no**
retry-failed-item, **no** transfer history, and **no** cancel affordance:

- `CameraTransferService.ACTION_CANCEL` (`CameraTransferService.kt:88-98`, `:155`) is
  **dead code** — there is no call site anywhere in `app/src` (verified by grep), and the
  notification carries no cancel action. The only way to stop a transfer is to let it
  fail or to kill the service.
- Two counter-intuitive behaviours follow from the current shape:
  - **Starting another transfer silently kills the running one.** `transferJob?.cancel()`
    (`CameraTransferService.kt:61`) is unconditional and carries no user-visible warning.
    Because the picker flow clears `selection` only *after* dispatch
    (`CameraScreen.kt:216`), a second pick while the first is in flight will replace it.
  - **Cancelling destroys the partial file and reports the wrong state.**
    `CameraFileRepository.kt:104` catches `Throwable`, which includes
    `CancellationException`: the sink is discarded (file deleted) and the published state
    is `FAILED`, not `CANCELLED`. The `CANCELLED` state published by the service's
    `ACTION_CANCEL` branch races with that `FAILED` publication — whichever lands last
    wins.

## Impact / Conflict

- **Data loss, silent.** A user transferring 50 photos over a flaky Wi-Fi link loses
  everything from the point of failure onward, with no indication of which objects were
  already saved and no way to resume. The only trace is a one-line "download failed"
  label and a notification.
- **The half-file is deleted**, so the user's storage is left consistent — but the
  already-received bytes of that one object are thrown away too.
- **The abort is not distinguishable from a device error.** `FAILED` is published for
  every failure mode, with the raw exception attached but not surfaced in the UI.
- **No queue means no throughput control.** There is no concurrency, no inter-file gap,
  and no way to lower the request rate for a camera that is struggling.
- **This conflicts with the feature's own documented promise.** `feature/camera/ARCHITECTURE.md`
  describes `CameraTransferService` as the mechanism that "keeps a batch download alive
  while the UI is gone" — true for the UI, false for the batch itself, which dies with
  the link.
- **Not a conflict with `ACTION-ROLLBK-000001`**: no architecture is being downgraded
  here; this is a missing capability, recorded for a future design decision.

## Recommendation

Not a prescription to implement now — the shape a fix must take, and the constraints any
fix has to respect.

1. **Model the transfer as a task list, not a batch call.** Replace the single
   `List<PtpObject>` with a queue of per-object tasks, each carrying its own state
   (`PENDING` / `RUNNING` / `DONE` / `FAILED`), so that a failure consumes exactly one
   task instead of aborting the batch.
2. **Key tasks on semantic identity, never on `handle`.** Persist
   `(storageId, parent path, filename, size)` and resolve the handle again at transfer
   time. This is a hard requirement for any cross-camera or cross-session behaviour.
3. **Persist the queue.** A `DataStore` entry (or a small table) holding the task list
   and the last completed index is what makes "resume after reconnect" and "resume after
   process death" possible. Note that `START_STICKY` with a null intent does not do this
   today — the service would have to read the persisted task list on start.
4. **Bind progress to a task, not to a repository instance.** The progress stream must
   live above the session (a process-wide owner in `CameraCore`, or the service itself),
   so a reconnect that swaps the `CameraFileRepository` does not orphan the subscriber.
5. **Give the user real controls**: a visible queue list with per-item state, a cancel
   button in both the screen and the notification, and a retry action for failed items.
   Wire `ACTION_CANCEL` to an actual call site, and make the repository distinguish
   `CancellationException` from a genuine failure so a cancelled item reports `CANCELLED`
   and keeps (or explicitly discards) its partial file by an agreed rule.
6. **Decide the partial-file policy explicitly** — either keep the partial file and
   resume into it from the recorded byte offset, or delete it and say so. The current
   behaviour (delete silently) should at least become visible.

## Follow-up

The queue this observation found missing now exists: `FOTLAB-DATABS-000004` fixes the schema
(`camera_file_queue` in the camera feature's own database) and the semantics (the table is the
queue's memory; the transfer service is only its runner). The recommendations below are covered
to different degrees:

- **Persist the queue** — done. The rows live in a Room table, so they survive the process.
- **Resume after reconnect** — done for the same camera: a link loss leaves the row
  unfinished and the next connection picks it up. Resuming on a *different* camera is not
  solved: a PTP handle is device-local, and the device exposes no stable identity to
  re-resolve the object from (see `FOTLAB-DATABS-000004`, Open Question 1).
- **Bind progress to a task, not to a repository instance** — partly: the running item lives
  in `CameraCore`, above the session, so a reconnect no longer orphans it.
- **Give the user real controls** — partly: the queue view shows every batch with its items
  and their state. A per-item cancel button and a retry action are still missing.
- **Byte-level resume and the partial-file policy** — not done: an interrupted transfer
  restarts from the beginning.

## Change History

- 2026-11-26 — Created. Recorded the traced behaviour of multi-file camera transfers:
  no queue abstraction, whole-batch abort on link loss with the remaining objects
  discarded, no byte-level resume, no persistence and no restart path, handle-based
  identity that cannot cross cameras, a progress subscription that breaks across an IP
  auto-reconnect, and the absence of any user-facing queue management. No code changed.
