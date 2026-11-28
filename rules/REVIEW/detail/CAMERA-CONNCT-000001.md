# Camera PTP/IP connection dies on background — App loses process priority before CameraTransferService fires

- ID: CAMERA-CONNCT-000001
- Status: Implemented
- Priority: P1
- Created: 2026-11-28
- Owner: —
- Related: `CAMERA-QUEUED-000001` (queue runs on top of the connection lifecycle this item fixes; both converge on a `CameraTransferService` that arrives too late)

> **Terminology — "PTP" in this repository:** **PTP** always means **Picture Transfer Protocol
> (ISO 15740)**, the camera file-transfer protocol implemented in
> `app/src/main/kotlin/io/github/fotlab/fotlab/feature/camera/`. It does **not** mean
> Precision Time Protocol (IEEE 1588), the network clock-synchronisation standard; the two share
> only the acronym.

## Background & Goal

`CameraCore` owns the single active PTP session, a keepalive coroutine and an event
reader. The keepalive runs on `Dispatchers.Default` and probes with a cheap
`PtpSession.deviceInfo()` every `CameraSettings.keepaliveIntervalSeconds` (default 20 s).

The report: switching away from the camera screen — a system folder picker (`ACTION_OPEN_DOCUMENT_TREE` dispatched on "Download"), another app, the launcher — silently drops the PTP/IP link. The symptom was reproduced by switching apps mid-browse and observing the connection state flip to `Failed`; it was also mistaken for "the download button disconnects the camera" because the picker sits between the user action and the queue dispatch.

This document records the root cause, why the existing pieces (`CameraTransferService`,
`Dispatchers.Default`, the socket's `SO_KEEPALIVE`) did not protect the link, and the fix
introduced.

## Finding

### F1. The keepalive coroutine's `delay()` is throttled when the process drops to `IMPORTANCE_CACHED`

`CameraCore.scope` is `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. Android
background throttling reduces thread-pool scheduling when the process is not foreground.
A `delay(20_000)` inside `startKeepalive` therefore waits **much** longer than 20 s —
observed to stretch past 60 s on mid-tier devices. The camera-side TCP idle timeout
fires before the keepalive fires, and the socket closes from the camera's end.

The event reader in `IpPtpTransport.startEventReader` is launched on a separate
`Dispatchers.IO` scope but suffers the same starvation — `Dispatchers.IO` is throttled
alongside the whole process when Doze enters its idle phase.

### F2. `CameraTransferService` does not fire until **after** the link has already died

`CameraTransferService` is the queue runner — a foreground service that starts after
the user picks a destination folder and `enqueueTransfers` appends rows to the table.
The sequence for a download is:

```
tap Download → folderLauncher.launch(ACTION_OPEN_DOCUMENT_TREE) → Activity.onStop()
                    ↓                                       ↓
             process priority                            folder picker
             → IMPORTANCE_CACHED                         stays on top
                    ↓                                       ↓
         keepalive delay throttled                    user picks folder
                    ↓                                       ↓
         camera TCP timeout                          CameraTransferService.start()
                    ↓                                       ↓
               link dies                                   ← too late
```

Between the two foreground services — none. `CameraTransferService` protects the
**transfer queue**, not the **connection** that must exist before any transfer can happen.

### F3. `SO_KEEPALIVE` on the socket does not help against Doze

`IpHandshake.connect` sets `Socket.keepAlive = true` for both the command and event
sockets. The Linux kernel's TCP keepalive (default idle 2 h → probes at 75 s intervals)
is much slower than the camera's own PTP/IP TCP timeout and is further gated by Doze —
Android's Doze mode does not forward TCP keepalive probes when the device is idle.

### F4. The reconnect/backoff cycle is also throttled

`startReconnect` doubles `delay` between attempts (1 s → 2 s → 4 s → 8 s → 16 s). Each
`delay` lives on the same `Dispatchers.Default` scope. Under background throttling an
individual retry wait stretches past the camera's TCP session lifetime, so the backoff
cycle never converges — every reconnect attempt races against a freshly expired camera
session.

### F5. Exiting the screen is not only the folder picker

The same effect occurs when the user:

- presses Home to return to the launcher;
- switches to another app via the recent-tasks drawer;
- answers a phone call while browsing.

In every case `Activity.onStop()` drops the process priority to `IMPORTANCE_CACHED` and
no foreground service exists yet to pin it higher.

## Impact / Conflict

- **Silent data loss.** A user browsing the camera mid-way through a long selection can
  lose the link by the simple act of opening the system folder picker, without any
  indication of what happened. The auto-reconnect usually brings the link back, but the
  connection is visibly flaky.
- **Wrong attribution.** The symptom looked like "clicking download disconnects" because
  the picker sits between the action and the queue dispatch; this sent the investigation
  toward the PTP opcode rather than Android scheduling.
- **Queue outlives the connection it depends on.** After
  `CAMERA-QUEUED-000001` → `FOTLAB-DATABS-000004`, the transfer queue is persisted and
  survives process death. But the queue's runtime driver — the PTP session — was still
  fragile. A persistent queue on top of a transient connection is a design gap, not a
  full architecture conflict, because the queue has an explicit waiter
  (`CameraRetryState`); it just waits in vain.

## Fix — `CameraConnectionDaemon` foreground service

A new lightweight foreground service pins the process at `IMPORTANCE_SERVICE` for the
**full connection lifecycle**, not just while transfers run. The daemon owns no camera
work — no discovery, no keepalive payload, no queue. Its only job is to hold the
foreground-service lifecycle.

### Lifecycle boundaries

```
    CameraConnectionDaemon 存活区间
  ┌──────────────────────────────────┐
  │                                  │
  │  connect() 成功 ─► start()       │
  │                                  │
  │  keepalive 协程运行              │
  │  event reader 运行               │
  │  failLink() ─► daemon 不 stop！  │  ← 跨越 retry 周期
  │                                  │
  │  startReconnect() retry 循环     │  ← daemon 还活着，delay 不被节流 ✓
  │    delay(1s) delay(2s) ...       │
  │                                  │
  │  ├─ 成功 ─► connect() → start()  │  ← 幂等
  │  └─ exhausted ─► stop() ✓       │
  │                                  │
  │  用户 disconnect() ─► stop() ✓  │
  │  USB 拔出 ─► stop() ✓           │
  └──────────────────────────────────┘
```

### start 触发条件（1 处）

| 位置 | 条件 | 说明 |
| --- | --- | --- |
| `CameraCore.connect()` 成功后 | `active` 被赋值、`startKeepalive` 已调用、`_state` 即将置为 `Connected` | 在 keepalive 启动后立刻把进程优先级钉住；后续任何 `start()` 调用都是幂等的 |

### stop 触发条件（3 个终态）

| 位置 | 条件 | 说明 |
| --- | --- | --- |
| `CameraCore.disconnect(userInitiated=true)` | 用户点"断开"或显式关闭会话 | 最明确的终点 |
| `CameraCore.handleLinkBroken` USB 分支 | 接口类型是 USB 且没有 `startReconnect` | 物理拔出，没有什么好 retry 的 |
| `CameraCore.startReconnect` exhausted | IP 重试 5 次都失败 | 彻底放弃 |

### **不** stop 的位置（关键设计）

`CameraCore.failLink()` does **not** call `daemon.stop()`. `failLink` cleans up the
session resources (cancels event job and keepalive job, closes transport, sets state
to `Failed`) but the daemon stays up so that the subsequent `startReconnect`'s
exponential-backoff `delay` calls are not throttled either. The guard comment in the
source repeats this explicitly so future edits do not "clean up" the daemon from
`failLink`.

### Service identity

- Class: `CameraConnectionDaemon` — named for the **lifecycle** it guards, not for
  any particular mechanism it runs (`KeepaliveService` would have been misleading
  because it would imply the daemon only ran during idle probes).
- Manifest: `android:foregroundServiceType="specialUse"` with
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="Guard the full PTP/IP connection lifecycle
  (keepalive, reconnect, backoff) while the app is backgrounded"`.
- Notification channel: `camera_connection`, low importance, ongoing text
  "Keeping connection to {device} alive".
- Coexists happily with `CameraTransferService` — Android allows multiple
  foreground services from the same process; the two services have disjoint
  responsibilities and disjoint notification IDs (7712 vs 7711).

## Change History

- 2026-11-28 — Created. Recorded the connection-lifecycle throttling root cause:
  `Dispatchers.Default` keepalive delays stretch past the camera's TCP idle timeout
  when the process falls to `IMPORTANCE_CACHED`, and `CameraTransferService` fires
  too late to protect the folder-picker window and any temporary app switch. Fix:
  `CameraConnectionDaemon` foreground service pinned at `IMPORTANCE_SERVICE` from
  `connect()` success until user-initiated disconnect, USB detach, or IP retry
  exhaustion. Deliberately **not** stopped in `failLink` so the daemon guards the
  `startReconnect` backoff cycle as well. Commit `c7867b0`.
